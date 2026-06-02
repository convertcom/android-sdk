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
 * [Source: javascript-sdk/packages/enums/src/system-events.ts:12-22]
 *
 * Story 2.1 actively fires only [READY]; the remaining five lifecycle
 * events ([CONFIG_UPDATED], [BUCKETING], [CONVERSION],
 * [API_QUEUE_RELEASED], plus the [SEGMENTS] / [LOCATION_ACTIVATED] /
 * [LOCATION_DEACTIVATED] / [AUDIENCES] / [DATA_STORE_QUEUE_RELEASED]
 * stubs added for full JS-SDK parity) are declared now so callers
 * (DataManager, ApiManager, BucketingManager, EventQueue introduced in
 * later stories) don't have to stringly-type them. The unused stub
 * constants carry `@Suppress("unused")` until a later story wires the
 * matching fire-site.
 *
 * ### Visibility (Story 2.1)
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
     */
    const val READY: String = "ready"

    /**
     * Fired whenever a subsequent refresh produces a new configuration
     * (Story 2.3 polling loop, or Story 5.3 explicit refresh). Payload
     * contains the new config timestamp.
     */
    const val CONFIG_UPDATED: String = "config.updated"

    /**
     * Fired by the bucketing engine after an experience evaluation
     * (Story 3.2/3.3). Payload: `{ "experienceKey", "variationKey",
     * "visitorId", "tracking": Boolean }`.
     */
    const val BUCKETING: String = "bucketing"

    /**
     * Fired by the event queue whenever a conversion is recorded
     * (Story 4.2). Payload: `{ "goalKey", "goalData": List<GoalData>? }`.
     */
    const val CONVERSION: String = "conversion"

    /**
     * Fired by the event queue after a batch of outbound events has been
     * flushed to the tracking API (Story 5.1/5.2). Payload:
     * `{ "batchSize": Int, "statusCode": Int }`.
     */
    const val API_QUEUE_RELEASED: String = "api.queue.released"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    const val SEGMENTS: String = "segments"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    const val LOCATION_ACTIVATED: String = "location.activated"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    const val LOCATION_DEACTIVATED: String = "location.deactivated"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    const val AUDIENCES: String = "audiences"

    /**
     * JS-SDK parity stub. Fire-site lands in a future story; declared now
     * so callers don't string-type the value.
     */
    @Suppress("unused")
    const val DATA_STORE_QUEUE_RELEASED: String = "datastore.queue.released"
}
