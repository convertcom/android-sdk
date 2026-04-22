/*
 * Convert Android SDK Demo App — InspectorEvent
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.LogLevel
import java.util.concurrent.atomic.AtomicLong

/**
 * Delivery-lifecycle stage of an inspector event.
 *
 * Story 7.2 AC-7: every networked SDK event (`bucketing`, `conversion`)
 * starts life as [QUEUED] (amber), may pass through [FLUSHING] (blue)
 * while the API flush is mid-flight, and lands at [DELIVERED] (green)
 * when `api.queue.released` reports a 2xx status.
 *
 * Non-networked system events (`ready`, `config.updated`) carry
 * [NONE] — they fire internally and never cross the wire, so no
 * status badge is shown (matches the UX-spec table "Event: Online
 * delivered — Type badge + payload, no status badge").
 */
public enum class EventLifecycle {
    /** The event has been observed but has not yet been flushed. */
    QUEUED,

    /**
     * The API flush is in-flight. The demo treats this as a transient
     * state surfaced optimistically while a flush is happening; the
     * SDK does not fire a dedicated "flush-started" event today, so
     * the Event Inspector uses this primarily as a visual affordance
     * for the demo's offline/reconnect scenario (Story 7.5).
     */
    FLUSHING,

    /** A 2xx `api.queue.released` confirmed delivery. */
    DELIVERED,

    /**
     * The event is internal-only — not destined for the network.
     * Used for `ready` and `config.updated`. The UI renders no
     * status badge for NONE events.
     */
    NONE,
}

/**
 * A single event captured from the SDK's [com.convert.sdk.core.event.EventManager]
 * and rendered in the demo's Events tab.
 *
 * @property id monotonically-increasing unique id. Used as the
 *   `LazyColumn` `items(key = ...)` value to keep Compose stable
 *   across recompositions (Story 7.2 Gotcha 1).
 * @property eventName well-known system-event name — matches
 *   [com.convert.sdk.core.event.SystemEvents] constants verbatim.
 * @property payload the raw payload map the SDK fired. Keys and value
 *   types are event-specific; the inspector renders them with
 *   `key: value` cards.
 * @property lifecycle current delivery stage — see [EventLifecycle].
 * @property timestampMs wall-clock time when the ViewModel observed the
 *   event. Wall-clock (not monotonic) because it is displayed in a human
 *   log alongside other time-of-day hints.
 */
public data class InspectorEvent(
    val id: Long,
    val eventName: String,
    val payload: Map<String, Any?>,
    val lifecycle: EventLifecycle,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    public companion object {
        private val idSeq: AtomicLong = AtomicLong(0L)

        /** Mints the next monotonic id. Thread-safe; used by [SdkViewModel]. */
        public fun nextId(): Long = idSeq.incrementAndGet()
    }
}

/**
 * A single log entry captured from the SDK's [com.convert.sdk.core.port.Logger]
 * and rendered in the demo's Logs tab.
 *
 * @property id monotonically-increasing unique id (shared counter with
 *   [InspectorEvent] is unnecessary — logs use their own).
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
public data class LogEntry(
    val id: Long,
    val level: LogLevel,
    val message: String,
    val tag: String?,
    val throwable: Throwable? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    public companion object {
        private val idSeq: AtomicLong = AtomicLong(0L)

        /** Mints the next monotonic id. Thread-safe; used by [SdkViewModel]. */
        public fun nextId(): Long = idSeq.incrementAndGet()
    }
}

/**
 * Tab selector for the Event Inspector. Persisted in [SdkViewModel]
 * so the choice survives screen navigation (Story 7.2 AC-2 /
 * Gotcha 3).
 */
public enum class InspectorTab {
    EVENTS,
    LOGS,
}
