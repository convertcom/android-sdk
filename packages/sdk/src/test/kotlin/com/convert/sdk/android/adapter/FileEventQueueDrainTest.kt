/*
 * Convert Android SDK — sdk/adapter tests (PR #39 Cluster 1)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * PR #39 Cluster 1 tests for [FileEventQueue.drain].
 *
 * Covers:
 * - **AC-1.1** drain is atomic; no loss across interleaved persist
 * - **AC-1.2** foreground + worker (same-process, path-keyed Mutex) — exactly-once delivery
 * - **AC-1.3** delivery failure → drained events re-persisted (via worker's persist call)
 */
@RunWith(RobolectricTestRunner::class)
internal class FileEventQueueDrainTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var queueFile: File
    private lateinit var tmpFile: File
    private lateinit var lockFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, "convert-sdk")
        queueFile = File(cacheDir, FileEventQueue.QUEUE_FILENAME)
        tmpFile = File(cacheDir, FileEventQueue.QUEUE_FILENAME_TMP)
        lockFile = File(cacheDir, FileEventQueue.QUEUE_LOCK_FILENAME)
        cleanCacheDir()
    }

    @After
    fun tearDown() {
        cleanCacheDir()
    }

    private fun cleanCacheDir() {
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    private fun bucketingEvent(
        visitorId: String,
        experienceId: String,
        variationId: String,
    ): VisitorEvent = VisitorEvent(
        visitorId = visitorId,
        event = BucketingEvent(experienceId = experienceId, variationId = variationId),
    )

    // ---------------------------------------------------------------
    // AC-1.1 — drain atomicity; no loss on interleaved persist
    // ---------------------------------------------------------------

    @Test
    fun `drain returns all events and empties the queue`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        val events = listOf(
            bucketingEvent("v-1", "e-1", "var-a"),
            bucketingEvent("v-2", "e-2", "var-b"),
        )
        queue.persist(events)

        val drained = queue.drain()

        assertEquals("drain must return all persisted events", 2, drained.size)
        assertEquals("v-1", drained[0].visitorId)
        assertEquals("v-2", drained[1].visitorId)
        assertFalse("queue file must not exist after drain", queueFile.exists())
        assertEquals("size() must be 0 after drain", 0, queue.size())
    }

    @Test
    fun `drain on empty queue returns emptyList and does not throw`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        val drained = queue.drain()
        assertTrue("drain on absent file must return empty", drained.isEmpty())
    }

    @Test
    fun `drain on blank file returns emptyList and deletes the file`() = runTest {
        cacheDir.mkdirs()
        queueFile.writeText("")
        assertTrue("pre-condition: blank file must exist", queueFile.exists())

        val queue = FileEventQueue(context, Logger.NoOp)
        val drained = queue.drain()

        assertTrue("drain on blank file must return empty", drained.isEmpty())
        assertFalse("blank file must be deleted by drain", queueFile.exists())
    }

    @Test
    fun `drain on corrupted file returns emptyList and deletes the file`() = runTest {
        cacheDir.mkdirs()
        queueFile.writeText("{{not valid json at all}}")
        assertTrue("pre-condition: corrupt file must exist", queueFile.exists())

        val queue = FileEventQueue(context, Logger.NoOp)
        val drained = queue.drain()

        assertTrue("drain on corrupt file must return empty", drained.isEmpty())
        assertFalse("corrupt file must be deleted by drain", queueFile.exists())
    }

    /**
     * AC-1.1 interleave test: a persist() that arrives while drain() is in
     * progress (or immediately after) must not be lost.
     *
     * We verify this by running drain() and persist() concurrently many
     * times with two instances sharing the same path-keyed Mutex. After all
     * iterations the total events accounted for (drained + remaining in
     * file) must equal the total events persisted — none may vanish.
     */
    @Test
    fun `concurrent persist and drain - no event loss`() {
        val iterations = 20
        val executor = Executors.newFixedThreadPool(4)
        val totalPersisted = AtomicInteger(0)
        val totalDrained = AtomicInteger(0)
        val latch = CountDownLatch(iterations * 2)

        repeat(iterations) { i ->
            // Foreground persister
            executor.submit {
                runBlocking {
                    val q = FileEventQueue(context, Logger.NoOp)
                    q.persist(listOf(bucketingEvent("v-$i", "e-$i", "var-$i")))
                    totalPersisted.incrementAndGet()
                }
                latch.countDown()
            }
            // Worker drainer (separate instance, same path-keyed Mutex)
            executor.submit {
                runBlocking {
                    val worker = FileEventQueue(context, Logger.NoOp)
                    val drained = worker.drain()
                    totalDrained.addAndGet(drained.size)
                }
                latch.countDown()
            }
        }

        val finished = latch.await(30, TimeUnit.SECONDS)
        assertTrue("all tasks must complete within 30s", finished)
        executor.shutdown()

        // Tally any remaining persisted events
        val remaining = runBlocking { FileEventQueue(context, Logger.NoOp).read().size }
        val accountedFor = totalDrained.get() + remaining

        assertEquals(
            "every persisted event must be accounted for (drained + remaining = persisted)",
            totalPersisted.get(),
            accountedFor,
        )
    }

    // ---------------------------------------------------------------
    // AC-1.2 — foreground + worker (same-process) — exactly-once delivery
    // ---------------------------------------------------------------

    @Test
    fun `two concurrent drainers - exactly-once delivery via path-keyed Mutex`() {
        // Seed events then race two instances of FileEventQueue to drain them.
        val eventCount = 10
        runBlocking {
            FileEventQueue(context, Logger.NoOp).persist(
                (1..eventCount).map { i -> bucketingEvent("v-$i", "e-$i", "var-$i") },
            )
        }

        val collected = mutableListOf<VisitorEvent>()
        val latch = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            val drained = runBlocking { FileEventQueue(context, Logger.NoOp).drain() }
            synchronized(collected) { collected.addAll(drained) }
            latch.countDown()
        }
        executor.submit {
            val drained = runBlocking { FileEventQueue(context, Logger.NoOp).drain() }
            synchronized(collected) { collected.addAll(drained) }
            latch.countDown()
        }

        val finished = latch.await(10, TimeUnit.SECONDS)
        assertTrue("both drainers must complete", finished)
        executor.shutdown()

        assertEquals(
            "each event must be delivered exactly once (no duplication, no loss)",
            eventCount,
            collected.size,
        )
        val visitorIds = collected.map { it.visitorId }.toSet()
        assertEquals(
            "all distinct visitor ids must appear exactly once",
            eventCount,
            visitorIds.size,
        )
    }

    // ---------------------------------------------------------------
    // AC-1.3 — delivery failure → re-persist drained events
    // ---------------------------------------------------------------

    @Test
    fun `re-persisting drained events after delivery failure preserves them`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        val original = listOf(
            bucketingEvent("v-1", "e-1", "var-a"),
            VisitorEvent(
                visitorId = "v-2",
                event = ConversionEvent(goalId = "goal-1"),
            ),
        )
        queue.persist(original)

        // Simulate drain (as the worker does).
        val drained = queue.drain()
        assertEquals(2, drained.size)
        assertFalse("file must be gone after drain", queueFile.exists())

        // Simulate delivery failure → re-persist.
        queue.persist(drained)

        // Events must survive for the next retry.
        val recovered = queue.read()
        assertEquals(
            "re-persisted events must survive for the next delivery attempt",
            2,
            recovered.size,
        )
        assertEquals("v-1", recovered[0].visitorId)
        assertEquals("v-2", recovered[1].visitorId)
    }

    // ---------------------------------------------------------------
    // drain() does NOT interfere with persist() append
    // ---------------------------------------------------------------

    @Test
    fun `persist after drain appends to freshly-emptied file`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        queue.persist(listOf(bucketingEvent("v-old", "e-old", "var-old")))

        val drained = queue.drain()
        assertEquals(1, drained.size)
        assertFalse("file gone after drain", queueFile.exists())

        // Persist a new event after drain.
        queue.persist(listOf(bucketingEvent("v-new", "e-new", "var-new")))

        val afterPersist = queue.read()
        assertEquals(1, afterPersist.size)
        assertEquals("v-new", afterPersist[0].visitorId)
    }

    // ---------------------------------------------------------------
    // Shared path-keyed Mutex across instances
    // ---------------------------------------------------------------

    @Test
    fun `two instances over same path share the Mutex - serialized access`() = runTest {
        // Persist via instance A, drain via instance B — should see the data.
        val queueA = FileEventQueue(context, Logger.NoOp)
        val queueB = FileEventQueue(context, Logger.NoOp)

        queueA.persist(listOf(bucketingEvent("v-1", "e-1", "var-1")))

        val drained = queueB.drain()
        assertEquals(
            "instance B must drain events persisted by instance A via shared path-keyed Mutex",
            1,
            drained.size,
        )
        assertEquals("v-1", drained[0].visitorId)

        // No events should remain.
        val remaining = queueA.read()
        assertTrue("queue must be empty after drain by sibling instance", remaining.isEmpty())
    }

    // ---------------------------------------------------------------
    // B-1 — FileLock always acquired even on fresh / deleted cacheDir
    // ---------------------------------------------------------------

    /**
     * B-1 regression test: persist() on a fresh install (cacheDir absent)
     * must acquire the FileLock (not bypass it) and succeed end-to-end.
     *
     * Before the fix, acquireFileLockAndRun() short-circuited to `block()`
     * when cacheDir was absent, skipping the FileLock for ALL operations
     * (including drain/read/clear/size), which broke the inter-process
     * exactly-once guarantee (AC-1.2). The fix calls mkdirs() so the lock
     * file can always be opened — drain() verifying the persisted data
     * confirms the full path (create dir → acquire lock → write → drain)
     * works correctly.
     */
    @Test
    fun `persist on absent cacheDir acquires FileLock and succeeds`() = runTest {
        // Precondition: cacheDir must not exist (setUp already deletes it).
        assertFalse("pre-condition: cacheDir must be absent", cacheDir.exists())

        val queue = FileEventQueue(context, Logger.NoOp)
        val events = listOf(bucketingEvent("v-fresh", "e-1", "var-a"))

        // persist() on a brand-new install — cacheDir will be created inside
        // acquireFileLockAndRun before the FileLock is acquired.
        queue.persist(events)

        assertTrue("cacheDir must exist after persist on fresh install", cacheDir.exists())
        assertTrue("lockFile must have been created", lockFile.exists())

        // drain() also goes through acquireFileLockAndRun — verify it sees the data.
        val drained = queue.drain()
        assertEquals("drain must return the freshly-persisted event", 1, drained.size)
        assertEquals("v-fresh", drained[0].visitorId)
        assertFalse("queue file must be absent after drain", queueFile.exists())
    }

    /**
     * B-1 regression test: drain() on a fresh install (cacheDir absent)
     * must acquire the FileLock and return empty (not bypass the lock and
     * short-circuit, which was the old behaviour that allowed a concurrent
     * separate-process drain to run unguarded).
     */
    @Test
    fun `drain on absent cacheDir acquires FileLock and returns empty`() = runTest {
        assertFalse("pre-condition: cacheDir must be absent", cacheDir.exists())

        val queue = FileEventQueue(context, Logger.NoOp)
        val drained = queue.drain()

        assertTrue("drain on absent cacheDir must return empty list", drained.isEmpty())
        // cacheDir is created as a side-effect of the mkdirs() in acquireFileLockAndRun.
        assertTrue("cacheDir must exist after drain (mkdirs side-effect)", cacheDir.exists())
        assertTrue("lockFile must have been created", lockFile.exists())
    }
}
