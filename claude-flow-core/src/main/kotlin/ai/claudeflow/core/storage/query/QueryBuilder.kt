package ai.claudeflow.core.storage.query

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Type-safe SQL Query Builder
 *
 * Provides fluent API for building SQL queries with proper parameter binding
 * to prevent SQL injection and improve code readability.
 */
class QueryBuilder(private val connection: Connection) {

    private var selectClause: String = "*"
    private var fromClause: String = ""
    private var whereConditions: MutableList<String> = mutableListOf()
    private var parameters: MutableList<Any?> = mutableListOf()
    private var orderByClause: String? = null
    private var limitValue: Int? = null
    private var offsetValue: Int? = null
    private var groupByClause: String? = null
    private var joinClauses: MutableList<String> = mutableListOf()

    fun select(columns: String): QueryBuilder {
        selectClause = columns
        return this
    }

    fun select(vararg columns: String): QueryBuilder {
        selectClause = columns.joinToString(", ")
        return this
    }

    fun from(table: String): QueryBuilder {
        fromClause = table
        return this
    }

    fun join(joinType: JoinType, table: String, condition: String): QueryBuilder {
        joinClauses.add("${joinType.sql} $table ON $condition")
        return this
    }

    fun leftJoin(table: String, condition: String): QueryBuilder =
        join(JoinType.LEFT, table, condition)

    fun innerJoin(table: String, condition: String): QueryBuilder =
        join(JoinType.INNER, table, condition)

    fun where(condition: String, vararg params: Any?): QueryBuilder {
        whereConditions.add(condition)
        parameters.addAll(params)
        return this
    }

    fun whereIf(condition: Boolean, clause: String, vararg params: Any?): QueryBuilder {
        if (condition) {
            whereConditions.add(clause)
            parameters.addAll(params)
        }
        return this
    }

    fun whereIn(column: String, values: List<Any>): QueryBuilder {
        if (values.isNotEmpty()) {
            val placeholders = values.joinToString(", ") { "?" }
            whereConditions.add("$column IN ($placeholders)")
            parameters.addAll(values)
        }
        return this
    }

    fun whereBetween(column: String, from: Any, to: Any): QueryBuilder {
        whereConditions.add("$column BETWEEN ? AND ?")
        parameters.add(from)
        parameters.add(to)
        return this
    }

    fun whereLike(column: String, pattern: String): QueryBuilder {
        whereConditions.add("$column LIKE ?")
        parameters.add(pattern)
        return this
    }

    fun whereNotNull(column: String): QueryBuilder {
        whereConditions.add("$column IS NOT NULL")
        return this
    }

    fun whereNull(column: String): QueryBuilder {
        whereConditions.add("$column IS NULL")
        return this
    }

    fun and(condition: String, vararg params: Any?): QueryBuilder = where(condition, *params)

    fun orderBy(column: String, direction: SortDirection = SortDirection.ASC): QueryBuilder {
        orderByClause = "$column ${direction.sql}"
        return this
    }

    fun orderBy(vararg columns: Pair<String, SortDirection>): QueryBuilder {
        orderByClause = columns.joinToString(", ") { "${it.first} ${it.second.sql}" }
        return this
    }

    fun groupBy(vararg columns: String): QueryBuilder {
        groupByClause = columns.joinToString(", ")
        return this
    }

    fun limit(limit: Int): QueryBuilder {
        limitValue = limit
        return this
    }

    fun offset(offset: Int): QueryBuilder {
        offsetValue = offset
        return this
    }

    fun build(): String {
        val sql = StringBuilder()
        sql.append("SELECT $selectClause FROM $fromClause")

        joinClauses.forEach { sql.append(" $it") }

        if (whereConditions.isNotEmpty()) {
            sql.append(" WHERE ${whereConditions.joinToString(" AND ")}")
        }

        groupByClause?.let { sql.append(" GROUP BY $it") }
        orderByClause?.let { sql.append(" ORDER BY $it") }
        limitValue?.let { sql.append(" LIMIT $it") }
        offsetValue?.let { sql.append(" OFFSET $it") }

        return sql.toString()
    }

    fun prepare(): PreparedStatement {
        val sql = build()
        val stmt = connection.prepareStatement(sql)
        parameters.forEachIndexed { index, param ->
            setParameter(stmt, index + 1, param)
        }
        return stmt
    }

    fun <T> execute(mapper: (ResultSet) -> T): List<T> {
        val results = mutableListOf<T>()
        prepare().use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        return results
    }

    fun <T> executeOne(mapper: (ResultSet) -> T): T? {
        prepare().use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return mapper(rs)
                }
            }
        }
        return null
    }

    fun executeCount(): Long {
        val countQuery = QueryBuilder(connection)
            .select("COUNT(*)")
            .from(fromClause)

        whereConditions.forEach { countQuery.whereConditions.add(it) }
        countQuery.parameters.addAll(parameters)
        joinClauses.forEach { countQuery.joinClauses.add(it) }

        return countQuery.executeOne { it.getLong(1) } ?: 0L
    }

    private fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(index, java.sql.Types.NULL)
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Double -> stmt.setDouble(index, value)
            is Float -> stmt.setFloat(index, value)
            is Boolean -> stmt.setBoolean(index, value)
            is java.time.Instant -> stmt.setString(index, value.toString())
            else -> stmt.setObject(index, value)
        }
    }

    enum class JoinType(val sql: String) {
        INNER("INNER JOIN"),
        LEFT("LEFT JOIN"),
        RIGHT("RIGHT JOIN"),
        FULL("FULL OUTER JOIN")
    }

    enum class SortDirection(val sql: String) {
        ASC("ASC"),
        DESC("DESC")
    }

    companion object {
        fun from(connection: Connection, table: String): QueryBuilder {
            return QueryBuilder(connection).from(table)
        }
    }
}

/**
 * Insert Builder for constructing INSERT statements
 */
class InsertBuilder(private val connection: Connection, private val table: String) {
    private val columns = mutableListOf<String>()
    private val values = mutableListOf<Any?>()

    fun column(name: String, value: Any?): InsertBuilder {
        columns.add(name)
        values.add(value)
        return this
    }

    fun columns(vararg pairs: Pair<String, Any?>): InsertBuilder {
        pairs.forEach { (name, value) ->
            columns.add(name)
            values.add(value)
        }
        return this
    }

    fun build(): String {
        val placeholders = columns.map { "?" }.joinToString(", ")
        return "INSERT INTO $table (${columns.joinToString(", ")}) VALUES ($placeholders)"
    }

    fun buildOrReplace(): String {
        val placeholders = columns.map { "?" }.joinToString(", ")
        return "INSERT OR REPLACE INTO $table (${columns.joinToString(", ")}) VALUES ($placeholders)"
    }

    fun execute(): Int {
        return executeInternal(build())
    }

    fun executeOrReplace(): Int {
        return executeInternal(buildOrReplace())
    }

    private fun executeInternal(sql: String): Int {
        connection.prepareStatement(sql).use { stmt ->
            values.forEachIndexed { index, value ->
                setParameter(stmt, index + 1, value)
            }
            return stmt.executeUpdate()
        }
    }

    private fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(index, java.sql.Types.NULL)
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Double -> stmt.setDouble(index, value)
            is Float -> stmt.setFloat(index, value)
            is Boolean -> stmt.setBoolean(index, value)
            is java.time.Instant -> stmt.setString(index, value.toString())
            else -> stmt.setObject(index, value)
        }
    }
}

/**
 * Update Builder for constructing UPDATE statements
 */
class UpdateBuilder(private val connection: Connection, private val table: String) {
    private val setClause = mutableMapOf<String, Any?>()
    private val whereConditions = mutableListOf<String>()
    private val whereParams = mutableListOf<Any?>()

    fun set(column: String, value: Any?): UpdateBuilder {
        setClause[column] = value
        return this
    }

    fun set(vararg pairs: Pair<String, Any?>): UpdateBuilder {
        pairs.forEach { (column, value) -> setClause[column] = value }
        return this
    }

    fun where(condition: String, vararg params: Any?): UpdateBuilder {
        whereConditions.add(condition)
        whereParams.addAll(params)
        return this
    }

    fun execute(): Int {
        val setClauses = setClause.keys.map { "$it = ?" }.joinToString(", ")
        val sql = StringBuilder("UPDATE $table SET $setClauses")

        if (whereConditions.isNotEmpty()) {
            sql.append(" WHERE ${whereConditions.joinToString(" AND ")}")
        }

        connection.prepareStatement(sql.toString()).use { stmt ->
            var index = 1
            setClause.values.forEach { value ->
                setParameter(stmt, index++, value)
            }
            whereParams.forEach { param ->
                setParameter(stmt, index++, param)
            }
            return stmt.executeUpdate()
        }
    }

    private fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(index, java.sql.Types.NULL)
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Double -> stmt.setDouble(index, value)
            is Float -> stmt.setFloat(index, value)
            is Boolean -> stmt.setBoolean(index, value)
            is java.time.Instant -> stmt.setString(index, value.toString())
            else -> stmt.setObject(index, value)
        }
    }
}

/**
 * Delete Builder for constructing DELETE statements
 */
class DeleteBuilder(private val connection: Connection, private val table: String) {
    private val whereConditions = mutableListOf<String>()
    private val whereParams = mutableListOf<Any?>()

    fun where(condition: String, vararg params: Any?): DeleteBuilder {
        whereConditions.add(condition)
        whereParams.addAll(params)
        return this
    }

    fun execute(): Int {
        val sql = StringBuilder("DELETE FROM $table")

        if (whereConditions.isNotEmpty()) {
            sql.append(" WHERE ${whereConditions.joinToString(" AND ")}")
        }

        connection.prepareStatement(sql.toString()).use { stmt ->
            whereParams.forEachIndexed { index, param ->
                setParameter(stmt, index + 1, param)
            }
            return stmt.executeUpdate()
        }
    }

    private fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.setNull(index, java.sql.Types.NULL)
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            is Long -> stmt.setLong(index, value)
            is Double -> stmt.setDouble(index, value)
            is Float -> stmt.setFloat(index, value)
            is Boolean -> stmt.setBoolean(index, value)
            is java.time.Instant -> stmt.setString(index, value.toString())
            else -> stmt.setObject(index, value)
        }
    }
}
