package com.whereareu.app.core.ws

import android.util.Log
import com.whereareu.app.core.net.AlertDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface SocketEvent {
    data object Connected : SocketEvent
    data class Disconnected(val reason: String?) : SocketEvent
    data class Locate(val commandId: String, val deadlineAt: String?) : SocketEvent
    data class Alert(val alert: AlertDto) : SocketEvent
    data object Unauthorized : SocketEvent
}

/**
 * Device WebSocket with:
 *  - Authorization: Bearer <deviceToken>
 *  - hello on connect, pong every 30s and in reply to server ping
 *  - exponential backoff + jitter reconnect, reset after a successful open
 *  - locate frames forwarded to the service, which performs a one-shot fix
 */
class DeviceSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val urlProvider: () -> String?,
    private val tokenProvider: () -> String?,
    private val appVersion: String,
    private val batteryProvider: () -> Int?,
    private val backoffPolicy: BackoffPolicy = BackoffPolicy(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<SocketEvent>(extraBufferCapacity = 32)
    val events: kotlinx.coroutines.flow.SharedFlow<SocketEvent> = _events

    @Volatile private var running = false
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var heartbeatJob: Job? = null
    private var connectionJob: Job? = null
    private val attempt = AtomicInteger(0)
    private val unauthorized = AtomicBoolean(false)

    fun start() {
        if (running) return
        running = true
        connectionJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.let { runCatching { it.close(1000, "client closing") } }
        webSocket = null
        connectionJob?.cancel()
        connectionJob = null
    }

    private suspend fun connectLoop() {
        while (running) {
            val token = tokenProvider()?.takeIf { it.isNotBlank() }
            val url = urlProvider()?.takeIf { it.isNotBlank() }
            if (token == null || url == null) {
                Log.w(TAG, "WebSocket missing token or url, stopping")
                return
            }
            val closedSignal = CompletableDeferred<String?>()
            unauthorized.set(false)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            try {
                webSocket = client.newWebSocket(request, listener(closedSignal))
            } catch (t: Throwable) {
                Log.w(TAG, "newWebSocket failed", t)
                closedSignal.complete(t.message)
            }

            val reason = closedSignal.await()
            heartbeatJob?.cancel()
            heartbeatJob = null
            webSocket = null
            if (!running) break

            if (unauthorized.get()) {
                // Device token rejected (HTTP 401 handshake or close 4401).
                // Stop the reconnect loop; the service will re-register or log out.
                Log.w(TAG, "WebSocket unauthorized, stopping reconnect")
                running = false
                _events.tryEmit(SocketEvent.Unauthorized)
                break
            }

            _events.tryEmit(SocketEvent.Disconnected(reason))
            val delayMs = backoffPolicy.nextDelay(attempt.getAndIncrement())
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${attempt.get()})")
            delay(delayMs)
        }
    }

    private fun listener(closedSignal: CompletableDeferred<String?>): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                attempt.set(0)
                sendHello(webSocket)
                heartbeatJob = scope.launch { heartbeat(webSocket) }
                _events.tryEmit(SocketEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = ServerSocketParser.parse(text, json)) {
                    is ServerSocketEvent.Locate -> {
                        Log.i(TAG, "locate command received: ${event.commandId}")
                        _events.tryEmit(SocketEvent.Locate(event.commandId, event.deadlineAt))
                    }

                    is ServerSocketEvent.Alert -> {
                        Log.i(TAG, "follow alert received: ${event.alert.type}")
                        _events.tryEmit(SocketEvent.Alert(event.alert))
                    }

                    is ServerSocketEvent.Ping -> {
                        webSocket.send(pongFrame())
                    }

                    ServerSocketEvent.Unknown, null -> Log.d(TAG, "Ignoring WS frame")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == CLOSE_UNAUTHORIZED) {
                    unauthorized.set(true)
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                if (code == CLOSE_UNAUTHORIZED) {
                    unauthorized.set(true)
                }
                closedSignal.complete(reason.ifBlank { "closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: HTTP ${response?.code} ${t.message}")
                if (response?.code == 401) {
                    unauthorized.set(true)
                }
                closedSignal.complete(t.message ?: "connection failure")
            }
        }

    private fun sendHello(webSocket: WebSocket) {
        val payload = buildJsonObject {
            put("type", "hello")
            put("appVersion", appVersion)
            batteryProvider()?.let { put("battery", it) }
        }.toString()
        webSocket.send(payload)
    }

    private suspend fun heartbeat(webSocket: WebSocket) {
        while (scope.isActive && running) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (!webSocket.send(pongFrame())) break
        }
    }

    private fun pongFrame(): String = buildJsonObject {
        put("type", "pong")
        put("ts", Instant.now().toString())
    }.toString()

    private companion object {
        const val TAG = "DeviceSocket"
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val CLOSE_UNAUTHORIZED = 4401
    }
}
