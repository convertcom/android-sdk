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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 5.2 AC-7 tests for [NetworkObserver].
 *
 * Robolectric-backed so the real [ConnectivityManager] is available;
 * `shadowOf(...)` grants access to Robolectric's test hooks on the
 * manager. We drive network availability by iterating the registered
 * [ConnectivityManager.NetworkCallback] list via shadow reflection,
 * simulating `onAvailable` by invoking the callback directly.
 */
@RunWith(RobolectricTestRunner::class)
internal class NetworkObserverTest {

    @Test
    fun `onAvailable triggers callback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val counter = AtomicInteger(0)

        val observer = NetworkObserver(context) { counter.incrementAndGet() }
        observer.register()

        // Pull the registered default-network callback off the shadow
        // ConnectivityManager and invoke onAvailable — Robolectric does not
        // emit real network transitions so we drive the callback directly.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callbacks = shadowOf(cm).registeredNetworkCallbacks
        assertTrue(
            "NetworkObserver.register should register a default-network callback",
            callbacks.isNotEmpty(),
        )
        val fakeNetwork = shadowOf(cm).activeNetwork ?: Network::class.java
            .getDeclaredConstructor(Int::class.java)
            .apply { isAccessible = true }
            .newInstance(1)

        callbacks.forEach { cb -> cb.onAvailable(fakeNetwork as Network) }

        assertEquals("onAvailable should fire the onNetworkAvailable callback", 1, counter.get())
    }
}
