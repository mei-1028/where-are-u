package com.whereareu.app.core.location

import com.whereareu.app.core.model.LocationFix
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.ReportLocationsRequest
import com.whereareu.app.core.net.ReportLocationsResponse
import com.whereareu.app.core.net.ReportPointDto
import com.whereareu.app.core.net.RuntimeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocationReporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeApi : LocationReportApi {
        var error: ApiException? = null
        var delayMs: Long = 0
        val uploaded = mutableListOf<ReportPointDto>()

        override suspend fun report(request: ReportLocationsRequest): ReportLocationsResponse {
            if (delayMs > 0) delay(delayMs)
            error?.let { throw it }
            uploaded += request.points
            return ReportLocationsResponse(accepted = request.points.size)
        }
    }

    private lateinit var api: FakeApi
    private lateinit var queue: OfflineLocationQueue
    private var scheduleCalls = 0
    private val unauthorizedTokens = mutableListOf<String?>()

    @Before
    fun setUp() {
        RuntimeConfig.deviceToken = "device-token"
        RuntimeConfig.userToken = "user-jwt"
        api = FakeApi()
        queue = OfflineLocationQueue(File(folder.root, OfflineLocationQueue.FILE_NAME))
        scheduleCalls = 0
        unauthorizedTokens.clear()
    }

    @After
    fun tearDown() {
        RuntimeConfig.deviceToken = null
        RuntimeConfig.userToken = null
    }

    private fun reporter() = LocationReporter(
        api = { request -> api.report(request) },
        queue = queue,
        batteryProvider = { 66 },
        scheduleFlush = { scheduleCalls += 1 },
        onUnauthorized = { token -> unauthorizedTokens += token },
    )

    private fun point(lat: Double, commandId: String? = null) = QueuedLocation(
        commandId = commandId,
        point = ReportPointDto(
            lat = lat,
            lng = 120.0,
            accuracy = 10f,
            speed = 0f,
            bearing = 0f,
            battery = 66,
            provider = "test",
            recordedAt = "2026-11-26T12:04:00Z",
        ),
    )


    private fun fix() = LocationFix(
        lat = 30.2741,
        lng = 120.1551,
        accuracy = 12f,
        speed = 0f,
        bearing = 0f,
        provider = "test",
        recordedAt = 1_765_000_000_000L,
    )

    @Test
    fun `401 keeps the queued batch and triggers re-authentication`() = runTest {
        queue.enqueue(listOf(point(1.0), point(2.0)))
        api.error = ApiException("UNAUTHORIZED", "未授权", httpStatus = 401)

        reporter().flush()

        assertEquals(2, queue.size())
        assertEquals(listOf<String?>("device-token"), unauthorizedTokens)
        assertTrue(scheduleCalls > 0)
    }

    @Test
    fun `5xx keeps the queued batch for retry`() = runTest {
        queue.enqueue(listOf(point(1.0), point(2.0)))
        api.error = ApiException("UNKNOWN", "服务器错误", httpStatus = 500)

        reporter().flush()

        assertEquals(2, queue.size())
        assertTrue(unauthorizedTokens.isEmpty())
        assertTrue(scheduleCalls > 0)
    }

    @Test
    fun `business 4xx drops the unsendable batch`() = runTest {
        queue.enqueue(listOf(point(1.0), point(2.0)))
        api.error = ApiException("VALIDATION", "参数错误", httpStatus = 400)

        reporter().flush()

        assertEquals(0, queue.size())
        assertTrue(unauthorizedTokens.isEmpty())
        assertEquals(0, scheduleCalls)
    }

    @Test
    fun `401 submit keeps the new point and asks for device re-registration`() = runTest {
        api.error = ApiException("UNAUTHORIZED", "未授权", httpStatus = 401)

        reporter().submit(fix())

        assertEquals(1, queue.size())
        assertEquals(listOf<String?>("device-token"), unauthorizedTokens)
        assertTrue(scheduleCalls > 0)
    }

    @Test
    fun `concurrent flushes are serialized and do not lose or duplicate points`() = runTest {
        queue.enqueue((1..4).map { point(it.toDouble()) })
        api.delayMs = 10

        val reporter = reporter()
        val jobs = listOf(
            launch { reporter.flush() },
            launch { reporter.flush() },
        )
        jobs.forEach { it.join() }

        assertEquals(4, api.uploaded.size)
        assertEquals(4, api.uploaded.map { it.lat }.toSet().size)
        assertEquals(0, queue.size())
    }
}
