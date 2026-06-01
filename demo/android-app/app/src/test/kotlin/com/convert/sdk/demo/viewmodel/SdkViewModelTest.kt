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

    // --- Story 7.2 additions -----------------------------------------

    @Test
    fun `tab selection defaults to Events`() {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        assertEquals(InspectorTab.EVENTS, vm.selectedTab.value)
    }

    @Test
    fun `selectTab updates the selectedTab flow`() {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        vm.selectTab(InspectorTab.LOGS)
        assertEquals(InspectorTab.LOGS, vm.selectedTab.value)
        vm.selectTab(InspectorTab.EVENTS)
        assertEquals(InspectorTab.EVENTS, vm.selectedTab.value)
    }

    @Test
    fun `bucketing event added with QUEUED lifecycle`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        subscriber.emit(
            "bucketing",
            mapOf("experienceKey" to "welcome", "variationKey" to "control", "visitorId" to "v-1"),
        )
        advanceUntilIdle()
        val e = vm.events.value.single()
        assertEquals("bucketing", e.eventName)
        assertEquals(EventLifecycle.QUEUED, e.lifecycle)
    }

    @Test
    fun `conversion event added with QUEUED lifecycle`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        subscriber.emit(
            "conversion",
            mapOf("visitorId" to "v-1", "goalKey" to "purchase"),
        )
        advanceUntilIdle()
        val e = vm.events.value.single()
        assertEquals("conversion", e.eventName)
        assertEquals(EventLifecycle.QUEUED, e.lifecycle)
    }

    @Test
    fun `api queue released with 2xx transitions QUEUED to DELIVERED`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)

        subscriber.emit("bucketing", mapOf("experienceKey" to "e-1", "visitorId" to "v-1"))
        subscriber.emit("conversion", mapOf("goalKey" to "purchase", "visitorId" to "v-1"))
        advanceUntilIdle()
        // Both start QUEUED.
        assertTrue(vm.events.value.all { it.lifecycle == EventLifecycle.QUEUED })

        subscriber.emit("api.queue.released", mapOf("batchSize" to 2, "statusCode" to 200))
        advanceUntilIdle()

        // Both now DELIVERED.
        val all = vm.events.value.filter { it.eventName in setOf("bucketing", "conversion") }
        assertEquals(2, all.size, "the two networked events are still tracked")
        assertTrue(
            all.all { it.lifecycle == EventLifecycle.DELIVERED },
            "2xx release should transition QUEUED -> DELIVERED",
        )
    }

    @Test
    fun `api queue released with 5xx does not transition`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)

        subscriber.emit("bucketing", mapOf("experienceKey" to "e-1", "visitorId" to "v-1"))
        advanceUntilIdle()
        assertEquals(EventLifecycle.QUEUED, vm.events.value.single().lifecycle)

        subscriber.emit("api.queue.released", mapOf("batchSize" to 1, "statusCode" to 500))
        advanceUntilIdle()
        // Non-2xx is ignored by the resolver — event stays QUEUED.
        assertEquals(
            EventLifecycle.QUEUED,
            vm.events.value.single { it.eventName == "bucketing" }.lifecycle,
            "non-2xx release should NOT transition to DELIVERED",
        )
    }

    @Test
    fun `ready and config updated events have NONE lifecycle`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        subscriber.emit("ready", mapOf("environment" to "production"))
        subscriber.emit("config.updated", mapOf("timestamp" to 1L))
        advanceUntilIdle()
        val system = vm.events.value.filter { it.eventName in setOf("ready", "config.updated") }
        assertEquals(2, system.size)
        assertTrue(
            system.all { it.lifecycle == EventLifecycle.NONE },
            "system events that never hit the network have NONE lifecycle",
        )
    }

    @Test
    fun `event ids are unique and stable`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        subscriber.emit("bucketing", mapOf("experienceKey" to "e-1"))
        subscriber.emit("bucketing", mapOf("experienceKey" to "e-2"))
        subscriber.emit("conversion", mapOf("goalKey" to "g-1"))
        advanceUntilIdle()
        val ids = vm.events.value.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "every inspector event must have a unique id")
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
