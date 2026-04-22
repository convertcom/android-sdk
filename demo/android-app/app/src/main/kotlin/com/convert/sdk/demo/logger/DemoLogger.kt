/*
 * Convert Android SDK Demo App — DemoLogger
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.logger

import android.util.Log
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.port.Logger

/**
 * [Logger] implementation used by the demo that fans every SDK log
 * call out to TWO sinks:
 *
 *  1. `android.util.Log` — so `adb logcat -s ConvertSDK` still works
 *     exactly as it does for any consumer app that relies on the
 *     built-in [com.convert.sdk.android.adapter.AndroidLogger].
 *
 *  2. A caller-provided [sink] lambda — the demo's
 *     [com.convert.sdk.demo.viewmodel.SdkViewModel] passes its internal
 *     logger handle so every message surfaces in the inspector's Logs
 *     tab with matching level, tag, and throwable.
 *
 * The level-filtering policy mirrors [AndroidLogger]: `SILENT` drops
 * everything; otherwise emit when `call.ordinal <= level.ordinal`.
 *
 * ### Why re-implement instead of wrapping AndroidLogger
 *
 * [com.convert.sdk.android.adapter.AndroidLogger] is `internal` to
 * `:packages:sdk`, so the demo cannot construct one directly. The
 * SDK's Builder uses it internally when you call `.logLevel(...)` —
 * but the demo needs a Logger that ALSO captures to the UI flow,
 * which the internal adapter cannot do. So the demo builds its own
 * two-sink Logger and hands THAT to the SDK via a hook that the
 * Builder exposes (if available) — or, when no such hook exists,
 * simply uses this Logger alongside the SDK's own and tolerates the
 * small duplication in Logcat.
 *
 * For Story 7.1 we take the simpler "alongside" path: the SDK keeps
 * its own AndroidLogger (driven by `.logLevel(LogLevel.DEBUG)`), and
 * the demo's own application-level log calls go through this
 * DemoLogger → (Logcat + ViewModel flow). Story 7.2 expands the
 * inspector with proper event coverage and may revisit this wiring.
 */
class DemoLogger(
    private val level: LogLevel,
    private val sink: Logger,
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
            sink.error(message, throwable, resolvedTag)
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
            sink.warn(message, throwable, resolvedTag)
        }
    }

    override fun info(message: String, tag: String?) {
        if (shouldEmit(LogLevel.INFO)) {
            val resolvedTag = tag ?: defaultTag
            Log.i(resolvedTag, message)
            sink.info(message, resolvedTag)
        }
    }

    override fun debug(message: String, tag: String?) {
        if (shouldEmit(LogLevel.DEBUG)) {
            val resolvedTag = tag ?: defaultTag
            Log.d(resolvedTag, message)
            sink.debug(message, resolvedTag)
        }
    }

    private fun shouldEmit(callLevel: LogLevel): Boolean {
        if (level == LogLevel.SILENT) return false
        return callLevel.ordinal <= level.ordinal
    }

    companion object {
        /** Matches the SDK's own `"ConvertSDK"` default for Logcat filtering. */
        const val DEFAULT_TAG: String = "ConvertSDK"
    }
}
