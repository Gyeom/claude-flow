package ai.claudeflow.core.plugin

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 플러그인 레지스트리
 *
 * 플러그인의 중앙 집중식 등록 및 조회 관리.
 * Thread-safe하게 설계되어 동시 접근 가능.
 */
class PluginRegistry {
    private val plugins = ConcurrentHashMap<String, PluginEntry>()
    private val pluginsByType = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * 플러그인 등록 엔트리
     */
    data class PluginEntry(
        val plugin: Plugin,
        val metadata: PluginMetadata,
        val config: Map<String, String>,
        val registeredAt: Long = System.currentTimeMillis()
    )

    /**
     * 플러그인 등록
     */
    fun register(plugin: Plugin, metadata: PluginMetadata, config: Map<String, String> = emptyMap()): Boolean {
        if (plugins.containsKey(plugin.id)) {
            logger.warn { "Plugin already registered: ${plugin.id}" }
            return false
        }

        val entry = PluginEntry(plugin, metadata, config)
        plugins[plugin.id] = entry

        // 타입별 인덱스 추가
        val pluginType = plugin::class.simpleName ?: "Unknown"
        pluginsByType.getOrPut(pluginType) { mutableListOf() }.add(plugin.id)

        logger.info { "Plugin registered: ${plugin.id} (${plugin.name}) v${metadata.version}" }
        return true
    }

    /**
     * 플러그인 등록 해제
     */
    fun unregister(pluginId: String): Plugin? {
        val entry = plugins.remove(pluginId)
        if (entry != null) {
            val pluginType = entry.plugin::class.simpleName ?: "Unknown"
            pluginsByType[pluginType]?.remove(pluginId)
            logger.info { "Plugin unregistered: $pluginId" }
        }
        return entry?.plugin
    }

    /**
     * 플러그인 조회
     */
    fun get(pluginId: String): Plugin? = plugins[pluginId]?.plugin

    /**
     * 플러그인 엔트리 조회
     */
    fun getEntry(pluginId: String): PluginEntry? = plugins[pluginId]

    /**
     * 플러그인 존재 여부
     */
    fun contains(pluginId: String): Boolean = plugins.containsKey(pluginId)

    /**
     * 모든 플러그인 목록
     */
    fun getAll(): List<Plugin> = plugins.values.map { it.plugin }

    /**
     * 모든 플러그인 엔트리 목록
     */
    fun getAllEntries(): List<PluginEntry> = plugins.values.toList()

    /**
     * 활성화된 플러그인 목록
     */
    fun getEnabled(): List<Plugin> = plugins.values.filter { it.plugin.enabled }.map { it.plugin }

    /**
     * 비활성화된 플러그인 목록
     */
    fun getDisabled(): List<Plugin> = plugins.values.filter { !it.plugin.enabled }.map { it.plugin }

    /**
     * 타입별 플러그인 조회
     */
    fun getByType(typeName: String): List<Plugin> {
        val ids = pluginsByType[typeName] ?: return emptyList()
        return ids.mapNotNull { plugins[it]?.plugin }
    }

    /**
     * 플러그인 설정 조회
     */
    fun getConfig(pluginId: String): Map<String, String>? = plugins[pluginId]?.config

    /**
     * 플러그인 설정 업데이트
     */
    fun updateConfig(pluginId: String, config: Map<String, String>): Boolean {
        val entry = plugins[pluginId] ?: return false
        plugins[pluginId] = entry.copy(config = config)
        logger.debug { "Plugin config updated: $pluginId" }
        return true
    }

    /**
     * 플러그인 메타데이터 조회
     */
    fun getMetadata(pluginId: String): PluginMetadata? = plugins[pluginId]?.metadata

    /**
     * 메시지를 처리할 수 있는 플러그인 찾기
     */
    fun findHandler(message: String): Plugin? {
        return getEnabled().find { it.shouldHandle(message) }
    }

    /**
     * 메시지를 처리할 수 있는 모든 플러그인 찾기
     */
    fun findAllHandlers(message: String): List<Plugin> {
        return getEnabled().filter { it.shouldHandle(message) }
    }

    /**
     * 특정 명령어를 지원하는 플러그인 찾기
     */
    fun findByCommand(commandName: String): Plugin? {
        return getEnabled().find { plugin ->
            plugin.commands.any { it.name.equals(commandName, ignoreCase = true) }
        }
    }

    /**
     * 등록된 플러그인 수
     */
    fun count(): Int = plugins.size

    /**
     * 활성화된 플러그인 수
     */
    fun countEnabled(): Int = plugins.values.count { it.plugin.enabled }

    /**
     * 레지스트리 초기화 (모든 플러그인 제거)
     */
    fun clear() {
        plugins.clear()
        pluginsByType.clear()
        logger.info { "Plugin registry cleared" }
    }

    /**
     * 플러그인 상태 요약
     */
    fun getSummary(): RegistrySummary {
        val allEntries = plugins.values.toList()
        return RegistrySummary(
            totalPlugins = allEntries.size,
            enabledPlugins = allEntries.count { it.plugin.enabled },
            disabledPlugins = allEntries.count { !it.plugin.enabled },
            pluginsByType = pluginsByType.mapValues { it.value.size }
        )
    }

    data class RegistrySummary(
        val totalPlugins: Int,
        val enabledPlugins: Int,
        val disabledPlugins: Int,
        val pluginsByType: Map<String, Int>
    )
}
