/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.android

/**
 * Callback registered with [ConvertSDK.on] to receive SDK events.
 *
 * Declared as a `fun interface` so Java consumers can pass a lambda without
 * the ceremony of `Function1<Map, Unit>`. Kotlin consumers still get SAM
 * conversion and trailing-lambda syntax.
 */
public fun interface EventCallback {

    /**
     * Invoked when the subscribed event fires.
     *
     * @param data event payload; the exact keys depend on the event name.
     */
    public fun onEvent(data: Map<String, Any?>)
}
