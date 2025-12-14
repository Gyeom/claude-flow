package ai.claudeflow.core.context

import ai.claudeflow.core.storage.Storage
import ai.claudeflow.core.storage.UserContext
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 사용자 컨텍스트 관리자
 *
 * 사용자별 선호도, 언어, 전문 분야 등을 관리하여 개인화된 응답 제공
 */
class UserContextManager(
    private val storage: Storage
) {
    /**
     * 사용자 컨텍스트 조회 또는 생성
     */
    fun getOrCreate(userId: String, displayName: String? = null): UserContext {
        val existing = storage.getUserContext(userId)
        if (existing != null) {
            // 마지막 접속 시간 업데이트
            val updated = existing.copy(
                lastSeen = Instant.now(),
                totalInteractions = existing.totalInteractions + 1,
                displayName = displayName ?: existing.displayName
            )
            storage.saveUserContext(updated)
            return updated
        }

        // 새 사용자 컨텍스트 생성
        val newContext = UserContext(
            userId = userId,
            displayName = displayName,
            preferredLanguage = detectLanguageFromName(displayName),
            domain = null,
            lastSeen = Instant.now(),
            totalInteractions = 1
        )
        storage.saveUserContext(newContext)
        logger.info { "Created new user context: $userId" }
        return newContext
    }

    /**
     * 사용자 선호 언어 설정
     */
    fun setPreferredLanguage(userId: String, language: String) {
        val context = storage.getUserContext(userId) ?: return
        storage.saveUserContext(context.copy(preferredLanguage = language))
        logger.info { "Updated language for $userId: $language" }
    }

    /**
     * 사용자 도메인(전문 분야) 설정
     */
    fun setDomain(userId: String, domain: String) {
        val context = storage.getUserContext(userId) ?: return
        storage.saveUserContext(context.copy(domain = domain))
        logger.info { "Updated domain for $userId: $domain" }
    }

    /**
     * 사용자 컨텍스트를 시스템 프롬프트에 반영
     */
    fun buildContextPrompt(userId: String): String? {
        val context = storage.getUserContext(userId) ?: return null

        val parts = mutableListOf<String>()

        // 언어 설정
        when (context.preferredLanguage) {
            "ko" -> parts.add("Respond in Korean (한국어로 답변하세요).")
            "en" -> parts.add("Respond in English.")
            "ja" -> parts.add("Respond in Japanese (日本語で回答してください).")
        }

        // 도메인 전문성
        context.domain?.let { domain ->
            parts.add("The user is experienced in $domain, so you can use technical terms related to this field.")
        }

        // 사용자 이름
        context.displayName?.let { name ->
            parts.add("The user's name is $name.")
        }

        return if (parts.isNotEmpty()) {
            "User Context:\n" + parts.joinToString("\n")
        } else null
    }

    /**
     * 메시지에서 사용자 설정 명령어 감지
     */
    fun detectAndApplySettings(userId: String, message: String): SettingsUpdate? {
        val lowerMessage = message.lowercase()

        // 언어 설정 감지
        val languagePatterns = mapOf(
            listOf("한국어로", "korean", "한글로") to "ko",
            listOf("영어로", "english", "in english") to "en",
            listOf("일본어로", "japanese", "日本語で") to "ja"
        )

        for ((patterns, lang) in languagePatterns) {
            if (patterns.any { lowerMessage.contains(it) }) {
                setPreferredLanguage(userId, lang)
                return SettingsUpdate("language", lang)
            }
        }

        // 도메인 설정 감지
        val domainPatterns = mapOf(
            listOf("backend", "백엔드", "서버") to "backend development",
            listOf("frontend", "프론트엔드", "프론트") to "frontend development",
            listOf("devops", "데브옵스", "인프라") to "DevOps/Infrastructure",
            listOf("data", "데이터", "ml", "머신러닝") to "Data/ML Engineering",
            listOf("mobile", "모바일", "앱") to "Mobile Development"
        )

        for ((patterns, domain) in domainPatterns) {
            if (patterns.any { lowerMessage.contains(it) && lowerMessage.contains("전문") }) {
                setDomain(userId, domain)
                return SettingsUpdate("domain", domain)
            }
        }

        return null
    }

    /**
     * 이름에서 언어 추측
     */
    private fun detectLanguageFromName(name: String?): String {
        if (name == null) return "ko"  // 기본값 한국어

        // 한글 포함 여부
        if (name.any { it in '\uAC00'..'\uD7A3' }) return "ko"

        // 일본어 포함 여부
        if (name.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }) return "ja"

        return "ko"  // 기본값
    }
}

data class SettingsUpdate(
    val type: String,
    val value: String
)
