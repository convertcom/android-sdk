/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class VisitorTest {

    @Test
    fun `visitor serializes and deserializes symmetrically`() {
        val original = Visitor(
            visitorId = "visitor-123",
            segments = mapOf("country" to JsonPrimitive("US")),
            events = listOf(
                TrackingEvent(
                    eventType = "viewExp",
                    data = mapOf("experienceId" to JsonPrimitive("exp-1")),
                    timestamp = 1_000L,
                ),
            ),
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<Visitor>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `visitor preserves the visitorId JSON field name exactly`() {
        val original = Visitor(visitorId = "visitor-456", events = emptyList())
        val encoded = testJson.encodeToString(original)
        assertTrue(
            encoded.contains("\"visitorId\":\"visitor-456\""),
            "expected visitorId camelCase; got: $encoded",
        )
    }
}
