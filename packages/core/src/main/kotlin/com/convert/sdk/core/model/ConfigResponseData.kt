/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 *
 * Placeholder until Story 1.5 (OpenAPI type generation) — do not add
 * fields manually. Story 1.5 replaces this stub with the generated type
 * covering experiences, features, goals, audiences, rules, and
 * integrations; every reference (`ConvertConfig.data`, the builder
 * `.data(...)` setter) continues to compile because the type name is
 * preserved.
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Skeleton representation of the Convert config-API response body.
 *
 * Story 1.5 replaces this class with a generated type derived from the
 * backend OpenAPI spec. Until then the raw response is captured as a
 * single [JsonElement] so existing callers can still pass a pre-fetched
 * config blob through [com.convert.sdk.core.config.ConvertConfig].
 *
 * @property rawJson opaque JSON captured from the config API; `null` when
 *   the SDK has not yet loaded a configuration.
 */
@Serializable
public data class ConfigResponseData(
    val rawJson: JsonElement? = null,
)
