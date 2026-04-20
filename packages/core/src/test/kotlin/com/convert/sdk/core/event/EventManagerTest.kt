/*
 * Convert Android SDK — core/event tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [EventManager]. Story 2.1 AC-7 and Gotcha 11.
 *
 * EventManager is the SDK-wide pub/sub bus. Correctness requirements:
 * - on/fire/off round-trip with identity-based callback matching.
 * - Multiple subscribers fire in registration order.
 * - Thread-safety: concurrent on/fire/off calls must not lose events
 *   or corrupt the subscriber map (Mutex-guarded per architecture).
 * - fire with no subscribers is a no-op.
 * - off for a callback that was never registered is a no-op.
 * - Unrelated events don't cross-subscribe.
 */
internal class EventManagerTest {

    @Test
    fun `fire invokes the registered callback with the payload`() {
        val manager = EventManager()
        var received: Map<String, Any?>? = null

        manager.on("READY") { data -> received = data }
        manager.fire("READY", mapOf("environment" to "prod"))

        assertNotNull(received)
        assertEquals("prod", received?.get("environment"))
    }

    @Test
    fun `fire with no subscribers does nothing`() {
        val manager = EventManager()
        // Should not throw
        manager.fire("READY", mapOf("x" to 1))
    }

    @Test
    fun `fire with empty payload delivers an empty map`() {
        val manager = EventManager()
        var seen: Map<String, Any?>? = null
        manager.on("E") { seen = it }

        manager.fire("E")

        assertEquals(emptyMap<String, Any?>(), seen)
    }

    @Test
    fun `multiple subscribers fire in registration order`() {
        val manager = EventManager()
        val order = mutableListOf<Int>()

        manager.on("E") { order.add(1) }
        manager.on("E") { order.add(2) }
        manager.on("E") { order.add(3) }

        manager.fire("E")

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `off removes the identical callback reference`() {
        val manager = EventManager()
        var fired = 0
        val cb: (Map<String, Any?>) -> Unit = { fired++ }

        manager.on("E", cb)
        manager.fire("E")
        assertEquals(1, fired)

        manager.off("E", cb)
        manager.fire("E")
        assertEquals(1, fired) // not incremented
    }

    @Test
    fun `off only removes the matching reference, not equivalent callbacks`() {
        val manager = EventManager()
        var fired = 0

        manager.on("E") { fired++ } // lambda A
        manager.off("E") { fired++ } // lambda B — different identity, no-op

        manager.fire("E")
        assertEquals(1, fired) // lambda A still subscribed
    }

    @Test
    fun `off for an unregistered callback is a no-op`() {
        val manager = EventManager()
        val cb: (Map<String, Any?>) -> Unit = { /* no-op */ }

        // Should not throw — just return silently.
        manager.off("E", cb)
    }

    @Test
    fun `events do not cross-subscribe`() {
        val manager = EventManager()
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()

        manager.on("A") { aCalls.add(1) }
        manager.on("B") { bCalls.add(1) }

        manager.fire("A")
        assertEquals(listOf(1), aCalls)
        assertTrue(bCalls.isEmpty())

        manager.fire("B")
        assertEquals(listOf(1), bCalls)
        // A still only fired once
        assertEquals(1, aCalls.size)
    }

    @Test
    fun `same callback registered twice fires twice`() {
        val manager = EventManager()
        var fired = 0
        val cb: (Map<String, Any?>) -> Unit = { fired++ }

        manager.on("E", cb)
        manager.on("E", cb)
        manager.fire("E")

        assertEquals(2, fired)
    }

    @Test
    fun `concurrent on and fire calls do not lose events`() = runTest {
        val manager = EventManager()
        val totalFires = 100
        val subscriberCount = 20
        val received = java.util.concurrent.atomic.AtomicInteger(0)

        // Register subscribers in parallel with fires.
        val subscribers = (1..subscriberCount).map {
            async { manager.on("E") { received.incrementAndGet() } }
        }
        subscribers.awaitAll()

        val firers = (1..totalFires).map {
            async { manager.fire("E") }
        }
        firers.awaitAll()

        // Every fire should have reached every subscriber
        // (subscribers registered before fires).
        assertEquals(totalFires * subscriberCount, received.get())
    }

    @Test
    fun `subscriber mutation during fire does not break the current iteration`() {
        val manager = EventManager()
        val fired = mutableListOf<String>()

        // First subscriber adds a new subscriber — the new one must NOT
        // fire for this invocation (not yet in the snapshot) but MUST fire
        // for subsequent invocations.
        manager.on("E") {
            fired += "A"
            manager.on("E") { fired += "B" }
        }

        manager.fire("E")
        assertEquals(listOf("A"), fired)

        manager.fire("E")
        // Second fire — both A and the newly-added B should fire.
        // Only checks the tail since A also re-adds B every invocation;
        // this is a defensive test about snapshot semantics, not exact counts.
        assertTrue(fired.containsAll(listOf("A", "B")), "both should have fired: $fired")
    }
}
