package ai.claudeflow.core.storage

import ai.claudeflow.core.model.Agent
import mu.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite 기반 스토리지
 */
class Storage(dbPath: String = "claude-flow.db") {
    private val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        initTables()
        logger.info { "Storage initialized: $dbPath" }
    }

    private fun initTables() {
        connection.createStatement().use { stmt ->
            // 실행 이력 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS executions (
                    id TEXT PRIMARY KEY,
                    prompt TEXT NOT NULL,
                    result TEXT,
                    status TEXT NOT NULL,
                    agent_id TEXT NOT NULL,
                    project_id TEXT,
                    user_id TEXT,
                    channel TEXT,
                    thread_ts TEXT,
                    reply_ts TEXT,
                    duration_ms INTEGER,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    cost REAL,
                    error TEXT,
                    created_at TEXT NOT NULL
                )
            """)

            // 피드백 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id TEXT PRIMARY KEY,
                    execution_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    reaction TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (execution_id) REFERENCES executions(id)
                )
            """)

            // 프로젝트 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    working_directory TEXT NOT NULL,
                    git_remote TEXT,
                    default_branch TEXT DEFAULT 'main',
                    created_at TEXT NOT NULL
                )
            """)

            // 채널-프로젝트 매핑 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS channel_projects (
                    channel TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    FOREIGN KEY (project_id) REFERENCES projects(id)
                )
            """)

            // 사용자 컨텍스트 테이블 (확장)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_contexts (
                    user_id TEXT PRIMARY KEY,
                    display_name TEXT,
                    preferred_language TEXT DEFAULT 'ko',
                    domain TEXT,
                    last_seen TEXT NOT NULL,
                    total_interactions INTEGER DEFAULT 0,
                    summary TEXT,
                    summary_updated_at TEXT,
                    summary_lock_id TEXT,
                    summary_lock_at TEXT,
                    total_chars INTEGER DEFAULT 0
                )
            """)

            // 사용자 규칙 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_rules (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    rule TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)

            // 설정 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)

            // 에이전트 테이블 (프로젝트별 에이전트 지원)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS agents (
                    id TEXT NOT NULL,
                    project_id TEXT,
                    name TEXT NOT NULL,
                    description TEXT,
                    keywords TEXT,
                    system_prompt TEXT NOT NULL,
                    model TEXT DEFAULT 'claude-sonnet-4-20250514',
                    max_tokens INTEGER DEFAULT 4096,
                    allowed_tools TEXT,
                    working_directory TEXT,
                    enabled INTEGER DEFAULT 1,
                    priority INTEGER DEFAULT 0,
                    examples TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (id, project_id)
                )
            """)

            // 인덱스 생성
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_channel ON executions(channel)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_created ON executions(created_at)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_user ON executions(user_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_feedback_execution ON feedback(execution_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agents_project ON agents(project_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_rules_user ON user_rules(user_id)")
        }
    }

    // ==================== Execution ====================

    fun saveExecution(record: ExecutionRecord) {
        val sql = """
            INSERT INTO executions (id, prompt, result, status, agent_id, project_id, user_id,
                channel, thread_ts, reply_ts, duration_ms, input_tokens, output_tokens, cost, error, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, record.id)
            stmt.setString(2, record.prompt)
            stmt.setString(3, record.result)
            stmt.setString(4, record.status)
            stmt.setString(5, record.agentId)
            stmt.setString(6, record.projectId)
            stmt.setString(7, record.userId)
            stmt.setString(8, record.channel)
            stmt.setString(9, record.threadTs)
            stmt.setString(10, record.replyTs)
            stmt.setLong(11, record.durationMs)
            stmt.setInt(12, record.inputTokens)
            stmt.setInt(13, record.outputTokens)
            stmt.setObject(14, record.cost)
            stmt.setString(15, record.error)
            stmt.setString(16, record.createdAt.toString())
            stmt.executeUpdate()
        }
    }

    fun getExecution(id: String): ExecutionRecord? {
        val sql = "SELECT * FROM executions WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return ExecutionRecord(
                        id = rs.getString("id"),
                        prompt = rs.getString("prompt"),
                        result = rs.getString("result"),
                        status = rs.getString("status"),
                        agentId = rs.getString("agent_id"),
                        projectId = rs.getString("project_id"),
                        userId = rs.getString("user_id"),
                        channel = rs.getString("channel"),
                        threadTs = rs.getString("thread_ts"),
                        replyTs = rs.getString("reply_ts"),
                        durationMs = rs.getLong("duration_ms"),
                        inputTokens = rs.getInt("input_tokens"),
                        outputTokens = rs.getInt("output_tokens"),
                        cost = rs.getObject("cost") as? Double,
                        error = rs.getString("error"),
                        createdAt = Instant.parse(rs.getString("created_at"))
                    )
                }
            }
        }
        return null
    }

    fun findExecutionByReplyTs(replyTs: String): ExecutionRecord? {
        val sql = "SELECT * FROM executions WHERE reply_ts = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, replyTs)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return ExecutionRecord(
                        id = rs.getString("id"),
                        prompt = rs.getString("prompt"),
                        result = rs.getString("result"),
                        status = rs.getString("status"),
                        agentId = rs.getString("agent_id"),
                        projectId = rs.getString("project_id"),
                        userId = rs.getString("user_id"),
                        channel = rs.getString("channel"),
                        threadTs = rs.getString("thread_ts"),
                        replyTs = rs.getString("reply_ts"),
                        durationMs = rs.getLong("duration_ms"),
                        inputTokens = rs.getInt("input_tokens"),
                        outputTokens = rs.getInt("output_tokens"),
                        cost = rs.getObject("cost") as? Double,
                        error = rs.getString("error"),
                        createdAt = Instant.parse(rs.getString("created_at"))
                    )
                }
            }
        }
        return null
    }

    fun getRecentExecutions(limit: Int = 50): List<ExecutionRecord> {
        val sql = "SELECT * FROM executions ORDER BY created_at DESC LIMIT ?"
        val results = mutableListOf<ExecutionRecord>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        ExecutionRecord(
                            id = rs.getString("id"),
                            prompt = rs.getString("prompt"),
                            result = rs.getString("result"),
                            status = rs.getString("status"),
                            agentId = rs.getString("agent_id"),
                            projectId = rs.getString("project_id"),
                            userId = rs.getString("user_id"),
                            channel = rs.getString("channel"),
                            threadTs = rs.getString("thread_ts"),
                            replyTs = rs.getString("reply_ts"),
                            durationMs = rs.getLong("duration_ms"),
                            inputTokens = rs.getInt("input_tokens"),
                            outputTokens = rs.getInt("output_tokens"),
                            cost = rs.getObject("cost") as? Double,
                            error = rs.getString("error"),
                            createdAt = Instant.parse(rs.getString("created_at"))
                        )
                    )
                }
            }
        }
        return results
    }

    fun updateExecutionReplyTs(id: String, replyTs: String) {
        val sql = "UPDATE executions SET reply_ts = ? WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, replyTs)
            stmt.setString(2, id)
            stmt.executeUpdate()
        }
    }

    // ==================== Feedback ====================

    fun saveFeedback(record: FeedbackRecord) {
        val sql = "INSERT INTO feedback (id, execution_id, user_id, reaction, created_at) VALUES (?, ?, ?, ?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, record.id)
            stmt.setString(2, record.executionId)
            stmt.setString(3, record.userId)
            stmt.setString(4, record.reaction)
            stmt.setString(5, record.createdAt.toString())
            stmt.executeUpdate()
        }
    }

    fun deleteFeedback(executionId: String, userId: String, reaction: String) {
        val sql = "DELETE FROM feedback WHERE execution_id = ? AND user_id = ? AND reaction = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, executionId)
            stmt.setString(2, userId)
            stmt.setString(3, reaction)
            stmt.executeUpdate()
        }
    }

    fun getFeedbackForExecution(executionId: String): List<FeedbackRecord> {
        val sql = "SELECT * FROM feedback WHERE execution_id = ?"
        val results = mutableListOf<FeedbackRecord>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, executionId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        FeedbackRecord(
                            id = rs.getString("id"),
                            executionId = rs.getString("execution_id"),
                            userId = rs.getString("user_id"),
                            reaction = rs.getString("reaction"),
                            createdAt = Instant.parse(rs.getString("created_at"))
                        )
                    )
                }
            }
        }
        return results
    }

    // ==================== User Context ====================

    fun getUserContext(userId: String): UserContext? {
        val sql = "SELECT * FROM user_contexts WHERE user_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rowToUserContext(rs)
                }
            }
        }
        return null
    }

    fun saveUserContext(context: UserContext) {
        val sql = """
            INSERT OR REPLACE INTO user_contexts
            (user_id, display_name, preferred_language, domain, last_seen, total_interactions,
             summary, summary_updated_at, summary_lock_id, summary_lock_at, total_chars)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, context.userId)
            stmt.setString(2, context.displayName)
            stmt.setString(3, context.preferredLanguage)
            stmt.setString(4, context.domain)
            stmt.setString(5, context.lastSeen.toString())
            stmt.setInt(6, context.totalInteractions)
            stmt.setString(7, context.summary)
            stmt.setString(8, context.summaryUpdatedAt?.toString())
            stmt.setString(9, context.summaryLockId)
            stmt.setString(10, context.summaryLockAt?.toString())
            stmt.setLong(11, context.totalChars)
            stmt.executeUpdate()
        }
    }

    fun getAllUserContexts(): List<UserContext> {
        val sql = "SELECT * FROM user_contexts ORDER BY last_seen DESC"
        val results = mutableListOf<UserContext>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    results.add(rowToUserContext(rs))
                }
            }
        }
        return results
    }

    /**
     * 사용자 컨텍스트 응답 조회 (규칙, 요약, 최근 대화 포함)
     */
    fun getUserContextResponse(
        userId: String,
        acquireLock: Boolean = false,
        lockId: String? = null
    ): UserContextResponse {
        val context = getUserContext(userId)
        val rules = getUserRules(userId)
        val recentConversations = getRecentConversations(userId)
        val conversationCount = getConversationCount(userId)

        val needsSummary = context?.let {
            UserContextResponse.needsSummary(
                it.totalChars,
                conversationCount,
                it.summaryUpdatedAt,
                it.summary
            )
        } ?: false

        var newLockId: String? = null
        var summaryLocked = context?.summaryLockId != null &&
            context.summaryLockAt?.let {
                Instant.now().epochSecond - it.epochSecond < UserContextResponse.SUMMARY_LOCK_TTL_SECS
            } ?: false

        if (acquireLock && needsSummary && !summaryLocked) {
            newLockId = lockId ?: java.util.UUID.randomUUID().toString()
            acquireSummaryLock(userId, newLockId)
            summaryLocked = true
        }

        return UserContextResponse(
            rules = rules,
            summary = context?.summary,
            recentConversations = recentConversations,
            totalConversationCount = conversationCount,
            needsSummary = needsSummary,
            summaryLocked = summaryLocked,
            lockId = newLockId
        )
    }

    /**
     * 사용자 요약 저장
     */
    fun saveUserSummary(userId: String, summary: String) {
        val sql = """
            UPDATE user_contexts
            SET summary = ?, summary_updated_at = ?, summary_lock_id = NULL, summary_lock_at = NULL
            WHERE user_id = ?
        """
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, summary)
            stmt.setString(2, Instant.now().toString())
            stmt.setString(3, userId)
            stmt.executeUpdate()
        }
    }

    /**
     * 요약 잠금 획득
     */
    fun acquireSummaryLock(userId: String, lockId: String): Boolean {
        val sql = """
            UPDATE user_contexts
            SET summary_lock_id = ?, summary_lock_at = ?
            WHERE user_id = ? AND (summary_lock_id IS NULL OR summary_lock_at < ?)
        """
        val now = Instant.now()
        val expiredBefore = now.minusSeconds(UserContextResponse.SUMMARY_LOCK_TTL_SECS)
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, lockId)
            stmt.setString(2, now.toString())
            stmt.setString(3, userId)
            stmt.setString(4, expiredBefore.toString())
            return stmt.executeUpdate() > 0
        }
    }

    /**
     * 요약 잠금 해제
     */
    fun releaseSummaryLock(userId: String, lockId: String): Boolean {
        val sql = "UPDATE user_contexts SET summary_lock_id = NULL, summary_lock_at = NULL WHERE user_id = ? AND summary_lock_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, lockId)
            return stmt.executeUpdate() > 0
        }
    }

    /**
     * 최근 대화 조회
     */
    fun getRecentConversations(userId: String, limit: Int = 10): List<RecentConversation> {
        val sql = """
            SELECT e.id, e.prompt, e.result, e.created_at,
                   EXISTS(SELECT 1 FROM feedback f WHERE f.execution_id = e.id) as has_reactions
            FROM executions e
            WHERE e.user_id = ?
            ORDER BY e.created_at DESC
            LIMIT ?
        """
        val results = mutableListOf<RecentConversation>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.setInt(2, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        RecentConversation(
                            id = rs.getString("id"),
                            userMessage = rs.getString("prompt"),
                            response = rs.getString("result"),
                            createdAt = rs.getString("created_at"),
                            hasReactions = rs.getBoolean("has_reactions")
                        )
                    )
                }
            }
        }
        return results
    }

    /**
     * 사용자 대화 수 조회
     */
    fun getConversationCount(userId: String): Int {
        val sql = "SELECT COUNT(*) FROM executions WHERE user_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getInt(1)
                }
            }
        }
        return 0
    }

    // ==================== User Rules ====================

    /**
     * 사용자 규칙 조회
     */
    fun getUserRules(userId: String): List<String> {
        val sql = "SELECT rule FROM user_rules WHERE user_id = ? ORDER BY created_at"
        val results = mutableListOf<String>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(rs.getString("rule"))
                }
            }
        }
        return results
    }

    /**
     * 사용자 규칙 추가
     */
    fun addUserRule(userId: String, rule: String): Boolean {
        // 중복 체크
        val existing = getUserRules(userId)
        if (existing.contains(rule)) return false

        val sql = "INSERT INTO user_rules (id, user_id, rule, created_at) VALUES (?, ?, ?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, java.util.UUID.randomUUID().toString())
            stmt.setString(2, userId)
            stmt.setString(3, rule)
            stmt.setString(4, Instant.now().toString())
            stmt.executeUpdate()
        }
        return true
    }

    /**
     * 사용자 규칙 삭제
     */
    fun deleteUserRule(userId: String, rule: String): Boolean {
        val sql = "DELETE FROM user_rules WHERE user_id = ? AND rule = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, rule)
            return stmt.executeUpdate() > 0
        }
    }

    private fun rowToUserContext(rs: java.sql.ResultSet): UserContext {
        return UserContext(
            userId = rs.getString("user_id"),
            displayName = rs.getString("display_name"),
            preferredLanguage = rs.getString("preferred_language") ?: "ko",
            domain = rs.getString("domain"),
            lastSeen = Instant.parse(rs.getString("last_seen")),
            totalInteractions = rs.getInt("total_interactions"),
            summary = rs.getString("summary"),
            summaryUpdatedAt = rs.getString("summary_updated_at")?.let { Instant.parse(it) },
            summaryLockId = rs.getString("summary_lock_id"),
            summaryLockAt = rs.getString("summary_lock_at")?.let { Instant.parse(it) },
            totalChars = rs.getLong("total_chars")
        )
    }

    // ==================== Settings ====================

    fun getSetting(key: String): String? {
        val sql = "SELECT value FROM settings WHERE key = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, key)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getString("value")
                }
            }
        }
        return null
    }

    fun setSetting(key: String, value: String) {
        val sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
    }

    // ==================== Stats ====================

    fun getStats(): StorageStats {
        val totalExecutions = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM executions").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

        val successCount = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM executions WHERE status = 'SUCCESS'").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

        val totalTokens = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT SUM(input_tokens + output_tokens) FROM executions").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }

        val avgDuration = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT AVG(duration_ms) FROM executions WHERE status = 'SUCCESS'").use { rs ->
                if (rs.next()) rs.getDouble(1) else 0.0
            }
        }

        val thumbsUp = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM feedback WHERE reaction = 'thumbsup' OR reaction = '+1'").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

        val thumbsDown = connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM feedback WHERE reaction = 'thumbsdown' OR reaction = '-1'").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

        return StorageStats(
            totalExecutions = totalExecutions,
            successRate = if (totalExecutions > 0) successCount.toDouble() / totalExecutions else 0.0,
            totalTokens = totalTokens,
            avgDurationMs = avgDuration,
            thumbsUp = thumbsUp,
            thumbsDown = thumbsDown
        )
    }

    fun close() {
        connection.close()
    }

    // ==================== Agent ====================

    /**
     * 에이전트 저장 (프로젝트별 에이전트)
     */
    fun saveAgent(agent: Agent) {
        val sql = """
            INSERT OR REPLACE INTO agents
            (id, project_id, name, description, keywords, system_prompt, model, max_tokens,
             allowed_tools, working_directory, enabled, priority, examples, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        val now = Instant.now().toString()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, agent.id)
            stmt.setString(2, agent.projectId ?: "global")
            stmt.setString(3, agent.name)
            stmt.setString(4, agent.description)
            stmt.setString(5, agent.keywords.joinToString(","))
            stmt.setString(6, agent.systemPrompt)
            stmt.setString(7, agent.model)
            stmt.setInt(8, agent.maxTokens)
            stmt.setString(9, agent.allowedTools.joinToString(","))
            stmt.setString(10, agent.workingDirectory)
            stmt.setInt(11, if (agent.enabled) 1 else 0)
            stmt.setInt(12, agent.priority)
            stmt.setString(13, agent.examples.joinToString("|||"))
            stmt.setString(14, now)
            stmt.setString(15, now)
            stmt.executeUpdate()
        }
        logger.info { "Saved agent: ${agent.id} (project: ${agent.projectId ?: "global"})" }
    }

    /**
     * 에이전트 조회
     */
    fun getAgent(agentId: String, projectId: String? = null): Agent? {
        val sql = "SELECT * FROM agents WHERE id = ? AND project_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, agentId)
            stmt.setString(2, projectId ?: "global")
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return rowToAgent(rs)
                }
            }
        }
        return null
    }

    /**
     * 프로젝트별 에이전트 목록 조회
     */
    fun getAgentsByProject(projectId: String? = null): List<Agent> {
        val sql = "SELECT * FROM agents WHERE project_id = ? ORDER BY priority DESC"
        val results = mutableListOf<Agent>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, projectId ?: "global")
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(rowToAgent(rs))
                }
            }
        }
        return results
    }

    /**
     * 모든 에이전트 목록 조회
     */
    fun getAllAgents(): List<Agent> {
        val sql = "SELECT * FROM agents ORDER BY project_id, priority DESC"
        val results = mutableListOf<Agent>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    results.add(rowToAgent(rs))
                }
            }
        }
        return results
    }

    /**
     * 에이전트 삭제
     */
    fun deleteAgent(agentId: String, projectId: String? = null): Boolean {
        val sql = "DELETE FROM agents WHERE id = ? AND project_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, agentId)
            stmt.setString(2, projectId ?: "global")
            val deleted = stmt.executeUpdate() > 0
            if (deleted) {
                logger.info { "Deleted agent: $agentId (project: ${projectId ?: "global"})" }
            }
            return deleted
        }
    }

    /**
     * 에이전트 활성화/비활성화
     */
    fun setAgentEnabled(agentId: String, projectId: String?, enabled: Boolean): Boolean {
        val sql = "UPDATE agents SET enabled = ?, updated_at = ? WHERE id = ? AND project_id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, if (enabled) 1 else 0)
            stmt.setString(2, Instant.now().toString())
            stmt.setString(3, agentId)
            stmt.setString(4, projectId ?: "global")
            return stmt.executeUpdate() > 0
        }
    }

    private fun rowToAgent(rs: java.sql.ResultSet): Agent {
        val projectIdValue = rs.getString("project_id")
        return Agent(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description") ?: "",
            keywords = rs.getString("keywords")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            systemPrompt = rs.getString("system_prompt"),
            model = rs.getString("model") ?: "claude-sonnet-4-20250514",
            maxTokens = rs.getInt("max_tokens"),
            allowedTools = rs.getString("allowed_tools")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            workingDirectory = rs.getString("working_directory"),
            enabled = rs.getInt("enabled") == 1,
            priority = rs.getInt("priority"),
            examples = rs.getString("examples")?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
            projectId = if (projectIdValue == "global") null else projectIdValue
        )
    }
}

data class StorageStats(
    val totalExecutions: Int,
    val successRate: Double,
    val totalTokens: Long,
    val avgDurationMs: Double,
    val thumbsUp: Int,
    val thumbsDown: Int
)
