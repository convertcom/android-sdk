/*
 * Convert Android SDK — core/config
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.LogLevel

/**
 * Compile-time defaults sourced from the JavaScript SDK reference implementation.
 *
 * Values here MUST match the JS SDK runtime defaults so that an Android
 * consumer omitting overrides ends up bucketing and reporting identically
 * to a JS consumer with the same merchant credentials.
 *
 * Reference sources (verified 2026-04-20):
 * - `javascript-sdk/packages/js-sdk/src/config/default.ts` — environment,
 *   refresh interval, batching, bucketing, logger, network defaults.
 * - `javascript-sdk/.env.example` and `generate-rollup-config.mjs` —
 *   template URLs injected into the bundle at build time as
 *   `process.env.CONFIG_ENDPOINT` / `process.env.TRACK_ENDPOINT`.
 *
 * Deviations from the story spec's `AC-3` placeholders (which were
 * described as "to verify"):
 * - `DEFAULT_CONFIG_ENDPOINT` uses the CDN host rather than the story's
 *   illustrative `config-api.convertexperiments.com` — the real JS SDK
 *   ships `https://cdn-4.convertexperiments.com/api/v1/`.
 * - `DEFAULT_TRACK_ENDPOINT` contains the `[project_id]` placeholder the
 *   JS SDK uses; runtime substitution happens once the project id is
 *   known.
 * - `DEFAULT_DATA_REFRESH_INTERVAL_MS` is 5 minutes (`300_000L`) to match
 *   the JS SDK's `dataRefreshInterval: 300000`.
 * - `DEFAULT_EVENTS_RELEASE_INTERVAL_MS` is 1 second (`1_000L`) to match
 *   the JS SDK's `events.release_interval: 1000`.
 * - `DEFAULT_LOG_LEVEL` is `DEBUG` to match the JS SDK's
 *   `logger.logLevel: LogLevel.DEBUG`.
 * - `DEFAULT_CACHE_LEVEL` is `"default"` per the JS SDK's
 *   `network.cacheLevel: 'default'`.
 */
internal object ConfigDefaults {

    /**
     * Configuration-fetch endpoint. The JS SDK bundles this value from
     * `process.env.CONFIG_ENDPOINT` at build time; the resolved URL is
     * the one shipped by the public rollup pipeline.
     */
    const val DEFAULT_CONFIG_ENDPOINT: String =
        "https://cdn-4.convertexperiments.com/api/v1/"

    /**
     * Tracking-event endpoint template. The literal `[project_id]`
     * substring is replaced with the merchant project id at runtime once
     * the SDK has loaded its configuration.
     */
    const val DEFAULT_TRACK_ENDPOINT: String =
        "https://[project_id].metrics.convertexperiments.com/v1/"

    /**
     * Polling interval (ms) at which the SDK refetches its configuration.
     * JS SDK default: 5 minutes.
     */
    const val DEFAULT_DATA_REFRESH_INTERVAL_MS: Long = 300_000L

    /** Maximum number of events flushed per tracking HTTP request. */
    const val DEFAULT_EVENTS_BATCH_SIZE: Int = 10

    /**
     * Minimum interval (ms) between tracking flushes. JS SDK default:
     * 1 second.
     */
    const val DEFAULT_EVENTS_RELEASE_INTERVAL_MS: Long = 1_000L

    /** MurmurHash3 seed; locked to the JS SDK value for bucket parity. */
    const val DEFAULT_BUCKETING_HASH_SEED: Int = 9999

    /** Total traffic basis points across all variations in a wheel. */
    const val DEFAULT_BUCKETING_MAX_TRAFFIC: Int = 10_000

    /** Default minimum log severity (JS SDK default: DEBUG). */
    val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.DEBUG

    /** Whether outbound tracking is enabled by default. */
    const val DEFAULT_TRACKING_ENABLED: Boolean = true

    /**
     * HTTP cache directive hint. `"default"` matches the JS SDK;
     * `"low"` is intended only for local development.
     */
    const val DEFAULT_CACHE_LEVEL: String = "default"
}
