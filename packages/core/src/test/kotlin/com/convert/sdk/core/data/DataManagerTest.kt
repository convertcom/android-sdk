/*
 * Convert Android SDK — core/data tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.generated.ConfigResponseData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DataManager]. Story 2.1 AC-7 (stub) and AC-9.
 *
 * Verifies:
 * - Fresh manager has no data.
 * - setData stores the config and fires READY with the environment payload.
 * - hasData flips from false to true after setData.
 * - Re-setData overwrites the previous config and re-fires READY.
 */
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
    fun `setData fires READY event with environment payload`() {
        val eventManager = EventManager()
        val manager = DataManager(eventManager, environment = "prod")
        var received: Map<String, Any?>? = null
        eventManager.on(SystemEvents.READY) { received = it }

        manager.setData(ConfigResponseData())

        assertNotNull(received)
        assertEquals("prod", received?.get("environment"))
    }

    @Test
    fun `second setData overwrites and re-fires READY`() {
        val eventManager = EventManager()
        val manager = DataManager(eventManager, environment = "staging")
        var readyFires = 0
        eventManager.on(SystemEvents.READY) { readyFires++ }

        val first = ConfigResponseData()
        val second = ConfigResponseData()

        manager.setData(first)
        manager.setData(second)

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
