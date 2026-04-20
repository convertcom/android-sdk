/*
 * Convert Android SDK — sdk/adapter tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Story 2.2 AC-10 tests for [FileConfigCache].
 *
 * Robolectric-backed: uses [ApplicationProvider.getApplicationContext] to
 * materialise a real `context.filesDir`. Each test cleans the cache
 * directory up-front so state from a prior test cannot leak across.
 *
 * ### JUnit 4 in this module
 *
 * `packages/sdk` uses JUnit 4 tests (via the vintage engine) so that
 * `@RunWith(RobolectricTestRunner::class)` works — Robolectric 4.x is
 * JUnit 4 only. Assertion argument order is `(message, condition)` —
 * the JUnit 4 convention — opposite to the core module's Jupiter tests.
 */
@RunWith(RobolectricTestRunner::class)
internal class FileConfigCacheTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File
    private lateinit var tmpFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, "convert-sdk")
        cacheFile = File(cacheDir, "config.json")
        tmpFile = File(cacheDir, "config.json.tmp")
        // Robolectric's filesDir persists across tests within a single JVM
        // invocation — clean up to prevent state leakage.
        cacheFile.delete()
        tmpFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @After
    fun tearDown() {
        cacheFile.delete()
        tmpFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @Test
    fun `write and read round-trips valid config`() = runTest {
        val cache = FileConfigCache(context, Logger.NoOp)
        val config = ConfigResponseData(accountId = "acct-42")

        cache.write(config)
        val read = cache.read()

        assertNotNull("round-tripped config must not be null", read)
        assertEquals("acct-42", read?.accountId)
    }

    @Test
    fun `read returns null when file absent`() = runTest {
        val logger = CapturingLogger()
        val cache = FileConfigCache(context, logger)

        val read = cache.read()

        assertNull("expected null when cache file does not exist", read)
        // AC-4: absent file logs INFO, not WARN or ERROR.
        assertTrue(
            "expected an INFO-level message about absent cache; got $logger",
            logger.infoMessages().isNotEmpty(),
        )
    }

    @Test
    fun `read recovers from corrupted JSON by deleting file`() = runTest {
        // Pre-seed the cache file with garbage.
        cacheDir.mkdirs()
        cacheFile.writeText("not even close to JSON {{{")
        assertTrue("pre-seed: cacheFile must exist", cacheFile.exists())

        val logger = CapturingLogger()
        val cache = FileConfigCache(context, logger)
        val read = cache.read()

        assertNull("corrupt file must return null", read)
        assertFalse("corrupt file must be deleted after read", cacheFile.exists())
        // AC-8 requires ERROR-level log on corruption.
        assertTrue(
            "expected an ERROR message on corruption; got ${logger.errorMessages()}",
            logger.errorMessages().isNotEmpty(),
        )
    }

    @Test
    fun `write creates parent directory if missing`() = runTest {
        assertFalse("pre-condition: cacheDir must not exist", cacheDir.exists())

        val cache = FileConfigCache(context, Logger.NoOp)
        cache.write(ConfigResponseData(accountId = "x"))

        assertTrue("cacheDir should exist after write", cacheDir.exists())
        assertTrue("cacheFile should exist after write", cacheFile.exists())
    }

    @Test
    fun `delete removes cache file when present`() = runTest {
        cacheDir.mkdirs()
        cacheFile.writeText("""{"account_id":"x"}""")
        assertTrue(cacheFile.exists())

        val cache = FileConfigCache(context, Logger.NoOp)
        cache.delete()

        assertFalse("cacheFile should not exist after delete()", cacheFile.exists())
    }

    @Test
    fun `delete is a no-op when file is absent`() = runTest {
        assertFalse(cacheFile.exists())

        val cache = FileConfigCache(context, Logger.NoOp)
        // Should not throw.
        cache.delete()

        assertFalse(cacheFile.exists())
    }

    @Test
    fun `atomic write leaves no stale tmp file on success`() = runTest {
        val cache = FileConfigCache(context, Logger.NoOp)

        cache.write(ConfigResponseData(accountId = "acct-99"))

        assertTrue("cacheFile must exist after write", cacheFile.exists())
        assertFalse("tmp file must NOT remain after successful rename", tmpFile.exists())
    }

    @Test
    fun `write overwrites prior config`() = runTest {
        val cache = FileConfigCache(context, Logger.NoOp)

        cache.write(ConfigResponseData(accountId = "first"))
        cache.write(ConfigResponseData(accountId = "second"))

        val read = cache.read()
        assertEquals("second", read?.accountId)
    }

    @Test
    fun `write does not leak sdkKeySecret-like values — cache stores only ConfigResponseData`() =
        runTest {
            // This is a belt-and-braces check — ConfigResponseData has no
            // credential field by design. The test just verifies that what
            // we wrote is what we see: if someone ever refactors write() to
            // accept the request config by mistake, this test would catch
            // the unexpected content via the missing accountId check.
            val cache = FileConfigCache(context, Logger.NoOp)
            val config = ConfigResponseData(accountId = "acct-canary")

            cache.write(config)
            val rawBytes = cacheFile.readText()

            // Must contain accountId — it's the only field we populated.
            assertTrue(
                "cache file should serialise the account id: $rawBytes",
                rawBytes.contains("acct-canary"),
            )
            // Ensure typical credential substrings are absent.
            assertFalse(
                "cache file must never contain authorization-ish fields: $rawBytes",
                rawBytes.contains("sdkKeySecret", ignoreCase = true),
            )
            assertFalse(
                "cache file must never contain authorization-ish fields: $rawBytes",
                rawBytes.contains("authorization", ignoreCase = true),
            )
        }

    @Test
    fun `read returns null when file is empty`() = runTest {
        cacheDir.mkdirs()
        cacheFile.writeText("")

        val logger = CapturingLogger()
        val cache = FileConfigCache(context, logger)
        val read = cache.read()

        // An empty file is corrupt-ish — treat like corruption recovery.
        assertNull(read)
        assertFalse("empty file should be deleted after read", cacheFile.exists())
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Capturing [Logger] — collects every call so tests can assert log
     * expectations. Same pattern as ApiManagerTest.CapturingLogger but
     * exposed here via the sdk module's own file.
     */
    private class CapturingLogger : Logger {
        data class Entry(val level: String, val message: String, val tag: String?)

        private val entries: MutableList<Entry> = mutableListOf()

        override fun error(message: String, throwable: Throwable?, tag: String?) {
            entries += Entry("ERROR", message, tag)
        }
        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            entries += Entry("WARN", message, tag)
        }
        override fun info(message: String, tag: String?) {
            entries += Entry("INFO", message, tag)
        }
        override fun debug(message: String, tag: String?) {
            entries += Entry("DEBUG", message, tag)
        }

        fun errorMessages(): List<String> =
            entries.filter { it.level == "ERROR" }.map { it.message }
        fun warnMessages(): List<String> =
            entries.filter { it.level == "WARN" }.map { it.message }
        fun infoMessages(): List<String> =
            entries.filter { it.level == "INFO" }.map { it.message }

        override fun toString(): String = entries.toString()
    }
}
