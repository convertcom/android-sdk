/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.util.Log
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.port.Logger

/**
 * [Logger] adapter that forwards to Android's `android.util.Log`.
 *
 * Implements the level-filtering policy described in Story 2.1 AC-8:
 *
 * - `SILENT` drops every log call regardless of severity.
 * - Otherwise, a call emits when `call.ordinal <= configuredLevel.ordinal`.
 *   [LogLevel]'s declaration order is `ERROR, WARN, INFO, DEBUG, TRACE,
 *   SILENT` — lower-ordinal severities are more critical, so the inequality
 *   reads naturally as "emit anything at least as critical as the configured
 *   floor".
 *
 * Examples:
 * - `level = INFO` → emits `error`, `warn`, `info`; drops `debug`.
 * - `level = DEBUG` → emits all four standard levels.
 * - `level = ERROR` → emits only `error`.
 * - `level = SILENT` → emits nothing.
 *
 * The adapter is `internal` so consumers obtain a [Logger] instance via
 * [com.convert.sdk.android.ConvertSDK.builder] + `logLevel(...)`; direct
 * construction from application code is not part of the public surface.
 *
 * @property level minimum severity the logger is willing to emit; anything
 *   less critical (higher ordinal) is silently dropped.
 * @property defaultTag fallback Logcat tag used when a per-call `tag`
 *   argument is `null`. Defaults to `"ConvertSDK"` — the convention the
 *   JS SDK uses so cross-SDK filter strings are identical.
 */
internal class AndroidLogger(
    private val level: LogLevel,
    private val defaultTag: String = DEFAULT_TAG,
) : Logger {

    override fun error(message: String, throwable: Throwable?, tag: String?) {
        if (shouldEmit(LogLevel.ERROR)) {
            val resolvedTag = tag ?: defaultTag
            if (throwable != null) {
                Log.e(resolvedTag, message, throwable)
            } else {
                Log.e(resolvedTag, message)
            }
        }
    }

    override fun warn(message: String, throwable: Throwable?, tag: String?) {
        if (shouldEmit(LogLevel.WARN)) {
            val resolvedTag = tag ?: defaultTag
            if (throwable != null) {
                Log.w(resolvedTag, message, throwable)
            } else {
                Log.w(resolvedTag, message)
            }
        }
    }

    override fun info(message: String, tag: String?) {
        if (shouldEmit(LogLevel.INFO)) {
            Log.i(tag ?: defaultTag, message)
        }
    }

    override fun debug(message: String, tag: String?) {
        if (shouldEmit(LogLevel.DEBUG)) {
            Log.d(tag ?: defaultTag, message)
        }
    }

    /**
     * Returns `true` when a log call of the given [callLevel] should pass
     * through. SILENT is the universal drop; otherwise emit when the call
     * is at least as critical as the configured floor.
     */
    private fun shouldEmit(callLevel: LogLevel): Boolean {
        if (level == LogLevel.SILENT) return false
        return callLevel.ordinal <= level.ordinal
    }

    internal companion object {
        /**
         * Logcat tag used when the caller does not supply a per-call `tag`.
         * Matches the JS SDK's `"ConvertSDK"` prefix for cross-platform
         * logcat filtering.
         */
        const val DEFAULT_TAG: String = "ConvertSDK"
    }
}
