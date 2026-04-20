/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.ConfigResponseData
import com.convert.sdk.core.model.LogLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level SDK configuration.
 *
 * Consumers never build this type directly — the `ConvertSDK.Builder` in
 * `packages/sdk` assembles it from fluent setters. It is declared as
 * `@Serializable` so it can be snapshotted (for debugging or test fixtures)
 * and round-tripped through JSON without reflection.
 *
 * @property sdkKey public SDK key for this project.
 * @property sdkKeySecret secret SDK key — never logged, never persisted in
 *  plaintext (enforced from Story 2.2 onward).
 * @property data optional preloaded configuration, used when the caller
 *  bypasses the remote fetch.
 * @property environment backend environment identifier (e.g., `"prod"`).
 * @property api optional endpoint overrides.
 * @property bucketing optional bucketing-engine tuning knobs.
 * @property dataRefreshInterval how often to re-fetch config, in milliseconds.
 * @property events event batching configuration.
 * @property rules rule-engine configuration.
 * @property logger logger configuration.
 * @property network network-adapter configuration.
 */
@Serializable
public data class ConvertConfig(
    val sdkKey: String? = null,
    val sdkKeySecret: String? = null,
    val data: ConfigResponseData? = null,
    val environment: String = "prod",
    val api: ApiConfig? = null,
    val bucketing: BucketingConfig? = null,
    val dataRefreshInterval: Long = ConfigDefaults.DEFAULT_DATA_REFRESH_INTERVAL_MS,
    val events: EventsConfig? = null,
    val rules: RulesConfig? = null,
    val logger: LoggerConfig? = null,
    val network: NetworkConfig? = null,
)

/**
 * API endpoint configuration.
 *
 * @property endpoint optional endpoint overrides.
 */
@Serializable
public data class ApiConfig(
    val endpoint: ApiEndpoint? = null,
)

/**
 * Endpoint URLs for the config-fetch and tracking services.
 *
 * @property config fully-qualified URL for the config endpoint.
 * @property track fully-qualified URL for the tracking endpoint.
 */
@Serializable
public data class ApiEndpoint(
    val config: String? = null,
    val track: String? = null,
)

/**
 * Tuning knobs for the bucketing engine.
 *
 * @property hashSeed seed passed to the bucketing hash function.
 * @property maxTraffic maximum traffic value representing 100%.
 * @property excludeExperienceIdHash when `true`, omits the experience id from
 *  the hash input.
 */
@Serializable
public data class BucketingConfig(
    @SerialName("hash_seed")
    val hashSeed: Int? = null,
    @SerialName("max_traffic")
    val maxTraffic: Int? = null,
    @SerialName("exclude_experience_id_hash")
    val excludeExperienceIdHash: Boolean? = null,
)

/**
 * Event batching configuration.
 *
 * @property batchSize number of events uploaded per request.
 * @property releaseInterval how long to wait before flushing a partial batch,
 *  in milliseconds.
 */
@Serializable
public data class EventsConfig(
    @SerialName("batch_size")
    val batchSize: Int? = null,
    @SerialName("release_interval")
    val releaseInterval: Long? = null,
)

/**
 * Rule-engine configuration.
 *
 * @property keysCaseSensitive when `true`, rule keys are compared
 *  case-sensitively.
 * @property negation optional override for the negation token.
 */
@Serializable
public data class RulesConfig(
    @SerialName("keys_case_sensitive")
    val keysCaseSensitive: Boolean? = null,
    val negation: String? = null,
)

/**
 * Logger configuration.
 *
 * @property logLevel verbosity floor for SDK log messages.
 */
@Serializable
public data class LoggerConfig(
    val logLevel: LogLevel? = null,
)

/**
 * Network-adapter configuration.
 *
 * @property tracking when `false`, disables outbound tracking.
 * @property cacheLevel cache aggressiveness level (e.g., `"default"`, `"low"`).
 * @property source optional source identifier forwarded to the backend.
 */
@Serializable
public data class NetworkConfig(
    val tracking: Boolean? = null,
    val cacheLevel: String? = null,
    val source: String? = null,
)
