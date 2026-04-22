/*
 * Convert Android SDK Demo App — InspectorEvent
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.LogLevel

/**
 * A single event captured from the SDK's [com.convert.sdk.core.event.EventManager]
 * and rendered in the demo's Events tab.
 *
 * @property eventName well-known system-event name — matches
 *   [com.convert.sdk.core.event.SystemEvents] constants verbatim.
 * @property payload the raw payload map the SDK fired. Keys and value
 *   types are event-specific; the inspector renders them with
 *   `key: value` cards.
 * @property timestampMs wall-clock time when the ViewModel observed the
 *   event. Wall-clock (not monotonic) because it is displayed in a human
 *   log alongside other time-of-day hints.
 */
data class InspectorEvent(
    val eventName: String,
    val payload: Map<String, Any?>,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * A single log entry captured from the SDK's [com.convert.sdk.core.port.Logger]
 * and rendered in the demo's Logs tab.
 *
 * @property level severity of the log call — mirrors the [LogLevel]
 *   enum the SDK already exposes, keeping the demo's badge rendering
 *   trivial to map.
 * @property message human-readable log message as emitted by the SDK.
 * @property tag the log tag (falls back to the SDK default
 *   `"ConvertSDK"` when the caller did not supply one — identical to
 *   [com.convert.sdk.android.adapter.AndroidLogger]'s behaviour).
 * @property throwable optional exception for `error` / `warn` calls.
 * @property timestampMs wall-clock capture time, same rationale as
 *   [InspectorEvent.timestampMs].
 */
data class LogEntry(
    val level: LogLevel,
    val message: String,
    val tag: String?,
    val throwable: Throwable? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)
