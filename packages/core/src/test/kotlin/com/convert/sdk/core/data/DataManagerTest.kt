/*
 * Convert Android SDK — core/data tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.StoreData
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.DataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DataManager]. Story 2.1 AC-7 (stub) / AC-9 — carried
 * forward through Story 2.4's EventManager rewrite; Story 3.1 extends
 * with per-visitor [StoreData] management (AC-7/8/9), LRU eviction,
 * corruption recovery, and sanitized key mapping.
 *
 * ### Story 2.4 note — scope-dispatched EventManager
 *
 * As of Story 2.4, [EventManager.fire] dispatches subscribers on an
 * injected [CoroutineScope] rather than inline. Tests that assert on
 * a callback's side effect must drive the scheduler — we pass a
 * `runTest`-provided [TestScope] into the [EventManager] and
 * `advanceUntilIdle()` after each fire so the dispatched coroutine
 * completes before we assert.
 *
 * ### Why no MockK in this module
 *
 * `:packages:core` is a pure-Kotlin/JVM module. MockK on JVM 23 needs
 * ByteBuddy's external-process self-attach, which the SDKMAN!-backed
 * Temurin toolchain the repo targets does not support at test time
 * without extra JVM args. The rest of the core test suite (and the
 * SmokeTest in `:packages:sdk`) already follows a handwritten-fakes
 * convention; Story 3.1's [DataManagerTest] extends that convention
 * with [RecordingDataStore] — a tiny fake that tracks every
 * get/set/remove call without needing a mocking framework.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DataManagerTest {

    // --- Story 2.1 regression tests: preserve existing setData / hasData / data surface.

    @Test
    fun `fresh manager has no data`() {
        val manager = DataManager(EventManager(), environment = "staging")
        assertFalse(manager.hasData())
        assertNull(manager.data)
    }

    @Test
    fun `setData stores the config`() {
        val eventManager = EventManager()
        val manager = DataManager(eventManager, environment = "prod")
        val config = ConfigResponseData()

        manager.setData(config)

        assertTrue(manager.hasData())
        assertNotNull(manager.data)
        assertEquals(config, manager.data)
    }

    @Test
    fun `setData fires READY event with environment payload`() = runTest {
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "prod")
        var received: Map<String, Any?>? = null
        eventManager.on(SystemEvents.READY) { received = it }

        manager.setData(ConfigResponseData())
        advanceUntilIdle()

        assertNotNull(received)
        assertEquals("prod", received?.get("environment"))
    }

    @Test
    fun `second setData overwrites config and fires CONFIG_UPDATED, not READY`() = runTest {
        // AC-5.1: first call → READY; subsequent calls → CONFIG_UPDATED.
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "staging")
        var readyFires = 0
        var configUpdatedFires = 0
        eventManager.on(SystemEvents.READY) { readyFires++ }
        eventManager.on(SystemEvents.CONFIG_UPDATED) { configUpdatedFires++ }

        val first = ConfigResponseData()
        val second = ConfigResponseData()

        manager.setData(first)
        advanceUntilIdle()
        manager.setData(second)
        advanceUntilIdle()

        assertEquals(second, manager.data)
        // READY fires exactly once (first seed); second call fires CONFIG_UPDATED.
        assertEquals(1, readyFires)
        assertEquals(1, configUpdatedFires)
    }

    // --- Cluster 5 / AC-5.1: READY fires once, CONFIG_UPDATED per refresh.

    @Test
    fun `AC-5-1 READY fires exactly once across N setData calls`() = runTest {
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "prod")
        var readyFires = 0
        var configUpdatedFires = 0
        eventManager.on(SystemEvents.READY) { readyFires++ }
        eventManager.on(SystemEvents.CONFIG_UPDATED) { configUpdatedFires++ }

        // First seed (simulates cache or initial fetch).
        manager.setData(ConfigResponseData())
        advanceUntilIdle()

        // Three subsequent refreshes.
        repeat(3) {
            manager.setData(ConfigResponseData())
            advanceUntilIdle()
        }

        assertEquals(
            1,
            readyFires,
            "READY must fire exactly once across the SDK lifetime",
        )
        assertEquals(
            3,
            configUpdatedFires,
            "CONFIG_UPDATED must fire once per refresh (3 refreshes)",
        )
    }

    @Test
    fun `AC-5-1 cache-then-fetch — cache seed fires READY, first fetch fires CONFIG_UPDATED`() =
        runTest {
            val eventManager = EventManager(scope = this)
            val manager = DataManager(eventManager, environment = "staging")
            var readyFires = 0
            var configUpdatedFires = 0
            eventManager.on(SystemEvents.READY) { readyFires++ }
            eventManager.on(SystemEvents.CONFIG_UPDATED) { configUpdatedFires++ }

            // Simulate cache seed on cold start.
            manager.setData(ConfigResponseData())
            advanceUntilIdle()

            assertEquals(1, readyFires, "cache seed must fire READY")
            assertEquals(0, configUpdatedFires, "no CONFIG_UPDATED yet after cache seed")

            // Simulate first network fetch completing after the cache was used.
            manager.setData(ConfigResponseData())
            advanceUntilIdle()

            assertEquals(1, readyFires, "READY must not fire again after cache seed")
            assertEquals(
                1,
                configUpdatedFires,
                "first network fetch after cache fires CONFIG_UPDATED",
            )
        }

    @Test
    fun `AC-5-1 no double CONFIG_UPDATED — exactly N fires for N refreshes`() = runTest {
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "prod")
        val refreshCount = 5
        var configUpdatedFires = 0
        eventManager.on(SystemEvents.CONFIG_UPDATED) { configUpdatedFires++ }

        // Seed once (READY).
        manager.setData(ConfigResponseData())
        advanceUntilIdle()

        // N subsequent refreshes, each must fire CONFIG_UPDATED exactly once.
        repeat(refreshCount) {
            manager.setData(ConfigResponseData())
            advanceUntilIdle()
        }

        assertEquals(
            refreshCount,
            configUpdatedFires,
            "exactly $refreshCount CONFIG_UPDATED fires expected (one per refresh, not 2N)",
        )
    }

    @Test
    fun `AC-5-1 CONFIG_UPDATED payload contains timestamp`() = runTest {
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "prod")
        var configUpdatedPayload: Map<String, Any?>? = null
        eventManager.on(SystemEvents.CONFIG_UPDATED) { configUpdatedPayload = it }

        // Seed (fires READY, not CONFIG_UPDATED).
        manager.setData(ConfigResponseData())
        advanceUntilIdle()

        // Refresh (fires CONFIG_UPDATED).
        val before = System.currentTimeMillis()
        manager.setData(ConfigResponseData())
        advanceUntilIdle()
        val after = System.currentTimeMillis()

        assertNotNull(configUpdatedPayload)
        val ts = configUpdatedPayload?.get("timestamp") as? Long
        assertNotNull(ts, "CONFIG_UPDATED payload must contain a 'timestamp' Long")
        assertTrue(
            ts!! in before..after,
            "timestamp must be within the test's window",
        )
    }

    @Test
    fun `hasData is false until setData has been called`() {
        val manager = DataManager(EventManager(), environment = "staging")
        assertFalse(manager.hasData())

        manager.setData(ConfigResponseData())
        assertTrue(manager.hasData())
    }

    // --- Story 3.1 tests: visitor-state management.

    @Test
    fun `getStoreData returns empty StoreData for new visitor`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        val result = manager.getStoreData("new-visitor")

        assertEquals(StoreData(), result)
        assertNull(result.bucketing)
        assertNull(result.goals)
        assertNull(result.segments)
        assertNull(result.locations)
    }

    @Test
    fun `setStoreData persists to DataStore under sanitized visitor key`() {
        val dataStore = RecordingDataStore()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
            json = json,
        )
        val data = StoreData(bucketing = mapOf("exp-1" to "var-a"))

        manager.setStoreData("visitor-1", data)

        val expected = json.encodeToString(StoreData.serializer(), data)
        assertEquals(expected, dataStore.map["visitor.visitor-1"])
        assertEquals(listOf("set:visitor.visitor-1"), dataStore.log.filter { it.startsWith("set:") })
    }

    @Test
    fun `getStoreData loads from DataStore for previously-seen visitor`() {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val seeded = StoreData(bucketing = mapOf("exp-1" to "var-a"))
        val encoded = json.encodeToString(StoreData.serializer(), seeded)
        val dataStore = RecordingDataStore()
        dataStore.map["visitor.visitor-1"] = encoded
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
            json = json,
        )

        // First call pulls from disk.
        val first = manager.getStoreData("visitor-1")
        // Second call should hit the in-memory cache — only ONE dataStore.get invocation total.
        val second = manager.getStoreData("visitor-1")

        assertEquals(seeded, first)
        assertEquals(seeded, second)
        val gets = dataStore.log.filter { it == "get:visitor.visitor-1" }
        assertEquals(1, gets.size)
    }

    @Test
    fun `corrupted visitor state is deleted and replaced with empty`() {
        val dataStore = RecordingDataStore()
        dataStore.map["visitor.corrupt"] = "this is not valid json {{{"
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        val result = manager.getStoreData("corrupt")

        assertEquals(StoreData(), result)
        assertTrue(dataStore.log.contains("remove:visitor.corrupt"))
        assertNull(dataStore.map["visitor.corrupt"])
    }

    @Test
    fun `LRU cache evicts oldest visitor beyond 1000 entries`() {
        // Insert 1001 distinct visitors; visitor-0 should be evicted from the cache
        // (so the next get for visitor-0 must hit dataStore.get() again).
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        for (i in 0 until 1001) {
            manager.setStoreData("visitor-$i", StoreData())
        }
        dataStore.log.clear()

        manager.getStoreData("visitor-0")

        // visitor-0 was evicted from the in-memory cache by visitor-1000's insertion,
        // so this get() must pull from disk again.
        assertTrue(
            dataStore.log.contains("get:visitor.visitor-0"),
            "expected cache miss to trigger dataStore.get(\"visitor.visitor-0\"); log was ${dataStore.log}",
        )
    }

    @Test
    fun `updateBucketing merges new entry and persists`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        manager.setStoreData("v", StoreData(bucketing = mapOf("exp-1" to "var-a")))

        manager.updateBucketing("v", "exp-2", "var-b")

        val final = manager.getStoreData("v")
        assertEquals(mapOf("exp-1" to "var-a", "exp-2" to "var-b"), final.bucketing)
    }

    @Test
    fun `updateBucketing preserves other StoreData fields`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        val initial = StoreData(
            goals = mapOf("goal-x" to true),
            segments = mapOf("seg-1" to JsonPrimitive("gold")),
        )
        manager.setStoreData("v", initial)

        manager.updateBucketing("v", "exp-1", "var-a")

        val final = manager.getStoreData("v")
        assertEquals(mapOf("exp-1" to "var-a"), final.bucketing)
        assertEquals(mapOf("goal-x" to true), final.goals)
        assertEquals(mapOf("seg-1" to JsonPrimitive("gold")), final.segments)
    }

    @Test
    fun `updateGoal sets goal dedup flag and persists`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        manager.updateGoal("v", "goal-1", tracked = true)

        val final = manager.getStoreData("v")
        assertEquals(mapOf("goal-1" to true), final.goals)
    }

    @Test
    fun `visitor ID with non-alphanumeric chars is sanitized before keying`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        manager.setStoreData("foo bar/baz", StoreData())

        assertNotNull(dataStore.map["visitor.foo_bar_baz"])
    }

    @Test
    fun `UUID-formatted visitor IDs pass through sanitize unchanged`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        manager.setStoreData(uuid, StoreData())

        // Hex + dashes are preserved; no mutation.
        assertNotNull(dataStore.map["visitor.$uuid"])
    }

    @Test
    fun `getStoreData returns same cached instance across repeated calls`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        val seeded = StoreData(bucketing = mapOf("exp-1" to "var-a"))
        manager.setStoreData("v", seeded)

        val first = manager.getStoreData("v")
        val second = manager.getStoreData("v")

        assertSame(first, second)
    }

    // --- Story 3.2 SDK-3: atomic read-modify-write under concurrent writers.
    //
    // The pre-SDK-3 implementation released the visitor lock between the
    // getStoreData read and the setStoreData write, so two threads could
    // both read the same snapshot, both build a merged map, both write —
    // last write wins and the first thread's update is lost. These tests
    // spawn many parallel writers against a single visitor and assert
    // zero lost updates.

    @Test
    fun `updateBucketing is atomic under concurrent writers — no lost updates`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        // Seed the visitor with an empty StoreData to prime the cache.
        manager.setStoreData("v", StoreData())

        val writerCount = 64
        val startGate = java.util.concurrent.CountDownLatch(1)
        val doneGate = java.util.concurrent.CountDownLatch(writerCount)

        for (i in 0 until writerCount) {
            Thread {
                try {
                    // Start all threads in the narrow window around the
                    // read-modify-write so the race has maximum chance
                    // to manifest.
                    startGate.await()
                    manager.updateBucketing(
                        visitorId = "v",
                        experienceKey = "exp-$i",
                        variationId = "var-$i",
                    )
                } finally {
                    doneGate.countDown()
                }
            }.start()
        }

        startGate.countDown()
        assertTrue(
            doneGate.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "Writers did not complete within the timeout",
        )

        val finalBucketing = manager.getStoreData("v").bucketing ?: emptyMap()
        assertEquals(writerCount, finalBucketing.size, "Lost updates detected: $finalBucketing")
        for (i in 0 until writerCount) {
            assertEquals(
                "var-$i",
                finalBucketing["exp-$i"],
                "Missing or incorrect value for exp-$i",
            )
        }
    }

    @Test
    fun `updateGoal is atomic under concurrent writers — no lost updates`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        manager.setStoreData("v", StoreData())

        val writerCount = 64
        val startGate = java.util.concurrent.CountDownLatch(1)
        val doneGate = java.util.concurrent.CountDownLatch(writerCount)

        for (i in 0 until writerCount) {
            Thread {
                try {
                    startGate.await()
                    manager.updateGoal(
                        visitorId = "v",
                        goalKey = "goal-$i",
                        tracked = true,
                    )
                } finally {
                    doneGate.countDown()
                }
            }.start()
        }

        startGate.countDown()
        assertTrue(
            doneGate.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "Writers did not complete within the timeout",
        )

        val finalGoals = manager.getStoreData("v").goals ?: emptyMap()
        assertEquals(writerCount, finalGoals.size, "Lost updates detected: $finalGoals")
        for (i in 0 until writerCount) {
            assertEquals(true, finalGoals["goal-$i"], "Missing flag for goal-$i")
        }
    }

    @Test
    fun `updateBucketing interleaved with updateGoal on same visitor preserves both maps`() {
        // Cross-API race — updateBucketing + updateGoal both touch the same
        // visitor StoreData. If either fails to hold the lock across read +
        // write, updates from the other API will be lost. This test asserts
        // both maps end up complete.
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        manager.setStoreData("v", StoreData())

        val perApiCount = 32
        val startGate = java.util.concurrent.CountDownLatch(1)
        val doneGate = java.util.concurrent.CountDownLatch(perApiCount * 2)

        for (i in 0 until perApiCount) {
            Thread {
                try {
                    startGate.await()
                    manager.updateBucketing("v", "exp-$i", "var-$i")
                } finally {
                    doneGate.countDown()
                }
            }.start()
            Thread {
                try {
                    startGate.await()
                    manager.updateGoal("v", "goal-$i", tracked = true)
                } finally {
                    doneGate.countDown()
                }
            }.start()
        }

        startGate.countDown()
        assertTrue(doneGate.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val final = manager.getStoreData("v")
        assertEquals(perApiCount, final.bucketing?.size)
        assertEquals(perApiCount, final.goals?.size)
    }

    // --- Story 4.3 SDK-1: markGoalTracked atomic check-and-set.
    //
    // This method is the single source of dedup truth for Story 4.3.
    // Returns `true` the first time a goal is marked for a visitor and
    // `false` on every subsequent call for the same pair. The
    // check-and-set must happen atomically under the same [visitorLock]
    // that [updateGoal] / [updateBucketing] already use so that concurrent
    // `trackConversion` callers cannot both observe `goals[goalId] == null`
    // and both treat themselves as the "first mark" — exactly one caller
    // must win per (visitor, goal) pair.

    @Test
    fun `markGoalTracked returns true on first call and false on second`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        val first = manager.markGoalTracked("v", "g-42")
        val second = manager.markGoalTracked("v", "g-42")

        assertTrue(first, "first call must return true (this is the fresh mark)")
        assertFalse(second, "second call must return false (already marked)")
    }

    @Test
    fun `markGoalTracked persists the flag to the DataStore`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        manager.markGoalTracked("v", "g-42")

        // Persistence: the DataStore must see a `set:visitor.v` call after
        // the mark, and the stored payload must carry `goals["g-42"] = true`.
        assertTrue(
            dataStore.log.any { it == "set:visitor.v" },
            "markGoalTracked should persist via setStoreData; log=${dataStore.log}",
        )
        val raw = dataStore.map["visitor.v"]
        assertNotNull(raw)
        assertTrue(
            raw!!.contains("\"g-42\":true"),
            "persisted StoreData must record goals[\"g-42\"] = true; got $raw",
        )
    }

    @Test
    fun `markGoalTracked is per-visitor`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        val visitorA = manager.markGoalTracked("visitor-A", "g-42")
        val visitorB = manager.markGoalTracked("visitor-B", "g-42")

        // Two different visitors tracking the same goal both get true —
        // AC-4 per-visitor dedup. The flag lives in each visitor's
        // StoreData, not in a global goal-tracked set.
        assertTrue(visitorA, "visitor-A first call must return true")
        assertTrue(visitorB, "visitor-B first call must return true (per-visitor dedup)")
    }

    @Test
    fun `markGoalTracked leaves StoreData goals populated after the call`() {
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        manager.markGoalTracked("v", "g-42")

        val goals = manager.getStoreData("v").goals ?: emptyMap()
        assertEquals(true, goals["g-42"], "StoreData.goals must carry the flag after marking")
    }

    @Test
    fun `markGoalTracked preserves other StoreData fields`() {
        // A markGoalTracked call on a visitor that already has bucketing
        // state must not clobber the bucketing map — the critical section
        // is a strict read-merge-write that composes with updateBucketing.
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        manager.setStoreData("v", StoreData(bucketing = mapOf("exp-1" to "var-a")))

        manager.markGoalTracked("v", "g-42")

        val final = manager.getStoreData("v")
        assertEquals(mapOf("exp-1" to "var-a"), final.bucketing)
        assertEquals(true, final.goals?.get("g-42"))
    }

    @Test
    fun `markGoalTracked returns true exactly once under concurrent calls`() {
        // AC-6: 10 threads race to mark the same (visitor, goal) pair.
        // The atomic check-and-set inside visitorLock must ensure that
        // exactly one thread sees the transition from absent to true, and
        // nine threads see "already tracked".
        val dataStore = RecordingDataStore()
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        manager.setStoreData("v", StoreData())

        val threadCount = 10
        val startGate = java.util.concurrent.CountDownLatch(1)
        val doneGate = java.util.concurrent.CountDownLatch(threadCount)
        val trueCount = java.util.concurrent.atomic.AtomicInteger(0)
        val falseCount = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    startGate.await()
                    if (manager.markGoalTracked("v", "g-42")) {
                        trueCount.incrementAndGet()
                    } else {
                        falseCount.incrementAndGet()
                    }
                } finally {
                    doneGate.countDown()
                }
            }.start()
        }

        startGate.countDown()
        assertTrue(
            doneGate.await(10, java.util.concurrent.TimeUnit.SECONDS),
            "Writers did not complete within the timeout",
        )

        assertEquals(1, trueCount.get(), "exactly one thread must see the fresh mark")
        assertEquals(
            threadCount - 1,
            falseCount.get(),
            "all other threads must see 'already tracked'",
        )
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Hand-written recording fake for [DataStore]. Tracks every
     * operation in [log] (prefixed with `get:` / `set:` / `remove:`)
     * while also providing a real-map backing store so the test can
     * assert on both the call sequence and the final state.
     */
    private class RecordingDataStore : DataStore {
        val map: MutableMap<String, String> = mutableMapOf()
        val log: MutableList<String> = mutableListOf()

        override fun get(key: String): String? {
            log.add("get:$key")
            return map[key]
        }

        override fun set(key: String, value: String) {
            log.add("set:$key")
            map[key] = value
        }

        override fun remove(key: String) {
            log.add("remove:$key")
            map.remove(key)
        }

        override fun clear() {
            log.add("clear")
            map.clear()
        }
    }
}
