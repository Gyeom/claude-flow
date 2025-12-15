package ai.claudeflow.core.storage

import ai.claudeflow.core.storage.query.DeleteBuilder
import ai.claudeflow.core.storage.query.InsertBuilder
import ai.claudeflow.core.storage.query.QueryBuilder
import ai.claudeflow.core.storage.query.UpdateBuilder
import mu.KotlinLogging
import java.sql.Connection
import java.sql.ResultSet

/**
 * Abstract base repository with common CRUD operations
 *
 * Provides reusable query building, connection management, and row mapping.
 */
abstract class BaseRepository<T, ID>(
    protected val connectionProvider: ConnectionProvider
) : Repository<T, ID> {

    protected val logger = KotlinLogging.logger {}

    /**
     * Table name for this repository
     */
    protected abstract val tableName: String

    /**
     * Primary key column name
     */
    protected abstract val primaryKeyColumn: String

    /**
     * Map a ResultSet row to entity
     */
    protected abstract fun mapRow(rs: ResultSet): T

    /**
     * Get the ID from an entity
     */
    protected abstract fun getId(entity: T): ID

    protected val connection: Connection
        get() = connectionProvider.getConnection()

    // Query Builder helpers
    protected fun query(): QueryBuilder = QueryBuilder.from(connection, tableName)

    protected fun insert(): InsertBuilder = InsertBuilder(connection, tableName)

    protected fun update(): UpdateBuilder = UpdateBuilder(connection, tableName)

    protected fun delete(): DeleteBuilder = DeleteBuilder(connection, tableName)

    override fun findById(id: ID): T? {
        return query()
            .select("*")
            .where("$primaryKeyColumn = ?", id)
            .executeOne { mapRow(it) }
    }

    override fun findAll(): List<T> {
        return query()
            .select("*")
            .execute { mapRow(it) }
    }

    override fun deleteById(id: ID): Boolean {
        return delete()
            .where("$primaryKeyColumn = ?", id)
            .execute() > 0
    }

    override fun existsById(id: ID): Boolean {
        return query()
            .select("1")
            .where("$primaryKeyColumn = ?", id)
            .executeOne { true } ?: false
    }

    override fun count(): Long {
        return query()
            .select("COUNT(*)")
            .executeOne { it.getLong(1) } ?: 0L
    }

    /**
     * Find with pagination
     */
    fun findAll(pageRequest: PageRequest): Page<T> {
        val total = count()

        val query = query()
            .select("*")
            .limit(pageRequest.size)
            .offset(pageRequest.offset)

        pageRequest.sortBy?.let { sortBy ->
            query.orderBy(
                sortBy,
                if (pageRequest.sortDirection == SortDirection.ASC)
                    QueryBuilder.SortDirection.ASC
                else
                    QueryBuilder.SortDirection.DESC
            )
        }

        val content = query.execute { mapRow(it) }

        return Page.of(content, pageRequest.page, pageRequest.size, total)
    }

    /**
     * Find by date range with pagination
     */
    protected fun findByDateRange(
        dateColumn: String,
        dateRange: DateRange,
        pageRequest: PageRequest? = null
    ): List<T> {
        val query = query()
            .select("*")
            .whereBetween(dateColumn, dateRange.from.toString(), dateRange.to.toString())
            .orderBy(dateColumn, QueryBuilder.SortDirection.DESC)

        pageRequest?.let {
            query.limit(it.size).offset(it.offset)
        }

        return query.execute { mapRow(it) }
    }

    /**
     * Count by condition
     */
    protected fun countWhere(condition: String, vararg params: Any?): Long {
        return query()
            .select("COUNT(*)")
            .where(condition, *params)
            .executeOne { it.getLong(1) } ?: 0L
    }

    /**
     * Execute raw SQL query
     */
    protected fun <R> executeQuery(sql: String, vararg params: Any?, mapper: (ResultSet) -> R): List<R> {
        val results = mutableListOf<R>()
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    null -> stmt.setNull(index + 1, java.sql.Types.NULL)
                    is String -> stmt.setString(index + 1, param)
                    is Int -> stmt.setInt(index + 1, param)
                    is Long -> stmt.setLong(index + 1, param)
                    is Double -> stmt.setDouble(index + 1, param)
                    else -> stmt.setObject(index + 1, param)
                }
            }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        return results
    }

    /**
     * Execute raw SQL for single result
     */
    protected fun <R> executeQueryOne(sql: String, vararg params: Any?, mapper: (ResultSet) -> R): R? {
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    null -> stmt.setNull(index + 1, java.sql.Types.NULL)
                    is String -> stmt.setString(index + 1, param)
                    is Int -> stmt.setInt(index + 1, param)
                    is Long -> stmt.setLong(index + 1, param)
                    is Double -> stmt.setDouble(index + 1, param)
                    else -> stmt.setObject(index + 1, param)
                }
            }
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return mapper(rs)
                }
            }
        }
        return null
    }

    /**
     * Execute update SQL
     */
    protected fun executeUpdate(sql: String, vararg params: Any?): Int {
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                when (param) {
                    null -> stmt.setNull(index + 1, java.sql.Types.NULL)
                    is String -> stmt.setString(index + 1, param)
                    is Int -> stmt.setInt(index + 1, param)
                    is Long -> stmt.setLong(index + 1, param)
                    is Double -> stmt.setDouble(index + 1, param)
                    is Boolean -> stmt.setBoolean(index + 1, param)
                    else -> stmt.setObject(index + 1, param)
                }
            }
            return stmt.executeUpdate()
        }
    }
}
