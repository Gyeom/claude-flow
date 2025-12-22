package ai.claudeflow.core.enrichment

import java.time.Instant

/**
 * Enrichment 컨텍스트 (불변 객체)
 *
 * Pipeline을 통과하면서 점진적으로 풍부해지는 컨텍스트입니다.
 * 각 Enricher는 이 객체를 수정하지 않고 새 객체를 반환해야 합니다.
 *
 * @property originalPrompt 원본 사용자 프롬프트
 * @property enrichedPrompt 컨텍스트가 주입된 프롬프트
 * @property metadata 요청 메타데이터 (userId, projectId 등)
 * @property injectedContexts 주입된 컨텍스트 목록
 * @property workingDirectory 결정된 작업 디렉토리
 * @property enrichmentLog 처리 로그
 * @property startTime 처리 시작 시간
 */
data class EnrichmentContext(
    val originalPrompt: String,
    val enrichedPrompt: String = originalPrompt,
    val metadata: EnrichmentMetadata = EnrichmentMetadata(),
    val injectedContexts: List<InjectedContext> = emptyList(),
    val workingDirectory: String? = null,
    val enrichmentLog: List<EnrichmentLogEntry> = emptyList(),
    val startTime: Instant = Instant.now()
) {
    /**
     * 컨텍스트가 주입되었는지 여부
     */
    val hasInjectedContext: Boolean
        get() = injectedContexts.isNotEmpty()

    /**
     * 총 주입된 컨텍스트 크기 (문자 수)
     */
    val totalContextSize: Int
        get() = injectedContexts.sumOf { it.content.length }

    /**
     * 특정 타입의 컨텍스트가 주입되었는지 확인
     */
    fun hasContextOfType(type: EnricherType): Boolean =
        injectedContexts.any { it.enricherType == type }

    /**
     * 새 컨텍스트를 추가한 새 객체 반환
     */
    fun withAddedContext(
        enricherId: String,
        enricherType: EnricherType,
        content: String,
        metadata: Map<String, Any> = emptyMap()
    ): EnrichmentContext {
        val newContext = InjectedContext(
            enricherId = enricherId,
            enricherType = enricherType,
            content = content,
            metadata = metadata,
            injectedAt = Instant.now()
        )

        val newPrompt = buildEnrichedPrompt(content)

        return copy(
            enrichedPrompt = newPrompt,
            injectedContexts = injectedContexts + newContext
        )
    }

    /**
     * 작업 디렉토리를 설정한 새 객체 반환
     */
    fun withWorkingDirectory(directory: String): EnrichmentContext {
        // 이미 설정된 경우 덮어쓰지 않음 (우선순위 존중)
        if (workingDirectory != null) return this
        return copy(workingDirectory = directory)
    }

    /**
     * 로그 항목을 추가한 새 객체 반환
     */
    fun withLog(enricherId: String, message: String, durationMs: Long = 0): EnrichmentContext {
        val entry = EnrichmentLogEntry(
            enricherId = enricherId,
            message = message,
            durationMs = durationMs,
            timestamp = Instant.now()
        )
        return copy(enrichmentLog = enrichmentLog + entry)
    }

    /**
     * 풍부해진 프롬프트 빌드
     */
    private fun buildEnrichedPrompt(newContent: String): String {
        // 이미 컨텍스트가 있으면 추가
        return if (enrichedPrompt != originalPrompt) {
            "$enrichedPrompt\n\n$newContent"
        } else {
            // 첫 번째 컨텍스트
            """
                |$newContent
                |
                |--- User Request ---
                |$originalPrompt
            """.trimMargin()
        }
    }

    companion object {
        /**
         * 새 컨텍스트 생성
         */
        fun create(
            prompt: String,
            userId: String? = null,
            projectId: String? = null,
            agentId: String? = null,
            channelId: String? = null,
            threadTs: String? = null,
            additionalMetadata: Map<String, Any> = emptyMap()
        ): EnrichmentContext {
            return EnrichmentContext(
                originalPrompt = prompt,
                metadata = EnrichmentMetadata(
                    userId = userId,
                    projectId = projectId,
                    agentId = agentId,
                    channelId = channelId,
                    threadTs = threadTs,
                    additional = additionalMetadata
                )
            )
        }
    }
}

/**
 * Enrichment 메타데이터
 */
data class EnrichmentMetadata(
    val userId: String? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val channelId: String? = null,
    val threadTs: String? = null,
    val additional: Map<String, Any> = emptyMap()
) {
    operator fun get(key: String): Any? = additional[key]
}

/**
 * 주입된 컨텍스트 정보
 */
data class InjectedContext(
    val enricherId: String,
    val enricherType: EnricherType,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val injectedAt: Instant = Instant.now()
)

/**
 * Enrichment 처리 로그 항목
 */
data class EnrichmentLogEntry(
    val enricherId: String,
    val message: String,
    val durationMs: Long,
    val timestamp: Instant
)
