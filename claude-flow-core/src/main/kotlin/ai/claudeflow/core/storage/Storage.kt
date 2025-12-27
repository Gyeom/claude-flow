package ai.claudeflow.core.storage

import ai.claudeflow.core.model.Agent
import ai.claudeflow.core.model.Project
import ai.claudeflow.core.storage.repository.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * SQLite 기반 스토리지 (Facade Pattern)
 *
 * 하위 호환성을 유지하면서 Repository 패턴으로 위임합니다.
 * 새 코드에서는 개별 Repository를 직접 사용하는 것을 권장합니다.
 */
class Storage(dbPath: String = "claude-flow.db") : ConnectionProvider {
    private val connection: Connection

    // Repositories
    val executionRepository: ExecutionRepository
    val feedbackRepository: FeedbackRepository
    val userContextRepository: UserContextRepository
    val userRuleRepository: UserRuleRepository
    val agentRepository: AgentRepository
    val settingsRepository: SettingsRepository
    val analyticsRepository: AnalyticsRepository
    val projectRepository: ProjectRepository
    val projectAliasRepository: ProjectAliasRepository
    val sessionRepository: SessionRepository
    val sessionMessageRepository: SessionMessageRepository
    val knowledgeRepository: KnowledgeRepository

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        initTables()
        migrateSchema()

        // Initialize repositories
        executionRepository = ExecutionRepository(this)
        feedbackRepository = FeedbackRepository(this)
        userContextRepository = UserContextRepository(this)
        userRuleRepository = UserRuleRepository(this)
        agentRepository = AgentRepository(this)
        settingsRepository = SettingsRepository(this)
        analyticsRepository = AnalyticsRepository(this, executionRepository, feedbackRepository)
        projectRepository = ProjectRepository(this)
        projectAliasRepository = ProjectAliasRepository(this)
        sessionRepository = SessionRepository(this)
        sessionMessageRepository = SessionMessageRepository(this)
        knowledgeRepository = KnowledgeRepository(this)

        // 기본 프로젝트 및 Aliases 초기화
        initDefaultProjectsAndAliases()

        logger.info { "Storage initialized: $dbPath" }
    }

    /**
     * 프로젝트 초기화
     *
     * 1. config/projects.json 파일이 있으면 해당 설정으로 프로젝트 로드
     * 2. 없으면 WORKSPACE_PATH 환경변수의 기본 프로젝트 생성
     */
    private fun initDefaultProjectsAndAliases() {
        val workspacePath = System.getenv("WORKSPACE_PATH")
            ?: System.getenv("HOME")?.let { "$it/workspace" }
            ?: "/workspace"

        // config/projects.json 파일 확인
        val configFile = File("config/projects.json")
        if (configFile.exists()) {
            loadProjectsFromConfig(configFile, workspacePath)
            return
        }

        // 설정 파일이 없으면 기본 프로젝트만 등록
        if (projectRepository.findAll().isEmpty()) {
            val defaultProject = Project(
                id = "default",
                name = "Default Project",
                description = "Default workspace project",
                workingDirectory = workspacePath,
                gitRemote = null,
                defaultBranch = "develop",
                isDefault = true
            )
            try {
                projectRepository.save(defaultProject)
                logger.info { "Registered default project at: $workspacePath" }
            } catch (e: Exception) {
                logger.warn { "Failed to register default project: ${e.message}" }
            }
        }
    }

    /**
     * config/projects.json에서 프로젝트 로드 (Upsert)
     *
     * 설정 파일의 값이 항상 최신으로 반영됨 (DB 값 덮어쓰기)
     */
    private fun loadProjectsFromConfig(configFile: File, workspacePath: String) {
        try {
            val mapper = jacksonObjectMapper()
            val config: ProjectsFileConfig = mapper.readValue(configFile)
            val defaultBranch = config.defaultBranch ?: "develop"

            // 환경변수에서 GitLab 기본 그룹 로드 (예: sirius/ccds)
            val gitlabGroup = System.getenv("GITLAB_GROUP")

            var inserted = 0
            var updated = 0

            config.projects.forEach { entry ->
                // GitLab 경로 결정: 명시적 gitlabPath > gitlabGroup 기반 자동 생성
                val resolvedGitlabPath = entry.gitlabPath
                    ?: gitlabGroup?.let { "$it/${entry.path}" }

                val project = Project(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    workingDirectory = "$workspacePath/${entry.path}",
                    gitRemote = entry.gitRemote,
                    gitlabPath = resolvedGitlabPath,
                    defaultBranch = entry.defaultBranch ?: defaultBranch,
                    isDefault = entry.isDefault ?: false,
                    aliases = entry.aliases ?: emptyList()
                )

                try {
                    // save()는 내부적으로 INSERT OR REPLACE 사용 (upsert)
                    val isNew = projectRepository.findById(entry.id) == null
                    projectRepository.save(project)
                    if (isNew) inserted++ else updated++
                    logger.debug { "${if (isNew) "Inserted" else "Updated"} project: ${project.id} (gitlab: ${project.gitlabPath})" }
                } catch (e: Exception) {
                    logger.warn { "Failed to upsert project ${entry.id}: ${e.message}" }
                }

                // Aliases도 Upsert (save가 INSERT OR REPLACE 사용)
                val aliases = entry.aliases
                if (!aliases.isNullOrEmpty()) {
                    val aliasEntity = ProjectAliasEntity(
                        projectId = entry.id,
                        patterns = aliases,
                        description = entry.description
                    )
                    try {
                        projectAliasRepository.save(aliasEntity)
                    } catch (e: Exception) {
                        logger.warn { "Failed to upsert aliases for ${entry.id}: ${e.message}" }
                    }
                }
            }

            logger.info { "Synced ${config.projects.size} projects from config/projects.json (inserted: $inserted, updated: $updated)" }
        } catch (e: Exception) {
            logger.error { "Failed to load projects from config: ${e.message}" }
        }
    }

    override fun getConnection(): Connection = connection

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
                    model TEXT,
                    source TEXT,
                    routing_method TEXT,
                    routing_confidence REAL,
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
                    category TEXT DEFAULT 'other',
                    source TEXT DEFAULT 'unknown',
                    is_verified INTEGER DEFAULT 0,
                    verified_at TEXT,
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
                    is_default INTEGER DEFAULT 0,
                    enable_user_context INTEGER DEFAULT 1,
                    classify_model TEXT DEFAULT 'haiku',
                    classify_timeout INTEGER DEFAULT 30,
                    rate_limit_rpm INTEGER DEFAULT 0,
                    allowed_tools TEXT,
                    disallowed_tools TEXT,
                    fallback_agent_id TEXT DEFAULT 'general',
                    aliases TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
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

            // 사용자 컨텍스트 테이블
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

            // 에이전트 테이블
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
                    timeout INTEGER,
                    static_response INTEGER DEFAULT 0,
                    output_schema TEXT,
                    isolated INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (id, project_id)
                )
            """)

            // 라우팅 메트릭 테이블 (새로 추가)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS routing_metrics (
                    id TEXT PRIMARY KEY,
                    execution_id TEXT,
                    routing_method TEXT NOT NULL,
                    agent_id TEXT,
                    confidence REAL,
                    latency_ms INTEGER,
                    created_at TEXT NOT NULL
                )
            """)

            // Dead Letter Queue 테이블 (실패한 메시지 저장)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id TEXT PRIMARY KEY,
                    event_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    channel TEXT,
                    user_id TEXT,
                    text TEXT,
                    endpoint TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    error_message TEXT,
                    retry_count INTEGER DEFAULT 0,
                    failed_at TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)

            // 프로젝트 별칭 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS project_aliases (
                    project_id TEXT PRIMARY KEY,
                    patterns TEXT NOT NULL,
                    description TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """)

            // 세션 테이블 (스레드 기반 대화 세션)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    claude_session_id TEXT,
                    created_at TEXT NOT NULL,
                    last_activity_at TEXT NOT NULL
                )
            """)

            // 세션 메시지 테이블
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """)

            // GitLab AI 리뷰 레코드 테이블 (피드백 수집용)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS gitlab_reviews (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    mr_iid INTEGER NOT NULL,
                    note_id INTEGER NOT NULL,
                    discussion_id TEXT,
                    review_content TEXT NOT NULL,
                    mr_context TEXT,
                    created_at TEXT NOT NULL,
                    UNIQUE(project_id, mr_iid, note_id)
                )
            """)

            // 인덱스 생성
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_channel ON executions(channel)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_created ON executions(created_at)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gitlab_reviews_note ON gitlab_reviews(note_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gitlab_reviews_project_mr ON gitlab_reviews(project_id, mr_iid)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_executions_user ON executions(user_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_feedback_execution ON feedback(execution_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agents_project ON agents(project_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_rules_user ON user_rules(user_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_routing_metrics_created ON routing_metrics(created_at)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dead_letter_created ON dead_letter_queue(created_at)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_channel ON sessions(channel)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_activity ON sessions(last_activity_at)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_session_messages_session ON session_messages(session_id)")
        }
    }

    /**
     * 기존 DB 스키마 마이그레이션
     */
    private fun migrateSchema() {
        connection.createStatement().use { stmt ->
            // projects 테이블에 새 컬럼 추가 (기존 테이블에 없을 경우)
            val projectColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(projects)").use { rs ->
                while (rs.next()) {
                    projectColumns.add(rs.getString("name"))
                }
            }

            val newProjectColumns = mapOf(
                "is_default" to "INTEGER DEFAULT 0",
                "enable_user_context" to "INTEGER DEFAULT 1",
                "classify_model" to "TEXT DEFAULT 'haiku'",
                "classify_timeout" to "INTEGER DEFAULT 30",
                "rate_limit_rpm" to "INTEGER DEFAULT 0",
                "allowed_tools" to "TEXT",
                "disallowed_tools" to "TEXT",
                "fallback_agent_id" to "TEXT DEFAULT 'general'",
                "updated_at" to "TEXT",
                "created_at" to "TEXT",
                "gitlab_path" to "TEXT",
                "aliases" to "TEXT"
            )

            for ((column, definition) in newProjectColumns) {
                if (!projectColumns.contains(column)) {
                    try {
                        stmt.executeUpdate("ALTER TABLE projects ADD COLUMN $column $definition")
                        logger.info { "Migrated: Added column $column to projects table" }
                    } catch (e: Exception) {
                        logger.warn { "Migration skipped for $column: ${e.message}" }
                    }
                }
            }

            // executions 테이블에 새 컬럼 추가 (기존 테이블에 없을 경우)
            val executionColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(executions)").use { rs ->
                while (rs.next()) {
                    executionColumns.add(rs.getString("name"))
                }
            }

            val newExecutionColumns = mapOf(
                "model" to "TEXT",
                "source" to "TEXT",
                "routing_method" to "TEXT",
                "routing_confidence" to "REAL"
            )

            for ((column, definition) in newExecutionColumns) {
                if (!executionColumns.contains(column)) {
                    try {
                        stmt.executeUpdate("ALTER TABLE executions ADD COLUMN $column $definition")
                        logger.info { "Migrated: Added column $column to executions table" }
                    } catch (e: Exception) {
                        logger.warn { "Migration skipped for $column: ${e.message}" }
                    }
                }
            }

            // feedback 테이블에 GitLab 피드백 컬럼 추가
            val feedbackColumns = mutableSetOf<String>()
            stmt.executeQuery("PRAGMA table_info(feedback)").use { rs ->
                while (rs.next()) {
                    feedbackColumns.add(rs.getString("name"))
                }
            }

            val newFeedbackColumns = mapOf(
                "source" to "TEXT DEFAULT 'slack'",           // slack, gitlab_emoji, gitlab_note
                "gitlab_project_id" to "TEXT",                // GitLab 프로젝트 ID
                "gitlab_mr_iid" to "INTEGER",                 // MR 번호
                "gitlab_note_id" to "INTEGER",                // 코멘트 ID
                "comment" to "TEXT"                           // 답글 내용 (gitlab_note 용)
            )

            for ((column, definition) in newFeedbackColumns) {
                if (!feedbackColumns.contains(column)) {
                    try {
                        stmt.executeUpdate("ALTER TABLE feedback ADD COLUMN $column $definition")
                        logger.info { "Migrated: Added column $column to feedback table" }
                    } catch (e: Exception) {
                        logger.warn { "Migration skipped for $column: ${e.message}" }
                    }
                }
            }
        }
    }

    // ==================== Execution (Delegated) ====================

    @Deprecated("Use executionRepository.save() directly", ReplaceWith("executionRepository.save(record)"))
    fun saveExecution(record: ExecutionRecord) = executionRepository.save(record)

    @Deprecated("Use executionRepository.findById() directly", ReplaceWith("executionRepository.findById(id)"))
    fun getExecution(id: String): ExecutionRecord? = executionRepository.findById(id)

    @Deprecated("Use executionRepository.findByReplyTs() directly", ReplaceWith("executionRepository.findByReplyTs(replyTs)"))
    fun findExecutionByReplyTs(replyTs: String): ExecutionRecord? = executionRepository.findByReplyTs(replyTs)

    @Deprecated("Use executionRepository.findRecent() directly", ReplaceWith("executionRepository.findRecent(limit)"))
    fun getRecentExecutions(limit: Int = 50): List<ExecutionRecord> = executionRepository.findRecent(limit)

    @Deprecated("Use executionRepository.updateReplyTs() directly", ReplaceWith("executionRepository.updateReplyTs(id, replyTs)"))
    fun updateExecutionReplyTs(id: String, replyTs: String) { executionRepository.updateReplyTs(id, replyTs) }

    // ==================== Feedback (Delegated) ====================

    @Deprecated("Use feedbackRepository.save() directly", ReplaceWith("feedbackRepository.save(record)"))
    fun saveFeedback(record: FeedbackRecord) = feedbackRepository.save(record)

    @Deprecated("Use feedbackRepository.deleteByExecutionUserReaction() directly")
    fun deleteFeedback(executionId: String, userId: String, reaction: String) {
        feedbackRepository.deleteByExecutionUserReaction(executionId, userId, reaction)
    }

    @Deprecated("Use feedbackRepository.findByExecutionId() directly", ReplaceWith("feedbackRepository.findByExecutionId(executionId)"))
    fun getFeedbackForExecution(executionId: String): List<FeedbackRecord> = feedbackRepository.findByExecutionId(executionId)

    // ==================== User Context (Delegated) ====================

    @Deprecated("Use userContextRepository.findById() directly", ReplaceWith("userContextRepository.findById(userId)"))
    fun getUserContext(userId: String): UserContext? = userContextRepository.findById(userId)

    @Deprecated("Use userContextRepository.save() directly", ReplaceWith("userContextRepository.save(context)"))
    fun saveUserContext(context: UserContext) = userContextRepository.save(context)

    @Deprecated("Use userContextRepository.findAll() directly", ReplaceWith("userContextRepository.findAll()"))
    fun getAllUserContexts(): List<UserContext> = userContextRepository.findAll()

    fun getUserContextResponse(
        userId: String,
        acquireLock: Boolean = false,
        lockId: String? = null
    ): UserContextResponse {
        return userContextRepository.getUserContextResponse(
            userId, acquireLock, lockId, userRuleRepository, executionRepository
        )
    }

    @Deprecated("Use userContextRepository.saveUserSummary() directly")
    fun saveUserSummary(userId: String, summary: String) {
        userContextRepository.saveUserSummary(userId, summary)
    }

    @Deprecated("Use userContextRepository.acquireSummaryLock() directly")
    fun acquireSummaryLock(userId: String, lockId: String): Boolean =
        userContextRepository.acquireSummaryLock(userId, lockId)

    @Deprecated("Use userContextRepository.releaseSummaryLock() directly")
    fun releaseSummaryLock(userId: String, lockId: String): Boolean =
        userContextRepository.releaseSummaryLock(userId, lockId)

    @Deprecated("Use userContextRepository.getRecentConversations() directly")
    fun getRecentConversations(userId: String, limit: Int = 10): List<RecentConversation> =
        userContextRepository.getRecentConversations(userId, executionRepository, limit)

    @Deprecated("Use executionRepository.countByUserId() directly")
    fun getConversationCount(userId: String): Int = executionRepository.countByUserId(userId).toInt()

    /**
     * Update user interaction stats (called after each execution)
     */
    fun updateUserInteraction(userId: String, promptLength: Int, responseLength: Int) {
        userContextRepository.updateUserInteraction(userId, promptLength, responseLength)
    }

    // ==================== User Rules (Delegated) ====================

    @Deprecated("Use userRuleRepository.findRulesByUserId() directly")
    fun getUserRules(userId: String): List<String> = userRuleRepository.findRulesByUserId(userId)

    @Deprecated("Use userRuleRepository.addRule() directly")
    fun addUserRule(userId: String, rule: String): Boolean = userRuleRepository.addRule(userId, rule)

    @Deprecated("Use userRuleRepository.deleteRule() directly")
    fun deleteUserRule(userId: String, rule: String): Boolean = userRuleRepository.deleteRule(userId, rule)

    // ==================== Settings (Delegated) ====================

    @Deprecated("Use settingsRepository.getValue() directly")
    fun getSetting(key: String): String? = settingsRepository.getValue(key)

    @Deprecated("Use settingsRepository.setValue() directly")
    fun setSetting(key: String, value: String) = settingsRepository.setValue(key, value)

    // ==================== Stats ====================

    fun getStats(): StorageStats = getStats(DateRange.lastDays(30))

    fun getStats(dateRange: DateRange): StorageStats {
        val stats = executionRepository.getAggregatedStats(dateRange)
        val feedback = feedbackRepository.getFeedbackStats(dateRange)

        return StorageStats(
            totalExecutions = stats.totalRequests.toInt(),
            successRate = if (stats.totalRequests > 0)
                stats.successfulRequests.toDouble() / stats.totalRequests else 0.0,
            totalTokens = stats.totalInputTokens + stats.totalOutputTokens,
            avgDurationMs = stats.avgDurationMs,
            thumbsUp = feedback.positive.toInt(),
            thumbsDown = feedback.negative.toInt()
        )
    }

    // ==================== Analytics (Delegated) ====================

    @Deprecated("Use analyticsRepository.getPercentiles() directly")
    fun getPercentiles(days: Int = 7): PercentileStats {
        val dateRange = DateRange.lastDays(days)
        return analyticsRepository.getPercentiles(dateRange)
    }

    @Deprecated("Use analyticsRepository.getOverviewStats() directly")
    fun getOverviewStats(days: Int = 7): OverviewStats {
        val dateRange = DateRange.lastDays(days)
        return analyticsRepository.getOverviewStats(dateRange)
    }

    @Deprecated("Use feedbackRepository.getFeedbackStats() directly")
    fun getFeedbackStats(since: String? = null): FeedbackStats {
        val dateRange = since?.let {
            DateRange(Instant.parse(it), Instant.now())
        }
        return feedbackRepository.getFeedbackStats(dateRange)
    }

    @Deprecated("Use analyticsRepository.getTimeSeries() directly")
    fun getTimeSeries(days: Int = 7, granularity: String = "day"): List<TimeSeriesPoint> {
        val dateRange = DateRange.lastDays(days)
        val timeGranularity = when (granularity) {
            "hour" -> TimeGranularity.HOUR
            "week" -> TimeGranularity.WEEK
            else -> TimeGranularity.DAY
        }
        return analyticsRepository.getTimeSeries(dateRange, timeGranularity)
    }

    @Deprecated("Use agentRepository.getModelStats() directly")
    fun getModelStats(days: Int = 7): List<ModelStats> {
        val dateRange = DateRange.lastDays(days)
        return agentRepository.getModelStats(dateRange)
    }

    @Deprecated("Use analyticsRepository.getErrorStats() directly")
    fun getErrorStats(days: Int = 7): List<ErrorStats> {
        val dateRange = DateRange.lastDays(days)
        return analyticsRepository.getErrorStats(dateRange)
    }

    @Deprecated("Use analyticsRepository.getUserStats() directly")
    fun getUserStats(days: Int = 7, limit: Int = 20): List<UserStats> {
        val dateRange = DateRange.lastDays(days)
        return analyticsRepository.getUserStats(dateRange, limit)
    }

    // ==================== Agent (Delegated) ====================

    @Deprecated("Use agentRepository.save() directly")
    fun saveAgent(agent: Agent) = agentRepository.save(agent)

    @Deprecated("Use agentRepository.findByIdAndProject() directly")
    fun getAgent(agentId: String, projectId: String? = null): Agent? =
        agentRepository.findByIdAndProject(agentId, projectId)

    @Deprecated("Use agentRepository.findByProject() directly")
    fun getAgentsByProject(projectId: String? = null): List<Agent> =
        agentRepository.findByProject(projectId)

    @Deprecated("Use agentRepository.findAll() directly")
    fun getAllAgents(): List<Agent> = agentRepository.findAll()

    @Deprecated("Use agentRepository.deleteByIdAndProject() directly")
    fun deleteAgent(agentId: String, projectId: String? = null): Boolean =
        agentRepository.deleteByIdAndProject(agentId, projectId)

    @Deprecated("Use agentRepository.setEnabled() directly")
    fun setAgentEnabled(agentId: String, projectId: String?, enabled: Boolean): Boolean =
        agentRepository.setEnabled(agentId, projectId, enabled)

    // ==================== Routing Metrics ====================

    /**
     * 라우팅 메트릭 저장
     */
    fun saveRoutingMetric(
        executionId: String?,
        routingMethod: String,
        agentId: String?,
        confidence: Double?,
        latencyMs: Long
    ) {
        val sql = """
            INSERT INTO routing_metrics (id, execution_id, routing_method, agent_id, confidence, latency_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, java.util.UUID.randomUUID().toString())
            stmt.setString(2, executionId)
            stmt.setString(3, routingMethod)
            stmt.setString(4, agentId)
            stmt.setObject(5, confidence)
            stmt.setLong(6, latencyMs)
            stmt.setString(7, Instant.now().toString())
            stmt.executeUpdate()
        }
    }

    // ==================== Dead Letter Queue ====================

    /**
     * 실패한 메시지를 Dead Letter Queue에 저장
     */
    fun saveToDeadLetter(
        eventId: String,
        eventType: String,
        channel: String?,
        userId: String?,
        text: String?,
        endpoint: String,
        payload: String,
        errorMessage: String?,
        retryCount: Int
    ) {
        val sql = """
            INSERT INTO dead_letter_queue
            (id, event_id, event_type, channel, user_id, text, endpoint, payload, error_message, retry_count, failed_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, java.util.UUID.randomUUID().toString())
            stmt.setString(2, eventId)
            stmt.setString(3, eventType)
            stmt.setString(4, channel)
            stmt.setString(5, userId)
            stmt.setString(6, text)
            stmt.setString(7, endpoint)
            stmt.setString(8, payload)
            stmt.setString(9, errorMessage)
            stmt.setInt(10, retryCount)
            stmt.setString(11, Instant.now().toString())
            stmt.setString(12, Instant.now().toString())
            stmt.executeUpdate()
        }
        logger.debug { "Message saved to dead letter queue: $eventId" }
    }

    /**
     * Dead Letter Queue에서 재시도할 메시지 조회
     */
    fun getDeadLetterMessages(limit: Int = 100): List<DeadLetterMessage> {
        val sql = """
            SELECT id, event_id, event_type, channel, user_id, text, endpoint, payload, error_message, retry_count, failed_at, created_at
            FROM dead_letter_queue
            ORDER BY created_at ASC
            LIMIT ?
        """
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, limit)
            val rs = stmt.executeQuery()
            val messages = mutableListOf<DeadLetterMessage>()
            while (rs.next()) {
                messages.add(DeadLetterMessage(
                    id = rs.getString("id"),
                    eventId = rs.getString("event_id"),
                    eventType = rs.getString("event_type"),
                    channel = rs.getString("channel"),
                    userId = rs.getString("user_id"),
                    text = rs.getString("text"),
                    endpoint = rs.getString("endpoint"),
                    payload = rs.getString("payload"),
                    errorMessage = rs.getString("error_message"),
                    retryCount = rs.getInt("retry_count"),
                    failedAt = rs.getString("failed_at"),
                    createdAt = rs.getString("created_at")
                ))
            }
            messages
        }
    }

    /**
     * Dead Letter Queue에서 메시지 삭제
     */
    fun deleteFromDeadLetter(id: String): Boolean {
        val sql = "DELETE FROM dead_letter_queue WHERE id = ?"
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate() > 0
        }
    }

    /**
     * Dead Letter Queue 통계
     */
    fun getDeadLetterStats(): DeadLetterStats {
        val countSql = "SELECT COUNT(*) as total FROM dead_letter_queue"
        val oldestSql = "SELECT MIN(created_at) as oldest FROM dead_letter_queue"

        val total = connection.prepareStatement(countSql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt("total") else 0
        }

        val oldest = connection.prepareStatement(oldestSql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getString("oldest") else null
        }

        return DeadLetterStats(total = total, oldestMessageAt = oldest)
    }

    fun close() {
        connection.close()
    }
}

// ==================== Data Classes ====================

data class StorageStats(
    val totalExecutions: Int,
    val successRate: Double,
    val totalTokens: Long,
    val avgDurationMs: Double,
    val thumbsUp: Int,
    val thumbsDown: Int
)

data class DeadLetterMessage(
    val id: String,
    val eventId: String,
    val eventType: String,
    val channel: String?,
    val userId: String?,
    val text: String?,
    val endpoint: String,
    val payload: String,
    val errorMessage: String?,
    val retryCount: Int,
    val failedAt: String,
    val createdAt: String
)

data class DeadLetterStats(
    val total: Int,
    val oldestMessageAt: String?
)

// Re-export from repositories for backwards compatibility
typealias PercentileStats = ai.claudeflow.core.storage.repository.PercentileStats
typealias OverviewStats = ai.claudeflow.core.storage.repository.OverviewStats
typealias ComparisonStats = ai.claudeflow.core.storage.repository.ComparisonStats
typealias ModelStats = ai.claudeflow.core.storage.repository.ModelStats
typealias TimeGranularity = ai.claudeflow.core.storage.repository.TimeGranularity

// ==================== Projects Config ====================

/**
 * config/projects.json 설정 파일 구조
 */
data class ProjectsFileConfig(
    val defaultBranch: String? = "develop",
    val gitlabHost: String? = null,  // GitLab 인스턴스 URL (예: "https://gitlab.42dot.ai")
    val projects: List<ProjectFileEntry>
)

data class ProjectFileEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val path: String,
    val gitRemote: String? = null,
    val gitlabPath: String? = null,  // GitLab 프로젝트 경로 (예: "42dot/ccds-server")
    val defaultBranch: String? = null,
    val isDefault: Boolean? = false,
    val aliases: List<String>? = null
)
