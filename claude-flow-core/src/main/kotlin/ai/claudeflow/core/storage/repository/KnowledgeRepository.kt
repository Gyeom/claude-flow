package ai.claudeflow.core.storage.repository

import ai.claudeflow.core.knowledge.*
import ai.claudeflow.core.storage.BaseRepository
import ai.claudeflow.core.storage.ConnectionProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet
import java.time.Instant

/**
 * Knowledge Document Repository
 *
 * 지식 문서의 CRUD 및 상태 관리를 담당합니다.
 */
class KnowledgeRepository(
    connectionProvider: ConnectionProvider
) : BaseRepository<KnowledgeDocument, String>(connectionProvider) {

    override val tableName = "knowledge_documents"
    override val primaryKeyColumn = "id"

    // ObjectMapper 싱글톤 (매번 생성 방지)
    private val objectMapper = jacksonObjectMapper()

    init {
        // 테이블 생성
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS $tableName (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT,
                    source TEXT NOT NULL,
                    source_url TEXT,
                    source_file TEXT,
                    mime_type TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    chunk_count INTEGER DEFAULT 0,
                    error_message TEXT,
                    metadata TEXT,
                    project_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_indexed_at TEXT,
                    last_synced_at TEXT
                )
            """.trimIndent())
        }
        createIndexes()
    }

    private fun createIndexes() {
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_project ON $tableName(project_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_source ON $tableName(source)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_status ON $tableName(status)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_source_url ON $tableName(source_url)")
        }
    }

    override fun getId(entity: KnowledgeDocument): String = entity.id

    override fun mapRow(rs: ResultSet): KnowledgeDocument {
        return KnowledgeDocument(
            id = rs.getString("id"),
            title = rs.getString("title"),
            content = rs.getString("content") ?: "",
            source = SourceType.valueOf(rs.getString("source")),
            sourceUrl = rs.getString("source_url"),
            sourceFile = rs.getString("source_file"),
            mimeType = rs.getString("mime_type"),
            status = IndexStatus.valueOf(rs.getString("status")),
            chunkCount = rs.getInt("chunk_count"),
            errorMessage = rs.getString("error_message"),
            metadata = parseJsonMap(rs.getString("metadata")),
            projectId = rs.getString("project_id"),
            createdAt = parseInstant(rs.getString("created_at")),
            updatedAt = parseInstant(rs.getString("updated_at")),
            lastIndexedAt = rs.getString("last_indexed_at")?.let { parseInstant(it) },
            lastSyncedAt = rs.getString("last_synced_at")?.let { parseInstant(it) }
        )
    }

    override fun save(entity: KnowledgeDocument) {
        val sql = """
            INSERT INTO $tableName (
                id, title, content, source, source_url, source_file, mime_type,
                status, chunk_count, error_message, metadata, project_id,
                created_at, updated_at, last_indexed_at, last_synced_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                content = excluded.content,
                source = excluded.source,
                source_url = excluded.source_url,
                source_file = excluded.source_file,
                mime_type = excluded.mime_type,
                status = excluded.status,
                chunk_count = excluded.chunk_count,
                error_message = excluded.error_message,
                metadata = excluded.metadata,
                updated_at = excluded.updated_at,
                last_indexed_at = excluded.last_indexed_at,
                last_synced_at = excluded.last_synced_at
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, entity.id)
            stmt.setString(2, entity.title)
            stmt.setString(3, entity.content)
            stmt.setString(4, entity.source.name)
            stmt.setString(5, entity.sourceUrl)
            stmt.setString(6, entity.sourceFile)
            stmt.setString(7, entity.mimeType)
            stmt.setString(8, entity.status.name)
            stmt.setInt(9, entity.chunkCount)
            stmt.setString(10, entity.errorMessage)
            stmt.setString(11, toJson(entity.metadata))
            stmt.setString(12, entity.projectId)
            stmt.setString(13, entity.createdAt.toString())
            stmt.setString(14, entity.updatedAt.toString())
            stmt.setString(15, entity.lastIndexedAt?.toString())
            stmt.setString(16, entity.lastSyncedAt?.toString())
            stmt.executeUpdate()
        }
    }

    /**
     * Source URL로 문서 조회
     */
    fun findBySourceUrl(sourceUrl: String): KnowledgeDocument? {
        return executeQueryOne(
            "SELECT * FROM $tableName WHERE source_url = ?",
            sourceUrl
        ) { mapRow(it) }
    }

    /**
     * 프로젝트별 문서 조회
     */
    fun findByProject(projectId: String?): List<KnowledgeDocument> {
        return if (projectId != null) {
            executeQuery(
                "SELECT * FROM $tableName WHERE project_id = ? ORDER BY updated_at DESC",
                projectId
            ) { mapRow(it) }
        } else {
            executeQuery(
                "SELECT * FROM $tableName ORDER BY updated_at DESC"
            ) { mapRow(it) }
        }
    }

    /**
     * 상태별 문서 조회
     */
    fun findByStatus(status: IndexStatus): List<KnowledgeDocument> {
        return executeQuery(
            "SELECT * FROM $tableName WHERE status = ? ORDER BY updated_at DESC",
            status.name
        ) { mapRow(it) }
    }

    /**
     * 동기화 필요한 문서 조회 (URL 소스, 마지막 동기화 후 일정 시간 경과)
     */
    fun findNeedingSync(hoursThreshold: Int = 24): List<KnowledgeDocument> {
        val threshold = Instant.now().minusSeconds(hoursThreshold * 3600L)
        return executeQuery(
            """
            SELECT * FROM $tableName
            WHERE source IN ('URL', 'CONFLUENCE', 'NOTION')
            AND (last_synced_at IS NULL OR last_synced_at < ?)
            AND status != 'ERROR'
            ORDER BY last_synced_at ASC NULLS FIRST
            """.trimIndent(),
            threshold.toString()
        ) { mapRow(it) }
    }

    /**
     * 문서 상태 업데이트
     */
    fun updateStatus(id: String, status: IndexStatus, errorMessage: String? = null) {
        executeUpdate(
            """
            UPDATE $tableName
            SET status = ?, error_message = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            status.name, errorMessage, Instant.now().toString(), id
        )
    }

    /**
     * 인덱싱 완료 업데이트
     */
    fun markIndexed(id: String, chunkCount: Int) {
        val now = Instant.now()
        executeUpdate(
            """
            UPDATE $tableName
            SET status = 'INDEXED', chunk_count = ?,
                last_indexed_at = ?, updated_at = ?, error_message = NULL
            WHERE id = ?
            """.trimIndent(),
            chunkCount, now.toString(), now.toString(), id
        )
    }

    /**
     * 동기화 완료 업데이트
     */
    fun markSynced(id: String) {
        val now = Instant.now()
        executeUpdate(
            """
            UPDATE $tableName
            SET last_synced_at = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            now.toString(), now.toString(), id
        )
    }

    /**
     * 통계 조회
     */
    fun getStats(projectId: String? = null): KnowledgeStats {
        val whereClause = if (projectId != null) "WHERE project_id = ?" else ""
        val params = if (projectId != null) arrayOf<Any?>(projectId) else emptyArray()

        // Total documents
        val totalDocs = executeQueryOne(
            "SELECT COUNT(*) FROM $tableName $whereClause",
            *params
        ) { it.getInt(1) } ?: 0

        // Total chunks
        val totalChunks = executeQueryOne(
            "SELECT COALESCE(SUM(chunk_count), 0) FROM $tableName $whereClause",
            *params
        ) { it.getInt(1) } ?: 0

        // By source
        val bySource = mutableMapOf<SourceType, Int>()
        executeQuery(
            "SELECT source, COUNT(*) FROM $tableName $whereClause GROUP BY source",
            *params
        ) {
            SourceType.valueOf(it.getString(1)) to it.getInt(2)
        }.forEach { bySource[it.first] = it.second }

        // By status
        val byStatus = mutableMapOf<IndexStatus, Int>()
        executeQuery(
            "SELECT status, COUNT(*) FROM $tableName $whereClause GROUP BY status",
            *params
        ) {
            IndexStatus.valueOf(it.getString(1)) to it.getInt(2)
        }.forEach { byStatus[it.first] = it.second }

        // Last updated
        val lastUpdated = executeQueryOne(
            "SELECT MAX(updated_at) FROM $tableName $whereClause",
            *params
        ) { it.getString(1)?.let { ts -> parseInstant(ts) } }

        return KnowledgeStats(
            totalDocuments = totalDocs,
            totalChunks = totalChunks,
            bySource = bySource,
            byStatus = byStatus,
            recentQueries = 0, // TODO: 쿼리 로그에서 조회
            lastUpdated = lastUpdated
        )
    }

    /**
     * 문서 삭제
     */
    fun delete(id: String): Boolean {
        return deleteById(id)
    }

    private fun parseInstant(s: String): Instant = Instant.parse(s)

    private fun parseJsonMap(json: String?): Map<String, Any> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toJson(map: Map<String, Any>): String {
        return objectMapper.writeValueAsString(map)
    }
}
