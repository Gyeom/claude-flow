package ai.claudeflow.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Claude Flow WebSocket Client
 *
 * 실시간 스트리밍 및 이벤트 기반 통신
 *
 * 사용법:
 * ```kotlin
 * val wsClient = ClaudeFlowWebSocket.connect("ws://localhost:8080/ws") {
 *     apiKey = "your-api-key"
 *     autoReconnect = true
 * }
 *
 * // 이벤트 구독
 * wsClient.events.collect { event ->
 *     when (event) {
 *         is ExecutionStarted -> println("Started: ${event.requestId}")
 *         is ExecutionProgress -> println("Progress: ${event.progress}%")
 *         is ExecutionCompleted -> println("Done: ${event.result}")
 *     }
 * }
 *
 * // 스트리밍 실행
 * wsClient.executeStreaming("분석해줘").collect { chunk ->
 *     print(chunk)
 * }
 * ```
 */
class ClaudeFlowWebSocket private constructor(
    private val url: String,
    private val apiKey: String?,
    private val autoReconnect: Boolean,
    private val reconnectDelayMs: Long,
    private val maxReconnectAttempts: Int,
    private val httpClient: OkHttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = Channel<WebSocketEvent>(Channel.BUFFERED)
    val events: Flow<WebSocketEvent> = _events.receiveAsFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val isConnected = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    private val pendingRequests = mutableMapOf<String, Channel<StreamChunk>>()

    companion object {
        fun connect(url: String, block: WebSocketConfig.() -> Unit = {}): ClaudeFlowWebSocket {
            val config = WebSocketConfig().apply(block)
            return ClaudeFlowWebSocket(
                url = url,
                apiKey = config.apiKey,
                autoReconnect = config.autoReconnect,
                reconnectDelayMs = config.reconnectDelayMs,
                maxReconnectAttempts = config.maxReconnectAttempts,
                httpClient = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build()
            ).also { it.connect() }
        }
    }

    class WebSocketConfig {
        var apiKey: String? = null
        var autoReconnect: Boolean = true
        var reconnectDelayMs: Long = 1000
        var maxReconnectAttempts: Int = 10
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED
    }

    /**
     * WebSocket 연결
     */
    fun connect() {
        if (isConnected.get()) return

        _connectionState.value = ConnectionState.CONNECTING
        logger.info { "Connecting to WebSocket: $url" }

        val request = Request.Builder()
            .url(url)
            .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info { "WebSocket connected" }
                isConnected.set(true)
                reconnectAttempts.set(0)
                _connectionState.value = ConnectionState.CONNECTED
                scope.launch { _events.send(WebSocketEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.info { "WebSocket closing: $code - $reason" }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info { "WebSocket closed: $code - $reason" }
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.error(t) { "WebSocket error: ${t.message}" }
                handleDisconnect()
                scope.launch { _events.send(WebSocketEvent.Error(t.message ?: "Unknown error")) }
            }
        })
    }

    /**
     * 연결 해제 처리
     */
    private fun handleDisconnect() {
        isConnected.set(false)
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.launch { _events.send(WebSocketEvent.Disconnected) }

        if (autoReconnect && reconnectAttempts.get() < maxReconnectAttempts) {
            scheduleReconnect()
        } else if (reconnectAttempts.get() >= maxReconnectAttempts) {
            _connectionState.value = ConnectionState.FAILED
        }
    }

    /**
     * 재연결 스케줄링
     */
    private fun scheduleReconnect() {
        val attempt = reconnectAttempts.incrementAndGet()
        val delay = reconnectDelayMs * attempt
        logger.info { "Scheduling reconnect in ${delay}ms (attempt $attempt)" }
        _connectionState.value = ConnectionState.RECONNECTING

        scope.launch {
            delay(delay)
            connect()
        }
    }

    /**
     * 메시지 처리
     */
    private fun handleMessage(text: String) {
        try {
            val message = json.decodeFromString<WebSocketMessage>(text)
            logger.debug { "Received message: ${message.type}" }

            when (message.type) {
                "execution_started" -> {
                    val event = json.decodeFromString<ExecutionStartedEvent>(text)
                    scope.launch { _events.send(WebSocketEvent.ExecutionStarted(event.requestId)) }
                }
                "execution_progress" -> {
                    val event = json.decodeFromString<ExecutionProgressEvent>(text)
                    scope.launch { _events.send(WebSocketEvent.ExecutionProgress(event.requestId, event.progress)) }
                }
                "execution_completed" -> {
                    val event = json.decodeFromString<ExecutionCompletedEvent>(text)
                    scope.launch { _events.send(WebSocketEvent.ExecutionCompleted(event.requestId, event.result)) }
                }
                "stream_chunk" -> {
                    val chunk = json.decodeFromString<StreamChunkEvent>(text)
                    pendingRequests[chunk.requestId]?.trySend(StreamChunk(chunk.content, chunk.isFinal))
                }
                "error" -> {
                    val event = json.decodeFromString<ErrorEvent>(text)
                    scope.launch { _events.send(WebSocketEvent.Error(event.message)) }
                }
                else -> {
                    logger.warn { "Unknown message type: ${message.type}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse message: $text" }
        }
    }

    /**
     * 스트리밍 실행
     */
    fun executeStreaming(prompt: String, options: ExecuteOptions = ExecuteOptions()): Flow<String> = flow {
        val requestId = java.util.UUID.randomUUID().toString()
        val channel = Channel<StreamChunk>(Channel.BUFFERED)
        pendingRequests[requestId] = channel

        try {
            val request = StreamExecuteRequest(
                type = "execute_stream",
                requestId = requestId,
                prompt = prompt,
                model = options.model,
                maxTurns = options.maxTurns,
                projectId = options.projectId,
                agentId = options.agentId
            )

            send(json.encodeToString(request))

            for (chunk in channel) {
                emit(chunk.content)
                if (chunk.isFinal) break
            }
        } finally {
            pendingRequests.remove(requestId)
            channel.close()
        }
    }

    /**
     * 메시지 전송
     */
    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    /**
     * 구독 요청
     */
    fun subscribe(eventTypes: List<String>) {
        val request = SubscribeRequest(
            type = "subscribe",
            eventTypes = eventTypes
        )
        send(json.encodeToString(request))
    }

    /**
     * 구독 해제
     */
    fun unsubscribe(eventTypes: List<String>) {
        val request = UnsubscribeRequest(
            type = "unsubscribe",
            eventTypes = eventTypes
        )
        send(json.encodeToString(request))
    }

    /**
     * 연결 종료
     */
    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        scope.cancel()
        _events.close()
    }
}

// ==================== WebSocket Events ====================

sealed class WebSocketEvent {
    object Connected : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    data class Error(val message: String) : WebSocketEvent()
    data class ExecutionStarted(val requestId: String) : WebSocketEvent()
    data class ExecutionProgress(val requestId: String, val progress: Int) : WebSocketEvent()
    data class ExecutionCompleted(val requestId: String, val result: ExecuteResult?) : WebSocketEvent()
}

data class StreamChunk(
    val content: String,
    val isFinal: Boolean
)

// ==================== WebSocket Messages ====================

@Serializable
data class WebSocketMessage(
    val type: String
)

@Serializable
data class ExecutionStartedEvent(
    val type: String,
    val requestId: String
)

@Serializable
data class ExecutionProgressEvent(
    val type: String,
    val requestId: String,
    val progress: Int
)

@Serializable
data class ExecutionCompletedEvent(
    val type: String,
    val requestId: String,
    val result: ExecuteResult?
)

@Serializable
data class StreamChunkEvent(
    val type: String,
    val requestId: String,
    val content: String,
    val isFinal: Boolean = false
)

@Serializable
data class ErrorEvent(
    val type: String,
    val message: String
)

@Serializable
data class StreamExecuteRequest(
    val type: String,
    val requestId: String,
    val prompt: String,
    val model: String? = null,
    val maxTurns: Int? = null,
    val projectId: String? = null,
    val agentId: String? = null
)

@Serializable
data class SubscribeRequest(
    val type: String,
    val eventTypes: List<String>
)

@Serializable
data class UnsubscribeRequest(
    val type: String,
    val eventTypes: List<String>
)
