package com.whereareu.app.core.location

import com.whereareu.app.core.net.ReportPointDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineLocationQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queue(): OfflineLocationQueue =
        OfflineLocationQueue(File(folder.root, OfflineLocationQueue.FILE_NAME))

    private fun item(lat: Double, commandId: String? = null) = QueuedLocation(
        commandId = commandId,
        point = ReportPointDto(
            lat = lat,
            lng = 120.0,
            accuracy = 10f,
            speed = 0f,
            bearing = 0f,
            battery = 80,
            provider = "test",
            recordedAt = "2026-11-26T12:04:00Z",
        ),
    )

    @Test
    fun `persists FIFO order and returns the oldest points`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(item(1.0), item(2.0)))

        val peeked = queue.peek(10)

        assertEquals(listOf(1.0, 2.0), peeked.map { it.point.lat })
        assertEquals(2, queue.size())
    }

    @Test
    fun `removing the uploaded batch does not delete concurrently appended points`() = runTest {
        val queue = queue()
        val first = item(1.0)
        val second = item(2.0)
        queue.enqueue(listOf(first, second))
        val uploaded = queue.peek(100)

        val appended = item(3.0)
        queue.enqueue(listOf(appended))
        queue.remove(uploaded)

        val remaining = queue.peek(100)
        assertEquals(1, remaining.size)
        assertEquals(3.0, remaining.single().point.lat, 0.0)
    }

    @Test
    fun `clear removes the queue file contents`() = runTest {
        val queue = queue()
        queue.enqueue(listOf(item(1.0)))
        queue.clear()
        assertEquals(0, queue.size())
        assertEquals(emptyList<QueuedLocation>(), queue.peek(10))
    }
}
