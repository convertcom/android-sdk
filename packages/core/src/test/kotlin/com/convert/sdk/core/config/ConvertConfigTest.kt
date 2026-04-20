/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.config

import com.convert.sdk.core.model.LogLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConvertConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `convert config serializes and deserializes symmetrically`() {
        val original = ConvertConfig(
            sdkKey = "pk-abc",
            sdkKeySecret = "sk-xyz",
            environment = "prod",
            api = ApiConfig(
                endpoint = ApiEndpoint(
                    config = "https://example.com/config",
                    track = "https://example.com/track",
                ),
            ),
            bucketing = BucketingConfig(
                hashSeed = 1234,
                maxTraffic = 5000,
                excludeExperienceIdHash = true,
            ),
            events = EventsConfig(batchSize = 25, releaseInterval = 2_000L),
            rules = RulesConfig(keysCaseSensitive = true, negation = "!"),
            logger = LoggerConfig(logLevel = LogLevel.INFO),
            network = NetworkConfig(tracking = true, cacheLevel = "default", source = "android"),
            dataRefreshInterval = 60_000L,
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ConvertConfig>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `bucketing config serializes snake_case field names`() {
        val encoded = json.encodeToString(
            BucketingConfig(
                hashSeed = 9999,
                maxTraffic = 10_000,
                excludeExperienceIdHash = false,
            ),
        )
        assertTrue(encoded.contains("\"hash_seed\":9999"), "got: $encoded")
        assertTrue(encoded.contains("\"max_traffic\":10000"), "got: $encoded")
        assertTrue(
            encoded.contains("\"exclude_experience_id_hash\":false"),
            "got: $encoded",
        )
    }

    @Test
    fun `events config serializes snake_case field names`() {
        val encoded = json.encodeToString(EventsConfig(batchSize = 10, releaseInterval = 1_000L))
        assertTrue(encoded.contains("\"batch_size\":10"), "got: $encoded")
        assertTrue(encoded.contains("\"release_interval\":1000"), "got: $encoded")
    }

    @Test
    fun `default constants match verified JS SDK values`() {
        assertEquals(
            "https://cdn-4.convertexperiments.com/api/v1/",
            ConfigDefaults.DEFAULT_CONFIG_ENDPOINT,
        )
        assertEquals(300_000L, ConfigDefaults.DEFAULT_DATA_REFRESH_INTERVAL_MS)
        assertEquals(10, ConfigDefaults.DEFAULT_EVENTS_BATCH_SIZE)
        assertEquals(1_000L, ConfigDefaults.DEFAULT_EVENTS_RELEASE_INTERVAL_MS)
        assertEquals(9999, ConfigDefaults.DEFAULT_BUCKETING_HASH_SEED)
        assertEquals(10_000, ConfigDefaults.DEFAULT_BUCKETING_MAX_TRAFFIC)
        assertEquals(LogLevel.WARN, ConfigDefaults.DEFAULT_LOG_LEVEL)
        assertEquals(true, ConfigDefaults.DEFAULT_TRACKING_ENABLED)
    }
}
