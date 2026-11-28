package com.whereareu.app.core.ws

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
    fun `parses a follow alert`() {
        val event = ServerSocketParser.parse(
            """{"type":"alert","alert":{"id":"a_1","type":"MOVEMENT",""" +
                """"target":{"userId":"u_2","nickname":"妈妈"},""" +
                """"title":"妈妈 位置发生变化","body":"已移动约 1.2 公里",""" +
                """"silent":false,"data":{"distanceMeters":1200},""" +
                """"occurredAt":"2026-11-28T10:00:00.000Z",""" +
                """"createdAt":"2026-11-28T10:00:02.000Z","readAt":null}}""",
        )
        assertTrue(event is ServerSocketEvent.Alert)
        val alert = (event as ServerSocketEvent.Alert).alert
        assertEquals("a_1", alert.id)
        assertEquals("MOVEMENT", alert.type)
        assertEquals("妈妈", alert.target.nickname)
        assertEquals(false, alert.silent)
        assertEquals(1200, alert.data?.get("distanceMeters")?.jsonPrimitive?.int)
    }

    @Test
    fun `alert without required fields is rejected`() {
        assertNull(ServerSocketParser.parse("""{"type":"alert"}"""))
        assertNull(ServerSocketParser.parse("""{"type":"alert","alert":{"type":"MOVEMENT"}}"""))
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
