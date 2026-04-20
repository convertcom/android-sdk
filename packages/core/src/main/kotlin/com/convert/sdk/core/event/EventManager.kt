/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import com.convert.sdk.core.port.Logger

/**
 * In-process pub/sub bus used by SDK internals to signal lifecycle events.
 *
 * Story 2.1 RED stub — every method is empty; tests fail at runtime.
 */
public class EventManager(
    @Suppress("UnusedPrivateProperty") private val logger: Logger = Logger.NoOp,
) {

    public fun on(event: String, callback: (Map<String, Any?>) -> Unit) {
        // RED stub
    }

    public fun fire(event: String, data: Map<String, Any?> = emptyMap()) {
        // RED stub
    }

    public fun off(event: String, callback: (Map<String, Any?>) -> Unit) {
        // RED stub
    }
}
