/*
 * Convert Android SDK — core/api TrackingPayloadBuilder tests (Story 5.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 5.3 CORE-1 tests for [TrackingPayloadBuilder].
 *
 * The builder exists so [com.convert.sdk.android.worker.EventFlushWorker]
 * (Story 5.3 AC-1) can rebuild the same outbound POST body from
 * disk-loaded [VisitorEvent]s that [ApiManager.flush] builds from
 * in-memory [ApiManager.VisitorEvent]s — without the worker reaching
 * into ApiManager internals.
 *
 * These tests assert:
 *  1. Wire shape matches ApiManager's buildPayload exactly — same
 *     accountId / projectId / enrichData / source / visitors keys.
 *  2. Events group by visitorId with the LAST enqueue's segments
 *     snapshotted to the visitor (JS SDK parity).
 *  3. A null/empty segments map is rendered as an absent `segments` key
 *     (not an empty object).
 *  4. `source` is omitted when `config.network?.source` is null.
 *  5. `enrichData = true` when `config.data` is null; `false` otherwise.
 */
internal class TrackingPayloadBuilderTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun makeBucketingEvent(
        visitorId: String,
        segments: Map<String, JsonElement>? = null,
    ): VisitorEvent = VisitorEvent(
        visitorId = visitorId,
        segments = segments,
        event = BucketingEvent(experienceId = "exp-1", variationId = "var-1"),
    )

    private fun makeConversionEvent(
        visitorId: String,
        segments: Map<String, JsonElement>? = null,
    ): VisitorEvent = VisitorEvent(
        visitorId = visitorId,
        segments = segments,
        event = ConversionEvent(goalId = "goal-1"),
    )

    private fun configWithProject(source: String? = null): ConvertConfig = ConvertConfig(
        sdkKey = "s-key",
        data = ConfigResponseData(
            accountId = "acc-1",
            project = ConfigProject(id = "proj-1"),
        ),
        network = source?.let { NetworkConfig(source = it) },
    )

    private fun configWithoutData(source: String? = null): ConvertConfig = ConvertConfig(
        sdkKey = "s-key",
        data = null,
        network = source?.let { NetworkConfig(source = it) },
    )

    @Test
    fun `build groups events by visitorId and sets accountId and projectId`() {
        val events = listOf(
            makeBucketingEvent("v-1"),
            makeConversionEvent("v-2"),
            makeConversionEvent("v-1"),
        )
        val result = TrackingPayloadBuilder.build(events, configWithProject(), json)

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals("acc-1", payload["accountId"]?.jsonPrimitive?.content)
        assertEquals("proj-1", payload["projectId"]?.jsonPrimitive?.content)
        assertEquals(false, payload["enrichData"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(payload["source"], "source must be absent when unset")

        val visitors = payload["visitors"]!!.jsonArray
        assertEquals(2, visitors.size)

        val v1 = visitors.first { it.jsonObject["visitorId"]!!.jsonPrimitive.content == "v-1" }.jsonObject
        val v1Events = v1["events"]!!.jsonArray
        assertEquals(2, v1Events.size, "v-1 should have two events grouped")
    }

    @Test
    fun `build snapshots segments from last enqueue per visitor`() {
        val firstSegments = mapOf("tier" to kotlinx.serialization.json.JsonPrimitive("bronze"))
        val lastSegments = mapOf(
            "tier" to kotlinx.serialization.json.JsonPrimitive("gold"),
            "region" to kotlinx.serialization.json.JsonPrimitive("us-east"),
        )
        val events = listOf(
            makeBucketingEvent("v-1", firstSegments),
            makeConversionEvent("v-1", lastSegments),
        )
        val result = TrackingPayloadBuilder.build(events, configWithProject(), json)

        val visitors = json.parseToJsonElement(result).jsonObject["visitors"]!!.jsonArray
        val v1 = visitors.first { it.jsonObject["visitorId"]!!.jsonPrimitive.content == "v-1" }.jsonObject
        val segments = v1["segments"]!!.jsonObject
        assertEquals("gold", segments["tier"]!!.jsonPrimitive.content)
        assertEquals("us-east", segments["region"]!!.jsonPrimitive.content)
    }

    @Test
    fun `build omits segments key when the last enqueue segments are null or empty`() {
        val events = listOf(makeBucketingEvent("v-1", segments = null))
        val result = TrackingPayloadBuilder.build(events, configWithProject(), json)

        val visitors = json.parseToJsonElement(result).jsonObject["visitors"]!!.jsonArray
        val v1 = visitors.first().jsonObject
        assertNull(v1["segments"], "null segments must not render a segments key")
    }

    @Test
    fun `build sets enrichData true when config-data is null`() {
        val events = listOf(makeBucketingEvent("v-1"))
        val result = TrackingPayloadBuilder.build(events, configWithoutData(), json)

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals(true, payload["enrichData"]?.jsonPrimitive?.content?.toBoolean())
        // accountId / projectId default to the empty string when no config.data
        assertEquals("", payload["accountId"]?.jsonPrimitive?.content)
        assertEquals("", payload["projectId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `build renders source when network-source is set`() {
        val events = listOf(makeBucketingEvent("v-1"))
        val result = TrackingPayloadBuilder.build(events, configWithProject(source = "android"), json)

        val payload = json.parseToJsonElement(result).jsonObject
        assertEquals("android", payload["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `build renders empty visitors array when input is empty`() {
        val result = TrackingPayloadBuilder.build(emptyList(), configWithProject(), json)
        val payload = json.parseToJsonElement(result).jsonObject
        val visitors = payload["visitors"]!!.jsonArray
        assertTrue(visitors.isEmpty())
    }

    @Test
    fun `build preserves event order within a visitor group`() {
        val events = listOf(
            makeBucketingEvent("v-1"),
            makeBucketingEvent("v-1"),
            makeConversionEvent("v-1"),
        )
        val result = TrackingPayloadBuilder.build(events, configWithProject(), json)

        val visitors = json.parseToJsonElement(result).jsonObject["visitors"]!!.jsonArray
        val v1Events = visitors.first().jsonObject["events"]!!.jsonArray
        assertEquals(3, v1Events.size)
        assertEquals("bucketing", (v1Events[0] as JsonObject)["eventType"]!!.jsonPrimitive.content)
        assertEquals("bucketing", (v1Events[1] as JsonObject)["eventType"]!!.jsonPrimitive.content)
        assertEquals("conversion", (v1Events[2] as JsonObject)["eventType"]!!.jsonPrimitive.content)
    }
}
