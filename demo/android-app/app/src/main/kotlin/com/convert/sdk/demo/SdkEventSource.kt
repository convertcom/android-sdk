/*
 * Convert Android SDK Demo App — SdkEventSource seam + buildEventSubscriber
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.android.EventCallback
import com.convert.sdk.demo.viewmodel.EventSubscriber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Story 7.1 AC-3 (F-166) — thin abstraction over [ConvertSDK]'s
 * `on(event, EventCallback)` / `off(event, token)` pair.
 *
 * Exists so [buildEventSubscriber] can be exercised by JVM unit tests
 * without instantiating a real SDK (which requires an Android
 * [android.content.Context]) and without pulling in Robolectric or
 * `mockito-inline`. Production wraps a real [ConvertSDK] via
 * [asSdkEventSource]; tests substitute a fake.
 */
internal interface SdkEventSource {

    /**
     * Registers [callback] for [event] on the underlying source.
     * Returns an [AutoCloseable] whose [AutoCloseable.close] removes the
     * registration. Unifying the on/off pair into one [AutoCloseable] —
     * rather than exposing a token type — avoids depending on the SDK's
     * `SubscriptionToken` (which has an internal constructor and so
     * cannot be fabricated by demo-module tests).
     */
    fun on(event: String, callback: (Map<String, Any?>) -> Unit): AutoCloseable
}

/**
 * Adapts a real [ConvertSDK] instance into the [SdkEventSource] seam.
 * The returned [AutoCloseable] from [SdkEventSource.on] captures the
 * SDK-side [com.convert.sdk.core.event.SubscriptionToken] in its
 * closure and calls `sdk.off(event, token)` on close.
 */
internal fun ConvertSDK.asSdkEventSource(): SdkEventSource = object : SdkEventSource {
    override fun on(event: String, callback: (Map<String, Any?>) -> Unit): AutoCloseable {
        val convertSdk = this@asSdkEventSource
        val token = convertSdk.on(event, EventCallback { data -> callback(data) })
        return AutoCloseable { convertSdk.off(event, token) }
    }
}

/**
 * Factory for the application-wide [CoroutineScope] used by the demo.
 *
 * Defaults to [Dispatchers.Default] so SDK build/await work runs off
 * the main thread — satisfying the architecture NFR
 * "no main-thread blocking" (see
 * `_bmad-output/planning-artifacts/2026-03-23-convert-android-sdk/architecture.md`,
 * §4 NFR-12 and §11 "Never use Dispatchers.Main inside the SDK").
 *
 * Extracted as a top-level function so JVM unit tests can verify the
 * dispatcher policy without instantiating the [android.app.Application]
 * subclass that owns the scope in production.
 */
internal fun newApplicationScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

/**
 * Sentinel used by [buildEventSubscriber] as a CAS target after the
 * outer [AutoCloseable] has been closed. Distinct from `null` (the
 * pre-registration state) so the post-deferred coroutine can detect
 * the close-before-register race.
 */
private val DISPOSED_SENTINEL: AutoCloseable = AutoCloseable { /* no-op */ }

/**
 * Builds an [EventSubscriber] that defers actual `source.on(...)`
 * registration until [deferred] completes.
 *
 * Why deferred: production wires this against a [Deferred] that
 * resolves once the (background-built) [ConvertSDK] is ready —
 * `subscribe()` therefore never blocks the main thread on SDK init,
 * even when the consumer (e.g. `SdkViewModel.init`) calls subscribe()
 * synchronously during Activity construction.
 *
 * Cancel-before-register: if the consumer calls
 * [AutoCloseable.close] on the returned token BEFORE [deferred]
 * completes, the registration coroutine sees `DISPOSED_SENTINEL` via
 * a CAS on the shared [AtomicReference] and disposes the subscription
 * immediately on completion. Net registrations on [SdkEventSource]
 * for that subscriber are then zero.
 *
 * Top-level (and `internal`) so JVM tests can exercise both the
 * subscribe-before-ready and cancel-before-register paths against a
 * fake [SdkEventSource] + a [kotlinx.coroutines.CompletableDeferred],
 * without needing Robolectric or a real SDK.
 */
internal fun buildEventSubscriber(
    scope: CoroutineScope,
    deferred: Deferred<SdkEventSource>,
): EventSubscriber = EventSubscriber { event, callback ->
    val cancelRef = AtomicReference<AutoCloseable?>(null)
    scope.launch {
        val source = deferred.await()
        val sub = source.on(event, callback)
        // CAS(null → sub): if the consumer hasn't closed yet, install
        // the subscription. If the CAS fails the slot already holds
        // DISPOSED_SENTINEL — clean up immediately to keep net
        // registrations at zero.
        if (!cancelRef.compareAndSet(null, sub)) {
            sub.close()
        }
    }
    AutoCloseable {
        // getAndSet returns whatever was there:
        //   - null (deferred not completed yet): nothing to close —
        //     the post-deferred coroutine will see DISPOSED_SENTINEL
        //     via its CAS and dispose itself.
        //   - the live sub: close it.
        //   - DISPOSED_SENTINEL (already closed): close() is a no-op.
        cancelRef.getAndSet(DISPOSED_SENTINEL)?.close()
    }
}
