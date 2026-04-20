/*
 * Convert Android SDK — core/data
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.model.generated.ConfigResponseData

/**
 * Holds the SDK's currently-loaded configuration and notifies subscribers
 * when it changes.
 *
 * Story 2.1 RED stub — full visitor-state logic lands in Story 3.1.
 */
public class DataManager(
    @Suppress("UnusedPrivateProperty") private val eventManager: EventManager,
    @Suppress("UnusedPrivateProperty") private val environment: String,
) {

    public var data: ConfigResponseData? = null
        private set

    public fun setData(data: ConfigResponseData) {
        // RED stub
    }

    public fun hasData(): Boolean = false
}
