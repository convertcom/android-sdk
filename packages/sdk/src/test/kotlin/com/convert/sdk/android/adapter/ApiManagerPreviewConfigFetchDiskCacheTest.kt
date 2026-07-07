/*
 * Convert Android SDK — sdk/adapter tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * qs-02 AND-4 (contract §2 "Resolution" / AC8) disk-cache regression lock
 * for [ApiManager.fetchConfig]'s `experienceId` overload.
 *
 * ### Regression lock, not a RED/GREEN assertion
 *
 * [ApiManager] (`:packages:core`, `kotlin("jvm")`) has no reference to
 * [FileConfigCache] (`:packages:sdk`, requires `android.content.Context`)
 * at all — a pure-JVM module cannot depend on an Android type. The
 * `?exp=` preview-config fetch is therefore structurally unable to write
 * the on-disk cache regardless of how it is implemented. This mirrors the
 * "structurally safe today ... this is a regression lock, not a RED
 * assertion" precedent in `ApiManagerDebugTokenTest`'s track-URL test
 * (`:packages:core`).
 *
 * The test exists to guard the invariant against a future refactor — e.g.
 * a later qs-02 story (AND-5) accidentally feeding this method's result
 * into `FileConfigCache.write` when wiring the preview decision into
 * `ConvertContext`. The control assertion at the end of the test proves
 * the primary assertion is meaningful (a real [FileConfigCache.write]
 * call DOES create the file) rather than a false negative from a broken
 * check.
 */
@RunWith(RobolectricTestRunner::class)
internal class ApiManagerPreviewConfigFetchDiskCacheTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = sharedSerializersModule
    }

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.filesDir, FileConfigCache.CACHE_DIRNAME)
        cacheFile = File(cacheDir, FileConfigCache.CACHE_FILENAME)
        clearCache()
    }

    @After
    fun tearDown() {
        clearCache()
    }

    private fun clearCache() {
        cacheFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    @Test
    fun `exp preview fetch never writes the on-disk FileConfigCache`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = """{"accountId":"acc-preview"}""")
        val config = ConvertConfig(sdkKey = "sk-preview")
        val api = ApiManager(http, Logger.NoOp, config, json)

        val result = api.fetchConfig(experienceId = "555")

        assertNotNull("exp-fetch should succeed", result)
        assertFalse(
            "FileConfigCache must never be written by the exp-preview fetch path",
            cacheFile.exists(),
        )

        // Control assertion: a real FileConfigCache.write DOES create the
        // file, proving the assertion above is meaningful.
        FileConfigCache(context, Logger.NoOp, json).write(requireNotNull(result))
        assertTrue("control write should create the cache file", cacheFile.exists())
    }

    /** Minimal fixed-response [HttpClient] fake — no call recording needed here. */
    private class FakeHttpClient(
        private val statusCode: Int,
        private val body: String,
    ) : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse =
            HttpClient.HttpResponse(statusCode = statusCode, body = body, headers = emptyMap())

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse =
            HttpClient.HttpResponse(statusCode = statusCode, body = this.body, headers = emptyMap())
    }
}
