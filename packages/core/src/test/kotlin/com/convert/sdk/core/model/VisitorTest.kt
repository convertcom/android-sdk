/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import com.convert.sdk.core.model.generated.ConversionEventGoalDataInner
import com.convert.sdk.core.model.generated.ConversionEventGoalDataInnerValue
import com.convert.sdk.core.model.generated.VisitorTrackingEvents
import com.convert.sdk.core.model.generated.VisitorTrackingEventsData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Story 5.1 SDK-1 tests — [Visitor] now carries a list of the
 * OpenAPI-generated [VisitorTrackingEvents] on the wire. The previous
 * flat `TrackingEvent(eventType, data, timestamp)` model has been
 * retired (see readiness-assessment Q2): its discriminant strings
 * (`viewExp` / `hitGoal` / `tr`) did not match the JS SDK wire format
 * (`bucketing` / `conversion` only). Using the generated types directly
 * gives us 100% wire parity with the JS SDK via the shared OpenAPI
 * contract.
 */
internal class VisitorTest {

    @Test
    fun `visitor round-trips with segments and one BUCKETING event`() {
        val original = Visitor(
            visitorId = "visitor_abc",
            segments = mapOf(
                "country" to JsonPrimitive("US"),
                "plan" to JsonPrimitive("pro"),
            ),
            events = listOf(
                VisitorTrackingEvents(
                    eventType = VisitorTrackingEvents.EventType.BUCKETING,
                    data = VisitorTrackingEventsData(
                        experienceId = "e1",
                        variationId = "v1",
                        goalId = "",
                    ),
                ),
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Visitor>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor round-trips when segments is null and events is empty`() {
        val original = Visitor(
            visitorId = "v_1",
            events = emptyList(),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Visitor>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor encodes a CONVERSION event with goalData and bucketingData`() {
        val original = Visitor(
            visitorId = "v_2",
            segments = null,
            events = listOf(
                VisitorTrackingEvents(
                    eventType = VisitorTrackingEvents.EventType.CONVERSION,
                    data = VisitorTrackingEventsData(
                        experienceId = "",
                        variationId = "",
                        goalId = "g-42",
                        goalData = listOf(
                            ConversionEventGoalDataInner(
                                key = ConversionEventGoalDataInner.Key.AMOUNT,
                                value = ConversionEventGoalDataInnerValue(),
                            ),
                        ),
                        bucketingData = mapOf("exp-1" to "var-a"),
                    ),
                ),
            ),
        )

        val wire = testJson.encodeToString(original)
        val root = Json.parseToJsonElement(wire).jsonObject
        val event = root["events"]!!.jsonArray.first().jsonObject
        assertEquals("conversion", event["eventType"]!!.jsonPrimitive.content)
        val data = event["data"]!!.jsonObject
        assertEquals("g-42", data["goalId"]!!.jsonPrimitive.content)
        assertNotNull(data["goalData"])
        assertNotNull(data["bucketingData"])
    }

    @Test
    fun `visitor wire format matches JS SDK shape — eventType is bucketing`() {
        // JS SDK packages/types/src/config/types.gen.ts:2742 —
        // `eventType?: 'bucketing' | 'conversion'` (NOT viewExp/hitGoal/tr).
        // The generated @SerialName values give us this shape for free;
        // this test locks in the wire contract so future regressions
        // (e.g. someone renaming the enum) surface loudly.
        val v = Visitor(
            visitorId = "v_3",
            events = listOf(
                VisitorTrackingEvents(
                    eventType = VisitorTrackingEvents.EventType.BUCKETING,
                    data = VisitorTrackingEventsData(
                        experienceId = "e-1",
                        variationId = "var-a",
                        goalId = "",
                    ),
                ),
            ),
        )

        val wire = testJson.encodeToString(v)
        val root = Json.parseToJsonElement(wire).jsonObject
        assertEquals("v_3", root["visitorId"]!!.jsonPrimitive.content)
        // segments is null → omitted under explicitNulls=false.
        assertNull(root["segments"])
        val events = root["events"]!!.jsonArray
        assertEquals(1, events.size)
        val first = events.first().jsonObject
        assertEquals("bucketing", first["eventType"]!!.jsonPrimitive.content)
        val data = first["data"]!!.jsonObject
        assertEquals("e-1", data["experienceId"]!!.jsonPrimitive.content)
        assertEquals("var-a", data["variationId"]!!.jsonPrimitive.content)
        // Empty goalId is present on BUCKETING events (OpenAPI makes the
        // field required); under explicitNulls=false it still serializes
        // because the value is a non-null empty string, not null.
        assertEquals("", data["goalId"]!!.jsonPrimitive.content)
    }
}
