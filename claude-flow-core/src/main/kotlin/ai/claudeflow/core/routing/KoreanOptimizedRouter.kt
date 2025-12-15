package ai.claudeflow.core.routing

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.AgentMatch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 한국어 최적화 라우터
 *
 * Claudio 대비 차별화 기능:
 * 1. 초성 검색 지원 (ㅋㄷㄹㅂ → 코드 리뷰)
 * 2. 유사어/동의어 확장
 * 3. 조사 무시 (리뷰를, 리뷰가, 리뷰는 → 리뷰)
 * 4. 오타 교정 (Levenshtein distance)
 * 5. 형태소 기반 키워드 추출
 */
class KoreanOptimizedRouter(
    private val enableChoseongSearch: Boolean = true,
    private val enableSynonymExpansion: Boolean = true,
    private val enableJosaRemoval: Boolean = true,
    private val enableTypoCorrection: Boolean = true,
    private val typoThreshold: Int = 2  // 허용 오타 거리
) {
    // 초성 테이블
    private val choseong = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    // 한글 조사 목록
    private val josaList = listOf(
        "은", "는", "이", "가", "을", "를", "의", "에", "에서",
        "로", "으로", "와", "과", "도", "만", "까지", "부터",
        "에게", "한테", "께", "보다", "처럼", "같이", "요", "해"
    )

    // 동의어 사전 (기술 용어 중심)
    private val synonyms = mapOf(
        "리뷰" to listOf("검토", "검수", "확인", "봐줘", "체크"),
        "코드" to listOf("소스", "프로그램", "스크립트"),
        "버그" to listOf("오류", "에러", "결함", "이슈", "문제"),
        "수정" to listOf("고쳐", "패치", "fix", "변경"),
        "설명" to listOf("알려줘", "뭐야", "무엇", "어떻게"),
        "테스트" to listOf("검증", "확인", "시험"),
        "배포" to listOf("릴리즈", "release", "deploy"),
        "분석" to listOf("파악", "조사", "확인"),
        "생성" to listOf("만들어", "작성", "create", "추가"),
        "삭제" to listOf("제거", "지워", "remove", "delete"),
        "MR" to listOf("머지리퀘스트", "merge request", "풀리퀘스트", "PR"),
        "커밋" to listOf("commit", "저장", "반영")
    )

    // 역방향 동의어 맵 (빠른 조회용)
    private val reverseSynonyms: Map<String, String> by lazy {
        synonyms.flatMap { (key, values) ->
            values.map { it.lowercase() to key }
        }.toMap()
    }

    /**
     * 한국어 최적화 키워드 매칭
     */
    fun matchKeyword(message: String, agents: List<Agent>): AgentMatch? {
        val processedMessage = preprocessMessage(message)
        val keywords = extractKeywords(processedMessage)

        logger.debug { "Processed message: $processedMessage, keywords: $keywords" }

        for (agent in agents.sortedByDescending { it.priority }) {
            for (agentKeyword in agent.keywords) {
                val normalizedAgentKeyword = agentKeyword.lowercase()

                // 1. 정확한 키워드 매칭
                if (keywords.any { it.equals(normalizedAgentKeyword, ignoreCase = true) }) {
                    return AgentMatch(agent, 0.98, agentKeyword)
                }

                // 2. 부분 매칭 (키워드가 메시지에 포함)
                if (processedMessage.contains(normalizedAgentKeyword)) {
                    return AgentMatch(agent, 0.95, agentKeyword)
                }

                // 3. 초성 검색
                if (enableChoseongSearch) {
                    val choseongMatch = matchChoseong(processedMessage, normalizedAgentKeyword)
                    if (choseongMatch) {
                        return AgentMatch(agent, 0.90, agentKeyword)
                    }
                }

                // 4. 동의어 확장 매칭
                if (enableSynonymExpansion) {
                    val synonymMatch = matchSynonym(keywords, normalizedAgentKeyword)
                    if (synonymMatch != null) {
                        return AgentMatch(agent, 0.88, "$synonymMatch → $agentKeyword")
                    }
                }

                // 5. 오타 교정 매칭
                if (enableTypoCorrection) {
                    val typoMatch = matchWithTypoCorrection(keywords, normalizedAgentKeyword)
                    if (typoMatch != null) {
                        return AgentMatch(agent, 0.80, "$typoMatch ≈ $agentKeyword")
                    }
                }
            }
        }

        return null
    }

    /**
     * 메시지 전처리
     */
    private fun preprocessMessage(message: String): String {
        var processed = message.lowercase().trim()

        // 조사 제거
        if (enableJosaRemoval) {
            processed = removeJosa(processed)
        }

        return processed
    }

    /**
     * 키워드 추출 (공백/구두점 기준)
     */
    private fun extractKeywords(message: String): List<String> {
        return message
            .split(Regex("[\\s,.!?;:]+"))
            .filter { it.length >= 2 }
            .map { it.trim() }
    }

    /**
     * 조사 제거
     */
    private fun removeJosa(text: String): String {
        var result = text
        for (josa in josaList.sortedByDescending { it.length }) {
            result = result.replace(Regex("(\\S+)$josa(?=\\s|$)")) { matchResult ->
                matchResult.groupValues[1]
            }
        }
        return result
    }

    /**
     * 초성 추출
     */
    fun extractChoseong(text: String): String {
        return text.map { char ->
            if (char in '가'..'힣') {
                val index = (char.code - 0xAC00) / 28 / 21
                choseong[index]
            } else {
                char
            }
        }.joinToString("")
    }

    /**
     * 초성 매칭
     */
    private fun matchChoseong(message: String, keyword: String): Boolean {
        val messageChoseong = extractChoseong(message)
        val keywordChoseong = extractChoseong(keyword)

        // 메시지의 초성에 키워드 초성이 포함되어 있는지 확인
        return messageChoseong.contains(keywordChoseong)
    }

    /**
     * 동의어 매칭
     */
    private fun matchSynonym(keywords: List<String>, targetKeyword: String): String? {
        for (keyword in keywords) {
            // 동의어 사전에서 대표어 찾기
            val canonicalKeyword = reverseSynonyms[keyword.lowercase()]
            if (canonicalKeyword != null && canonicalKeyword.equals(targetKeyword, ignoreCase = true)) {
                return keyword
            }

            // 대상 키워드의 동의어 목록에 현재 키워드가 있는지
            synonyms[targetKeyword]?.let { targetSynonyms ->
                if (targetSynonyms.any { it.equals(keyword, ignoreCase = true) }) {
                    return keyword
                }
            }
        }
        return null
    }

    /**
     * 오타 교정 매칭 (Levenshtein distance)
     */
    private fun matchWithTypoCorrection(keywords: List<String>, targetKeyword: String): String? {
        for (keyword in keywords) {
            if (keyword.length >= 2 && targetKeyword.length >= 2) {
                val distance = levenshteinDistance(keyword, targetKeyword)
                if (distance <= typoThreshold && distance < keyword.length / 2) {
                    return keyword
                }
            }
        }
        return null
    }

    /**
     * Levenshtein 거리 계산
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 삭제
                    dp[i][j - 1] + 1,      // 삽입
                    dp[i - 1][j - 1] + cost // 대체
                )
            }
        }

        return dp[m][n]
    }

    /**
     * 동의어 확장 (키워드에 대한 모든 변형 반환)
     */
    fun expandKeyword(keyword: String): Set<String> {
        val result = mutableSetOf(keyword.lowercase())

        // 동의어 추가
        synonyms[keyword.lowercase()]?.forEach { result.add(it.lowercase()) }

        // 역방향 동의어 추가
        reverseSynonyms[keyword.lowercase()]?.let { canonical ->
            result.add(canonical)
            synonyms[canonical]?.forEach { result.add(it.lowercase()) }
        }

        return result
    }

    /**
     * 동의어 사전에 추가
     */
    fun addSynonym(canonical: String, synonym: String) {
        val existing = synonyms[canonical]?.toMutableList() ?: mutableListOf()
        if (synonym.lowercase() !in existing.map { it.lowercase() }) {
            existing.add(synonym)
            (synonyms as MutableMap)[canonical] = existing
            (reverseSynonyms as MutableMap)[synonym.lowercase()] = canonical
            logger.debug { "Added synonym: $synonym → $canonical" }
        }
    }

    /**
     * 메시지 유사도 계산 (0.0 ~ 1.0)
     */
    fun calculateSimilarity(message: String, keyword: String): Double {
        val processedMessage = preprocessMessage(message)

        // 정확한 매칭
        if (processedMessage.contains(keyword.lowercase())) {
            return 1.0
        }

        // 초성 매칭
        if (matchChoseong(processedMessage, keyword)) {
            return 0.8
        }

        // Levenshtein 거리 기반 유사도
        val distance = levenshteinDistance(processedMessage, keyword.lowercase())
        val maxLen = maxOf(processedMessage.length, keyword.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }
}
