/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Visitor slice of an outbound tracking payload.
 *
 * Mirrors the JS SDK's `Visitor` (`javascript-sdk/packages/types/src/Visitor.ts`).
 * One tracking batch contains one or more [Visitor] entries; each carries
 * the visitor's current segment snapshot plus the events emitted since
 * the previous flush.
 *
 * @property visitorId stable visitor identifier shared across SDKs.
 * @property segments custom segment values snapshotted for this visitor.
 * @property events events recorded for this visitor since the previous
 *   flush; order is preserved.
 */
@Serializable
public data class Visitor(
    val visitorId: String,
    val segments: Map<String, JsonElement>? = null,
    val events: List<TrackingEvent>,
)
