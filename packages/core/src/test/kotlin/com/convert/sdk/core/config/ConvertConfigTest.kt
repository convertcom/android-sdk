/*
 * Convert Android SDK — core/config tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.LogLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConvertConfigTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `convert config round-trips with all nested sub-configs populated`() {
        val original = ConvertConfig(
            sdkKey = "sdk_abc",
            sdkKeySecret = "secret_xyz",
            environment = "prod",
            api = ApiConfig(
                endpoint = ApiEndpoint(
                    config = "https://example.test/config",
                    track = "https://example.test/track",
                ),
            ),
            bucketing = BucketingConfig(
                hashSeed = 1234,
                maxTraffic = 10_000,
                excludeExperienceIdHash = true,
            ),
            dataRefreshInterval = 60_000L,
            events = EventsConfig(
                batchSize = 25,
                releaseInterval = 5_000L,
            ),
            rules = RulesConfig(
                keysCaseSensitive = false,
                negation = "not",
            ),
            logger = LoggerConfig(logLevel = LogLevel.INFO),
            network = NetworkConfig(
                tracking = true,
                cacheLevel = "low",
                source = "android",
            ),
        )

        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<ConvertConfig>(encoded)

        assertEquals(original, restored)
    }

    @Test
    fun `convert config round-trips with only required fields`() {
        val original = ConvertConfig()

        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<ConvertConfig>(encoded)

        assertEquals(original, restored)
    }

    @Test
    fun `bucketing config serializes hash_seed using snake_case`() {
        val bucketing = BucketingConfig(hashSeed = 9999, maxTraffic = 10_000)

        val encoded = json.encodeToString(bucketing)

        assertTrue(
            encoded.contains("\"hash_seed\":9999"),
            "Expected snake_case hash_seed in JSON, got: $encoded",
        )
        assertTrue(
            encoded.contains("\"max_traffic\":10000"),
            "Expected snake_case max_traffic in JSON, got: $encoded",
        )
    }

    @Test
    fun `events config serializes batch_size and release_interval using snake_case`() {
        val events = EventsConfig(batchSize = 10, releaseInterval = 1_000L)

        val encoded = json.encodeToString(events)

        assertTrue(
            encoded.contains("\"batch_size\":10"),
            "Expected snake_case batch_size in JSON, got: $encoded",
        )
        assertTrue(
            encoded.contains("\"release_interval\":1000"),
            "Expected snake_case release_interval in JSON, got: $encoded",
        )
    }

    @Test
    fun `rules config serializes keys_case_sensitive using snake_case`() {
        val rules = RulesConfig(keysCaseSensitive = true)

        val encoded = json.encodeToString(rules)

        assertTrue(
            encoded.contains("\"keys_case_sensitive\":true"),
            "Expected snake_case keys_case_sensitive in JSON, got: $encoded",
        )
    }

    @Test
    fun `config defaults expose verified JS SDK values`() {
        // Values sourced from javascript-sdk/packages/js-sdk/src/config/default.ts
        // and generate-rollup-config.mjs (verified 2026-04-20).
        assertEquals(
            "https://cdn-4.convertexperiments.com/api/v1/",
            ConfigDefaults.DEFAULT_CONFIG_ENDPOINT,
        )
        assertEquals(
            "https://[project_id].metrics.convertexperiments.com/v1/",
            ConfigDefaults.DEFAULT_TRACK_ENDPOINT,
        )
        assertEquals(300_000L, ConfigDefaults.DEFAULT_DATA_REFRESH_INTERVAL_MS)
        assertEquals(10, ConfigDefaults.DEFAULT_EVENTS_BATCH_SIZE)
        assertEquals(1_000L, ConfigDefaults.DEFAULT_EVENTS_RELEASE_INTERVAL_MS)
        assertEquals(9999, ConfigDefaults.DEFAULT_BUCKETING_HASH_SEED)
        assertEquals(10_000, ConfigDefaults.DEFAULT_BUCKETING_MAX_TRAFFIC)
        assertEquals(LogLevel.DEBUG, ConfigDefaults.DEFAULT_LOG_LEVEL)
        assertEquals(true, ConfigDefaults.DEFAULT_TRACKING_ENABLED)
        assertEquals("default", ConfigDefaults.DEFAULT_CACHE_LEVEL)
    }

    @Test
    fun `default convert config uses staging environment and 5 minute refresh`() {
        val cfg = ConvertConfig()

        assertEquals("staging", cfg.environment)
        assertEquals(300_000L, cfg.dataRefreshInterval)
    }
}
