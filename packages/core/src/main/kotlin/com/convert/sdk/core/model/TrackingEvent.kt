/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single event enqueued for upload to the tracking endpoint.
 *
 * This is a deliberately minimal, flat data class — the full sealed hierarchy
 * (`BucketingEvent`, `ConversionEvent`, `TransactionEvent`) lands in Story 5.1
 * when the batching logic is implemented. For this story, `eventType` is the
 * discriminator (`"viewExp"`, `"hitGoup"`, `"tr"` …) and `data` carries the
 * event payload.
 *
 * @property eventType discriminator matching the tracking API's event type.
 * @property data loosely-typed event payload.
 * @property timestamp millisecond timestamp at which the event was captured.
 */
@Serializable
public data class TrackingEvent(
    val eventType: String,
    val data: Map<String, JsonElement>,
    val timestamp: Long,
)
