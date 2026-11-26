package com.whereareu.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `explicit schemes are preserved`() {
        assertEquals("http://example.com", ServerConfig.normalize("http://example.com"))
        assertEquals("https://example.com", ServerConfig.normalize("https://example.com/"))
        assertEquals("http://example.com:443", ServerConfig.normalize("http://example.com:443"))
    }

    @Test
    fun `bare host with non-443 port defaults to http`() {
        assertEquals("http://192.168.0.102:3000", ServerConfig.normalize("192.168.0.102:3000"))
        assertEquals("http://home.example.com:8080", ServerConfig.normalize("home.example.com:8080"))
    }

    @Test
    fun `port 443 and bare domains default to https`() {
        assertEquals("https://example.com:443", ServerConfig.normalize("example.com:443"))
        assertEquals("https://example.com", ServerConfig.normalize("example.com"))
    }

    @Test
    fun `bare IPv4 defaults to http`() {
        assertEquals("http://192.168.0.102", ServerConfig.normalize("192.168.0.102"))
    }

    @Test
    fun `api suffix and trailing slash are stripped`() {
        assertEquals("https://example.com", ServerConfig.normalize("https://example.com/api/"))
        assertEquals("http://192.168.0.102:3000", ServerConfig.normalize("192.168.0.102:3000/api"))
        assertTrue(ServerConfig.isValid("192.168.0.102:3000"))
    }

    @Test
    fun `websocket url keeps http scheme for okhttp upgrade`() {
        assertEquals(
            "http://192.168.0.102:3000/ws",
            ServerConfig.websocketUrl("192.168.0.102:3000"),
        )
        assertEquals(
            "https://example.com/ws",
            ServerConfig.websocketUrl("example.com"),
        )
    }
}
