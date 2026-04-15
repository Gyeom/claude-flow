package ai.claudeflow.core.plugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Confluence 플러그인
 *
 * Confluence API를 통한 문서 검색 및 조회
 * - 코드 리뷰 시 도메인 지식 컨텍스트 제공
 * - 토큰 최적화된 요약 반환
 */
class ConfluencePlugin : BasePlugin() {
    override val id = "confluence"
    override val name = "Confluence"
    override val description = "Confluence 문서 검색, 도메인 지식 조회"

    override val commands = listOf(
        PluginCommand(
            name = "search",
            description = "문서 검색 (CQL)",
            usage = "/confluence search <query> [space]",
            examples = listOf("/confluence search 인증 API", "/confluence search OAuth DEVDOCS")
        ),
        PluginCommand(
            name = "page",
            description = "페이지 조회",
            usage = "/confluence page <page-id>",
            examples = listOf("/confluence page 123456")
        ),
        PluginCommand(
            name = "spaces",
            description = "스페이스 목록 조회",
            usage = "/confluence spaces",
            examples = listOf("/confluence spaces")
        ),
        PluginCommand(
            name = "glossary",
            description = "용어 사전 조회",
            usage = "/confluence glossary <space> [term]",
            examples = listOf("/confluence glossary DEVDOCS", "/confluence glossary DEVDOCS OAuth")
        ),
        PluginCommand(
            name = "review-context",
            description = "코드 리뷰용 컨텍스트 조회 (토큰 최적화)",
            usage = "/confluence review-context <space> <keywords>",
            examples = listOf("/confluence review-context DEVDOCS 인증,토큰,OAuth")
        )
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private lateinit var baseUrl: String
    private lateinit var authHeader: String

    // 캐시 (TTL 5분)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = 5 * 60 * 1000L

    data class CacheEntry(val data: Any, val timestamp: Long)

    override suspend fun initialize(config: Map<String, String>) {
        super.initialize(config)
        baseUrl = requireConfig("CONFLUENCE_URL").trimEnd('/')
        val email = requireConfig("CONFLUENCE_EMAIL")
        val apiToken = requireConfig("CONFLUENCE_API_TOKEN")

        val credentials = "$email:$apiToken"
        authHeader = "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"

        logger.info { "Confluence plugin initialized: $baseUrl" }
    }

    override fun shouldHandle(message: String): Boolean {
        val lower = message.lowercase()
        return lower.startsWith("/confluence") ||
                lower.contains("문서 검색") ||
                lower.contains("wiki") ||
                lower.contains("컨플루언스")
    }

    override suspend fun execute(command: String, args: Map<String, Any>): PluginResult {
        return when (command) {
            "search" -> searchPages(
                query = args["query"] as? String ?: return PluginResult(false, error = "Query required"),
                spaceKey = args["space"] as? String
            )
            "page" -> getPage(
                pageId = args["page_id"] as? String ?: return PluginResult(false, error = "Page ID required")
            )
            "spaces" -> listSpaces()
            "glossary" -> getGlossary(
                spaceKey = args["space"] as? String ?: return PluginResult(false, error = "Space required"),
                term = args["term"] as? String
            )
            "review-context" -> getReviewContext(
                spaceKey = args["space"] as? String ?: return PluginResult(false, error = "Space required"),
                keywords = (args["keywords"] as? String)?.split(",")?.map { it.trim() }
                    ?: return PluginResult(false, error = "Keywords required"),
                tokenBudget = (args["token_budget"] as? Number)?.toInt() ?: 1500
            )
            else -> PluginResult(false, error = "Unknown command: $command")
        }
    }

    // ==================== 공개 API ====================

    /**
     * 문서 검색
     */
    fun searchPages(query: String, spaceKey: String? = null, limit: Int = 10): PluginResult {
        return try {
            val cql = buildCql(query, spaceKey)
            val encodedCql = URLEncoder.encode(cql, "UTF-8")
            val url = "$baseUrl/wiki/rest/api/content/search?cql=$encodedCql&limit=$limit&expand=body.storage,space"

            val response = apiGet(url)
            val result: Map<String, Any> = mapper.readValue(response)

            @Suppress("UNCHECKED_CAST")
            val results = result["results"] as? List<Map<String, Any>> ?: emptyList()

            val pages = results.map { page ->
                ConfluencePage(
                    id = page["id"] as String,
                    title = page["title"] as String,
                    spaceKey = ((page["space"] as? Map<*, *>)?.get("key") as? String) ?: "",
                    url = "$baseUrl/wiki${(page["_links"] as? Map<*, *>)?.get("webui") ?: ""}",
                    excerpt = extractExcerpt(page),
                    body = extractBody(page)
                )
            }

            PluginResult(
                success = true,
                data = pages,
                message = "Found ${pages.size} pages"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search Confluence: $query" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 페이지 상세 조회
     */
    fun getPage(pageId: String): PluginResult {
        return try {
            val cacheKey = "page:$pageId"
            getCached(cacheKey)?.let { return PluginResult(true, data = it) }

            val url = "$baseUrl/wiki/rest/api/content/$pageId?expand=body.storage,space,version"
            val response = apiGet(url)
            val page: Map<String, Any> = mapper.readValue(response)

            val confluencePage = ConfluencePage(
                id = page["id"] as String,
                title = page["title"] as String,
                spaceKey = ((page["space"] as? Map<*, *>)?.get("key") as? String) ?: "",
                url = "$baseUrl/wiki${(page["_links"] as? Map<*, *>)?.get("webui") ?: ""}",
                excerpt = "",
                body = extractBody(page)
            )

            putCache(cacheKey, confluencePage)

            PluginResult(success = true, data = confluencePage)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get page: $pageId" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 스페이스 목록
     */
    fun listSpaces(): PluginResult {
        return try {
            val cacheKey = "spaces"
            getCached(cacheKey)?.let { return PluginResult(true, data = it) }

            val url = "$baseUrl/wiki/rest/api/space?limit=50"
            val response = apiGet(url)
            val result: Map<String, Any> = mapper.readValue(response)

            @Suppress("UNCHECKED_CAST")
            val spaces = (result["results"] as? List<Map<String, Any>>)?.map { space ->
                mapOf(
                    "key" to space["key"],
                    "name" to space["name"],
                    "type" to space["type"]
                )
            } ?: emptyList()

            putCache(cacheKey, spaces)

            PluginResult(success = true, data = spaces)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list spaces" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 용어 사전 조회
     * 'Glossary' 또는 '용어사전' 제목의 페이지에서 용어 추출
     */
    fun getGlossary(spaceKey: String, term: String? = null): PluginResult {
        return try {
            val cacheKey = "glossary:$spaceKey"
            @Suppress("UNCHECKED_CAST")
            val glossary = getCached(cacheKey) as? Map<String, String> ?: run {
                // 용어 사전 페이지 검색
                val cql = URLEncoder.encode("space=$spaceKey AND (title~\"Glossary\" OR title~\"용어사전\" OR title~\"용어 정의\")", "UTF-8")
                val url = "$baseUrl" +
                        "/wiki/rest/api/content/search?cql=$cql&limit=1&expand=body.storage"
                val response = apiGet(url)
                val result: Map<String, Any> = mapper.readValue(response)

                @Suppress("UNCHECKED_CAST")
                val pages = result["results"] as? List<Map<String, Any>> ?: emptyList()

                if (pages.isEmpty()) {
                    emptyMap()
                } else {
                    val body = extractBody(pages.first())
                    parseGlossary(body).also { putCache(cacheKey, it) }
                }
            }

            val filteredGlossary = if (term != null) {
                glossary.filterKeys { it.contains(term, ignoreCase = true) }
            } else {
                glossary
            }

            PluginResult(
                success = true,
                data = filteredGlossary,
                message = "Found ${filteredGlossary.size} terms"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get glossary: $spaceKey" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 코드 리뷰용 컨텍스트 조회 (토큰 최적화)
     *
     * MCP 대비 80% 토큰 절감:
     * - 서버에서 전처리/요약
     * - 키워드 관련 섹션만 추출
     * - 토큰 예산 내 반환
     */
    fun getReviewContext(
        spaceKey: String,
        keywords: List<String>,
        tokenBudget: Int = 1500
    ): PluginResult {
        return try {
            // 1. 키워드로 관련 문서 검색
            val query = keywords.joinToString(" OR ") { "text~\"$it\"" }
            val searchResult = searchPages(query, spaceKey, limit = 5)

            if (!searchResult.success) {
                return searchResult
            }

            @Suppress("UNCHECKED_CAST")
            val pages = searchResult.data as? List<ConfluencePage> ?: emptyList()

            // 2. 각 문서에서 관련 섹션만 추출
            val charBudget = tokenBudget * 4 // 대략 1토큰 = 4자
            val perPageBudget = if (pages.isNotEmpty()) charBudget / pages.size else charBudget

            val summaries = pages.map { page ->
                DocumentSummary(
                    title = page.title,
                    url = page.url,
                    relevantContent = extractRelevantSections(page.body, keywords, perPageBudget)
                )
            }

            // 3. 용어 사전에서 관련 용어 추출
            val glossaryResult = getGlossary(spaceKey)
            @Suppress("UNCHECKED_CAST")
            val allTerms = (glossaryResult.data as? Map<String, String>) ?: emptyMap()
            val relevantTerms = allTerms.filterKeys { termKey ->
                keywords.any { keyword ->
                    termKey.contains(keyword, ignoreCase = true) ||
                    keyword.contains(termKey, ignoreCase = true)
                }
            }.entries.take(5).associate { it.key to it.value }

            // 4. 최종 컨텍스트 조합
            val context = ReviewContext(
                documentSummaries = summaries,
                glossaryTerms = relevantTerms,
                totalDocuments = pages.size,
                keywords = keywords
            )

            PluginResult(
                success = true,
                data = context,
                message = "Context built from ${pages.size} documents, ${relevantTerms.size} terms"
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to build review context: $spaceKey, $keywords" }
            PluginResult(false, error = e.message)
        }
    }

    /**
     * 컨텍스트를 마크다운 문자열로 변환
     */
    fun formatReviewContext(context: ReviewContext): String {
        return buildString {
            if (context.glossaryTerms.isNotEmpty()) {
                appendLine("## 📖 관련 용어")
                context.glossaryTerms.forEach { (term, definition) ->
                    appendLine("- **$term**: $definition")
                }
                appendLine()
            }

            if (context.documentSummaries.isNotEmpty()) {
                appendLine("## 📄 관련 문서")
                context.documentSummaries.forEach { doc ->
                    appendLine("### ${doc.title}")
                    appendLine(doc.relevantContent)
                    appendLine("[원문 보기](${doc.url})")
                    appendLine()
                }
            }
        }
    }

    // ==================== 내부 헬퍼 ====================

    private fun apiGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Confluence API error: ${response.statusCode()} - ${response.body().take(200)}")
        }

        return response.body()
    }

    private fun buildCql(query: String, spaceKey: String?): String {
        val conditions = mutableListOf<String>()

        if (spaceKey != null) {
            conditions.add("space=$spaceKey")
        }

        // 검색어 처리
        val searchTerms = query.split(" ").filter { it.isNotBlank() }
        if (searchTerms.isNotEmpty()) {
            val textCondition = searchTerms.joinToString(" AND ") { "text~\"$it\"" }
            conditions.add("($textCondition)")
        }

        conditions.add("type=page")

        return conditions.joinToString(" AND ")
    }

    private fun extractExcerpt(page: Map<String, Any>): String {
        val body = extractBody(page)
        return body.take(200).let {
            if (body.length > 200) "$it..." else it
        }
    }

    private fun extractBody(page: Map<String, Any>): String {
        @Suppress("UNCHECKED_CAST")
        val bodyStorage = (page["body"] as? Map<String, Any>)?.get("storage") as? Map<String, Any>
        val htmlContent = bodyStorage?.get("value") as? String ?: return ""

        // HTML → Plain Text
        return try {
            Jsoup.parse(htmlContent).text()
        } catch (e: Exception) {
            htmlContent.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }

    /**
     * 키워드 관련 섹션만 추출 (토큰 최적화 핵심)
     */
    private fun extractRelevantSections(
        body: String,
        keywords: List<String>,
        maxChars: Int
    ): String {
        if (body.isBlank()) return ""

        // 문장 단위로 분할
        val sentences = body.split(Regex("[.!?。]")).filter { it.length > 10 }

        // 키워드 포함 문장 우선 선택
        val relevantSentences = sentences
            .map { sentence ->
                val score = keywords.count { keyword ->
                    sentence.contains(keyword, ignoreCase = true)
                }
                sentence to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first.trim() }

        // 예산 내에서 문장 조합
        val result = StringBuilder()
        for (sentence in relevantSentences) {
            if (result.length + sentence.length + 2 > maxChars) break
            if (result.isNotEmpty()) result.append(". ")
            result.append(sentence)
        }

        // 관련 문장이 없으면 앞부분 반환
        return if (result.isEmpty()) {
            body.take(maxChars).let {
                if (body.length > maxChars) "$it..." else it
            }
        } else {
            result.toString()
        }
    }

    /**
     * 용어 사전 파싱 (테이블 또는 정의 목록)
     */
    private fun parseGlossary(htmlContent: String): Map<String, String> {
        val glossary = mutableMapOf<String, String>()

        try {
            val doc = Jsoup.parse(htmlContent)

            // 테이블 형식 파싱
            doc.select("table tr").forEach { row ->
                val cells = row.select("td, th")
                if (cells.size >= 2) {
                    val term = cells[0].text().trim()
                    val definition = cells[1].text().trim()
                    if (term.isNotBlank() && definition.isNotBlank()) {
                        glossary[term] = definition.take(200)
                    }
                }
            }

            // 정의 목록 형식 파싱 (dt/dd)
            doc.select("dl").forEach { dl ->
                val terms = dl.select("dt")
                val definitions = dl.select("dd")
                terms.zip(definitions).forEach { (dt, dd) ->
                    val term = dt.text().trim()
                    val definition = dd.text().trim()
                    if (term.isNotBlank() && definition.isNotBlank()) {
                        glossary[term] = definition.take(200)
                    }
                }
            }

            // 볼드 + 콜론 형식 (예: **용어**: 정의)
            val boldPattern = Regex("\\*\\*([^*]+)\\*\\*\\s*[:：]\\s*([^*\\n]+)")
            boldPattern.findAll(doc.text()).forEach { match ->
                val term = match.groupValues[1].trim()
                val definition = match.groupValues[2].trim()
                if (term.isNotBlank() && definition.isNotBlank()) {
                    glossary[term] = definition.take(200)
                }
            }

        } catch (e: Exception) {
            logger.warn { "Failed to parse glossary: ${e.message}" }
        }

        return glossary
    }

    // 캐시 헬퍼
    private fun getCached(key: String): Any? {
        val entry = cache[key] ?: return null
        return if (System.currentTimeMillis() - entry.timestamp < cacheTtlMs) {
            entry.data
        } else {
            cache.remove(key)
            null
        }
    }

    private fun putCache(key: String, data: Any) {
        cache[key] = CacheEntry(data, System.currentTimeMillis())
    }

    fun clearCache() {
        cache.clear()
        logger.info { "Confluence cache cleared" }
    }
}

/**
 * Confluence 페이지
 */
data class ConfluencePage(
    val id: String,
    val title: String,
    val spaceKey: String,
    val url: String,
    val excerpt: String,
    val body: String
)

/**
 * 문서 요약 (토큰 최적화)
 */
data class DocumentSummary(
    val title: String,
    val url: String,
    val relevantContent: String
)

/**
 * 코드 리뷰용 컨텍스트
 */
data class ReviewContext(
    val documentSummaries: List<DocumentSummary>,
    val glossaryTerms: Map<String, String>,
    val totalDocuments: Int,
    val keywords: List<String>
)
