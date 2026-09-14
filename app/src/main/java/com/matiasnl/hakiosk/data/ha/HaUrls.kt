package com.matiasnl.hakiosk.data.ha

/** URL helpers for the Home Assistant server. */
object HaUrls {
    /** Trims whitespace and strips trailing slashes, e.g. ` http://ha.local:8123/ ` -> `http://ha.local:8123`. */
    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * WebSocket endpoint for [baseUrl]: only the scheme is replaced (http -> ws, https -> wss); host,
     * port and any path prefix are kept, e.g. `https://host:8443/ha` -> `wss://host:8443/ha/api/websocket`.
     * ws/wss input is kept as-is. Other or missing schemes are returned unchanged (and rejected later).
     */
    fun webSocketUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        val scheme = normalized.substringBefore("://", missingDelimiterValue = "")
        val rest = normalized.substringAfter("://")
        val wsBase = when (scheme.lowercase()) {
            "http", "ws" -> "ws://$rest"
            "https", "wss" -> "wss://$rest"
            else -> normalized
        }
        return "$wsBase/api/websocket"
    }

    fun apiUrl(baseUrl: String, path: String): String =
        normalizeBaseUrl(baseUrl) + "/api/" + path.trimStart('/')
}
