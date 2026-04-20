/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance used by round-trip tests. The configuration mirrors
 * what the SDK will eventually use for inbound config decoding — lenient on
 * unknown keys, compact on output.
 */
internal val testJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
