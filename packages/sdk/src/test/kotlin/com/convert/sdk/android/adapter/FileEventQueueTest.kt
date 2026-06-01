/*
 * Convert Android SDK — sdk/adapter tests (Story 5.2)
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

/**
 * Story 5.2 AC-7 tests for [FileEventQueue].
 *
 * Robolectric-backed so the suite gets a real `context.filesDir` for
 * atomic write + rename semantics. Mirrors the hygiene pattern of
 * `FileConfigCacheTest` — each test cleans the queue directory up-front
 * to prevent state leaking across tests within the same JVM run.
 *
 * The transport type is [VisitorEvent] (core.model) — per the Port Contract
 * Amendment (Story 5.3, spans 1.2 + 5.2 + 5.3) and the patched 5-2 spec
 * (F-002/F-014 resolution: no timestamp field, dedup via data-class equality).
 */
@RunWith(RobolectricTestRunner::class)
internal class FileEventQueueTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var queueFile: File
    private lateinit var tmpFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, "convert-sdk")
        queueFile = File(cacheDir, "events.json")
        tmpFile = File(cacheDir, "events.json.tmp")
        queueFile.delete()
        tmpFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @After
    fun tearDown() {
        queueFile.delete()
        tmpFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    private fun bucketingEvent(visitorId: String, experienceId: String, variationId: String): VisitorEvent =
        VisitorEvent(
            visitorId = visitorId,
            event = BucketingEvent(experienceId = experienceId, variationId = variationId),
        )

    private fun conversionEvent(visitorId: String, goalId: String): VisitorEvent =
        VisitorEvent(
            visitorId = visitorId,
            event = ConversionEvent(goalId = goalId),
        )

    @Test
    fun `persist and read round-trip`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        val events = listOf(
            bucketingEvent("v-1", "e-1", "var-a"),
            conversionEvent("v-2", "goal-x"),
        )

        queue.persist(events)
        val read = queue.read()

        assertEquals(2, read.size)
        assertEquals("v-1", read[0].visitorId)
        assertTrue(read[0].event is BucketingEvent)
        assertEquals("e-1", (read[0].event as BucketingEvent).experienceId)
        assertEquals("v-2", read[1].visitorId)
        assertTrue(read[1].event is ConversionEvent)
        assertEquals("goal-x", (read[1].event as ConversionEvent).goalId)
    }

    @Test
    fun `persist twice merges events`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)

        queue.persist(listOf(bucketingEvent("v-1", "e-1", "var-a")))
        queue.persist(listOf(conversionEvent("v-2", "goal-x")))

        val read = queue.read()
        assertEquals("persist twice must merge (append)", 2, read.size)
        assertEquals("v-1", read[0].visitorId)
        assertEquals("v-2", read[1].visitorId)
    }

    @Test
    fun `read returns empty when file absent`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        val read = queue.read()
        assertTrue("absent file must yield empty list", read.isEmpty())
    }

    @Test
    fun `read deletes corrupted file and returns empty`() = runTest {
        cacheDir.mkdirs()
        queueFile.writeText("{{not json}}")
        assertTrue("pre-seed: queueFile must exist", queueFile.exists())

        val logger = CapturingLogger()
        val queue = FileEventQueue(context, logger)
        val read = queue.read()

        assertTrue("corrupted file must return empty", read.isEmpty())
        assertFalse("corrupted file must be deleted after read", queueFile.exists())
        assertTrue(
            "expected ERROR-level log on corruption; got ${logger.errorMessages()}",
            logger.errorMessages().isNotEmpty(),
        )
    }

    @Test
    fun `clear removes the queue file`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        queue.persist(listOf(bucketingEvent("v-1", "e-1", "var-a")))
        assertTrue(queueFile.exists())

        queue.clear()

        assertFalse("clear() must delete the queue file", queueFile.exists())
        assertEquals(0, queue.size())
    }

    @Test
    fun `size reflects persisted count`() = runTest {
        val queue = FileEventQueue(context, Logger.NoOp)
        assertEquals(0, queue.size())

        queue.persist(
            listOf(
                bucketingEvent("v-1", "e-1", "var-a"),
                conversionEvent("v-2", "goal-x"),
                bucketingEvent("v-3", "e-2", "var-b"),
            ),
        )

        assertEquals(3, queue.size())
    }

    private class CapturingLogger : Logger {
        private val entries: MutableList<Pair<String, String>> = mutableListOf()
        override fun error(message: String, throwable: Throwable?, tag: String?) {
            entries += "ERROR" to message
        }
        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            entries += "WARN" to message
        }
        override fun info(message: String, tag: String?) {
            entries += "INFO" to message
        }
        override fun debug(message: String, tag: String?) {
            entries += "DEBUG" to message
        }
        fun errorMessages(): List<String> = entries.filter { it.first == "ERROR" }.map { it.second }
    }
}
