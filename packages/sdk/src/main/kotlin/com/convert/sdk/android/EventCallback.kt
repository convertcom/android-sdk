/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

/**
 * Callback invoked when the SDK emits an observed lifecycle event.
 *
 * Declared as a Kotlin `fun interface` — rather than a `() -> Unit` type
 * alias — so that Java consumers can pass a single-method lambda through
 * SAM conversion while Kotlin consumers keep the same ergonomic shape.
 *
 * Event wiring lands in Story 2.4; this skeleton fixes the public surface
 * today so that later stories can plug listeners in without churning the
 * consumer-visible API.
 */
public fun interface EventCallback {

    /**
     * Receives the payload for an event the SDK has emitted.
     *
     * @param data untyped event payload; concrete keys and value types are
     *   event-specific and documented alongside each event in Story 2.4.
     */
    public fun onEvent(data: Map<String, Any?>)
}
