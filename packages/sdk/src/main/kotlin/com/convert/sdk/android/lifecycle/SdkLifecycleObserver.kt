/*
 * Convert Android SDK — sdk/lifecycle
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Thin [DefaultLifecycleObserver] adapter that forwards `onStart` / `onStop`
 * callbacks to caller-supplied lambdas.
 *
 * ### Role (Story 2.3 AC-1)
 *
 * The SDK observes [androidx.lifecycle.ProcessLifecycleOwner] to detect
 * foreground / background transitions of the hosting app:
 *  - `ON_START` (first Activity starts) → resume / (re)start the config
 *    refresh loop.
 *  - `ON_STOP` (last Activity stops) → cancel the refresh loop so no
 *    coroutines or network traffic run while the app is backgrounded.
 *
 * This class is deliberately state-free: it owns neither the refresh job,
 * nor the SDK scope, nor any reference back to `ConvertSDK`. All it does is
 * route the two lifecycle callbacks the SDK cares about to the functions
 * the caller hands in at construction time. Separating the observer from
 * the refresh-state machine keeps testing trivial — tests exercise the
 * adapter with a [androidx.lifecycle.testing.TestLifecycleOwner] and
 * counter lambdas (see `SdkLifecycleObserverTest`) — and lets the refresh
 * state live on `ConvertSDK` where it can share the SDK scope.
 *
 * ### Thread safety
 *
 * `DefaultLifecycleObserver` callbacks are delivered on the main thread
 * by `ProcessLifecycleOwner`. The supplied lambdas must not perform
 * blocking work inline; the refresh-state functions on `ConvertSDK` only
 * launch coroutines onto the SDK scope, so the observer body is effectively
 * zero-cost even on the main thread (AC-7).
 *
 * ### Why `internal`
 *
 * The observer is a wiring detail of `ConvertSDK`. Consumers never instantiate
 * it directly, so it stays inside the SDK's module-internal scope. The class
 * itself is `open` only implicitly via Kotlin defaults — production code does
 * not subclass it.
 *
 * @property onStart invoked on the main thread when `ON_START` fires.
 * @property onStop invoked on the main thread when `ON_STOP` fires.
 */
internal class SdkLifecycleObserver(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        this.onStart.invoke()
    }

    override fun onStop(owner: LifecycleOwner) {
        this.onStop.invoke()
    }
}
