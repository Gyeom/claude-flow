package ai.claudeflow.core.plugin

import mu.KotlinLogging
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 설정 관리자
 *
 * TOML/Properties 파일 또는 환경 변수에서 플러그인 설정을 로드.
 * 런타임 설정 변경 지원.
 */
class PluginConfigManager(
    private val configPath: String? = null
) {
    private val configs = ConcurrentHashMap<String, PluginConfig>()
    private val globalSettings = ConcurrentHashMap<String, String>()

    /**
     * 플러그인 설정
     */
    data class PluginConfig(
        val pluginId: String,
        val enabled: Boolean = true,
        val className: String? = null,
        val autoInitialize: Boolean = true,
        val priority: Int = 0,
        val settings: Map<String, String> = emptyMap()
    )

    init {
        // 설정 파일이 있으면 로드
        configPath?.let { loadFromFile(it) }

        // 환경 변수에서 설정 로드
        loadFromEnvironment()
    }

    /**
     * 설정 파일에서 로드
     */
    fun loadFromFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) {
            logger.debug { "Plugin config file not found: $path" }
            return false
        }

        try {
            when {
                path.endsWith(".toml") -> loadFromToml(file)
                path.endsWith(".properties") -> loadFromProperties(file)
                else -> {
                    logger.warn { "Unknown config file format: $path" }
                    return false
                }
            }
            logger.info { "Plugin config loaded from: $path" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to load plugin config from: $path" }
            return false
        }
    }

    /**
     * TOML 파일에서 로드 (간단한 파서)
     */
    private fun loadFromToml(file: File) {
        var currentPlugin: String? = null
        val currentSettings = mutableMapOf<String, String>()
        var enabled = true
        var className: String? = null
        var autoInit = true
        var priority = 0

        fun saveCurrentPlugin() {
            currentPlugin?.let { pluginId ->
                configs[pluginId] = PluginConfig(
                    pluginId = pluginId,
                    enabled = enabled,
                    className = className,
                    autoInitialize = autoInit,
                    priority = priority,
                    settings = currentSettings.toMap()
                )
                currentSettings.clear()
                enabled = true
                className = null
                autoInit = true
                priority = 0
            }
        }

        file.forEachLine { line ->
            val trimmed = line.trim()

            when {
                // 주석 무시
                trimmed.startsWith("#") || trimmed.isEmpty() -> {}

                // 전역 설정 섹션
                trimmed == "[global]" -> {
                    saveCurrentPlugin()
                    currentPlugin = null
                }

                // 플러그인 섹션 시작
                trimmed.startsWith("[plugin.") && trimmed.endsWith("]") -> {
                    saveCurrentPlugin()
                    currentPlugin = trimmed.removePrefix("[plugin.").removeSuffix("]")
                }

                // 키=값 쌍
                trimmed.contains("=") -> {
                    val (key, value) = trimmed.split("=", limit = 2).map { it.trim() }
                    val cleanValue = value.trim('"', '\'')

                    if (currentPlugin == null) {
                        // 전역 설정
                        globalSettings[key] = cleanValue
                    } else {
                        // 플러그인 설정
                        when (key) {
                            "enabled" -> enabled = cleanValue.toBooleanStrictOrNull() ?: true
                            "class" -> className = cleanValue
                            "autoInitialize" -> autoInit = cleanValue.toBooleanStrictOrNull() ?: true
                            "priority" -> priority = cleanValue.toIntOrNull() ?: 0
                            else -> currentSettings[key] = cleanValue
                        }
                    }
                }
            }
        }

        // 마지막 플러그인 저장
        saveCurrentPlugin()
    }

    /**
     * Properties 파일에서 로드
     */
    private fun loadFromProperties(file: File) {
        val props = Properties()
        file.inputStream().use { props.load(it) }

        // plugin.{id}.{key} = value 형식
        val pluginSettings = mutableMapOf<String, MutableMap<String, String>>()

        for ((key, value) in props) {
            val keyStr = key.toString()
            val valueStr = value.toString()

            when {
                keyStr.startsWith("plugin.") -> {
                    val parts = keyStr.removePrefix("plugin.").split(".", limit = 2)
                    if (parts.size == 2) {
                        val pluginId = parts[0]
                        val settingKey = parts[1]
                        pluginSettings.getOrPut(pluginId) { mutableMapOf() }[settingKey] = valueStr
                    }
                }
                keyStr.startsWith("global.") -> {
                    globalSettings[keyStr.removePrefix("global.")] = valueStr
                }
            }
        }

        // 플러그인 설정 객체 생성
        for ((pluginId, settings) in pluginSettings) {
            configs[pluginId] = PluginConfig(
                pluginId = pluginId,
                enabled = settings["enabled"]?.toBooleanStrictOrNull() ?: true,
                className = settings["class"],
                autoInitialize = settings["autoInitialize"]?.toBooleanStrictOrNull() ?: true,
                priority = settings["priority"]?.toIntOrNull() ?: 0,
                settings = settings.filterKeys { it !in listOf("enabled", "class", "autoInitialize", "priority") }
            )
        }
    }

    /**
     * 환경 변수에서 설정 로드
     * 형식: CLAUDE_FLOW_PLUGIN_{PLUGIN_ID}_{KEY}
     */
    private fun loadFromEnvironment() {
        val prefix = "CLAUDE_FLOW_PLUGIN_"

        System.getenv().filter { it.key.startsWith(prefix) }.forEach { (key, value) ->
            val parts = key.removePrefix(prefix).split("_", limit = 2)
            if (parts.size == 2) {
                val pluginId = parts[0].lowercase()
                val settingKey = parts[1].lowercase()

                val existing = configs[pluginId]
                val newSettings = (existing?.settings ?: emptyMap()).toMutableMap()
                newSettings[settingKey] = value

                configs[pluginId] = (existing ?: PluginConfig(pluginId)).copy(
                    settings = newSettings
                )
            }
        }
    }

    /**
     * 플러그인 설정 조회
     */
    fun getPluginConfig(pluginId: String): PluginConfig? = configs[pluginId]

    /**
     * 모든 플러그인 설정 조회
     */
    fun getAllPluginConfigs(): Map<String, PluginConfig> = configs.toMap()

    /**
     * 활성화된 플러그인 설정만 조회
     */
    fun getEnabledPluginConfigs(): Map<String, PluginConfig> =
        configs.filter { it.value.enabled }

    /**
     * 전역 설정 조회
     */
    fun getGlobalSetting(key: String): String? = globalSettings[key]

    /**
     * 전역 설정 조회 (기본값)
     */
    fun getGlobalSetting(key: String, default: String): String =
        globalSettings[key] ?: default

    /**
     * 플러그인 설정 추가/업데이트
     */
    fun setPluginConfig(config: PluginConfig) {
        configs[config.pluginId] = config
        logger.debug { "Plugin config updated: ${config.pluginId}" }
    }

    /**
     * 플러그인 활성화/비활성화
     */
    fun setEnabled(pluginId: String, enabled: Boolean): Boolean {
        val config = configs[pluginId] ?: return false
        configs[pluginId] = config.copy(enabled = enabled)
        logger.info { "Plugin ${if (enabled) "enabled" else "disabled"}: $pluginId" }
        return true
    }

    /**
     * 플러그인 설정값 업데이트
     */
    fun updateSetting(pluginId: String, key: String, value: String): Boolean {
        val config = configs[pluginId] ?: return false
        val newSettings = config.settings.toMutableMap()
        newSettings[key] = value
        configs[pluginId] = config.copy(settings = newSettings)
        return true
    }

    /**
     * 플러그인 설정 삭제
     */
    fun removePluginConfig(pluginId: String): PluginConfig? {
        return configs.remove(pluginId)
    }

    /**
     * 전역 설정 업데이트
     */
    fun setGlobalSetting(key: String, value: String) {
        globalSettings[key] = value
    }

    /**
     * 설정 파일로 저장 (Properties 형식)
     */
    fun saveToFile(path: String): Boolean {
        try {
            val props = Properties()

            // 전역 설정
            globalSettings.forEach { (key, value) ->
                props["global.$key"] = value
            }

            // 플러그인 설정
            configs.forEach { (pluginId, config) ->
                props["plugin.$pluginId.enabled"] = config.enabled.toString()
                config.className?.let { props["plugin.$pluginId.class"] = it }
                props["plugin.$pluginId.autoInitialize"] = config.autoInitialize.toString()
                props["plugin.$pluginId.priority"] = config.priority.toString()

                config.settings.forEach { (key, value) ->
                    props["plugin.$pluginId.$key"] = value
                }
            }

            File(path).outputStream().use {
                props.store(it, "Claude Flow Plugin Configuration")
            }

            logger.info { "Plugin config saved to: $path" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to save plugin config to: $path" }
            return false
        }
    }

    /**
     * 설정 초기화
     */
    fun clear() {
        configs.clear()
        globalSettings.clear()
        logger.info { "Plugin config cleared" }
    }

    /**
     * 설정 요약
     */
    fun getSummary(): ConfigSummary {
        return ConfigSummary(
            totalPlugins = configs.size,
            enabledPlugins = configs.values.count { it.enabled },
            globalSettingsCount = globalSettings.size,
            pluginIds = configs.keys.toList()
        )
    }

    data class ConfigSummary(
        val totalPlugins: Int,
        val enabledPlugins: Int,
        val globalSettingsCount: Int,
        val pluginIds: List<String>
    )
}
