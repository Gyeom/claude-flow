package ai.claudeflow.core.plugin

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 관리자
 *
 * 플러그인 등록, 초기화, 실행 관리
 */
class PluginManager {
    private val plugins = mutableMapOf<String, Plugin>()
    private val pluginConfigs = mutableMapOf<String, Map<String, String>>()

    /**
     * 플러그인 등록
     */
    fun register(plugin: Plugin, config: Map<String, String> = emptyMap()) {
        plugins[plugin.id] = plugin
        pluginConfigs[plugin.id] = config
        logger.info { "Plugin registered: ${plugin.id} (${plugin.name})" }
    }

    /**
     * 플러그인 제거
     */
    suspend fun unregister(pluginId: String) {
        plugins[pluginId]?.let { plugin ->
            if (plugin.enabled) {
                plugin.shutdown()
            }
            plugins.remove(pluginId)
            pluginConfigs.remove(pluginId)
            logger.info { "Plugin unregistered: $pluginId" }
        }
    }

    /**
     * 플러그인 초기화
     */
    suspend fun initialize(pluginId: String) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("Plugin not found: $pluginId")
        val config = pluginConfigs[pluginId] ?: emptyMap()
        plugin.initialize(config)
    }

    /**
     * 모든 플러그인 초기화
     */
    suspend fun initializeAll() {
        for ((id, plugin) in plugins) {
            try {
                val config = pluginConfigs[id] ?: emptyMap()
                plugin.initialize(config)
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize plugin: $id" }
            }
        }
    }

    /**
     * 모든 플러그인 종료
     */
    suspend fun shutdownAll() {
        for (plugin in plugins.values) {
            try {
                plugin.shutdown()
            } catch (e: Exception) {
                logger.error(e) { "Failed to shutdown plugin: ${plugin.id}" }
            }
        }
    }

    /**
     * 플러그인 조회
     */
    fun get(pluginId: String): Plugin? = plugins[pluginId]

    /**
     * 활성화된 플러그인 목록
     */
    fun getEnabled(): List<Plugin> = plugins.values.filter { it.enabled }

    /**
     * 모든 플러그인 목록
     */
    fun getAll(): List<Plugin> = plugins.values.toList()

    /**
     * 메시지를 처리할 플러그인 찾기
     */
    fun findHandler(message: String): Plugin? {
        return getEnabled().find { it.shouldHandle(message) }
    }

    /**
     * 플러그인 명령어 실행
     */
    suspend fun execute(pluginId: String, command: String, args: Map<String, Any>): PluginResult {
        val plugin = plugins[pluginId]
            ?: return PluginResult(false, error = "Plugin not found: $pluginId")

        if (!plugin.enabled) {
            return PluginResult(false, error = "Plugin not enabled: $pluginId")
        }

        return try {
            plugin.execute(command, args)
        } catch (e: Exception) {
            logger.error(e) { "Plugin execution failed: $pluginId.$command" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 플러그인 정보 조회
     */
    fun getPluginInfo(): List<PluginInfo> {
        return plugins.values.map { plugin ->
            PluginInfo(
                id = plugin.id,
                name = plugin.name,
                description = plugin.description,
                enabled = plugin.enabled,
                commands = plugin.commands.map { it.name }
            )
        }
    }
}

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val commands: List<String>
)
