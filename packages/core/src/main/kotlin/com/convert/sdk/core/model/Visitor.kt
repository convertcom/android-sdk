/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The per-visitor element of the outbound tracking payload.
 *
 * Mirrors the JS SDK `Visitor` type — a visitor identifier, an optional
 * loosely-typed segment map, and the list of events captured for that
 * visitor.
 *
 * @property visitorId stable identifier for the visitor.
 * @property segments loosely-typed segment attributes.
 * @property events the list of [TrackingEvent]s attributed to this visitor.
 */
@Serializable
public data class Visitor(
    @SerialName("visitorId")
    val visitorId: String,
    val segments: Map<String, JsonElement>? = null,
    val events: List<TrackingEvent>,
)
