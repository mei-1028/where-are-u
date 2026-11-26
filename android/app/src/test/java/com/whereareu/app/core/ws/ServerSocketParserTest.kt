package com.whereareu.app.core.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSocketParserTest {

    @Test
    fun `parses locate command with deadline`() {
        val event = ServerSocketParser.parse(
            """{"type":"locate","commandId":"cmd_1","deadlineAt":"2026-11-26T12:04:30.000Z"}""",
        )
        assertTrue(event is ServerSocketEvent.Locate)
        val locate = event as ServerSocketEvent.Locate
        assertEquals("cmd_1", locate.commandId)
        assertEquals("2026-11-26T12:04:30.000Z", locate.deadlineAt)
    }

    @Test
    fun `parses locate command without deadline`() {
        val event = ServerSocketParser.parse("""{"type":"locate","commandId":"cmd_2"}""")
        assertTrue(event is ServerSocketEvent.Locate)
        val locate = event as ServerSocketEvent.Locate
        assertEquals("cmd_2", locate.commandId)
        assertNull(locate.deadlineAt)
    }

    @Test
    fun `locate without commandId is rejected`() {
        assertNull(ServerSocketParser.parse("""{"type":"locate"}"""))
        assertNull(ServerSocketParser.parse("""{"type":"locate","commandId":""}"""))
    }

    @Test
    fun `parses ping and keeps timestamp`() {
        val event = ServerSocketParser.parse("""{"type":"ping","ts":"2026-11-26T12:04:00Z"}""")
        assertTrue(event is ServerSocketEvent.Ping)
        assertEquals("2026-11-26T12:04:00Z", (event as ServerSocketEvent.Ping).ts)
    }

    @Test
    fun `unknown type is ignored but reported`() {
        assertEquals(
            ServerSocketEvent.Unknown,
            ServerSocketParser.parse("""{"type":"something_new","payload":1}"""),
        )
    }

    @Test
    fun `malformed frames return null`() {
        assertNull(ServerSocketParser.parse("not json"))
        assertNull(ServerSocketParser.parse("[]"))
        assertNull(ServerSocketParser.parse(""))
    }
}
