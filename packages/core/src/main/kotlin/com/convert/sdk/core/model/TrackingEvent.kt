/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy of tracking event types, matching the JS SDK wire contract
 * (`javascript-sdk/packages/types/src/config/types.gen.ts:2742-2803`).
 *
 * Story 5.1 AC-5 refactors the flat `TrackingEvent(eventType, data, timestamp)`
 * record (Story 1.2) into this sealed class. The JS SDK defines exactly two
 * event types — `"bucketing"` and `"conversion"` — and transactions fold into
 * [ConversionEvent] via the `goalData` list. There is no separate
 * `TransactionEvent` class.
 *
 * The `classDiscriminator` must be set to `"eventType"` on the shared
 * [kotlinx.serialization.json.Json] instance so polymorphic serialization
 * uses `"eventType"` as the discriminant key.
 *
 * [BucketingEvent] has NO `timestamp` field — the JS SDK event schema does not
 * include a timestamp on the event objects themselves.
 */
@Serializable
public sealed class TrackingEvent {
    public abstract val eventType: String
}

/**
 * A bucketing event emitted when a visitor is assigned to an experience
 * variation.
 *
 * Wire form:
 * ```json
 * { "eventType": "bucketing", "data": { "experienceId": "...", "variationId": "..." } }
 * ```
 *
 * @property experienceId the stable identifier of the experience.
 * @property variationId the stable identifier of the variation assigned.
 */
@Serializable
@SerialName("bucketing")
public data class BucketingEvent(
    val experienceId: String,
    val variationId: String,
) : TrackingEvent() {
    override val eventType: String get() = "bucketing"
}

/**
 * A conversion event emitted when a visitor triggers a goal.
 *
 * Wire form (bare conversion — no revenue):
 * ```json
 * { "eventType": "conversion", "data": { "goalId": "..." } }
 * ```
 *
 * Wire form (transaction / revenue conversion):
 * ```json
 * { "eventType": "conversion", "data": { "goalId": "...",
 *   "goalData": [{ "key": "amount", "value": 49.99 }, ...] } }
 * ```
 *
 * There is NO separate `TransactionEvent` class — transactions are
 * conversion events whose `goalData` carries `amount`, `productsCount`,
 * and/or `transactionId` entries.
 *
 * @property goalId the stable identifier of the conversion goal.
 * @property goalData optional list of data entries (revenue, transaction
 *   metadata, custom dimensions). Null or empty → bare conversion hit.
 * @property bucketingData optional map of bucketing metadata; may be
 *   omitted on the wire when null.
 */
@Serializable
@SerialName("conversion")
public data class ConversionEvent(
    val goalId: String,
    val goalData: List<GoalData>? = null,
    val bucketingData: Map<String, String>? = null,
) : TrackingEvent() {
    override val eventType: String get() = "conversion"
}
