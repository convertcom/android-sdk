/*
 * Convert Android SDK — sdk/adapter tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.util.Log
import com.convert.sdk.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Robolectric-backed tests for [AndroidLogger]. Story 2.1 AC-8.
 *
 * ### AC-6.1 isolation contract
 *
 * [ShadowLog.getLogs] is a JVM-static buffer shared across all Robolectric
 * tests that run in the same process. Cross-class log bleed can make
 * exact-size assertions fail non-deterministically under `org.gradle.parallel=true`.
 *
 * Every assertion in this file filters the static buffer for entries that
 * match the tag(s) emitted by the logger under test. The exact size of the
 * full buffer is never asserted — only that the test-owned entries satisfy
 * the expected type/content constraints.
 *
 * Covers:
 * - Level filtering: debug() emits nothing when level=INFO.
 * - All four levels pass through when level=DEBUG or more permissive.
 * - SILENT drops every level.
 * - Per-call tag overrides the default tag.
 * - Default tag is "ConvertSDK" when none supplied.
 * - error()/warn() forward the throwable to Log.e / Log.w.
 */
@RunWith(RobolectricTestRunner::class)
internal class AndroidLoggerTest {

    @Before
    fun resetLog() {
        ShadowLog.clear()
    }

    @Test
    fun `emits all four levels when configured at DEBUG`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(4, ownLogs.size)
        assertEquals(Log.ERROR, ownLogs[0].type)
        assertEquals(Log.WARN, ownLogs[1].type)
        assertEquals(Log.INFO, ownLogs[2].type)
        assertEquals(Log.DEBUG, ownLogs[3].type)
    }

    @Test
    fun `drops debug when configured at INFO`() {
        val logger = AndroidLogger(level = LogLevel.INFO)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(3, ownLogs.size)
        // DEBUG not present
        assertTrue(ownLogs.none { it.type == Log.DEBUG })
    }

    @Test
    fun `drops debug and info when configured at WARN`() {
        val logger = AndroidLogger(level = LogLevel.WARN)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(2, ownLogs.size)
        assertTrue(ownLogs.none { it.type == Log.INFO })
        assertTrue(ownLogs.none { it.type == Log.DEBUG })
    }

    @Test
    fun `emits only error when configured at ERROR`() {
        val logger = AndroidLogger(level = LogLevel.ERROR)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(1, ownLogs.size)
        assertEquals(Log.ERROR, ownLogs[0].type)
    }

    @Test
    fun `SILENT drops every level`() {
        val logger = AndroidLogger(level = LogLevel.SILENT)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertTrue(ownLogs.isEmpty())
    }

    @Test
    fun `TRACE emits every level including the four standard ones`() {
        // TRACE is more permissive than DEBUG — all standard levels pass through.
        val logger = AndroidLogger(level = LogLevel.TRACE)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(4, ownLogs.size)
    }

    @Test
    fun `default tag is ConvertSDK when per-call tag is null`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("hello")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == AndroidLogger.DEFAULT_TAG }
        assertEquals(1, ownLogs.size)
        assertEquals(AndroidLogger.DEFAULT_TAG, ownLogs[0].tag)
    }

    @Test
    fun `per-call tag overrides the default tag`() {
        val overrideTag = "DataManager"
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("hello", tag = overrideTag)

        val ownLogs = ShadowLog.getLogs().filter { it.tag == overrideTag }
        assertEquals(1, ownLogs.size)
        assertEquals(overrideTag, ownLogs[0].tag)
    }

    @Test
    fun `constructor-supplied default tag is used when per-call tag is null`() {
        val customTag = "ConvertSDK-Test"
        val logger = AndroidLogger(level = LogLevel.DEBUG, defaultTag = customTag)

        logger.warn("hello")

        val ownLogs = ShadowLog.getLogs().filter { it.tag == customTag }
        assertEquals(1, ownLogs.size)
        assertEquals(customTag, ownLogs[0].tag)
    }

    @Test
    fun `error forwards throwable to ShadowLog entry`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)
        val boom = IllegalStateException("boom")

        logger.error("broke", throwable = boom)

        val ownLogs = ShadowLog.getLogs().filter {
            it.tag == AndroidLogger.DEFAULT_TAG && it.type == Log.ERROR
        }
        assertEquals(1, ownLogs.size)
        assertEquals(Log.ERROR, ownLogs[0].type)
        assertEquals("broke", ownLogs[0].msg)
        assertNotNull(ownLogs[0].throwable)
        assertEquals(boom, ownLogs[0].throwable)
    }

    @Test
    fun `warn forwards throwable to ShadowLog entry`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)
        val warn = RuntimeException("warn")

        logger.warn("something", throwable = warn)

        val ownLogs = ShadowLog.getLogs().filter {
            it.tag == AndroidLogger.DEFAULT_TAG && it.type == Log.WARN
        }
        assertEquals(1, ownLogs.size)
        assertEquals(Log.WARN, ownLogs[0].type)
        assertEquals(warn, ownLogs[0].throwable)
    }

    @Test
    fun `message content is preserved verbatim`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("my informational message")

        val ownLogs = ShadowLog.getLogs().filter {
            it.tag == AndroidLogger.DEFAULT_TAG && it.msg == "my informational message"
        }
        assertEquals(1, ownLogs.size)
        assertEquals("my informational message", ownLogs.single().msg)
    }
}
