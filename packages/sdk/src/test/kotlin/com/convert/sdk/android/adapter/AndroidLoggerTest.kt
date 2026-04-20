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

        val logs = ShadowLog.getLogs()
        assertEquals(4, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
        assertEquals(Log.WARN, logs[1].type)
        assertEquals(Log.INFO, logs[2].type)
        assertEquals(Log.DEBUG, logs[3].type)
    }

    @Test
    fun `drops debug when configured at INFO`() {
        val logger = AndroidLogger(level = LogLevel.INFO)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val logs = ShadowLog.getLogs()
        assertEquals(3, logs.size)
        // DEBUG not present
        assertTrue(logs.none { it.type == Log.DEBUG })
    }

    @Test
    fun `drops debug and info when configured at WARN`() {
        val logger = AndroidLogger(level = LogLevel.WARN)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val logs = ShadowLog.getLogs()
        assertEquals(2, logs.size)
        assertTrue(logs.none { it.type == Log.INFO })
        assertTrue(logs.none { it.type == Log.DEBUG })
    }

    @Test
    fun `emits only error when configured at ERROR`() {
        val logger = AndroidLogger(level = LogLevel.ERROR)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
    }

    @Test
    fun `SILENT drops every level`() {
        val logger = AndroidLogger(level = LogLevel.SILENT)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        assertTrue(ShadowLog.getLogs().isEmpty())
    }

    @Test
    fun `TRACE emits every level including the four standard ones`() {
        // TRACE is more permissive than DEBUG — all standard levels pass through.
        val logger = AndroidLogger(level = LogLevel.TRACE)

        logger.error("e")
        logger.warn("w")
        logger.info("i")
        logger.debug("d")

        assertEquals(4, ShadowLog.getLogs().size)
    }

    @Test
    fun `default tag is ConvertSDK when per-call tag is null`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("hello")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals("ConvertSDK", logs[0].tag)
    }

    @Test
    fun `per-call tag overrides the default tag`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("hello", tag = "DataManager")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals("DataManager", logs[0].tag)
    }

    @Test
    fun `constructor-supplied default tag is used when per-call tag is null`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG, defaultTag = "ConvertSDK-Test")

        logger.warn("hello")

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals("ConvertSDK-Test", logs[0].tag)
    }

    @Test
    fun `error forwards throwable to ShadowLog entry`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)
        val boom = IllegalStateException("boom")

        logger.error("broke", throwable = boom)

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
        assertEquals("broke", logs[0].msg)
        assertNotNull(logs[0].throwable)
        assertEquals(boom, logs[0].throwable)
    }

    @Test
    fun `warn forwards throwable to ShadowLog entry`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)
        val warn = RuntimeException("warn")

        logger.warn("something", throwable = warn)

        val logs = ShadowLog.getLogs()
        assertEquals(1, logs.size)
        assertEquals(Log.WARN, logs[0].type)
        assertEquals(warn, logs[0].throwable)
    }

    @Test
    fun `message content is preserved verbatim`() {
        val logger = AndroidLogger(level = LogLevel.DEBUG)

        logger.info("my informational message")

        assertEquals("my informational message", ShadowLog.getLogs().single().msg)
    }
}
