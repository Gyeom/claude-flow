package ai.claudeflow.core.plugin

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 관리자
 *
 * 플러그인 등록, 초기화, 실행 관리.
 * PluginRegistry, PluginLoader, PluginConfigManager와 통합.
 */
class PluginManager(
    configPath: String? = null
) {
    val configManager = PluginConfigManager(configPath)
    val registry = PluginRegistry()
    val loader = PluginLoader(registry, configManager)

    /**
     * 설정 파일에서 모든 플러그인 로드
     */
    suspend fun loadPlugins(): PluginLoader.LoadResult {
        return loader.loadAll()
    }

    /**
     * 플러그인 등록
     */
    fun register(plugin: Plugin, config: Map<String, String> = emptyMap()) {
        val metadata = PluginMetadata(
            id = plugin.id,
            name = plugin.name,
            description = plugin.description,
            version = "1.0.0"
        )
        registry.register(plugin, metadata, config)
    }

    /**
     * 플러그인 제거
     */
    suspend fun unregister(pluginId: String) {
        loader.unload(pluginId)
    }

    /**
     * 플러그인 초기화
     */
    suspend fun initialize(pluginId: String) {
        val plugin = registry.get(pluginId)
            ?: throw IllegalArgumentException("Plugin not found: $pluginId")
        val config = registry.getConfig(pluginId) ?: emptyMap()
        plugin.initialize(config)
    }

    /**
     * 모든 플러그인 초기화
     */
    suspend fun initializeAll() {
        for (plugin in registry.getAll()) {
            try {
                val config = registry.getConfig(plugin.id) ?: emptyMap()
                plugin.initialize(config)
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize plugin: ${plugin.id}" }
            }
        }
    }

    /**
     * 모든 플러그인 종료
     */
    suspend fun shutdownAll() {
        loader.unloadAll()
    }

    /**
     * 플러그인 조회
     */
    fun get(pluginId: String): Plugin? = registry.get(pluginId)

    /**
     * 활성화된 플러그인 목록
     */
    fun getEnabled(): List<Plugin> = registry.getEnabled()

    /**
     * 모든 플러그인 목록
     */
    fun getAll(): List<Plugin> = registry.getAll()

    /**
     * 메시지를 처리할 플러그인 찾기
     */
    fun findHandler(message: String): Plugin? = registry.findHandler(message)

    /**
     * 플러그인 명령어 실행
     */
    suspend fun execute(pluginId: String, command: String, args: Map<String, Any>): PluginResult {
        val plugin = registry.get(pluginId)
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
        return registry.getAll().map { plugin ->
            PluginInfo(
                id = plugin.id,
                name = plugin.name,
                description = plugin.description,
                enabled = plugin.enabled,
                commands = plugin.commands.map { it.name }
            )
        }
    }

    /**
     * 플러그인 활성화/비활성화
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        return configManager.setEnabled(pluginId, enabled)
    }

    /**
     * 플러그인 리로드
     */
    suspend fun reloadPlugin(pluginId: String): Boolean {
        return loader.reload(pluginId)
    }

    /**
     * 레지스트리 요약 정보
     */
    fun getSummary(): PluginRegistry.RegistrySummary = registry.getSummary()
}

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val commands: List<String>
)
