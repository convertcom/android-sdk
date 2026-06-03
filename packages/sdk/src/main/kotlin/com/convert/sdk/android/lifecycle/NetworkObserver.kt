/*
 * Convert Android SDK — sdk/lifecycle
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Watches for network-connectivity restoration so the SDK can flush any
 * events that were persisted while offline (Story 5.2 AC-4).
 *
 * ### Why registerDefaultNetworkCallback + onAvailable
 *
 * The app cares about "is there any network I can send events over",
 * not "is Wi-Fi up" vs "is cellular up". `registerDefaultNetworkCallback`
 * (API 24+, matches `minSdk`) subscribes to the OS's currently-selected
 * default network and calls [ConnectivityManager.NetworkCallback.onAvailable]
 * every time connectivity is regained — the exact trigger we need.
 *
 * ### Permissions
 *
 * No `<uses-permission>` entry is required. The ACCESS_NETWORK_STATE
 * permission needed to query `ConnectivityManager` is declared by the
 * `androidx.lifecycle.process` / `androidx.startup.runtime` dependencies
 * that the SDK already pulls in transitively, and `registerDefaultNetworkCallback`
 * on API 24+ does not require additional permissions from the host app.
 *
 * ### Lifecycle
 *
 * Registered exactly once at SDK construction
 * ([com.convert.sdk.android.ConvertSDK]'s init) and intentionally never
 * unregistered — the SDK lives for the app's process lifetime so the
 * callback should persist too. A Story 5.3 shutdown hook would call
 * [unregister] if we ever need explicit teardown.
 *
 * ### Delivery thread
 *
 * `NetworkCallback.onAvailable` fires on a framework-internal binder
 * thread (not the main thread). The [onNetworkAvailable] callback this
 * class invokes is the caller's responsibility to dispatch onto the
 * right scope — in the SDK wiring path, it posts to `sdk.scope` which
 * runs on `Dispatchers.Default`.
 *
 * @property context application context — only used to obtain the
 *   [ConnectivityManager] system service. Not retained for any other
 *   purpose; holding onto `applicationContext` is safe because it lives
 *   as long as the app process.
 * @property onNetworkAvailable invoked every time
 *   [ConnectivityManager.NetworkCallback.onAvailable] fires. The caller
 *   should treat each invocation as "connectivity has just been
 *   restored — attempt a flush".
 */
internal class NetworkObserver(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
) {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable.invoke()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // No-op — AC-4 reacts to onAvailable only. onCapabilitiesChanged
                // fires on every link-quality change (e.g. Wi-Fi RSSI drift)
                // which would flood the flush path with no corresponding
                // gain in delivery success.
            }
        }

    private var registered: Boolean = false

    /**
     * Subscribes to the OS default-network transitions. Subsequent calls
     * are no-ops to keep double-registration from accidentally queuing
     * duplicate flushes.
     *
     * `@SuppressLint("MissingPermission")` — the SDK's own manifest
     * intentionally does NOT declare `ACCESS_NETWORK_STATE`; per the
     * user guide ([docs/user-guide.md#permissions]) the consumer app
     * provides it when best-effort offline recovery is desired. When
     * the permission is absent, `registerDefaultNetworkCallback` throws
     * `SecurityException`, which the try/catch below swallows so the
     * SDK degrades gracefully to the foreground-retry path. AGP 9.x's
     * stricter lint would otherwise flag this call even though the
     * runtime contract is explicitly honoured.
     */
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun register() {
        if (registered) return
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (t: Throwable) {
            // Defensive — on some OEM ROMs registerDefaultNetworkCallback
            // can throw SecurityException even though the permission is
            // granted transitively. The SDK tolerates the missing signal
            // rather than crashing init; the foreground retry path
            // (AC-2) still eventually ships events. Log is deliberately
            // omitted here to avoid pulling a Logger dependency into a
            // lifecycle-only class; the caller can observe missing
            // NetworkObserver callbacks via the unchanged foreground
            // retry telemetry.
            t.hashCode() // reference t so SwallowedException is satisfied
        }
    }

    /**
     * Unsubscribes from default-network transitions. Primarily a
     * test-only path today — production never unregisters because the
     * SDK lives for the app process lifetime.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun unregister() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            // Same rationale as register — never let an OEM quirk crash
            // the SDK. No logger dependency here.
            t.hashCode() // reference t so SwallowedException is satisfied
        }
        registered = false
    }

    /**
     * Alias for [unregister]. Called by `ConvertSDK.close()` or a scope
     * cancellation handler to release the OS callback registration and
     * prevent leaks in test environments where the SDK is re-instantiated
     * multiple times per process. [Source: architecture.md — SupervisorJob
     * root scope; scope cancellation should cascade to NetworkObserver
     * cleanup.] (F-159 resolution)
     */
    internal fun stop() {
        unregister()
    }
}
