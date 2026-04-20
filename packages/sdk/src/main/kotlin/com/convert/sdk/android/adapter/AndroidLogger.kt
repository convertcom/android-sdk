/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.port.Logger

/**
 * [Logger] adapter that forwards to Android's `android.util.Log`.
 *
 * Story 2.1 RED stub — the real implementation lands in Phase 2 (GREEN).
 * This stub exists solely so the test file (`AndroidLoggerTest`) can compile
 * and fail at runtime.
 */
internal class AndroidLogger(
    @Suppress("UnusedPrivateProperty") private val level: LogLevel,
    @Suppress("UnusedPrivateProperty") private val defaultTag: String = "ConvertSDK",
) : Logger {

    override fun error(message: String, throwable: Throwable?, tag: String?) {
        // RED stub
    }

    override fun warn(message: String, throwable: Throwable?, tag: String?) {
        // RED stub
    }

    override fun info(message: String, tag: String?) {
        // RED stub
    }

    override fun debug(message: String, tag: String?) {
        // RED stub
    }
}
