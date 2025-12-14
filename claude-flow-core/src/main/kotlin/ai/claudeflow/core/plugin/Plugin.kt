package ai.claudeflow.core.plugin

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 인터페이스
 *
 * GitLab, Jira, GitHub 등 외부 서비스 연동을 위한 확장 포인트
 */
interface Plugin {
    /** 플러그인 고유 ID */
    val id: String

    /** 플러그인 이름 */
    val name: String

    /** 플러그인 설명 */
    val description: String

    /** 지원하는 명령어 목록 */
    val commands: List<PluginCommand>

    /** 플러그인 활성화 여부 */
    var enabled: Boolean

    /** 플러그인 초기화 */
    suspend fun initialize(config: Map<String, String>)

    /** 플러그인 종료 */
    suspend fun shutdown()

    /** 명령어 실행 */
    suspend fun execute(command: String, args: Map<String, Any>): PluginResult

    /** 메시지가 이 플러그인으로 라우팅되어야 하는지 확인 */
    fun shouldHandle(message: String): Boolean
}

/**
 * 플러그인 명령어
 */
data class PluginCommand(
    val name: String,
    val description: String,
    val usage: String,
    val examples: List<String> = emptyList()
)

/**
 * 플러그인 실행 결과
 */
data class PluginResult(
    val success: Boolean,
    val data: Any? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * 플러그인 메타데이터
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String = "Unknown",
    val requiredConfig: List<String> = emptyList()
)

/**
 * 기본 플러그인 구현
 */
abstract class BasePlugin : Plugin {
    override var enabled: Boolean = false
    protected var config: Map<String, String> = emptyMap()

    override suspend fun initialize(config: Map<String, String>) {
        this.config = config
        this.enabled = true
        logger.info { "Plugin initialized: $name" }
    }

    override suspend fun shutdown() {
        this.enabled = false
        logger.info { "Plugin shutdown: $name" }
    }

    protected fun requireConfig(key: String): String {
        return config[key] ?: throw IllegalStateException("Missing required config: $key")
    }

    protected fun getConfig(key: String, default: String = ""): String {
        return config[key] ?: default
    }
}
