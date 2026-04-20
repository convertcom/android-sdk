/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class TrackingEventTest {

    @Test
    fun `tracking event serializes and deserializes symmetrically`() {
        val original = TrackingEvent(
            eventType = "viewExp",
            data = mapOf(
                "experienceId" to JsonPrimitive("exp-1"),
                "variationId" to JsonPrimitive("var-a"),
            ),
            timestamp = 1_711_000_000_000L,
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<TrackingEvent>(encoded)
        assertEquals(original, decoded)
    }
}
