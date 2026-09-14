package com.matiasnl.hakiosk.data.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class HaUrlsTest {
    @Test
    fun normalizeTrimsAndStripsTrailingSlashes() {
        assertEquals("http://192.168.1.50:8123", HaUrls.normalizeBaseUrl("  http://192.168.1.50:8123// \n"))
        assertEquals("https://ha.example.com", HaUrls.normalizeBaseUrl("https://ha.example.com"))
    }

    @Test
    fun httpMapsToWs() {
        assertEquals("ws://192.168.1.50:8123/api/websocket", HaUrls.webSocketUrl("http://192.168.1.50:8123/"))
        assertEquals("ws://ha.local:8123/api/websocket", HaUrls.webSocketUrl("HTTP://ha.local:8123"))
    }

    @Test
    fun httpsMapsToWssKeepingPortAndPathPrefix() {
        assertEquals("wss://ha.example.com/api/websocket", HaUrls.webSocketUrl("https://ha.example.com"))
        assertEquals("wss://host:8443/api/websocket", HaUrls.webSocketUrl("https://host:8443/"))
        assertEquals("wss://host:8443/ha/api/websocket", HaUrls.webSocketUrl(" https://host:8443/ha/ "))
    }

    @Test
    fun wsSchemesAreKept() {
        assertEquals("ws://h:8123/api/websocket", HaUrls.webSocketUrl("ws://h:8123"))
        assertEquals("wss://h/api/websocket", HaUrls.webSocketUrl("wss://h"))
    }

    @Test
    fun apiUrlKeepsSchemePortAndPrefix() {
        assertEquals("https://host:8443/ha/api/config", HaUrls.apiUrl("https://host:8443/ha/", "config"))
        assertEquals("http://10.0.0.2:8123/api/states", HaUrls.apiUrl("http://10.0.0.2:8123", "/states"))
    }
}
