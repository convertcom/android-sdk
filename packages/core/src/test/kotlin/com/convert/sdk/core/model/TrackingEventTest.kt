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

internal class TrackingEventTest {

    @Test
    fun `tracking event round-trips with nested data map`() {
        val original = TrackingEvent(
            eventType = "viewExp",
            data = mapOf(
                "experienceId" to JsonPrimitive("exp_1"),
                "variationId" to JsonPrimitive("var_1"),
            ),
            timestamp = 1_700_000_000_000L,
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<TrackingEvent>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `tracking event round-trips with empty data map`() {
        val original = TrackingEvent(
            eventType = "tr",
            data = emptyMap(),
            timestamp = 0L,
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<TrackingEvent>(json)

        assertEquals(original, restored)
    }
}
