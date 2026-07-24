/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.android.adapter.FileConfigCache
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.data.DataManager
import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * qs-02 AND-1 (contract §1 — `debugToken`) RED-phase tests.
 *
 * Covers AC1 (transport on both the initial fetch and the refresh loop)
 * and AC2 (the on-disk config cache is neither read nor written while
 * `debugToken` is set). AC3 token-hygiene transport/logging assertions
 * live in `:packages:core`'s `ApiManagerDebugTokenTest`; this file only
 * covers the `:packages:sdk`-level integration surface (Builder wiring,
 * `FileConfigCache`, the refresh loop).
 *
 * The RED-phase stub ([ConvertSDK.Builder.debugToken]) only threads the
 * value onto [ConvertConfig] — it does not yet gate the cache reads/writes
 * in `ConvertSDK.kt` / `launchInitialDataSeed`, so every test in this file
 * fails on assertions today.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKDebugTokenTest {

    private lateinit var appContext: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = sharedSerializersModule
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        cacheDir = File(appContext.filesDir, FileConfigCache.CACHE_DIRNAME)
        cacheFile = File(cacheDir, FileConfigCache.CACHE_FILENAME)
        clearCache()
    }

    @After
    fun tearDown() {
        server.shutdown()
        clearCache()
    }

    private fun clearCache() {
        cacheFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    // ------------------------------------------------------------------
    // AC1 — initial fetch transport
    // ------------------------------------------------------------------

    @Test
    fun `debugToken set - initial fetch request path carries debug_token and forced low-cache`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"account_id":"acct-dbg"}"""))

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-dbg")
            .debugToken("dbg-canary-init")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()
        sdk.onReady { latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)

        val recorded = server.takeRequest(500, TimeUnit.MILLISECONDS)
        assertEquals(
            "initial fetch path must carry debug_token and forced _conv_low_cache=1",
            "/api/v1/config/sk-dbg?environment=staging&debug_token=dbg-canary-init&_conv_low_cache=1",
            recorded?.path,
        )
    }

    // ------------------------------------------------------------------
    // AC2 — cold-start cache elimination
    // ------------------------------------------------------------------

    @Test
    fun `debugToken set - successful initial fetch does not write the on-disk cache`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"account_id":"acct-dbg"}"""))

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-dbg-write")
            .debugToken("dbg-canary-write")
            .configEndpoint(server.url("/api/v1/").toString())
            .build()
        sdk.onReady { latch.countDown() }
        assertTrue("onReady should fire on successful fetch", latch.await(3, TimeUnit.SECONDS))

        // Give the fire-and-forget cache-write coroutine its usual window
        // (mirrors ConvertSDKConfigFetchTest's WAIT_CACHE_WRITE_MS) before
        // asserting it never landed.
        Thread.sleep(WAIT_CACHE_WRITE_MS)
        assertFalse(
            "on-disk cache must NOT be written while debugToken is set",
            cacheFile.exists(),
        )
    }

    @Test
    fun `debugToken set - pre-seeded cache is never read even when the fetch fails`() {
        cacheDir.mkdirs()
        cacheFile.writeText("""{"account_id":"acct-cached"}""")

        val fakeUnreachable = server.url("/api/v1/").toString()
        server.shutdown()

        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-dbg-read")
            .debugToken("dbg-canary-read")
            .configEndpoint(fakeUnreachable)
            .build()
        sdk.onReady { latch.countDown() }

        val fired = latch.await(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(
            "onReady must NOT fire from the on-disk cache while debugToken is set, " +
                "even though a cache file is present",
            fired,
        )
    }

    // ------------------------------------------------------------------
    // AC1 / AC2 — refresh loop transport + cache elimination
    // ------------------------------------------------------------------

    @Test
    fun `debugToken set - refresh loop request carries debug_token and never writes the cache`() = runBlocking {
        val refreshIntervalMs = 50L
        val http = UrlCapturingHttpClient { HttpClient.HttpResponse(200, "{}", emptyMap()) }
        val logger = Logger.NoOp
        val config = ConvertConfig(
            sdkKey = "sk-dbg-refresh",
            debugToken = "dbg-canary-refresh",
            dataRefreshInterval = refreshIntervalMs,
        )
        val eventManager = EventManager(logger = logger)
        val dataManager = DataManager(eventManager, config.environment).also {
            it.setData(ConfigResponseData())
        }
        val apiManager = ApiManager(http, logger, config, json)
        val cache = FileConfigCache(appContext, logger, json)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = logger,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = scope,
        )

        sdk.startRefreshLoopForTest()
        try {
            delay(refreshIntervalMs * 3 + 30L)

            assertTrue(
                "expected at least one refresh fetch; got ${http.urls.size}",
                http.urls.isNotEmpty(),
            )
            assertTrue(
                "every refresh-loop fetch URL must carry debug_token and forced " +
                    "_conv_low_cache=1; got ${http.urls}",
                http.urls.all {
                    it.contains("debug_token=dbg-canary-refresh") && it.contains("_conv_low_cache=1")
                },
            )
            assertFalse(
                "refresh loop must NOT write the on-disk cache while debugToken is set",
                cacheFile.exists(),
            )
        } finally {
            sdk.stopRefreshLoopForTest()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    // --- Test helpers -------------------------------------------------------

    /** Records every fetched URL; response is produced by [responder]. */
    private class UrlCapturingHttpClient(
        private val responder: () -> HttpClient.HttpResponse,
    ) : HttpClient {
        val urls: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse {
            urls += url
            return responder()
        }
        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            urls += url
            return responder()
        }
    }

    private companion object {
        private const val WAIT_CACHE_WRITE_MS: Long = 2_000
        private const val FETCH_TIMEOUT_SECONDS: Long = 5
    }
}
