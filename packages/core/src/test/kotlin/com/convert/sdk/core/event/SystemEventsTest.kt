/*
 * Convert Android SDK — core/event tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Story 2.1 SDK-1: verify [SystemEvents] constants match the JS SDK
 * canonical string values
 * (`javascript-sdk/packages/enums/src/system-events.ts:12-22`).
 *
 * These constants are the ONLY load-bearing string values in the event
 * system — callers use them verbatim; typos would silently mis-route.
 * Test acts as a tripwire against accidental rename.
 */
internal class SystemEventsTest {

    @Test
    fun `ready constant matches JS SDK value`() {
        assertEquals("ready", SystemEvents.READY)
    }

    @Test
    fun `config updated constant matches JS SDK value`() {
        assertEquals("config.updated", SystemEvents.CONFIG_UPDATED)
    }

    @Test
    fun `bucketing constant matches JS SDK value`() {
        assertEquals("bucketing", SystemEvents.BUCKETING)
    }

    @Test
    fun `conversion constant matches JS SDK value`() {
        assertEquals("conversion", SystemEvents.CONVERSION)
    }

    @Test
    fun `api queue released constant matches JS SDK value`() {
        assertEquals("api.queue.released", SystemEvents.API_QUEUE_RELEASED)
    }

    @Test
    fun `segments stub matches JS SDK value`() {
        assertEquals("segments", SystemEvents.SEGMENTS)
    }

    @Test
    fun `location activated stub matches JS SDK value`() {
        assertEquals("location.activated", SystemEvents.LOCATION_ACTIVATED)
    }

    @Test
    fun `location deactivated stub matches JS SDK value`() {
        assertEquals("location.deactivated", SystemEvents.LOCATION_DEACTIVATED)
    }

    @Test
    fun `audiences stub matches JS SDK value`() {
        assertEquals("audiences", SystemEvents.AUDIENCES)
    }

    @Test
    fun `data store queue released stub matches JS SDK value`() {
        assertEquals("datastore.queue.released", SystemEvents.DATA_STORE_QUEUE_RELEASED)
    }

    @Test
    fun `every system event name is non-empty and lowercase JS SDK form`() {
        val all = listOf(
            SystemEvents.READY,
            SystemEvents.CONFIG_UPDATED,
            SystemEvents.BUCKETING,
            SystemEvents.CONVERSION,
            SystemEvents.API_QUEUE_RELEASED,
            SystemEvents.SEGMENTS,
            SystemEvents.LOCATION_ACTIVATED,
            SystemEvents.LOCATION_DEACTIVATED,
            SystemEvents.AUDIENCES,
            SystemEvents.DATA_STORE_QUEUE_RELEASED,
        )
        all.forEach { name ->
            assertFalse(name.isEmpty(), "event name must be non-empty")
            // JS SDK canonical form: lowercase, optionally dotted.
            assertEquals(
                name,
                name.lowercase(),
                "event name must be lowercase (JS SDK parity): $name",
            )
        }
    }
}
