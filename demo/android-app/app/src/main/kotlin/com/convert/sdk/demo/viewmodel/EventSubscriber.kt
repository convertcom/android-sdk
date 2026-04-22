/*
 * Convert Android SDK Demo App — EventSubscriber contract
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

/**
 * Minimal subscription contract the [SdkViewModel] depends on.
 *
 * Production: [com.convert.sdk.demo.SdkEventSubscriber] wraps
 * [com.convert.sdk.android.ConvertSDK]'s pub/sub surface (the
 * `on(event, EventCallback)` method).
 *
 * Tests: the suite in `src/test/...` provides a simple in-memory fake
 * so the ViewModel can be exercised without building a real SDK
 * instance (which requires an Android Context).
 */
fun interface EventSubscriber {

    /**
     * Registers [callback] for the named [event]. Returns an
     * [AutoCloseable] — calling `close()` removes the registration.
     *
     * The payload map is the same shape the SDK's EventManager fires.
     */
    fun subscribe(
        event: String,
        callback: (Map<String, Any?>) -> Unit,
    ): AutoCloseable
}
