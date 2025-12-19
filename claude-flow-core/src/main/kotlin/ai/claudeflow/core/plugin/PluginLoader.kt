package ai.claudeflow.core.plugin

import mu.KotlinLogging
import java.util.ServiceLoader

private val logger = KotlinLogging.logger {}

/**
 * 동적 플러그인 로더
 *
 * ServiceLoader 또는 직접 인스턴스화를 통해 플러그인을 로드.
 * 설정 파일 기반 플러그인 활성화/비활성화 지원.
 */
class PluginLoader(
    private val registry: PluginRegistry,
    private val configManager: PluginConfigManager
) {
    private val builtInPlugins = mutableMapOf<String, () -> Plugin>()

    init {
        // 기본 제공 플러그인 등록
        registerBuiltIn("gitlab") { GitLabPlugin() }
        registerBuiltIn("github") { GitHubPlugin() }
        registerBuiltIn("jira") { JiraPlugin() }
        registerBuiltIn("n8n") { N8nPlugin() }
    }

    /**
     * 내장 플러그인 팩토리 등록
     */
    fun registerBuiltIn(id: String, factory: () -> Plugin) {
        builtInPlugins[id] = factory
        logger.debug { "Built-in plugin factory registered: $id" }
    }

    /**
     * 설정에 따라 모든 플러그인 로드
     */
    suspend fun loadAll(): LoadResult {
        val result = LoadResult()
        val pluginConfigs = configManager.getAllPluginConfigs()

        for ((pluginId, pluginConfig) in pluginConfigs) {
            if (!pluginConfig.enabled) {
                logger.debug { "Plugin disabled in config: $pluginId" }
                result.skipped.add(pluginId)
                continue
            }

            try {
                val loaded = load(pluginId, pluginConfig)
                if (loaded) {
                    result.loaded.add(pluginId)
                } else {
                    result.failed.add(pluginId to "Unknown plugin")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load plugin: $pluginId" }
                result.failed.add(pluginId to (e.message ?: "Unknown error"))
            }
        }

        // ServiceLoader로 추가 플러그인 검색
        loadFromServiceLoader(result)

        logger.info {
            "Plugin loading complete: ${result.loaded.size} loaded, " +
            "${result.skipped.size} skipped, ${result.failed.size} failed"
        }

        return result
    }

    /**
     * 특정 플러그인 로드
     */
    suspend fun load(pluginId: String, config: PluginConfigManager.PluginConfig? = null): Boolean {
        val pluginConfig = config ?: configManager.getPluginConfig(pluginId)

        // 이미 등록되어 있으면 스킵
        if (registry.contains(pluginId)) {
            logger.debug { "Plugin already loaded: $pluginId" }
            return true
        }

        // 내장 플러그인 확인
        val factory = builtInPlugins[pluginId]
        if (factory != null) {
            return loadBuiltIn(pluginId, factory, pluginConfig)
        }

        // 클래스 이름으로 로드 시도
        if (pluginConfig?.className != null) {
            return loadByClassName(pluginId, pluginConfig.className, pluginConfig)
        }

        logger.warn { "No loader found for plugin: $pluginId" }
        return false
    }

    /**
     * 내장 플러그인 로드
     */
    private suspend fun loadBuiltIn(
        pluginId: String,
        factory: () -> Plugin,
        config: PluginConfigManager.PluginConfig?
    ): Boolean {
        try {
            val plugin = factory()
            val metadata = createMetadata(plugin)
            val pluginSettings = config?.settings ?: emptyMap()

            registry.register(plugin, metadata, pluginSettings)

            if (config?.autoInitialize != false) {
                plugin.initialize(pluginSettings)
            }

            logger.info { "Built-in plugin loaded: $pluginId" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to load built-in plugin: $pluginId" }
            return false
        }
    }

    /**
     * 클래스 이름으로 플러그인 로드
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun loadByClassName(
        pluginId: String,
        className: String,
        config: PluginConfigManager.PluginConfig?
    ): Boolean {
        try {
            val clazz = Class.forName(className)
            if (!Plugin::class.java.isAssignableFrom(clazz)) {
                logger.error { "Class $className does not implement Plugin interface" }
                return false
            }

            val plugin = clazz.getDeclaredConstructor().newInstance() as Plugin
            val metadata = createMetadata(plugin)
            val pluginSettings = config?.settings ?: emptyMap()

            registry.register(plugin, metadata, pluginSettings)

            if (config?.autoInitialize != false) {
                plugin.initialize(pluginSettings)
            }

            logger.info { "Plugin loaded by class name: $pluginId ($className)" }
            return true
        } catch (e: ClassNotFoundException) {
            logger.error { "Plugin class not found: $className" }
            return false
        } catch (e: Exception) {
            logger.error(e) { "Failed to load plugin by class name: $className" }
            return false
        }
    }

    /**
     * ServiceLoader를 사용하여 플러그인 검색
     */
    private suspend fun loadFromServiceLoader(result: LoadResult) {
        try {
            val loader = ServiceLoader.load(Plugin::class.java)
            for (plugin in loader) {
                val pluginId = plugin.id
                if (registry.contains(pluginId)) {
                    continue
                }

                val config = configManager.getPluginConfig(pluginId)
                if (config?.enabled == false) {
                    result.skipped.add(pluginId)
                    continue
                }

                try {
                    val metadata = createMetadata(plugin)
                    val pluginSettings = config?.settings ?: emptyMap()
                    registry.register(plugin, metadata, pluginSettings)

                    if (config?.autoInitialize != false) {
                        plugin.initialize(pluginSettings)
                    }

                    result.loaded.add(pluginId)
                    logger.info { "Plugin loaded via ServiceLoader: $pluginId" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to initialize ServiceLoader plugin: $pluginId" }
                    result.failed.add(pluginId to (e.message ?: "Unknown error"))
                }
            }
        } catch (e: Exception) {
            logger.debug { "ServiceLoader scan completed: ${e.message}" }
        }
    }

    /**
     * 플러그인 언로드
     */
    suspend fun unload(pluginId: String): Boolean {
        val plugin = registry.get(pluginId)
        if (plugin == null) {
            logger.warn { "Plugin not found for unload: $pluginId" }
            return false
        }

        try {
            if (plugin.enabled) {
                plugin.shutdown()
            }
            registry.unregister(pluginId)
            logger.info { "Plugin unloaded: $pluginId" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Error unloading plugin: $pluginId" }
            return false
        }
    }

    /**
     * 플러그인 리로드
     */
    suspend fun reload(pluginId: String): Boolean {
        val entry = registry.getEntry(pluginId)
        if (entry == null) {
            logger.warn { "Plugin not found for reload: $pluginId" }
            return false
        }

        // 언로드 후 재로드
        unload(pluginId)

        val config = configManager.getPluginConfig(pluginId)
        return load(pluginId, config)
    }

    /**
     * 모든 플러그인 언로드
     */
    suspend fun unloadAll() {
        val plugins = registry.getAll().toList()
        for (plugin in plugins) {
            unload(plugin.id)
        }
        logger.info { "All plugins unloaded" }
    }

    /**
     * 플러그인 메타데이터 생성
     */
    private fun createMetadata(plugin: Plugin): PluginMetadata {
        return PluginMetadata(
            id = plugin.id,
            name = plugin.name,
            description = plugin.description,
            version = "1.0.0",
            author = "Claude Flow",
            requiredConfig = emptyList()
        )
    }

    /**
     * 로드 결과
     */
    data class LoadResult(
        val loaded: MutableList<String> = mutableListOf(),
        val skipped: MutableList<String> = mutableListOf(),
        val failed: MutableList<Pair<String, String>> = mutableListOf()
    ) {
        fun isSuccess(): Boolean = failed.isEmpty()
        fun summary(): String = "Loaded: ${loaded.size}, Skipped: ${skipped.size}, Failed: ${failed.size}"
    }
}
