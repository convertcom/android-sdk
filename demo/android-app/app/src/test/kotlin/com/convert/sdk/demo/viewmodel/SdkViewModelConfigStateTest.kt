/*
 * Convert Android SDK Demo App — SdkViewModel configState tests (Story 7.6 DEMO-1)
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Story 7.6 AC-5 / AC-6 / AC-7 — configState drives the ConfigScreen's
 * Loading / Loaded / Failed branches. The state transitions on the
 * SDK's `ready` / `config.updated` events (Loaded) and on WARN/ERROR
 * log entries that accumulate BEFORE `ready` fires (Failed).
 *
 * The tests drive the ViewModel through the injected EventSubscriber
 * and a fake ConfigSnapshotProvider — no real SDK required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkViewModelConfigStateTest {

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
    fun `configState starts in Loading`() {
        val vm = SdkViewModel(
            eventSubscriber = FakeEventSubscriber(),
            initialNetworkOnline = true,
        )
        assertTrue(vm.configState.value is ConfigState.Loading)
    }

    @Test
    fun `ready event transitions to Loaded with snapshot from provider`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot(
                sdkKey = "abcdef12-3456-7890",
                environment = "staging",
                experienceKeys = listOf("exp-1", "exp-2"),
                featureKeys = listOf("feat-a"),
                trackingEnabled = true,
            ),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        subscriber.emit("ready", mapOf("environment" to "staging"))
        advanceUntilIdle()

        val state = vm.configState.value
        assertTrue(state is ConfigState.Loaded, "expected Loaded, got $state")
        val loaded = state as ConfigState.Loaded
        assertEquals("abcdef12-3456-7890", loaded.snapshot.sdkKey)
        assertEquals("staging", loaded.snapshot.environment)
        assertEquals(listOf("exp-1", "exp-2"), loaded.snapshot.experienceKeys)
        assertEquals(listOf("feat-a"), loaded.snapshot.featureKeys)
        assertEquals(true, loaded.snapshot.trackingEnabled)
        assertTrue(loaded.lastFetchedAt > 0L, "timestamp should be stamped on ready")
        assertEquals(1, provider.snapshotCalls)
    }

    @Test
    fun `config updated event refreshes Loaded state with new timestamp`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot(
                sdkKey = "k",
                environment = null,
                experienceKeys = emptyList(),
                featureKeys = emptyList(),
                trackingEnabled = null,
            ),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        subscriber.emit("ready", mapOf("environment" to "staging"))
        advanceUntilIdle()
        val firstLoaded = vm.configState.value as ConfigState.Loaded
        val firstTs = firstLoaded.lastFetchedAt

        // Tiny sleep so the wall-clock increments are observable in the test.
        Thread.sleep(2)

        subscriber.emit("config.updated", mapOf("environment" to "staging"))
        advanceUntilIdle()
        val secondLoaded = vm.configState.value as ConfigState.Loaded
        assertTrue(
            secondLoaded.lastFetchedAt >= firstTs,
            "config.updated must refresh the timestamp (>=)",
        )
        assertEquals(2, provider.snapshotCalls, "provider invoked once per ready+config.updated")
    }

    @Test
    fun `warn log before ready transitions to Failed`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot("", null, emptyList(), emptyList(), null),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        // Emit a WARN log before any ready.
        vm.logger.warn("no cached config, fetch failed")
        advanceUntilIdle()

        val state = vm.configState.value
        assertTrue(state is ConfigState.Failed, "expected Failed, got $state")
        val failed = state as ConfigState.Failed
        assertNotNull(failed.reason)
        assertNotNull(failed.hint)
        assertTrue(
            failed.reason.contains("no cached config") || failed.reason.contains("fetch failed"),
            "reason should include the WARN message: ${failed.reason}",
        )
    }

    @Test
    fun `error log before ready transitions to Failed`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot("", null, emptyList(), emptyList(), null),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        vm.logger.error("network unreachable")
        advanceUntilIdle()

        val state = vm.configState.value
        assertTrue(state is ConfigState.Failed)
    }

    @Test
    fun `warn log after ready stays Loaded`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot(
                sdkKey = "k",
                environment = "prod",
                experienceKeys = emptyList(),
                featureKeys = emptyList(),
                trackingEnabled = true,
            ),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        subscriber.emit("ready", mapOf("environment" to "prod"))
        advanceUntilIdle()
        val loadedBefore = vm.configState.value
        assertTrue(loadedBefore is ConfigState.Loaded)

        // A WARN that fires AFTER ready (e.g. a subsequent fetch failing)
        // must not downgrade the panel back to Failed — the app already
        // has usable config.
        vm.logger.warn("subsequent refresh failed")
        advanceUntilIdle()

        assertSame(loadedBefore, vm.configState.value, "Loaded state should not regress on post-ready warn")
    }

    @Test
    fun `info and debug logs before ready do not transition to Failed`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        val provider = FakeConfigSnapshotProvider(
            snapshot = ConfigSnapshot("", null, emptyList(), emptyList(), null),
        )
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        vm.logger.info("initialising")
        vm.logger.debug("trace frame")
        advanceUntilIdle()

        assertTrue(
            vm.configState.value is ConfigState.Loading,
            "info/debug logs should not transition state",
        )
    }

    @Test
    fun `default no-op config snapshot provider yields empty snapshot after ready`() = runTest(testDispatcher) {
        val subscriber = FakeEventSubscriber()
        // No provider argument — fall back to the default NoOp.
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
        )
        subscriber.emit("ready", mapOf("environment" to "dev"))
        advanceUntilIdle()

        val loaded = vm.configState.value as ConfigState.Loaded
        assertEquals("", loaded.snapshot.sdkKey)
        assertTrue(loaded.snapshot.experienceKeys.isEmpty())
        assertTrue(loaded.snapshot.featureKeys.isEmpty())
    }

    // -----------------------------------------------------------------

    private class FakeConfigSnapshotProvider(
        private val snapshot: ConfigSnapshot,
    ) : ConfigSnapshotProvider {
        var snapshotCalls: Int = 0
            private set

        override fun snapshot(): ConfigSnapshot {
            snapshotCalls++
            return snapshot
        }
    }

    private class FakeEventSubscriber : EventSubscriber {
        private val subscriptions: MutableMap<String, MutableList<(Map<String, Any?>) -> Unit>> =
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

    // Silence the unused-LogLevel import warning on the JUnit classpath.
    @Suppress("unused")
    private val logLevelRef = LogLevel.WARN
}
