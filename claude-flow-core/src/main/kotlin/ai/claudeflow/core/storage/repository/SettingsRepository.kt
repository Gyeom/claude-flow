package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import java.sql.ResultSet

/**
 * Repository for key-value settings storage
 */
class SettingsRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<Setting, String>(connectionProvider) {

    override val tableName: String = "settings"
    override val primaryKeyColumn: String = "key"

    override fun mapRow(rs: ResultSet): Setting {
        return Setting(
            key = rs.getString("key"),
            value = rs.getString("value")
        )
    }

    override fun getId(entity: Setting): String = entity.key

    override fun save(entity: Setting) {
        insert()
            .columns(
                "key" to entity.key,
                "value" to entity.value
            )
            .executeOrReplace()
    }

    /**
     * Get setting value by key
     */
    fun getValue(key: String): String? {
        return query()
            .select("value")
            .where("key = ?", key)
            .executeOne { it.getString("value") }
    }

    /**
     * Set or update a setting
     */
    fun setValue(key: String, value: String) {
        save(Setting(key, value))
    }

    /**
     * Get setting as Int
     */
    fun getInt(key: String, default: Int = 0): Int {
        return getValue(key)?.toIntOrNull() ?: default
    }

    /**
     * Get setting as Long
     */
    fun getLong(key: String, default: Long = 0L): Long {
        return getValue(key)?.toLongOrNull() ?: default
    }

    /**
     * Get setting as Boolean
     */
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return getValue(key)?.toBooleanStrictOrNull() ?: default
    }

    /**
     * Get setting as Double
     */
    fun getDouble(key: String, default: Double = 0.0): Double {
        return getValue(key)?.toDoubleOrNull() ?: default
    }

    /**
     * Get all settings as a map
     */
    fun getAllAsMap(): Map<String, String> {
        return findAll().associate { it.key to it.value }
    }

    /**
     * Delete a setting
     */
    fun deleteValue(key: String): Boolean {
        return deleteById(key)
    }

    /**
     * Check if a setting exists
     */
    fun hasKey(key: String): Boolean {
        return existsById(key)
    }
}

data class Setting(
    val key: String,
    val value: String
)
