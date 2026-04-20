/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Per-visitor persisted state.
 *
 * Serializes the subset of state the SDK writes to [com.convert.sdk.core.port.DataStore]
 * so it can restore cached bucketing decisions, visited locations, segments,
 * and conversion goals across process launches.
 *
 * @property bucketing experience-key → variation-id map of cached bucketing.
 * @property locations list of location identifiers the visitor has matched.
 * @property segments loosely-typed segment attributes, matching the JS SDK
 *  `VisitorSegments` shape.
 * @property goals goal-key → tracked flag map, used to suppress duplicate
 *  conversions.
 */
@Serializable
public data class StoreData(
    val bucketing: Map<String, String>? = null,
    val locations: List<String>? = null,
    val segments: Map<String, JsonElement>? = null,
    val goals: Map<String, Boolean>? = null,
)
