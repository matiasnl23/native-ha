package com.matiasnl.hakiosk.data.ha

/** URL helpers for the Home Assistant server. */
object HaUrls {
    /** Trims whitespace and strips trailing slashes, e.g. ` http://ha.local:8123/ ` -> `http://ha.local:8123`. */
    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * WebSocket endpoint for [baseUrl]. OkHttp accepts http/https URLs for WebSocket requests and
     * upgrades them (http -> ws, https -> wss), so ws/wss input is mapped back to http/https.
     */
    fun webSocketUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        val httpBase = when {
            normalized.startsWith("ws://", ignoreCase = true) -> "http://" + normalized.substring(5)
            normalized.startsWith("wss://", ignoreCase = true) -> "https://" + normalized.substring(6)
            else -> normalized
        }
        return "$httpBase/api/websocket"
    }

    fun apiUrl(baseUrl: String, path: String): String =
        normalizeBaseUrl(baseUrl) + "/api/" + path.trimStart('/')
}
