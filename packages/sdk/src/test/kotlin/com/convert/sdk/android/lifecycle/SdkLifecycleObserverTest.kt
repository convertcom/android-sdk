/*
 * Convert Android SDK — sdk/lifecycle tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 2.3 AC-10 unit tests for [SdkLifecycleObserver].
 *
 * The observer is a thin adapter that maps `DefaultLifecycleObserver.onStart`
 * and `onStop` callbacks onto caller-supplied lambdas. These tests drive a
 * [TestLifecycleOwner] through CREATED → STARTED → CREATED (and beyond) and
 * assert that the observer's `onStart` / `onStop` counters increment exactly
 * once per transition.
 *
 * Robolectric is required here only because androidx.lifecycle's internals
 * reference `android.os.Looper` when installing the main-thread check on
 * `Lifecycle.addObserver(...)`. On a pure-JVM test this fails with
 * `RuntimeException: Method getMainLooper in android.os.Looper not mocked`.
 * Robolectric supplies the shadow that makes that check pass.
 */
@RunWith(RobolectricTestRunner::class)
internal class SdkLifecycleObserverTest {

    /**
     * ON_START delivered through the lifecycle owner triggers the onStart
     * callback exactly once. Uses [TestLifecycleOwner] from
     * `androidx.lifecycle:lifecycle-runtime-testing` (added in SDK-1) to
     * avoid spinning up a real Android process.
     */
    @Test
    fun `onStart triggers onStart callback`() {
        val onStartCalls = AtomicInteger(0)
        val onStopCalls = AtomicInteger(0)
        val owner = TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = UnconfinedTestDispatcher(),
        )
        val observer = SdkLifecycleObserver(
            onStart = { onStartCalls.incrementAndGet() },
            onStop = { onStopCalls.incrementAndGet() },
        )
        owner.lifecycle.addObserver(observer)

        // Transition CREATED → STARTED — fires ON_START via DefaultLifecycleObserver.
        owner.currentState = Lifecycle.State.STARTED

        assertEquals("onStart should fire exactly once", 1, onStartCalls.get())
        assertEquals("onStop should not fire yet", 0, onStopCalls.get())
    }

    /**
     * After ON_START, transitioning STARTED → CREATED fires ON_STOP. The
     * observer's onStop callback fires exactly once and the earlier onStart
     * count is preserved.
     */
    @Test
    fun `onStop triggers onStop callback`() {
        val onStartCalls = AtomicInteger(0)
        val onStopCalls = AtomicInteger(0)
        val owner = TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = UnconfinedTestDispatcher(),
        )
        val observer = SdkLifecycleObserver(
            onStart = { onStartCalls.incrementAndGet() },
            onStop = { onStopCalls.incrementAndGet() },
        )
        owner.lifecycle.addObserver(observer)

        owner.currentState = Lifecycle.State.STARTED
        owner.currentState = Lifecycle.State.CREATED

        assertEquals("onStart should have fired once", 1, onStartCalls.get())
        assertEquals("onStop should fire exactly once", 1, onStopCalls.get())
    }

    /**
     * Once the observer is removed, further lifecycle transitions must NOT
     * invoke its callbacks. This guards against the common leak pattern
     * where a removed-but-still-strongly-referenced observer keeps firing.
     */
    @Test
    fun `observer is removed cleanly on teardown`() {
        val onStartCalls = AtomicInteger(0)
        val onStopCalls = AtomicInteger(0)
        val owner = TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = UnconfinedTestDispatcher(),
        )
        val observer = SdkLifecycleObserver(
            onStart = { onStartCalls.incrementAndGet() },
            onStop = { onStopCalls.incrementAndGet() },
        )
        owner.lifecycle.addObserver(observer)

        owner.currentState = Lifecycle.State.STARTED
        assertEquals(1, onStartCalls.get())

        owner.lifecycle.removeObserver(observer)

        // Any further transitions should be ignored by the now-detached observer.
        owner.currentState = Lifecycle.State.CREATED
        owner.currentState = Lifecycle.State.STARTED
        owner.currentState = Lifecycle.State.CREATED

        assertEquals("no more onStart after removeObserver", 1, onStartCalls.get())
        assertEquals(
            "no onStop should have fired while the observer is detached",
            0,
            onStopCalls.get(),
        )
    }

    /**
     * The observer survives multiple start/stop cycles and fires its
     * callbacks once per transition. Regression guard for a future
     * optimisation that might accidentally deduplicate identical callbacks.
     */
    @Test
    fun `observer fires on each start-stop cycle`() {
        val onStartCalls = AtomicInteger(0)
        val onStopCalls = AtomicInteger(0)
        val owner = TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = UnconfinedTestDispatcher(),
        )
        val observer = SdkLifecycleObserver(
            onStart = { onStartCalls.incrementAndGet() },
            onStop = { onStopCalls.incrementAndGet() },
        )
        owner.lifecycle.addObserver(observer)

        owner.currentState = Lifecycle.State.STARTED
        owner.currentState = Lifecycle.State.CREATED
        owner.currentState = Lifecycle.State.STARTED
        owner.currentState = Lifecycle.State.CREATED
        owner.currentState = Lifecycle.State.STARTED

        assertEquals(3, onStartCalls.get())
        assertEquals(2, onStopCalls.get())
    }
}
