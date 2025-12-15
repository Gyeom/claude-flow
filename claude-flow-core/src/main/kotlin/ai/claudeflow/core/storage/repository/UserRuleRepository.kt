package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.storage.*
import ai.claudeflow.core.storage.query.QueryBuilder
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

// UserRule is defined in ExecutionRecord.kt

/**
 * Repository for user rules
 */
class UserRuleRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<UserRule, String>(connectionProvider) {

    override val tableName: String = "user_rules"
    override val primaryKeyColumn: String = "id"

    override fun mapRow(rs: ResultSet): UserRule {
        return UserRule(
            id = rs.getString("id"),
            userId = rs.getString("user_id"),
            rule = rs.getString("rule"),
            createdAt = Instant.parse(rs.getString("created_at"))
        )
    }

    override fun getId(entity: UserRule): String = entity.id

    override fun save(entity: UserRule) {
        insert()
            .columns(
                "id" to entity.id,
                "user_id" to entity.userId,
                "rule" to entity.rule,
                "created_at" to entity.createdAt.toString()
            )
            .execute()
    }

    /**
     * Find all rules for a user (returns rule strings only)
     */
    fun findRulesByUserId(userId: String): List<String> {
        return query()
            .select("rule")
            .where("user_id = ?", userId)
            .orderBy("created_at", QueryBuilder.SortDirection.ASC)
            .execute { it.getString("rule") }
    }

    /**
     * Find all user rules (full objects)
     */
    fun findByUserId(userId: String): List<UserRule> {
        return query()
            .select("*")
            .where("user_id = ?", userId)
            .orderBy("created_at", QueryBuilder.SortDirection.ASC)
            .execute { mapRow(it) }
    }

    /**
     * Add a new rule for user (with duplicate check)
     * @return true if rule was added, false if duplicate
     */
    fun addRule(userId: String, rule: String): Boolean {
        // Check for duplicate
        val existing = findRulesByUserId(userId)
        if (existing.contains(rule)) {
            return false
        }

        save(UserRule(
            id = UUID.randomUUID().toString(),
            userId = userId,
            rule = rule,
            createdAt = Instant.now()
        ))
        return true
    }

    /**
     * Delete a specific rule for user
     */
    fun deleteRule(userId: String, rule: String): Boolean {
        return delete()
            .where("user_id = ?", userId)
            .where("rule = ?", rule)
            .execute() > 0
    }

    /**
     * Delete all rules for user
     */
    fun deleteAllForUser(userId: String): Int {
        return delete()
            .where("user_id = ?", userId)
            .execute()
    }

    /**
     * Count rules for user
     */
    fun countByUserId(userId: String): Long {
        return countWhere("user_id = ?", userId)
    }
}
