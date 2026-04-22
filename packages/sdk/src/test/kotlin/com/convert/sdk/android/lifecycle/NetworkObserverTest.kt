/*
 * Convert Android SDK — sdk/lifecycle tests (Story 5.2)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 5.2 AC-7 tests for [NetworkObserver].
 *
 * Robolectric provides a real [ConnectivityManager] system service. We
 * exercise the observer's callback by reaching into its internal
 * [ConnectivityManager.NetworkCallback] via reflection — Robolectric's
 * `ShadowConnectivityManager` exposes registered callbacks through
 * a protected API we can't reach from Kotlin, and the simplest
 * portable path is to invoke the callback we constructed directly.
 * This proves the wiring (onAvailable → onNetworkAvailable) without
 * depending on Robolectric internals that vary between versions.
 */
@RunWith(RobolectricTestRunner::class)
internal class NetworkObserverTest {

    @Test
    fun `onAvailable triggers callback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val counter = AtomicInteger(0)

        val observer = NetworkObserver(context) { counter.incrementAndGet() }
        observer.register()

        // Reach into NetworkObserver's private NetworkCallback via
        // reflection and invoke onAvailable. Robolectric's default
        // ShadowConnectivityManager swallows real network events, so
        // driving the callback directly is the most reliable way to
        // verify the wiring.
        val callbackField = NetworkObserver::class.java.getDeclaredField("callback")
        callbackField.isAccessible = true
        val callback = callbackField.get(observer) as ConnectivityManager.NetworkCallback
        assertNotNull("NetworkObserver must construct a NetworkCallback", callback)

        // Build a test Network instance via the private (Int) constructor —
        // the only way to materialise one from the JVM side.
        val networkCtor = Network::class.java.getDeclaredConstructor(Int::class.java)
        networkCtor.isAccessible = true
        val fakeNetwork = networkCtor.newInstance(1)

        callback.onAvailable(fakeNetwork)
        // Second tick should also count — register() is idempotent but
        // onAvailable is not; every network re-establish runs it.
        callback.onAvailable(fakeNetwork)

        assertEquals(
            "each onAvailable should invoke the onNetworkAvailable hook",
            2,
            counter.get(),
        )
    }

    @Test
    fun `register is idempotent`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val observer = NetworkObserver(context) { /* no-op */ }

        // Should not throw on repeated register calls; the observer
        // suppresses the double-registration internally.
        observer.register()
        observer.register()
        observer.register()
    }
}
