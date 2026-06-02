/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.android.adapter.FileConfigCache
import com.convert.sdk.core.model.generated.ConfigResponseData
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Story 2.2 AC-5/6/7/10 integration tests for the full
 * `Builder.build() → ApiManager.fetchConfig() → DataManager.setData() →
 * FileConfigCache.write()` flow.
 *
 * Uses [MockWebServer] so every path is deterministic (no real network
 * calls; no flaky CDN reachability dependencies).
 *
 * Cleans `context.filesDir/convert-sdk/` before and after each test so the
 * cache-fallback cases are isolated.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKConfigFetchTest {

    private lateinit var appContext: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        cacheDir = File(appContext.filesDir, FileConfigCache.CACHE_DIRNAME)
        cacheFile = File(cacheDir, FileConfigCache.CACHE_FILENAME)
        // Start from a known-clean state so a prior test's write can't
        // satisfy a later test's "offline cold start" expectation.
        cacheFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @Test
    fun `sdk-key mode with successful fetch fires onReady and writes cache`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"account_id":"acct-success"}"""),
        )

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-success")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()

        sdk.onReady { latch.countDown() }

        assertTrue(
            "onReady should fire after successful fetch (within 3s)",
            latch.await(3, TimeUnit.SECONDS),
        )

        // Story 2.2 AC-1 / AC-10 — the request path must be the canonical
        // shape: `/api/v1/config/{sdkKey}?environment={env}`. Asserts the
        // exact full path string (no substring assertions on URL parity)
        // per the audit's class 4.4 test-shape rule.
        val recorded = server.takeRequest(500, TimeUnit.MILLISECONDS)
        assertNotNull("mockwebserver should have seen a request", recorded)
        assertEquals(
            "request path must be the exact canonical config path",
            "/api/v1/config/sk-success?environment=staging",
            recorded!!.path,
        )

        // Cache should have been written in the fire-and-forget scope.launch.
        // Allow a little time for the FS flush on the IO dispatcher.
        val deadline = System.currentTimeMillis() + WAIT_CACHE_WRITE_MS
        while (!cacheFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(CACHE_POLL_INTERVAL_MS)
        }
        assertTrue("cache file should exist after successful fetch", cacheFile.exists())
        val cachedText = cacheFile.readText()
        assertTrue(
            "cache should contain the fetched accountId: $cachedText",
            cachedText.contains("acct-success"),
        )
    }

    @Test
    fun `sdk-key mode with unreachable server and cached config loads from cache`() {
        // Pre-seed the cache with a valid config.
        cacheDir.mkdirs()
        cacheFile.writeText("""{"account_id":"acct-cached"}""")

        // Shut the MockWebServer so the fetch guarantees a transport failure.
        val fakeUnreachable = server.url("/api/v1/").toString()
        server.shutdown()

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-offline")
            .configEndpoint(fakeUnreachable)
            .build()

        sdk.onReady { latch.countDown() }

        assertTrue(
            "onReady should fire from cached config when fetch fails (within 5s)",
            latch.await(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `sdk-key mode with unreachable server and no cache stays unready`() {
        val fakeUnreachable = server.url("/api/v1/").toString()
        server.shutdown()

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-nothing")
            .configEndpoint(fakeUnreachable)
            .build()

        sdk.onReady { latch.countDown() }

        // Give the async fetch + cache-read path time to run; assert onReady
        // does NOT fire.
        val fired = latch.await(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(
            "onReady must NOT fire when both network and cache are empty",
            fired,
        )
    }

    @Test
    fun `direct-data mode skips fetch entirely — mockwebserver sees no requests`() {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-ignored")
            .data(ConfigResponseData(accountId = "direct"))
            .configEndpoint(server.url("/api/v1/").toString())
            .build()

        val latch = CountDownLatch(1)
        sdk.onReady { latch.countDown() }
        assertTrue(
            "direct-data mode should fire onReady",
            latch.await(2, TimeUnit.SECONDS),
        )

        // No request should have been sent to the MockWebServer.
        val recorded = server.takeRequest(500, TimeUnit.MILLISECONDS)
        assertEquals(
            "direct-data mode must skip the network fetch",
            null,
            recorded,
        )
    }

    @Test
    fun `fetch response with parse failure does not write cache`() {
        // Server returns garbage that cannot deserialise.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not valid json {{{"),
        )

        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-bad-json")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()

        val latch = CountDownLatch(1)
        sdk.onReady { latch.countDown() }

        val fired = latch.await(2, TimeUnit.SECONDS)
        assertFalse("parse failure must not fire onReady", fired)

        // And the cache should remain absent — we only write on successful parse.
        Thread.sleep(WAIT_CACHE_WRITE_MS.toLong())
        assertFalse(
            "parse failure must NOT write the cache",
            cacheFile.exists(),
        )
    }

    @Test
    fun `fetch response non-2xx does not write cache`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-500")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()

        val latch = CountDownLatch(1)
        sdk.onReady { latch.countDown() }

        val fired = latch.await(2, TimeUnit.SECONDS)
        assertFalse("500 response must not fire onReady (no cache)", fired)

        Thread.sleep(WAIT_CACHE_WRITE_MS.toLong())
        assertFalse(
            "500 response must NOT write the cache",
            cacheFile.exists(),
        )
    }

    @Test
    fun `fetch uses Authorization header when sdkKeySecret is set`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"account_id":"secret-ok"}"""),
        )

        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-authed")
            .sdkKeySecret("super-secret-xyz")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()

        val latch = CountDownLatch(1)
        sdk.onReady { latch.countDown() }
        assertTrue(latch.await(3, TimeUnit.SECONDS))

        val recorded = server.takeRequest(500, TimeUnit.MILLISECONDS)
        assertNotNull(recorded)
        assertEquals(
            "Authorization header must be set verbatim",
            "super-secret-xyz",
            recorded!!.getHeader("Authorization"),
        )
    }

    companion object {
        /** Max time (ms) to wait for the fire-and-forget cache write to flush. */
        private const val WAIT_CACHE_WRITE_MS: Int = 2_000
        private const val CACHE_POLL_INTERVAL_MS: Long = 50

        /** Max time (s) for the fetch+fallback path to complete in an unreachable-server test. */
        private const val FETCH_TIMEOUT_SECONDS: Long = 5
    }
}
