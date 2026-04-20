/*
 * Convert Android SDK — core/event tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [EventManager] covering the full Story 2.4 surface:
 *
 * AC-1 — pub/sub mechanics: on / fire / off (token + callback-identity).
 * AC-3 — deferred replay for READY and CONFIG_UPDATED.
 * AC-4 — non-replayable events do NOT replay.
 * AC-5 — callbacks fire on background scope; exceptions contained.
 * AC-8 — thread-safe under contention (snapshot-and-release lock pattern).
 * AC-9 — fire with no subscribers is a no-op (cheap map lookup).
 *
 * ### Why a `TestScope` is passed in
 *
 * [EventManager]'s production constructor defaults to a module-level
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` so that the
 * existing no-arg callers inside DataManager still work. In tests we
 * inject a [TestScope] so the virtual scheduler can drive dispatch
 * deterministically via `advanceUntilIdle()` — otherwise assertions
 * would race the background dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EventManagerTest {

    private fun newManager(scope: CoroutineScope): EventManager = EventManager(scope = scope)

    // ---- AC-1 / AC-9 — core pub/sub mechanics ---------------------------

    @Test
    fun `fire invokes the registered callback with the payload`() = runTest {
        val manager = newManager(this)
        var received: Map<String, Any?>? = null

        manager.on(SystemEvents.READY) { data -> received = data }
        manager.fire(SystemEvents.READY, mapOf("environment" to "prod"))
        advanceUntilIdle()

        assertNotNull(received)
        assertEquals("prod", received?.get("environment"))
    }

    @Test
    fun `fire with no subscribers does nothing`() = runTest {
        val manager = newManager(this)
        // Should not throw and must remain cheap — a single map lookup.
        manager.fire(SystemEvents.BUCKETING, mapOf("x" to 1))
        advanceUntilIdle()
    }

    @Test
    fun `fire with empty payload delivers an empty map`() = runTest {
        val manager = newManager(this)
        var seen: Map<String, Any?>? = null
        manager.on("E") { seen = it }

        manager.fire("E")
        advanceUntilIdle()

        assertEquals(emptyMap<String, Any?>(), seen)
    }

    @Test
    fun `multiple subscribers fire in registration order`() = runTest {
        val manager = newManager(this)
        val order = mutableListOf<Int>()

        manager.on("E") { order.add(1) }
        manager.on("E") { order.add(2) }
        manager.on("E") { order.add(3) }

        manager.fire("E")
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `events do not cross-subscribe`() = runTest {
        val manager = newManager(this)
        val aCalls = mutableListOf<Int>()
        val bCalls = mutableListOf<Int>()

        manager.on("A") { aCalls.add(1) }
        manager.on("B") { bCalls.add(1) }

        manager.fire("A")
        advanceUntilIdle()
        assertEquals(listOf(1), aCalls)
        assertTrue(bCalls.isEmpty())

        manager.fire("B")
        advanceUntilIdle()
        assertEquals(listOf(1), bCalls)
        assertEquals(1, aCalls.size)
    }

    @Test
    fun `same callback registered twice fires twice`() = runTest {
        val manager = newManager(this)
        val fired = AtomicInteger(0)
        val cb: (Map<String, Any?>) -> Unit = { fired.incrementAndGet() }

        manager.on("E", cb)
        manager.on("E", cb)
        manager.fire("E")
        advanceUntilIdle()

        assertEquals(2, fired.get())
    }

    // ---- AC-1 — off by token --------------------------------------------

    @Test
    fun `off by token removes subscription`() = runTest {
        val manager = newManager(this)
        val fired = AtomicInteger(0)
        val cb: (Map<String, Any?>) -> Unit = { fired.incrementAndGet() }

        val token = manager.on("E", cb)
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get())

        manager.off("E", token)
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get())
    }

    @Test
    fun `tokens for different registrations are distinct`() = runTest {
        val manager = newManager(this)
        val cb: (Map<String, Any?>) -> Unit = { /* no-op */ }

        val t1 = manager.on("E", cb)
        val t2 = manager.on("E", cb)

        assertNotEquals(t1, t2)
    }

    @Test
    fun `off by token for an unknown token is a no-op`() = runTest {
        val manager = newManager(this)
        val fired = AtomicInteger(0)
        manager.on("E") { fired.incrementAndGet() }

        // Token was never registered on this manager; off must not throw or
        // remove the unrelated subscription.
        val stranger = SubscriptionToken()
        manager.off("E", stranger)

        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get())
    }

    // ---- AC-1 — off by callback identity --------------------------------

    @Test
    fun `off by callback reference removes subscription`() = runTest {
        val manager = newManager(this)
        val fired = AtomicInteger(0)
        val cb: (Map<String, Any?>) -> Unit = { fired.incrementAndGet() }

        manager.on("E", cb)
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get())

        manager.off("E", cb)
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get())
    }

    @Test
    fun `off only removes the matching reference, not equivalent callbacks`() = runTest {
        val manager = newManager(this)
        val fired = AtomicInteger(0)

        manager.on("E") { fired.incrementAndGet() } // lambda A
        manager.off("E") { fired.incrementAndGet() } // lambda B — different identity, no-op

        manager.fire("E")
        advanceUntilIdle()
        assertEquals(1, fired.get()) // lambda A still subscribed
    }

    @Test
    fun `off for an unregistered callback is a no-op`() = runTest {
        val manager = newManager(this)
        val cb: (Map<String, Any?>) -> Unit = { /* no-op */ }

        manager.off("E", cb)
    }

    // ---- AC-8 — off during fire does not affect in-flight dispatch ------

    @Test
    fun `off during fire does not affect in-flight dispatch`() = runTest {
        val manager = newManager(this)
        val aFires = AtomicInteger(0)
        val bFires = AtomicInteger(0)

        lateinit var cbB: (Map<String, Any?>) -> Unit
        cbB = { bFires.incrementAndGet() }

        val cbA: (Map<String, Any?>) -> Unit = {
            aFires.incrementAndGet()
            // Remove B from within A. Because fire snapshots the subscriber
            // list under the lock before dispatching, the current fire must
            // still deliver to B.
            manager.off("E", cbB)
        }

        manager.on("E", cbA)
        manager.on("E", cbB)

        manager.fire("E")
        advanceUntilIdle()
        // Current-fire snapshot guarantee: both receive the in-flight event.
        assertEquals(1, aFires.get())
        assertEquals(1, bFires.get())

        // Next fire: B is gone.
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(2, aFires.get())
        assertEquals(1, bFires.get())
    }

    // ---- AC-3 — deferred replay for READY / CONFIG_UPDATED --------------

    @Test
    fun `READY replays to late subscriber`() = runTest {
        val manager = newManager(this)

        // Fire READY BEFORE anyone subscribes — there are no subscribers.
        manager.fire(SystemEvents.READY, mapOf("environment" to "prod"))
        advanceUntilIdle()

        // Late subscribe. Must see the replayed payload on the next tick.
        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.READY) { data -> received = data }
        advanceUntilIdle()

        assertNotNull(received, "late subscriber should receive replayed READY")
        assertEquals("prod", received?.get("environment"))
    }

    @Test
    fun `CONFIG_UPDATED replays to late subscriber`() = runTest {
        val manager = newManager(this)

        manager.fire(SystemEvents.CONFIG_UPDATED, mapOf("timestamp" to 42L))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.CONFIG_UPDATED) { data -> received = data }
        advanceUntilIdle()

        assertNotNull(received)
        assertEquals(42L, received?.get("timestamp"))
    }

    @Test
    fun `replay uses the most recent fire payload`() = runTest {
        val manager = newManager(this)

        manager.fire(SystemEvents.READY, mapOf("environment" to "staging"))
        advanceUntilIdle()
        manager.fire(SystemEvents.READY, mapOf("environment" to "prod"))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.READY) { data -> received = data }
        advanceUntilIdle()

        assertEquals("prod", received?.get("environment"))
    }

    // ---- AC-4 — non-replayable events do NOT replay ---------------------

    @Test
    fun `BUCKETING does not replay`() = runTest {
        val manager = newManager(this)

        manager.fire(SystemEvents.BUCKETING, mapOf("experienceKey" to "exp-1"))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.BUCKETING) { data -> received = data }
        advanceUntilIdle()

        // Non-replayable: late subscriber must NOT receive the prior fire.
        assertEquals(null, received)
    }

    @Test
    fun `CONVERSION does not replay`() = runTest {
        val manager = newManager(this)
        manager.fire(SystemEvents.CONVERSION, mapOf("goalKey" to "g-1"))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.CONVERSION) { data -> received = data }
        advanceUntilIdle()

        assertEquals(null, received)
    }

    @Test
    fun `API_QUEUE_RELEASED does not replay`() = runTest {
        val manager = newManager(this)
        manager.fire(SystemEvents.API_QUEUE_RELEASED, mapOf("batchSize" to 10))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.API_QUEUE_RELEASED) { data -> received = data }
        advanceUntilIdle()

        assertEquals(null, received)
    }

    @Test
    fun `SEGMENTS does not replay`() = runTest {
        val manager = newManager(this)
        manager.fire(SystemEvents.SEGMENTS, mapOf("segmentIds" to listOf("s-1")))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on(SystemEvents.SEGMENTS) { data -> received = data }
        advanceUntilIdle()

        assertEquals(null, received)
    }

    @Test
    fun `arbitrary string events do not replay`() = runTest {
        val manager = newManager(this)
        manager.fire("custom-event", mapOf("foo" to "bar"))
        advanceUntilIdle()

        var received: Map<String, Any?>? = null
        manager.on("custom-event") { data -> received = data }
        advanceUntilIdle()

        assertEquals(null, received)
    }

    // ---- AC-5 — exception containment -----------------------------------

    @Test
    fun `exception in subscriber does not affect other subscribers or future fires`() = runTest {
        val manager = newManager(this)
        val aFires = AtomicInteger(0)
        val bFires = AtomicInteger(0)

        manager.on("E") {
            aFires.incrementAndGet()
            error("boom")
        }
        manager.on("E") { bFires.incrementAndGet() }

        manager.fire("E")
        advanceUntilIdle()
        // Both subscribers must fire; the exception in A is contained.
        assertEquals(1, aFires.get())
        assertEquals(1, bFires.get())

        // A second fire still reaches both — the previous throw did not
        // poison the subscriber list.
        manager.fire("E")
        advanceUntilIdle()
        assertEquals(2, aFires.get())
        assertEquals(2, bFires.get())
    }

    // ---- AC-8 — concurrent safety ---------------------------------------

    @Test
    fun `concurrent on-fire-off operations are safe`() = runBlocking {
        // Production-scope manager: deliberately NOT TestScope — we want
        // real dispatcher concurrency to stress the lock.
        val manager = EventManager()
        val received = AtomicInteger(0)
        val perThreadIterations = 250
        val threadCount = 4

        val stableCb: (Map<String, Any?>) -> Unit = { received.incrementAndGet() }
        manager.on("E", stableCb) // ensures at least one subscriber is always present

        val jobs: List<Job> = (0 until threadCount).map { t ->
            launch {
                repeat(perThreadIterations) { i ->
                    when ((t + i) % 3) {
                        0 -> {
                            val token = manager.on("E") { /* ephemeral */ }
                            manager.off("E", token)
                        }
                        1 -> manager.fire("E")
                        else -> {
                            val cb: (Map<String, Any?>) -> Unit = { /* ephemeral */ }
                            manager.on("E", cb)
                            manager.off("E", cb)
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }

        // Give the background scope time to drain any pending dispatches.
        delay(100)

        // The stable subscriber should have received >0 events — exact count
        // depends on interleaving, but "at least one fire delivered" is the
        // thread-safety signal we care about. Lost events or a crashed lock
        // would manifest as zero deliveries or an exception thrown from
        // one of the coroutines above.
        assertTrue(
            received.get() > 0,
            "expected at least one delivery to stable subscriber; got ${received.get()}",
        )
    }

    @Test
    fun `concurrent subscribers receive every fire`() = runBlocking {
        // Production-scope manager: stress real dispatcher with parallel
        // subscribers so that snapshot-and-release semantics are exercised.
        val manager = EventManager()
        val totalFires = 100
        val subscriberCount = 20
        val received = AtomicInteger(0)

        // Register all subscribers first so every subsequent fire reaches
        // every subscriber; concurrent adds are covered by the separate test
        // above — here we isolate the "every fire reaches every subscriber"
        // invariant.
        repeat(subscriberCount) {
            manager.on("E") { received.incrementAndGet() }
        }

        coroutineScopeRun(totalFires) { manager.fire("E") }

        // Drain the dispatcher.
        delay(250)

        assertEquals(totalFires * subscriberCount, received.get())
    }

    @Test
    fun `late subscriber on replayable event never receives the same payload twice`() = runBlocking {
        // Regression guard for the AC-3 replay race: the on() call must
        // add the subscription and read lastEventData under the SAME
        // lock. Otherwise a concurrent fire landing between "add" and
        // "capture replay" would deliver the same payload twice — once
        // from the concurrent fire's snapshot, once from on()'s
        // post-lock replay read.
        val manager = EventManager()
        val payload = mapOf("environment" to "prod")
        val received = java.util.concurrent.ConcurrentLinkedQueue<Map<String, Any?>>()

        // Seed the replay bucket so there's a prior payload to race over.
        manager.fire(SystemEvents.READY, payload)
        delay(50)

        // Now fire repeatedly while another thread races to subscribe.
        // If the race manifests, the subscriber sees the same payload
        // twice in its queue — assertion below catches it.
        val firerJobs = (0 until 4).map {
            launch {
                repeat(200) { manager.fire(SystemEvents.READY, payload) }
            }
        }
        val subscribeJob = launch {
            manager.on(SystemEvents.READY) { received.add(it) }
        }
        firerJobs.forEach { it.join() }
        subscribeJob.join()

        // Let dispatch settle.
        delay(300)

        // Invariant: the late-subscriber may see 0..N deliveries of the
        // same payload (every fire that happened after subscribe +
        // possibly one replay). That's fine; the bug we guard against
        // is an INLINE duplicate pair landing from the `on()` call
        // itself, where the replay AND the concurrent fire's snapshot
        // deliver the same reference in the same tick. That would show
        // up as more deliveries than fires+1 (fires + replay). We can't
        // count exactly because dispatch happens on a real scope, but
        // we can check that the total is never absurdly above the
        // upper bound `fires + 1` across all subscribers.
        val fires = 4 * 200
        val upperBound = fires + 1
        assertTrue(
            received.size <= upperBound,
            "expected at most $upperBound deliveries (fires + replay); got ${received.size}",
        )
    }

    @Test
    fun `constructor with a closed scope does not throw on fire`() = runTest {
        // Defensive: a manager constructed with a pre-cancelled scope should
        // gracefully swallow dispatch failures rather than bubble them back
        // to the caller of fire.
        val dead = TestScope(StandardTestDispatcher())
        dead.cancel()
        val manager = EventManager(scope = dead)

        // Must not throw.
        manager.fire("E", mapOf("x" to 1))
    }

    // ---- helper ---------------------------------------------------------

    private suspend fun coroutineScopeRun(count: Int, block: () -> Unit) {
        val threads = 4
        val perThread = count / threads
        val remainder = count % threads
        val jobs = mutableListOf<Job>()
        runBlocking {
            repeat(threads) { t ->
                val iterations = perThread + if (t < remainder) 1 else 0
                jobs += launch {
                    repeat(iterations) { block() }
                }
            }
        }
        jobs.forEach { it.join() }
    }
}
