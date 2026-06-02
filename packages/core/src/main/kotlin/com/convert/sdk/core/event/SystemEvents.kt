/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

/**
 * Well-known event names fired on the SDK's internal [EventManager].
 *
 * The constant **NAMES** are `UPPER_SNAKE_CASE` in keeping with Kotlin's
 * `const val` conventions; the constant **VALUES** are the lowercase /
 * dotted strings that the JS SDK emits verbatim, so cross-SDK observer
 * code that subscribes via `sdk.on("config.updated") { … }` (the form
 * shown in JS-SDK examples) works on Android too.
 *
 * ### Cross-SDK parity — JS SDK is authoritative
 *
 * The string values here mirror the JS SDK's `system-events.ts` enum
 * **verbatim**:
 *
 * ```
 * javascript-sdk/packages/enums/src/system-events.ts
 *   READY                    = 'ready'
 *   CONFIG_UPDATED           = 'config.updated'
 *   API_QUEUE_RELEASED       = 'api.queue.released'
 *   BUCKETING                = 'bucketing'
 *   CONVERSION               = 'conversion'
 *   SEGMENTS                 = 'segments'
 *   LOCATION_ACTIVATED       = 'location.activated'
 *   LOCATION_DEACTIVATED     = 'location.deactivated'
 *   AUDIENCES                = 'audiences'
 *   DATA_STORE_QUEUE_RELEASED = 'datastore.queue.released'
 * ```
 *
 * [Source: javascript-sdk/packages/enums/src/system-events.ts:12-22]
 *
 * The JS SDK uses lowercase dotted identifiers (NOT uppercase snake-case).
 * Story 2.4 AC-2 deliberately adopts the JS canon so observer code can
 * share event names verbatim across runtimes. Do not invent new names or
 * capitalisation variants — when a new event is needed, copy the name
 * (and value) from the JS SDK enum.
 *
 * ### Replay semantics
 *
 * [EventManager] deferred-replay (Story 2.4 AC-3) covers only [READY] and
 * [CONFIG_UPDATED]. Every other event fires exactly once per occurrence
 * and is NOT retroactively delivered to late subscribers.
 *
 * ### Visibility
 *
 * Declared `public` so that `:packages:sdk` — which lives in a separate
 * Gradle module and therefore a separate Kotlin `internal` visibility
 * scope — can reference these constants when routing `ConvertSDK.onReady`
 * through the [com.convert.sdk.core.event.EventManager]. The public names
 * also appear in the event payloads consumers observe via `ConvertSDK.on`,
 * so consumers can compare against them directly.
 */
public object SystemEvents {

    /**
     * Fired once when the SDK has a usable configuration in memory —
     * either because direct-data mode seeded [com.convert.sdk.core.data.DataManager]
     * at build time (Story 2.1 AC-3) or because the first config fetch
     * succeeded (Story 2.2). Payload: `{ "environment": String, ... }`.
     *
     * **Replayable** — a late subscriber that registers after READY has
     * fired is delivered the last payload on its next dispatch tick
     * (Story 2.4 AC-3).
     */
    public const val READY: String = "ready"

    /**
     * Fired whenever a subsequent refresh produces a new configuration
     * (Story 2.3 polling loop, or Story 5.3 explicit refresh). Payload
     * contains the new config timestamp.
     *
     * **Replayable** — same semantics as [READY].
     */
    public const val CONFIG_UPDATED: String = "config.updated"

    /**
     * Fired by the event queue after a batch of outbound events has been
     * flushed to the tracking API (Story 5.1/5.2). Payload:
     * `{ "batchSize": Int, "statusCode": Int }`.
     */
    public const val API_QUEUE_RELEASED: String = "api.queue.released"

    /**
     * Fired by the bucketing engine after an experience evaluation
     * (Story 3.2/3.3). Payload: `{ "experienceKey", "variationKey",
     * "visitorId", "tracking": Boolean }`.
     */
    public const val BUCKETING: String = "bucketing"

    /**
     * Fired by the event queue whenever a conversion is recorded
     * (Story 4.2). Payload: `{ "goalKey", "goalData": List<GoalData>? }`.
     */
    public const val CONVERSION: String = "conversion"

    /**
     * Fired when the visitor's segment membership changes as a result of
     * a segment re-evaluation (later-epic feature). Payload:
     * `{ "segmentIds": List<String> }`. Matches the JS SDK's `SEGMENTS`
     * enum member — singular, NOT `SEGMENTS_UPDATED`.
     */
    public const val SEGMENTS: String = "segments"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    public const val LOCATION_ACTIVATED: String = "location.activated"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    public const val LOCATION_DEACTIVATED: String = "location.deactivated"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    public const val AUDIENCES: String = "audiences"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    public const val DATA_STORE_QUEUE_RELEASED: String = "datastore.queue.released"
}
