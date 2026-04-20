/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

/**
 * Well-known event names fired on the SDK's internal [EventManager].
 *
 * These mirror the JS SDK's system event catalogue so that cross-SDK
 * observer code can share event names verbatim. Only [READY] is actively
 * used in Story 2.1; the rest are declared now so callers (DataManager,
 * ApiManager, BucketingManager, EventQueue — introduced in later stories)
 * don't have to stringly-type them.
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
    const val READY: String = "READY"

    /**
     * Fired whenever a subsequent refresh produces a new configuration
     * (Story 2.3 polling loop, or Story 5.3 explicit refresh). Payload
     * contains the new config timestamp.
     */
    const val CONFIG_UPDATED: String = "CONFIG_UPDATED"

    /**
     * Fired by the bucketing engine after an experience evaluation
     * (Story 3.2/3.3). Payload: `{ "experienceKey", "variationKey",
     * "visitorId", "tracking": Boolean }`.
     */
    const val BUCKETING: String = "BUCKETING"

    /**
     * Fired by the event queue whenever a conversion is recorded
     * (Story 4.2). Payload: `{ "goalKey", "goalData": List<GoalData>? }`.
     */
    const val CONVERSION: String = "CONVERSION"

    /**
     * Fired by the event queue after a batch of outbound events has been
     * flushed to the tracking API (Story 5.1/5.2). Payload:
     * `{ "batchSize": Int, "statusCode": Int }`.
     */
    const val API_QUEUE_RELEASED: String = "API_QUEUE_RELEASED"
}
