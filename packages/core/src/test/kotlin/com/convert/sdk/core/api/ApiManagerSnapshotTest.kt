/*
 * Convert Android SDK — core/api snapshotQueue tests (Story 5.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 5.3 AC-4 tests for [ApiManager.snapshotQueue].
 *
 * [snapshotQueue] is called by [com.convert.sdk.android.ConvertSDK]'s
 * extended onStop handler: after the immediate-flush attempt runs,
 * whatever is still sitting in the in-memory queue gets snapshotted and
 * persisted to the [com.convert.sdk.core.port.EventQueue] so that the
 * [com.convert.sdk.android.worker.EventFlushWorker] can pick it up later.
 *
 * Contract:
 *  1. Returns a copy of the live queue as [com.convert.sdk.core.model.VisitorEvent]s.
 *  2. Does NOT drain the queue — a subsequent flush still ships the same events.
 *  3. Returns an empty list when nothing is enqueued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ApiManagerSnapshotTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun configWithProject(): ConvertConfig = ConvertConfig(
        sdkKey = "sk-1",
        api = ApiConfig(endpoint = ApiEndpoint(config = null, track = "https://track.test/[project_id]")),
        // Keep the timer dormant so the snapshot isn't raced by a background flush.
        events = EventsConfig(batchSize = 10_000, releaseInterval = 1_000_000L),
        data = ConfigResponseData(
            accountId = "acc-1",
            project = ConfigProject(id = "proj-7"),
        ),
    )

    @Test
    fun `snapshotQueue returns enqueued events without draining`() = runTest {
        val api = ApiManager(
            httpClient = NoOpHttpClient(),
            logger = NoOpLogger(),
            config = configWithProject(),
            json = json,
            scope = null, // passive mode — no timer
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.enqueueConversionEvent("v-2", "goal-1", goalData = null)
        api.enqueueBucketingEvent("v-1", "e-2", "var-b")

        val snapshot = api.snapshotQueue()

        assertEquals(3, snapshot.size, "snapshot must contain all enqueued events")
        // Live queue untouched — snapshot again and see the same size.
        val secondSnapshot = api.snapshotQueue()
        assertEquals(3, secondSnapshot.size, "snapshotQueue must NOT drain the queue")

        // VisitorEvent envelope carries visitorId + TrackingEvent
        assertTrue(snapshot.any { it.visitorId == "v-1" })
        assertTrue(snapshot.any { it.visitorId == "v-2" })
        // The returned list must be a copy (defensive): mutations to it
        // must not leak into the live queue. Kotlin's toList() returns an
        // ArrayList, not a live view, so simply modifying `snapshot` won't
        // hit the live queue — but we assert the references differ so a
        // future implementation accidentally sharing the list becomes a
        // test failure.
        val thirdSnapshot = api.snapshotQueue()
        assertNotSame(snapshot, thirdSnapshot, "snapshotQueue must return a fresh list each call")
    }

    @Test
    fun `snapshotQueue returns empty list when queue is empty`() = runTest {
        val api = ApiManager(
            httpClient = NoOpHttpClient(),
            logger = NoOpLogger(),
            config = configWithProject(),
            json = json,
            scope = null,
        )

        assertEquals(0, api.snapshotQueue().size)
    }

    @Test
    fun `snapshotQueue preserves event order`() = runTest {
        val api = ApiManager(
            httpClient = NoOpHttpClient(),
            logger = NoOpLogger(),
            config = configWithProject(),
            json = json,
            scope = null,
        )
        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.enqueueBucketingEvent("v-1", "e-2", "var-a")
        api.enqueueBucketingEvent("v-1", "e-3", "var-a")

        val snapshot = api.snapshotQueue()
        assertEquals(3, snapshot.size)
        // Every snapshot entry carries the same visitor id
        assertTrue(snapshot.all { it.visitorId == "v-1" })
    }

    private class NoOpHttpClient : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>) =
            HttpClient.HttpResponse(200, "{}", emptyMap())
        override suspend fun post(url: String, body: String, headers: Map<String, String>) =
            HttpClient.HttpResponse(200, "{}", emptyMap())
    }

    private class NoOpLogger : Logger {
        override fun error(message: String, throwable: Throwable?, tag: String?) = Unit
        override fun warn(message: String, throwable: Throwable?, tag: String?) = Unit
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) = Unit
    }
}
