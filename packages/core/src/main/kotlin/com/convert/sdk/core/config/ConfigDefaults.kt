/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.LogLevel

/**
 * Compile-time default values for [ConvertConfig].
 *
 * The SDK resolves a missing configuration field to the corresponding constant
 * here. Values are verified against the JS SDK sources at
 * `javascript-sdk/packages/js-sdk/src/config/default.ts`,
 * `javascript-sdk/packages/js-sdk/src/config.ts`,
 * `javascript-sdk/generate-rollup-config.mjs`, and
 * `javascript-sdk/.env.example`.
 *
 * See the Dev Agent Record in Story 1.2 for deviations from the original
 * story constants.
 */
internal object ConfigDefaults {

    /**
     * Default config endpoint.
     *
     * Verified against `javascript-sdk/generate-rollup-config.mjs` (line 51)
     * and `javascript-sdk/.env.example` (line 12). The story file cited
     * `https://config-api.convertexperiments.com/v1/config`; the actual
     * JS SDK default is `https://cdn-4.convertexperiments.com/api/v1/`.
     * Using the verified JS SDK value.
     */
    const val DEFAULT_CONFIG_ENDPOINT: String = "https://cdn-4.convertexperiments.com/api/v1/"

    /**
     * Default tracking endpoint template.
     *
     * Verified against `javascript-sdk/.env.example` (line 13). The JS SDK
     * uses `https://[project_id].metrics.convertexperiments.com/v1/` — the
     * `[project_id]` placeholder is substituted at runtime. The story file
     * cited `https://logger.convertexperiments.com/track`; using the
     * verified JS SDK template.
     */
    const val DEFAULT_TRACK_ENDPOINT: String =
        "https://[project_id].metrics.convertexperiments.com/v1/"

    /**
     * Default interval between config refreshes, in milliseconds.
     *
     * Verified against `javascript-sdk/packages/js-sdk/src/config/default.ts`
     * (line 23) and `javascript-sdk/packages/js-sdk/src/core.ts` (line 23):
     * `300000` ms (5 minutes). The story file cited `30_000L`; using the
     * verified JS SDK value of 300_000L.
     */
    const val DEFAULT_DATA_REFRESH_INTERVAL_MS: Long = 300_000L

    /** Default number of events per outbound batch. Verified: JS SDK `batch_size = 10`. */
    const val DEFAULT_EVENTS_BATCH_SIZE: Int = 10

    /**
     * Default interval between partial-batch flushes, in milliseconds.
     *
     * Verified against `javascript-sdk/packages/js-sdk/src/config/default.ts`
     * (line 26): `1000` ms. The story file cited `10_000L`; using the
     * verified JS SDK value of 1_000L.
     */
    const val DEFAULT_EVENTS_RELEASE_INTERVAL_MS: Long = 1_000L

    /** Bucketing hash seed. Verified: JS SDK `hash_seed = 9999`. */
    const val DEFAULT_BUCKETING_HASH_SEED: Int = 9999

    /** Bucketing traffic ceiling representing 100%. Verified: JS SDK `max_traffic = 10000`. */
    const val DEFAULT_BUCKETING_MAX_TRAFFIC: Int = 10_000

    /**
     * Default verbosity.
     *
     * Verified against `javascript-sdk/packages/js-sdk/src/config.ts`
     * (`DEFAULT_LOGGER_SETTINGS`): `LogLevel.WARN` is applied on top of the
     * defaults file, so `WARN` is the effective default.
     */
    val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.WARN

    /** Whether outbound tracking is enabled by default. Verified: JS SDK `tracking = true`. */
    const val DEFAULT_TRACKING_ENABLED: Boolean = true
}
