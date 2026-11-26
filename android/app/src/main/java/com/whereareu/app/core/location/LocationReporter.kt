package com.whereareu.app.core.location

import android.util.Log
import com.whereareu.app.core.model.LocationFix
import com.whereareu.app.core.net.ApiErrorMapper
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.ReportLocationsRequest
import com.whereareu.app.core.net.ReportLocationsResponse
import com.whereareu.app.core.net.ReportPointDto
import com.whereareu.app.core.net.RuntimeConfig
import com.whereareu.app.core.net.safeApiCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

fun interface LocationReportApi {
    suspend fun report(request: ReportLocationsRequest): ReportLocationsResponse
}

/**
 * Sends points immediately, keeps a persistent offline queue for transient
 * failures, and asks the scheduler to retry when connectivity returns.
 *
 * 401 is never permanently dropped: the failed batch stays queued and
 * [onUnauthorized] can re-register the device / end the session. A stale
 * device token must not silently destroy location history.
 */
class LocationReporter(
    private val api: LocationReportApi,
    private val queue: OfflineLocationQueue,
    private val batteryProvider: () -> Int?,
    private val scheduleFlush: () -> Unit,
    private val onUnauthorized: suspend (failedToken: String?) -> Unit = {},
) {
    private val flushMutex = Mutex()

    suspend fun submit(fix: LocationFix, commandId: String? = null) {
        val point = fix.toReportPoint(batteryProvider())

        // A locate command should not sit behind older queued points.
        if (commandId == null) {
            flush()
        }

        // A 401 during flush may have just logged us out; do not enqueue points
        // from the expired session.
        if (RuntimeConfig.userToken.isNullOrBlank()) return

        val token = RuntimeConfig.deviceToken
        if (token.isNullOrBlank()) {
            queue.enqueue(listOf(QueuedLocation(commandId, point)))
            scheduleFlush()
            return
        }

        val result = safeApiCall {
            api.report(
                ReportLocationsRequest(
                    commandId = commandId,
                    points = listOf(point),
                ),
            )
        }
        result.fold(
            onSuccess = {
                if (queue.size() > 0) scheduleFlush()
            },
            onFailure = { throwable ->
                val error = throwable as? ApiException ?: ApiErrorMapper.map(throwable)
                when {
                    error.isUnauthorized -> {
                        // Keep the point and ask for device re-registration.
                        queue.enqueue(listOf(QueuedLocation(commandId, point)))
                        onUnauthorized(token)
                        scheduleFlush()
                    }

                    isPermanent(error) -> {
                        Log.w(TAG, "Dropping location point: ${error.code} ${error.message}")
                    }

                    else -> {
                        Log.w(TAG, "Reporting failed, queueing: ${error.message}")
                        queue.enqueue(listOf(QueuedLocation(commandId, point)))
                        scheduleFlush()
                    }
                }
            },
        )
    }

    /** Sends at most 100 queued points per call. Serialized with [flushMutex]. */
    suspend fun flush() = flushMutex.withLock {
        val token = RuntimeConfig.deviceToken
        if (token.isNullOrBlank()) return@withLock
        val batch = queue.peek(BATCH_SIZE)
        if (batch.isEmpty()) return@withLock

        val commandId = batch.firstOrNull { it.commandId != null }?.commandId
        val result = safeApiCall {
            api.report(
                ReportLocationsRequest(
                    commandId = commandId,
                    points = batch.map { it.point },
                ),
            )
        }

        result.fold(
            onSuccess = {
                // Only the batch that was actually uploaded is removed.
                queue.remove(batch)
                Log.i(TAG, "Flushed ${batch.size} queued points")
                if (queue.size() > 0) scheduleFlush()
            },
            onFailure = { throwable ->
                val error = throwable as? ApiException ?: ApiErrorMapper.map(throwable)
                when {
                    error.isUnauthorized -> {
                        // Never delete on 401: re-auth, then upload this batch.
                        onUnauthorized(token)
                        scheduleFlush()
                    }

                    isPermanent(error) -> {
                        Log.w(TAG, "Dropping ${batch.size} unsendable points: ${error.code}")
                        queue.remove(batch)
                    }

                    else -> {
                        scheduleFlush()
                    }
                }
            },
        )
    }

    private fun isPermanent(error: ApiException): Boolean =
        error.httpStatus in 400..499 &&
            !error.isUnauthorized &&
            error.code != "RATE_LIMITED"

    private fun LocationFix.toReportPoint(battery: Int?): ReportPointDto = ReportPointDto(
        lat = lat,
        lng = lng,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        battery = battery,
        provider = provider,
        recordedAt = Instant.ofEpochMilli(recordedAt).toString(),
    )

    private companion object {
        const val TAG = "LocationReporter"
        const val BATCH_SIZE = 100
    }
}
