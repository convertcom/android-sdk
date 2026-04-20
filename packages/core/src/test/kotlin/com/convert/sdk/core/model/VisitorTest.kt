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

internal class VisitorTest {

    @Test
    fun `visitor round-trips with segments and events`() {
        val original = Visitor(
            visitorId = "visitor_abc",
            segments = mapOf(
                "country" to JsonPrimitive("US"),
                "plan" to JsonPrimitive("pro"),
            ),
            events = listOf(
                TrackingEvent(
                    eventType = "viewExp",
                    data = mapOf("experienceId" to JsonPrimitive("e1")),
                    timestamp = 1L,
                ),
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Visitor>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor round-trips when segments is null`() {
        val original = Visitor(
            visitorId = "v_1",
            events = emptyList(),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Visitor>(json)

        assertEquals(original, restored)
    }
}
