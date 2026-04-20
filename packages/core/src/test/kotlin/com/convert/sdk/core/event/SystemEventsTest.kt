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
 * Story 2.1 SDK-1: verify [SystemEvents] constants match the architecture's
 * internal event catalogue (architecture §Internal EventManager events).
 *
 * These constants are the ONLY load-bearing string values in the event
 * system — callers use them verbatim; typos would silently mis-route.
 * Test acts as a tripwire against accidental rename.
 */
internal class SystemEventsTest {

    @Test
    fun `ready constant matches JS SDK value`() {
        assertEquals("READY", SystemEvents.READY)
    }

    @Test
    fun `config updated constant matches JS SDK value`() {
        assertEquals("CONFIG_UPDATED", SystemEvents.CONFIG_UPDATED)
    }

    @Test
    fun `bucketing constant matches JS SDK value`() {
        assertEquals("BUCKETING", SystemEvents.BUCKETING)
    }

    @Test
    fun `conversion constant matches JS SDK value`() {
        assertEquals("CONVERSION", SystemEvents.CONVERSION)
    }

    @Test
    fun `api queue released constant matches JS SDK value`() {
        assertEquals("API_QUEUE_RELEASED", SystemEvents.API_QUEUE_RELEASED)
    }

    @Test
    fun `every system event name is non-empty and uppercase`() {
        val all = listOf(
            SystemEvents.READY,
            SystemEvents.CONFIG_UPDATED,
            SystemEvents.BUCKETING,
            SystemEvents.CONVERSION,
            SystemEvents.API_QUEUE_RELEASED,
        )
        all.forEach { name ->
            assertFalse(name.isEmpty(), "event name must be non-empty")
            assertEquals(name, name.uppercase(), "event name must be uppercase: $name")
        }
    }
}
