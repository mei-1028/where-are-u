package com.whereareu.app.core.ws

import com.whereareu.app.core.net.AlertDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ServerSocketEvent {
    data class Locate(val commandId: String, val deadlineAt: String?) : ServerSocketEvent
    data class Ping(val ts: String?) : ServerSocketEvent
    data class Alert(val alert: AlertDto) : ServerSocketEvent
    data object Unknown : ServerSocketEvent
}

/**
 * Parses the server -> device WebSocket protocol from docs/api-contract.md.
 * Malformed frames return null; unknown-but-valid frames return Unknown so the
 * caller can ignore forward-compatible additions.
 */
object ServerSocketParser {

    private val defaultJson = Json { ignoreUnknownKeys = true }

    fun parse(text: String, json: Json = defaultJson): ServerSocketEvent? = try {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "locate" -> {
                val commandId = obj["commandId"]?.jsonPrimitive?.contentOrNull
                if (commandId.isNullOrBlank()) {
                    null
                } else {
                    ServerSocketEvent.Locate(
                        commandId = commandId,
                        deadlineAt = obj["deadlineAt"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }

            "ping" -> ServerSocketEvent.Ping(
                ts = obj["ts"]?.jsonPrimitive?.contentOrNull,
            )

            "alert" -> {
                val payload = obj["alert"] ?: return null
                val alert = runCatching {
                    json.decodeFromJsonElement(AlertDto.serializer(), payload)
                }.getOrNull()
                if (alert == null || alert.id.isBlank()) null
                else ServerSocketEvent.Alert(alert)
            }

            else -> ServerSocketEvent.Unknown
        }
    } catch (_: Exception) {
        null
    }
}
