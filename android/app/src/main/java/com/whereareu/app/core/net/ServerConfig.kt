package com.whereareu.app.core.net

import com.whereareu.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Runtime configuration read synchronously by OkHttp interceptors.
 * The DataStore flow keeps this in sync; login/saveDevice also update it eagerly.
 */
object RuntimeConfig {
    @Volatile
    var serverBaseUrl: String = ServerConfig.normalize(BuildConfig.API_BASE_URL)

    @Volatile
    var userToken: String? = null

    @Volatile
    var deviceToken: String? = null
}

object ServerConfig {

    const val DEFAULT_BASE_URL = "http://10.0.2.2:3000"

    private const val API_SUFFIX = "/api"

    /**
     * Accepts "your-domain", "https://your-domain/", "https://your-domain/api"
     * and returns a root URL without a trailing slash. The app always appends
     * /api for REST and /ws for the device socket.
     */
    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.isBlank()) value = DEFAULT_BASE_URL
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "${defaultScheme(value)}://$value"
        }
        value = value.trimEnd('/')
        if (value.endsWith(API_SUFFIX, ignoreCase = true)) {
            value = value.dropLast(API_SUFFIX.length).trimEnd('/')
        }
        return value
    }

    /**
     * Explicit schemes are always preserved. For a bare host:
     *  - any port other than 443 -> http (self-hosted LAN servers)
     *  - bare IPv4 address -> http
     *  - otherwise (domain, domain:443, [IPv6]:443) -> https
     */
    private fun defaultScheme(raw: String): String {
        val hostAndPort = raw.substringBefore('/').substringBefore('?')
        val port = hostAndPort.substringAfterLast(':', missingDelimiterValue = "")
            .toIntOrNull()
        if (port != null) {
            return if (port == 443) "https" else "http"
        }
        val host = hostAndPort
        if (BARE_IPV4.matches(host) || host.startsWith("[")) {
            return "http"
        }
        return "https"
    }

    private val BARE_IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    fun isValid(raw: String): Boolean = normalize(raw).toHttpUrlOrNull() != null

    fun websocketUrl(rawBase: String): String? {
        val base = normalize(rawBase).toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            // OkHttp only accepts http/https here; the WebSocket client upgrades internally.
            .encodedPath("/ws")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }
}
