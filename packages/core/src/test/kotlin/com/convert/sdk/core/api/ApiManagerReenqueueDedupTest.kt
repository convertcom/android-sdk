/*
 * Convert Android SDK — core/api tests (PR #39 Cluster 2)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.EventQueue
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * PR #39 Cluster 2 — visitorId-aware dedup in [ApiManager.reenqueuePersisted].
 *
 * Covers **AC-2.1**: different visitors with identical payloads must both be
 * kept; same visitor with identical payload is deduplicated.
 *
 * Uses JUnit5 [ParameterizedTest] + [MethodSource] to avoid copy-paste
 * duplication across test cases (SonarQube CPD discipline: ≤3% new
 * duplicated lines). Each row of [cases] maps to exactly one test run.
 */
internal class ApiManagerReenqueueDedupTest {

    companion object {

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        private val noopConfig = ConvertConfig(
            sdkKey = "sk-dedup-test",
            api = ApiConfig(
                endpoint = ApiEndpoint(track = "https://track.test/[project_id]"),
            ),
            events = EventsConfig(batchSize = 10_000, releaseInterval = 1_000_000L),
            data = ConfigResponseData(
                accountId = "acc-1",
                project = ConfigProject(id = "proj-1"),
            ),
        )

        private val noopHttp = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) =
                HttpClient.HttpResponse(200, "{}", emptyMap())
            override suspend fun post(url: String, body: String, headers: Map<String, String>) =
                HttpClient.HttpResponse(200, "{}", emptyMap())
        }

        private fun bucketing(
            visitorId: String,
            experienceId: String = "e-1",
            variationId: String = "var-a",
        ): VisitorEvent = VisitorEvent(
            visitorId = visitorId,
            event = BucketingEvent(experienceId = experienceId, variationId = variationId),
        )

        private fun conversion(
            visitorId: String,
            goalId: String = "goal-1",
            goalData: List<GoalData>? = null,
        ): VisitorEvent = VisitorEvent(
            visitorId = visitorId,
            event = ConversionEvent(goalId = goalId, goalData = goalData),
        )

        /**
         * Parameterized cases for [testReenqueueDedupWithVisitorId].
         *
         * Each row: (description, liveEvents, persistedEvents, expectedSize, expectedVisitorIds)
         */
        @JvmStatic
        fun cases(): Stream<Arguments> = Stream.of(
            *bucketingCases(),
            *conversionCases(),
            *mixedCases(),
        )

        private fun bucketingCases(): Array<Arguments> = arrayOf(
            // Different visitors, identical bucketing payload — BOTH kept (AC-2.1 main)
            Arguments.of(
                "different visitors with identical bucketing payload — both kept",
                listOf(bucketing("visitor-A")),
                listOf(bucketing("visitor-B")),
                2,
                setOf("visitor-A", "visitor-B"),
            ),
            // Same visitor, identical bucketing payload — duplicate dropped
            Arguments.of(
                "same visitor same bucketing payload — duplicate dropped",
                listOf(bucketing("visitor-A")),
                listOf(bucketing("visitor-A")),
                1,
                setOf("visitor-A"),
            ),
            // Same visitor, different experiences — both kept
            Arguments.of(
                "same visitor different experiences — both kept",
                listOf(bucketing("visitor-A", experienceId = "e-1", variationId = "var-a")),
                listOf(bucketing("visitor-A", experienceId = "e-2", variationId = "var-b")),
                2,
                setOf("visitor-A"),
            ),
        )

        private fun conversionCases(): Array<Arguments> = arrayOf(
            // Different visitors, identical conversion payload — both kept
            Arguments.of(
                "different visitors with identical conversion payload — both kept",
                listOf(conversion("visitor-A", goalId = "goal-1")),
                listOf(conversion("visitor-B", goalId = "goal-1")),
                2,
                setOf("visitor-A", "visitor-B"),
            ),
            // Same visitor, same conversion goal — duplicate dropped
            Arguments.of(
                "same visitor same conversion payload — duplicate dropped",
                listOf(conversion("visitor-A", goalId = "goal-1")),
                listOf(conversion("visitor-A", goalId = "goal-1")),
                1,
                setOf("visitor-A"),
            ),
            // Same visitor, same goal but different goalData — both kept
            Arguments.of(
                "same visitor same goal different goalData — both kept",
                listOf(
                    conversion(
                        "visitor-A",
                        goalId = "goal-1",
                        goalData = listOf(GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(10.0))),
                    ),
                ),
                listOf(
                    conversion(
                        "visitor-A",
                        goalId = "goal-1",
                        goalData = listOf(GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(20.0))),
                    ),
                ),
                2,
                setOf("visitor-A"),
            ),
        )

        private fun mixedCases(): Array<Arguments> = arrayOf(
            // Same visitor, bucketing + conversion — both kept (different event type)
            Arguments.of(
                "same visitor bucketing and conversion — both kept",
                listOf(bucketing("visitor-A")),
                listOf(conversion("visitor-A", goalId = "goal-1")),
                2,
                setOf("visitor-A"),
            ),
            // Empty persisted list — live queue unchanged
            Arguments.of(
                "empty persisted events — live queue unchanged",
                listOf(bucketing("visitor-A")),
                emptyList<VisitorEvent>(),
                1,
                setOf("visitor-A"),
            ),
        )

        /** Minimal no-op [EventQueue] for tests that don't exercise persistence. */
        private object NoOpEventQueue : EventQueue {
            override suspend fun persist(events: List<VisitorEvent>) = Unit
            override suspend fun read(): List<VisitorEvent> = emptyList()
            override suspend fun clear() = Unit
            override suspend fun size(): Int = 0
            override suspend fun drain(): List<VisitorEvent> = emptyList()
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    fun testReenqueueDedupWithVisitorId(
        description: String,
        liveEvents: List<VisitorEvent>,
        persistedEvents: List<VisitorEvent>,
        expectedQueueSize: Int,
        expectedVisitorIds: Set<String>,
    ) {
        val api = ApiManager(
            httpClient = noopHttp,
            logger = Logger.NoOp,
            config = noopConfig,
            json = json,
            eventQueue = NoOpEventQueue,
        )

        // Seed the live queue using enqueueAll (bypasses disk; sets up in-memory state).
        api.enqueueAll(liveEvents)

        // Re-enqueue persisted events — this is the method under test.
        api.reenqueuePersisted(persistedEvents)

        val snapshot = api.snapshotQueueForTest()

        assertEquals(
            expectedQueueSize,
            snapshot.size,
            "[$description] queue size mismatch",
        )

        val actualVisitorIds = snapshot.map { it.visitorId }.toSet()
        assertEquals(
            expectedVisitorIds,
            actualVisitorIds,
            "[$description] visitor ids mismatch",
        )

        // All expected visitor ids must be present
        assertTrue(
            actualVisitorIds.containsAll(expectedVisitorIds),
            "[$description] snapshot must contain all expected visitor ids",
        )
    }
}
