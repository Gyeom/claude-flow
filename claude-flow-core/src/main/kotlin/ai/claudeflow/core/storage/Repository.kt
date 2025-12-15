package ai.claudeflow.core.storage

import java.sql.Connection

/**
 * Base Repository interface for CRUD operations
 *
 * Provides standard data access patterns with type safety.
 */
interface Repository<T, ID> {
    /**
     * Save an entity (insert or update)
     */
    fun save(entity: T)

    /**
     * Find entity by ID
     */
    fun findById(id: ID): T?

    /**
     * Find all entities
     */
    fun findAll(): List<T>

    /**
     * Delete entity by ID
     * @return true if entity was deleted
     */
    fun deleteById(id: ID): Boolean

    /**
     * Check if entity exists by ID
     */
    fun existsById(id: ID): Boolean

    /**
     * Count all entities
     */
    fun count(): Long
}

/**
 * Pageable request for pagination
 */
data class PageRequest(
    val page: Int = 0,
    val size: Int = 20,
    val sortBy: String? = null,
    val sortDirection: SortDirection = SortDirection.DESC
) {
    val offset: Int get() = page * size

    companion object {
        fun of(page: Int, size: Int): PageRequest = PageRequest(page, size)
        fun first(size: Int): PageRequest = PageRequest(0, size)
    }
}

enum class SortDirection {
    ASC, DESC
}

/**
 * Page result wrapper
 */
data class Page<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    val hasNext: Boolean get() = page < totalPages - 1
    val hasPrevious: Boolean get() = page > 0
    val isFirst: Boolean get() = page == 0
    val isLast: Boolean get() = page == totalPages - 1 || totalPages == 0

    companion object {
        fun <T> of(content: List<T>, page: Int, size: Int, totalElements: Long): Page<T> {
            val totalPages = if (size > 0) ((totalElements + size - 1) / size).toInt() else 0
            return Page(content, page, size, totalElements, totalPages)
        }

        fun <T> empty(): Page<T> = Page(emptyList(), 0, 0, 0, 0)
    }
}

/**
 * Date range for filtering
 */
data class DateRange(
    val from: java.time.Instant,
    val to: java.time.Instant
) {
    companion object {
        fun lastDays(days: Int): DateRange {
            val now = java.time.Instant.now()
            return DateRange(
                from = now.minusSeconds(days * 24 * 60 * 60L),
                to = now
            )
        }

        fun lastHours(hours: Int): DateRange {
            val now = java.time.Instant.now()
            return DateRange(
                from = now.minusSeconds(hours * 60 * 60L),
                to = now
            )
        }
    }
}

/**
 * Connection provider interface
 */
interface ConnectionProvider {
    fun getConnection(): Connection
}
