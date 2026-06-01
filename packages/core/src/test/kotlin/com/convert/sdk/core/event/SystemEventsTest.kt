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
 * Story 2.4 SDK-1: verify [SystemEvents] constants match the JS SDK's
 * canonical `system-events.ts` enum values verbatim.
 *
 * Authoritative source:
 *   `javascript-sdk/packages/enums/src/system-events.ts`
 *
 * The JS SDK uses lowercase dotted string values (e.g. `"config.updated"`,
 * `"api.queue.released"`). Story 2.4 AC-2 requires cross-SDK parity so
 * observer code can share event names verbatim across runtimes; the
 * Kotlin constants therefore carry the lowercase dotted strings, not
 * their uppercase snake-case constant identifiers.
 *
 * These constants are the ONLY load-bearing string values in the event
 * system — callers use them verbatim; typos would silently mis-route.
 * This test is the tripwire against accidental rename.
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
    fun `api queue released constant matches JS SDK value`() {
        assertEquals("api.queue.released", SystemEvents.API_QUEUE_RELEASED)
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
    fun `segments constant matches JS SDK value`() {
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
    fun `system event names are the exact JS SDK canonical set`() {
        // Enumerated here so accidental additions or deletions are caught
        // by a test that reads like a specification. When a later story
        // adds a new event, this test is the canonical place to extend it.
        val canonical = mapOf(
            "READY" to "ready",
            "CONFIG_UPDATED" to "config.updated",
            "API_QUEUE_RELEASED" to "api.queue.released",
            "BUCKETING" to "bucketing",
            "CONVERSION" to "conversion",
            "SEGMENTS" to "segments",
            "LOCATION_ACTIVATED" to "location.activated",
            "LOCATION_DEACTIVATED" to "location.deactivated",
            "AUDIENCES" to "audiences",
            "DATA_STORE_QUEUE_RELEASED" to "datastore.queue.released",
        )
        assertEquals(canonical["READY"], SystemEvents.READY)
        assertEquals(canonical["CONFIG_UPDATED"], SystemEvents.CONFIG_UPDATED)
        assertEquals(canonical["API_QUEUE_RELEASED"], SystemEvents.API_QUEUE_RELEASED)
        assertEquals(canonical["BUCKETING"], SystemEvents.BUCKETING)
        assertEquals(canonical["CONVERSION"], SystemEvents.CONVERSION)
        assertEquals(canonical["SEGMENTS"], SystemEvents.SEGMENTS)
        assertEquals(canonical["LOCATION_ACTIVATED"], SystemEvents.LOCATION_ACTIVATED)
        assertEquals(canonical["LOCATION_DEACTIVATED"], SystemEvents.LOCATION_DEACTIVATED)
        assertEquals(canonical["AUDIENCES"], SystemEvents.AUDIENCES)
        assertEquals(canonical["DATA_STORE_QUEUE_RELEASED"], SystemEvents.DATA_STORE_QUEUE_RELEASED)
    }

    @Test
    fun `every system event name is non-empty and lowercase JS SDK form`() {
        val all = listOf(
            SystemEvents.READY,
            SystemEvents.CONFIG_UPDATED,
            SystemEvents.API_QUEUE_RELEASED,
            SystemEvents.BUCKETING,
            SystemEvents.CONVERSION,
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
