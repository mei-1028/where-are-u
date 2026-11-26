package com.whereareu.app.core.location

import android.util.Log
import com.whereareu.app.core.net.ReportPointDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class QueuedLocation(
    val commandId: String? = null,
    val point: ReportPointDto,
)

/**
 * Disk-backed FIFO queue. Writes are atomic (temp file + replace) and capped,
 * so a long offline period cannot grow storage without bound. Oldest points are
 * dropped first.
 */
class OfflineLocationQueue(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(QueuedLocation.serializer())

    suspend fun enqueue(items: List<QueuedLocation>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mutex.withLock {
            val all = readLocked().toMutableList()
            all += items
            val trimmed = if (all.size > MAX_POINTS) all.takeLast(MAX_POINTS) else all
            writeLocked(trimmed)
        }
    }

    suspend fun peek(limit: Int): List<QueuedLocation> = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().take(limit) }
    }

    /** Removes exactly the uploaded items, not merely the first N entries. */
    suspend fun remove(items: List<QueuedLocation>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mutex.withLock {
            val remaining = readLocked().toMutableList()
            items.forEach { uploaded -> remaining.remove(uploaded) }
            writeLocked(remaining)
        }
    }

    suspend fun removeFirst(count: Int) = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext
        mutex.withLock {
            val remaining = readLocked().drop(count)
            writeLocked(remaining)
        }
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().size }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (file.exists()) file.delete()
        }
    }

    private fun readLocked(): List<QueuedLocation> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt location queue, discarding", t)
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun writeLocked(items: List<QueuedLocation>) {
        try {
            if (items.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(listSerializer, items))
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete old queue file")
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist location queue", t)
        }
    }

    companion object {
        const val FILE_NAME = "pending_locations.json"
        private const val TAG = "OfflineLocationQueue"
        private const val MAX_POINTS = 1000
    }
}
