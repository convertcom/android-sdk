/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal representation of a queued tracking event.
 *
 * This is NOT the full outbound tracking payload — that lands in Story
 * 5.1 as a sealed hierarchy (`BucketingEvent`, `ConversionEvent`,
 * `TransactionEvent`) wrapped in a batch envelope. For now the
 * [com.convert.sdk.core.port.EventQueue] stores these flat records until
 * the batcher is ready to reshape them into the wire format.
 *
 * @property eventType well-known event-type string
 *   (`"viewExp"`, `"hitGoal"`, `"tr"`, …) matching the JS SDK discriminant.
 * @property data event-specific payload; loosely typed so that the
 *   serializer does not need per-event-type schemas.
 * @property timestamp epoch-millis at which the event was recorded on
 *   the client.
 */
@Serializable
public data class TrackingEvent(
    val eventType: String,
    val data: Map<String, JsonElement>,
    val timestamp: Long,
)
