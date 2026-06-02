/*
 * Convert Android SDK — core/config
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Top-level SDK configuration.
 *
 * Mirrors the JS SDK `defaultConfig`
 * (`javascript-sdk/packages/js-sdk/src/config/default.ts`), flattened into a
 * single `@Serializable` data class with nested sub-configs. Every field is
 * nullable or has a default so that a consumer-supplied partial JSON blob
 * deserialises cleanly; the SDK runtime merges missing pieces against
 * [ConfigDefaults] when it reads this object.
 *
 * `data` is typed against [ConfigResponseData], the Kotlin data class
 * auto-generated from the backend Serving OpenAPI spec (Story 1.5) and
 * located in [com.convert.sdk.core.model.generated]. Regenerate via
 * `yarn generateKotlinTypes` in `backend/apiDoc/serving/` and sync to
 * `packages/core/src/main/kotlin/com/convert/sdk/core/model/generated/`.
 *
 * @property sdkKey merchant SDK key used to fetch configuration from the
 *   Convert CDN.
 * @property sdkKeySecret confidential SDK secret; never logged, never
 *   persisted in plaintext (NFR6 — enforced in Story 2.2).
 * @property data pre-fetched configuration blob; when non-null the SDK
 *   skips the initial HTTP fetch.
 * @property environment deployment environment hint
 *   (`"staging"` / `"prod"` / custom). Matches JS SDK default of
 *   `"staging"`.
 * @property api endpoint overrides for the config and tracking services.
 * @property bucketing bucketing-engine tuning knobs.
 * @property dataRefreshInterval polling interval (ms) at which the SDK
 *   refetches its configuration. Defaults to
 *   [ConfigDefaults.DEFAULT_DATA_REFRESH_INTERVAL_MS] (5 min). Non-nullable:
 *   the SDK always schedules a refresh; disabling polling should be
 *   expressed by a sufficiently large interval at the call site.
 * @property events event-queue batching knobs.
 * @property rules rule-engine tuning knobs.
 * @property logger logger configuration.
 * @property network networking behaviour (tracking on/off, cache level).
 * @property mapper JS-SDK FR2 parity hook (`mapper`). Opaque consumer-
 *   supplied object (callable, transformer, table, …) that future
 *   bucketing / response-mapping stories will route data through. Stored
 *   on this config for parity with the JS SDK config surface; not
 *   serialized (`@Transient`) because it has no JSON shape, and not
 *   read by any Story 2.1 code path. Wiring lands in a later story.
 */
@Serializable
public data class ConvertConfig(
    val sdkKey: String? = null,
    val sdkKeySecret: String? = null,
    val data: ConfigResponseData? = null,
    val environment: String = "staging",
    val api: ApiConfig? = null,
    val bucketing: BucketingConfig? = null,
    val dataRefreshInterval: Long = ConfigDefaults.DEFAULT_DATA_REFRESH_INTERVAL_MS,
    val events: EventsConfig? = null,
    val rules: RulesConfig? = null,
    val logger: LoggerConfig? = null,
    val network: NetworkConfig? = null,
    @Transient
    val mapper: Any? = null,
) {
    /**
     * Redacts [sdkKeySecret] from the auto-generated `toString()` output.
     *
     * Story 2.1 AC-5 / NFR6: the SDK secret must never appear in logs,
     * stack traces, or any rendered representation of this config.
     * Kotlin's data-class-synthesised toString dumps every field verbatim,
     * so we override it to substitute `[REDACTED]` whenever the secret
     * is non-null. All other fields remain visible so operational logs
     * are still useful.
     */
    override fun toString(): String = buildString {
        append("ConvertConfig(")
        append("sdkKey=").append(sdkKey)
        if (sdkKeySecret != null) {
            append(", sdkKeySecret=[REDACTED]")
        } else {
            append(", sdkKeySecret=null")
        }
        append(", data=").append(data)
        append(", environment=").append(environment)
        append(", api=").append(api)
        append(", bucketing=").append(bucketing)
        append(", dataRefreshInterval=").append(dataRefreshInterval)
        append(", events=").append(events)
        append(", rules=").append(rules)
        append(", logger=").append(logger)
        append(", network=").append(network)
        // Render mapper as a presence marker only — the consumer-supplied
        // object may be a callable closure capturing arbitrary state we
        // don't want spilled into logs.
        append(", mapper=").append(if (mapper != null) "[set]" else "null")
        append(")")
    }
}

/**
 * Endpoint overrides for the HTTP-facing SDK services.
 *
 * @property endpoint URL overrides for the config and tracking endpoints.
 */
@Serializable
public data class ApiConfig(
    val endpoint: ApiEndpoint? = null,
)

/**
 * Individual HTTP endpoint URLs.
 *
 * The tracking endpoint accepts a `[project_id]` template literal that the
 * runtime substitutes with the merchant project id before use.
 *
 * @property config override for the configuration-fetch endpoint.
 * @property track override for the event-tracking endpoint.
 */
@Serializable
public data class ApiEndpoint(
    val config: String? = null,
    val track: String? = null,
)

/**
 * Bucketing-engine tuning knobs.
 *
 * All fields are nullable so that consumer-supplied partial overrides can
 * be merged against [ConfigDefaults] at runtime.
 *
 * @property hashSeed MurmurHash3 seed; must match the JS SDK (`9999`) for
 *   cross-SDK bucket compatibility.
 * @property maxTraffic total basis points across all variations in a
 *   bucketing wheel; JS SDK uses `10_000`.
 * @property excludeExperienceIdHash opt-out flag used by legacy accounts
 *   whose bucketing was defined before experience ids were included in
 *   the hash input.
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
 * Event-queue batching knobs.
 *
 * @property batchSize maximum events flushed per HTTP request.
 * @property releaseInterval minimum interval (ms) between flushes.
 */
@Serializable
public data class EventsConfig(
    @SerialName("batch_size")
    val batchSize: Int? = null,
    @SerialName("release_interval")
    val releaseInterval: Long? = null,
)

/**
 * Rule-engine tuning knobs.
 *
 * @property keysCaseSensitive when `true`, rule key comparisons are
 *   case-sensitive. JS SDK default is `true`.
 * @property negation default negation semantics for rule matching; `null`
 *   falls back to the JS SDK's runtime default.
 * @property comparisonProcessor JS-SDK FR2 parity hook
 *   (`rules.comparisonProcessor`). String identifier of the comparison
 *   processor implementation the rule engine should use; `null` selects
 *   the default. The Kotlin rule engine (Story 3.4+) consumes this; in
 *   Story 2.1 it is captured for parity but not yet read.
 */
@Serializable
public data class RulesConfig(
    @SerialName("keys_case_sensitive")
    val keysCaseSensitive: Boolean? = null,
    val negation: String? = null,
    @SerialName("comparison_processor")
    val comparisonProcessor: String? = null,
)

/**
 * Logger configuration.
 *
 * @property logLevel minimum severity at which log entries are emitted.
 */
@Serializable
public data class LoggerConfig(
    val logLevel: LogLevel? = null,
)

/**
 * Networking behaviour flags.
 *
 * @property tracking enables outbound tracking requests. When `false` the
 *   SDK still performs bucketing locally but never calls the tracking
 *   endpoint.
 * @property cacheLevel HTTP cache directive hint
 *   (`"default"` / `"low"`). `"low"` is intended for development only.
 * @property source identifier appended to tracking payloads; set by
 *   platform-specific adapters (e.g. `"android"`, `"web"`).
 */
@Serializable
public data class NetworkConfig(
    val tracking: Boolean? = null,
    val cacheLevel: String? = null,
    val source: String? = null,
)
