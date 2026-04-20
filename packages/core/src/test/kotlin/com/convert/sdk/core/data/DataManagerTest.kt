/*
 * Convert Android SDK — core/data tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DataManager]. Story 2.1 AC-7 (stub) / AC-9 — carried
 * forward through Story 2.4's EventManager rewrite.
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
 * Verifies:
 * - Fresh manager has no data.
 * - setData stores the config and fires READY with the environment payload.
 * - hasData flips from false to true after setData.
 * - Re-setData overwrites the previous config and re-fires READY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DataManagerTest {

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
}
