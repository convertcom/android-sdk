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
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk implementation of the [EventQueue] port.
 *
 * ### Role (Story 5.2 AC-1, AC-5)
 *
 * Persists undelivered tracking events to app-private storage when
 * [com.convert.sdk.core.api.ApiManager] can't ship them (offline path or
 * foreground-retry exhaustion). A successful later flush drains + removes
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
 * [events], and rewrites the full list under the dual lock. Multiple
 * concurrent `persist` calls from the scope are serialised by [mutex]
 * (Gotcha 3 — without it, two concurrent persisters can race on the
 * read+rewrite and lose one batch).
 *
 * ### Dual locking (PR #39 remediation TD-1)
 *
 * Locking is two-layered because a `java.nio` [FileLock] is held on behalf
 * of the **whole JVM** and is **not** reentrant — two threads/instances in
 * the *same* process locking the same region throw [OverlappingFileLockException].
 * On most devices WorkManager runs in the **same** process as the foreground
 * SDK, so the worker's separate [FileEventQueue] instance and the foreground
 * instance are two instances in one JVM. Therefore:
 *
 * 1. **Intra-process, across instances:** a process-wide [Mutex] keyed by the
 *    canonical file path (`companion object { private val locks = ConcurrentHashMap<String, Mutex>() }`)
 *    ensures the foreground and worker instances over `events.json` share
 *    **one** [Mutex].
 * 2. **Inter-process (multi-process apps / `android:process` worker):** inside
 *    that [Mutex] critical section, a blocking [FileLock] via `FileChannel.lock()`
 *    on a sibling lock file (`events.json.lock`) covers the cross-process case.
 *    Because the shared [Mutex] guarantees only one thread in this JVM holds it,
 *    [OverlappingFileLockException] cannot fire from our own JVM; defensively
 *    catch it and retry once.
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
    private val lockFile: File = File(cacheDir, QUEUE_LOCK_FILENAME)

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
     * Process-wide [Mutex] keyed by the canonical path of [queueFile].
     *
     * When the foreground SDK and the [com.convert.sdk.android.worker.EventFlushWorker]
     * both run in the same JVM (the common case on most Android devices), they
     * construct **separate** [FileEventQueue] instances over the same file.
     * A per-instance mutex cannot serialise them. This companion-object map
     * returns the *same* mutex for every instance that resolves to the same
     * canonical path, ensuring intra-process serialisation (TD-1 layer 1).
     *
     * We use [File.canonicalPath] rather than [File.absolutePath] so that two
     * instances reaching the same physical file via different Context resolutions
     * (`/data/user/0/<pkg>` vs `/data/data/<pkg>`) still share one [Mutex].
     * [File.canonicalPath] can throw [java.io.IOException] on some filesystems;
     * the [runCatching] fallback to [File.absolutePath] preserves correctness
     * for the common case and avoids a hard crash on that edge.
     */
    private val mutex: Mutex = locks.getOrPut(
        runCatching { queueFile.canonicalPath }.getOrElse { queueFile.absolutePath },
    ) { Mutex() }

    override suspend fun persist(events: List<VisitorEvent>) {
        if (events.isEmpty()) return
        mutex.withLock {
            withFileLock {
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
        withFileLock { readOrEmptyLocked() }
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
        withFileLock {
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

    // size() delegates to read() intentionally: it acquires both locks and
    // deserialises the full JSON array to guarantee a correct count. A lighter
    // file-length heuristic would be unreliable for variable-length JSON. This
    // delegation is safe because size() is called rarely (only for diagnostics
    // and tests) and correctness outweighs the extra I/O cost.
    override suspend fun size(): Int = read().size

    /**
     * Atomically reads all events and deletes the queue file in one locked step.
     *
     * Atomicity property (TD-1): because read + delete happen under the same
     * dual lock that [persist] also contends, no [persist] can interleave
     * between the read and the deletion. The returned list equals the removed
     * set; a [persist] arriving mid-drain blocks, then appends to the
     * freshly-emptied file.
     *
     * On corruption / absent / blank file: returns [emptyList] and deletes the
     * bad file. No exception escapes this call.
     *
     * @return the events that were in the queue at the moment of the atomic
     *   claim, in enqueue order; empty when the queue was absent or corrupt.
     */
    override suspend fun drain(): List<VisitorEvent> = mutex.withLock {
        withFileLock {
            val events = readOrEmptyLocked()
            // Delete the queue file after a successful read so it is gone
            // before we release the lock. readOrEmptyLocked() already deletes
            // on corruption/blank, so we only need to delete here for the
            // non-empty success path.
            if (events.isNotEmpty() && queueFile.exists()) {
                queueFile.delete()
            }
            events
        }
    }

    /**
     * Runs [block] under a blocking [FileLock] on the sibling lock file
     * ([QUEUE_LOCK_FILENAME]) and on [Dispatchers.IO].
     *
     * This is layer 2 of the dual lock (TD-1): it guards against *separate
     * processes* (e.g. when the WorkManager worker runs in a separate
     * `android:process`) that each hold their own JVM and therefore their
     * own [mutex]. A JVM-internal [OverlappingFileLockException] is caught
     * and retried once — in practice it should never fire because the shared
     * [Mutex] above serialises same-JVM callers, but the defensive retry
     * handles any edge case where a third-party file lock overlaps.
     *
     * @param block the file I/O to run under both locks; must be non-suspending
     *   because it is dispatched on [Dispatchers.IO] via [withContext].
     * @return the result of [block].
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withFileLock(block: () -> T): T = withContext(Dispatchers.IO) {
        acquireFileLockAndRun(block, retryOnOverlap = true)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun <T> acquireFileLockAndRun(block: () -> T, retryOnOverlap: Boolean): T {
        if (!cacheDir.exists()) {
            // Best-effort dir creation so the lock file can always be opened
            // under the FileLock. If mkdirs() fails, RandomAccessFile(lockFile)
            // below throws FileNotFoundException (an IOException) — the correct
            // failure signal that propagates to the caller. We never bypass the
            // FileLock: skipping it for drain/read/clear/size on a missing dir
            // breaks the inter-process exactly-once guarantee (AC-1.2).
            cacheDir.mkdirs()
        }
        var channel: FileChannel? = null
        var fileLock: FileLock? = null
        return try {
            channel = RandomAccessFile(lockFile, "rw").channel
            fileLock = try {
                channel.lock()
            } catch (e: OverlappingFileLockException) {
                if (retryOnOverlap) {
                    // Defensive retry — should not normally fire because the
                    // shared Mutex prevents same-JVM re-entry.
                    channel.close()
                    return acquireFileLockAndRun(block, retryOnOverlap = false)
                } else {
                    throw e
                }
            }
            block()
        } finally {
            try { fileLock?.release() } catch (_: Throwable) { /* best effort */ }
            try { channel?.close() } catch (_: Throwable) { /* best effort */ }
        }
    }

    companion object {
        private const val TAG: String = "FileEventQueue"

        /** Filename of the persisted event queue JSON. */
        internal const val QUEUE_FILENAME: String = "events.json"

        /** Filename of the temporary staging file used for atomic writes. */
        internal const val QUEUE_FILENAME_TMP: String = "events.json.tmp"

        /** Sibling lock file used for inter-process [FileLock] coordination. */
        internal const val QUEUE_LOCK_FILENAME: String = "events.json.lock"

        /**
         * Process-wide map from **canonical** queue file path to its shared [Mutex].
         *
         * Ensures that two [FileEventQueue] instances over the same `events.json`
         * (e.g. the foreground SDK and the [com.convert.sdk.android.worker.EventFlushWorker]
         * running in the same JVM) share **one** [Mutex], preventing
         * [OverlappingFileLockException] from their own process (TD-1 layer 1).
         *
         * Keyed on canonical path (not absolute path) so that symlink-aliased
         * paths (`/data/user/0/<pkg>` ↔ `/data/data/<pkg>`) map to the same
         * entry (H-1 fix).
         */
        private val locks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()
    }
}
