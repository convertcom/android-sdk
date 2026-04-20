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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `second setData overwrites and re-fires READY`() = runTest {
        val eventManager = EventManager(scope = this)
        val manager = DataManager(eventManager, environment = "staging")
        var readyFires = 0
        eventManager.on(SystemEvents.READY) { readyFires++ }

        val first = ConfigResponseData()
        val second = ConfigResponseData()

        manager.setData(first)
        advanceUntilIdle()
        manager.setData(second)
        advanceUntilIdle()

        assertEquals(second, manager.data)
        assertEquals(2, readyFires)
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
        val dataStore = inMemoryDataStore()
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
        val dataStore = mockk<DataStore>(relaxed = true)
        every { dataStore.get(any()) } returns null
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
            json = json,
        )
        val data = StoreData(bucketing = mapOf("exp-1" to "var-a"))

        manager.setStoreData("visitor-1", data)

        verify { dataStore.set("visitor.visitor-1", json.encodeToString(StoreData.serializer(), data)) }
    }

    @Test
    fun `getStoreData loads from DataStore for previously-seen visitor`() {
        val dataStore = mockk<DataStore>(relaxed = true)
        val seeded = StoreData(bucketing = mapOf("exp-1" to "var-a"))
        val encoded = Json.encodeToString(StoreData.serializer(), seeded)
        every { dataStore.get("visitor.visitor-1") } returns encoded
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        // First call pulls from disk.
        val first = manager.getStoreData("visitor-1")
        // Second call should hit the in-memory cache — only ONE dataStore.get invocation.
        val second = manager.getStoreData("visitor-1")

        assertEquals(seeded, first)
        assertEquals(seeded, second)
        verify(exactly = 1) { dataStore.get("visitor.visitor-1") }
    }

    @Test
    fun `corrupted visitor state is deleted and replaced with empty`() {
        val dataStore = mockk<DataStore>(relaxed = true)
        every { dataStore.get("visitor.corrupt") } returns "this is not valid json {{{"
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        val result = manager.getStoreData("corrupt")

        assertEquals(StoreData(), result)
        verify { dataStore.remove("visitor.corrupt") }
    }

    @Test
    fun `LRU cache evicts oldest visitor beyond 1000 entries`() {
        // Insert 1001 distinct visitors; visitor-0 should be evicted from the cache
        // (so the next get for visitor-0 must hit dataStore.get() again).
        val dataStore = mockk<DataStore>(relaxed = true)
        every { dataStore.get(any()) } returns null
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        for (i in 0 until 1001) {
            manager.setStoreData("visitor-$i", StoreData())
        }
        // Clear mockk invocation history on the get() calls so we can assert on
        // the FOLLOWING get.
        io.mockk.clearMocks(dataStore, answers = false, recordedCalls = true)

        manager.getStoreData("visitor-0")

        // visitor-0 was evicted from the in-memory cache by visitor-1000's insertion,
        // so this get() must pull from disk again.
        verify(exactly = 1) { dataStore.get("visitor.visitor-0") }
    }

    @Test
    fun `updateBucketing merges new entry and persists`() {
        val dataStore = inMemoryDataStore()
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
        val dataStore = inMemoryDataStore()
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
        val dataStore = inMemoryDataStore()
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
        val dataStore = mockk<DataStore>(relaxed = true)
        every { dataStore.get(any()) } returns null
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )

        manager.setStoreData("foo bar/baz", StoreData())

        verify { dataStore.set(eq("visitor.foo_bar_baz"), any()) }
    }

    @Test
    fun `UUID-formatted visitor IDs pass through sanitize unchanged`() {
        val dataStore = mockk<DataStore>(relaxed = true)
        every { dataStore.get(any()) } returns null
        val manager = DataManager(
            eventManager = EventManager(),
            environment = "staging",
            dataStore = dataStore,
        )
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        manager.setStoreData(uuid, StoreData())

        // Hex + dashes are preserved; no mutation.
        verify { dataStore.set(eq("visitor.$uuid"), any()) }
    }

    @Test
    fun `getStoreData returns same cached instance across repeated calls`() {
        val dataStore = inMemoryDataStore()
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

    // --- helpers ---------------------------------------------------------

    private fun inMemoryDataStore(): DataStore = object : DataStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }
}
