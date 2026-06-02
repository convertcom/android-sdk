/*
 * Convert Android SDK Demo App — SdkViewModel unit tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Story 7.1 AC-9 — SdkViewModel manages SDK state and inspector events.
 *
 * These tests drive the ViewModel entirely through its injected
 * [EventSubscriber] + [LogSink] contracts so the test does NOT need to
 * build a real [com.convert.sdk.android.ConvertSDK] instance (which
 * requires an Android [android.content.Context]). A real demo launch
 * plugs in the SDK-backed implementations in DemoApplication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `events flow is empty at construction`() {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        assertTrue(vm.events.value.isEmpty(), "events flow should start empty")
    }

    @Test
    fun `logs flow is empty at construction`() {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        assertTrue(vm.logs.value.isEmpty(), "logs flow should start empty")
    }

    @Test
    fun `networkOnline flow reflects initial value`() {
        val subscriber = FakeEventSubscriber()
        val online = SdkViewModel(subscriber, initialNetworkOnline = true)
        val offline = SdkViewModel(subscriber, initialNetworkOnline = false)
        assertTrue(online.networkOnline.value)
        assertFalse(offline.networkOnline.value)
    }

    @Test
    fun `viewmodel subscribes to all five system events on construction`() {
        val subscriber = FakeEventSubscriber()
        SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        assertEquals(
            setOf("ready", "config.updated", "bucketing", "conversion", "api.queue.released"),
            subscriber.subscriptions.keys,
            "ViewModel should subscribe to the five system events named in AC-9",
        )
    }

    @Test
    fun `events flow accumulates fired events newest first`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)

        subscriber.emit("bucketing", mapOf("experienceId" to "exp-1", "variationId" to "var-a"))
        subscriber.emit("conversion", mapOf("goalKey" to "purchase"))
        advanceUntilIdle()

        val events = vm.events.value
        assertEquals(2, events.size, "two fires → two entries")
        // Newest first ordering (UX spec — inspector shows newest events first).
        assertEquals("conversion", events[0].eventName)
        assertEquals("bucketing", events[1].eventName)
        assertEquals("exp-1", events[1].payload["experienceId"])
    }

    @Test
    fun `logs flow accumulates log entries newest first`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)

        // Exercise the DemoLogger hook via the ViewModel's logger handle.
        vm.logger.info("first")
        vm.logger.warn("second")
        vm.logger.error("third")
        advanceUntilIdle()

        val logs = vm.logs.value
        assertEquals(3, logs.size)
        assertEquals(LogLevel.ERROR, logs[0].level)
        assertEquals("third", logs[0].message)
        assertEquals(LogLevel.WARN, logs[1].level)
        assertEquals(LogLevel.INFO, logs[2].level)
    }

    @Test
    fun `setNetworkOnline updates the networkOnline flow`() {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        vm.setNetworkOnline(false)
        assertFalse(vm.networkOnline.value)
        vm.setNetworkOnline(true)
        assertTrue(vm.networkOnline.value)
    }

    /**
     * Minimal in-memory [EventSubscriber] double. Registers callbacks by
     * name; tests invoke `emit(...)` to simulate SDK-side fires.
     */
    private class FakeEventSubscriber : EventSubscriber {
        val subscriptions: MutableMap<String, MutableList<(Map<String, Any?>) -> Unit>> =
            mutableMapOf()

        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable {
            subscriptions.getOrPut(event) { mutableListOf() }.add(callback)
            return AutoCloseable {
                subscriptions[event]?.remove(callback)
            }
        }

        fun emit(event: String, payload: Map<String, Any?>) {
            subscriptions[event]?.forEach { it(payload) }
        }
    }
}
