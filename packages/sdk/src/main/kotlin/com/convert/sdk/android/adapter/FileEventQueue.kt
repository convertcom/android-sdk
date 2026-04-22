/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.port.EventQueue
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk implementation of the [EventQueue] port.
 *
 * ### Role (Story 5.2 AC-1, AC-5)
 *
 * Persists undelivered tracking events to app-private storage when
 * [com.convert.sdk.core.api.ApiManager] can't ship them (offline path or
 * foreground-retry exhaustion). A successful later flush reads + clears
 * the file; the file exists only while there is unsent data.
 *
 * The transport type is [VisitorEvent] — each entry carries the visitor
 * id, the visitor's segment snapshot, and the [com.convert.sdk.core.model.TrackingEvent]
 * sealed subtype so all metadata survives process death. This is the Port
 * Contract Amendment from Story 5.3 (spans Stories 1.2 + 5.2 + 5.3).
 *
 * ### Storage location
 *
 * Writes land under `context.filesDir/convert-sdk/events.json` — same
 * per-app sandbox as [FileConfigCache] (mode 0700 on the parent directory
 * enforced by Android; no other app can read without root). No
 * encryption is layered on top: the events blob carries visitor ids +
 * pre-built event payloads, both of which already live in the outbound
 * HTTPS request stream.
 *
 * ### Atomic write (AC-1)
 *
 * [persist] serialises to `events.json.tmp` and renames it onto
 * `events.json`. This is the same tmp+rename pattern [FileConfigCache]
 * uses; on Android's ext4/f2fs the rename is atomic so a process death
 * mid-write leaves the previous valid version in place. If `renameTo`
 * fails (filesystem-level edge case) we fall back to a direct write and
 * log a WARN.
 *
 * ### Corruption recovery (AC-5, NFR13)
 *
 * [read] treats any parse failure as corruption: delete the file, log
 * ERROR, and return an empty list. Callers then behave as if the queue
 * were empty — the next enqueue+persist cycle recreates the file.
 *
 * ### Append semantics (AC-1)
 *
 * `persist(events)` is additive: it reads the existing queue, appends
 * [events], and rewrites the full list under the mutex. Multiple
 * concurrent `persist` calls from the scope are serialised by [mutex]
 * (Gotcha 3 — without it, two concurrent persisters can race on the
 * read+rewrite and lose one batch).
 *
 * ### Threading
 *
 * Every public operation dispatches onto [Dispatchers.IO] via
 * [withContext]; callers can invoke from any coroutine scope.
 *
 * @property context application context — used only for `filesDir`.
 * @property logger sink for INFO/ERROR/WARN messages.
 */
internal class FileEventQueue(
    private val context: Context,
    private val logger: Logger,
) : EventQueue {

    private val cacheDir: File = File(context.filesDir, FileConfigCache.CACHE_DIRNAME)
    private val queueFile: File = File(cacheDir, QUEUE_FILENAME)
    private val tmpFile: File = File(cacheDir, QUEUE_FILENAME_TMP)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        // VisitorEvent.event is a sealed TrackingEvent hierarchy; the
        // classDiscriminator must match what ApiManager reads as "eventType".
        classDiscriminator = "eventType"
    }
    private val serializer = ListSerializer(VisitorEvent.serializer())

    /**
     * Serialises all writes (read-modify-write in [persist]) so concurrent
     * callers do not race on the read+rewrite sequence (Gotcha 3).
     */
    private val mutex: Mutex = Mutex()

    override suspend fun persist(events: List<VisitorEvent>) {
        if (events.isEmpty()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                persistLocked(events)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persistLocked(events: List<VisitorEvent>) {
        try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                logger.warn(
                    message = "FileEventQueue.persist: failed to create dir ${cacheDir.path}",
                    tag = TAG,
                )
                return
            }

            val existing: List<VisitorEvent> = readOrEmptyLocked()
            val combined: List<VisitorEvent> = existing + events
            val serialised = json.encodeToString(serializer, combined)

            tmpFile.writeText(serialised)
            val renamed = tmpFile.renameTo(queueFile)
            if (!renamed) {
                logger.warn(
                    message = "FileEventQueue.persist: renameTo failed; falling back to direct write",
                    tag = TAG,
                )
                queueFile.writeText(serialised)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            logger.warn(
                message = "FileEventQueue.persist: failed (${t.message})",
                throwable = t,
                tag = TAG,
            )
            // Never rethrow — persistence is best-effort; losing a write
            // here is strictly better than propagating up into the
            // ApiManager flush path.
        }
    }

    override suspend fun read(): List<VisitorEvent> = mutex.withLock {
        withContext(Dispatchers.IO) { readOrEmptyLocked() }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun readOrEmptyLocked(): List<VisitorEvent> {
        if (!queueFile.exists()) return emptyList()

        val raw = try {
            queueFile.readText()
        } catch (t: Throwable) {
            logger.error(
                message = "FileEventQueue.read: failed to read ${queueFile.path}: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            queueFile.delete()
            return emptyList()
        }

        if (raw.isBlank()) {
            logger.error(
                message = "FileEventQueue.read: queue file is empty at ${queueFile.path}; deleting",
                tag = TAG,
            )
            queueFile.delete()
            return emptyList()
        }

        return try {
            json.decodeFromString(serializer, raw)
        } catch (t: Throwable) {
            logger.error(
                message = "FileEventQueue.read: failed to parse ${queueFile.path}: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            queueFile.delete()
            emptyList()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (queueFile.exists()) queueFile.delete()
            } catch (t: Throwable) {
                logger.warn(
                    message = "FileEventQueue.clear: failed (${t.message})",
                    throwable = t,
                    tag = TAG,
                )
            }
            Unit
        }
    }

    override suspend fun size(): Int = read().size

    companion object {
        private const val TAG: String = "FileEventQueue"

        /** Filename of the persisted event queue JSON. */
        internal const val QUEUE_FILENAME: String = "events.json"

        /** Filename of the temporary staging file used for atomic writes. */
        internal const val QUEUE_FILENAME_TMP: String = "events.json.tmp"
    }
}
