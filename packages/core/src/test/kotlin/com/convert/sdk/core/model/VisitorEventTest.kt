/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class VisitorEventTest {

    @Test
    fun `visitor event round-trips with segments and event`() {
        val original = VisitorEvent(
            visitorId = "visitor_abc",
            segments = mapOf(
                "country" to JsonPrimitive("US"),
                "plan" to JsonPrimitive("pro"),
            ),
            event = TrackingEvent(
                eventType = "viewExp",
                data = mapOf("experienceId" to JsonPrimitive("e1")),
                timestamp = 1_700_000_000_000L,
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorEvent>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor event round-trips when segments is null`() {
        val original = VisitorEvent(
            visitorId = "v_1",
            event = TrackingEvent(
                eventType = "hitGoal",
                data = mapOf("goalId" to JsonPrimitive("g1")),
                timestamp = 0L,
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorEvent>(json)

        assertEquals(original, restored)
    }
}
