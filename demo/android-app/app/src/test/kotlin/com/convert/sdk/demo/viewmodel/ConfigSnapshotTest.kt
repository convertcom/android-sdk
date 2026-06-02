/*
 * Convert Android SDK Demo App — ConfigSnapshot unit tests (Story 7.6 DEMO-1)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Story 7.6 AC-5 — maskedKey helper verifies the "first 8 chars + '...'"
 * literal. Short keys (<= 8 chars) fall back to the raw key verbatim so
 * the panel never renders "..." for a 3-character placeholder like
 * "x" — that would be actively misleading.
 */
class ConfigSnapshotTest {

    @Test
    fun `maskedKey returns first 8 chars with ellipsis for long key`() {
        val snap = ConfigSnapshot(
            sdkKey = "abcdef12-3456-7890-abcd-ef1234567890",
            environment = "production",
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = true,
        )
        assertEquals("abcdef12...", snap.maskedKey)
    }

    @Test
    fun `maskedKey returns raw key for key with length equal to 8`() {
        val snap = ConfigSnapshot(
            sdkKey = "12345678",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
        // <= 8 chars → no masking (nothing to mask).
        assertEquals("12345678", snap.maskedKey)
    }

    @Test
    fun `maskedKey returns raw key for short key below 8 chars`() {
        val snap = ConfigSnapshot(
            sdkKey = "demo",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
        assertEquals("demo", snap.maskedKey)
    }

    @Test
    fun `maskedKey returns empty string for empty key`() {
        val snap = ConfigSnapshot(
            sdkKey = "",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
        assertEquals("", snap.maskedKey)
    }

    @Test
    fun `maskedKey hides 9 char key correctly`() {
        val snap = ConfigSnapshot(
            sdkKey = "abcdefghi",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
        // 9 chars → first 8 + "..." → "abcdefgh..."
        assertEquals("abcdefgh...", snap.maskedKey)
    }

    @Test
    fun `copy preserves all fields`() {
        val snap = ConfigSnapshot(
            sdkKey = "abcdef12-3456",
            environment = "staging",
            experienceKeys = listOf("exp-1", "exp-2"),
            featureKeys = listOf("feat-a"),
            trackingEnabled = true,
        )
        val copy = snap.copy(environment = "production")
        assertEquals("abcdef12-3456", copy.sdkKey)
        assertEquals("production", copy.environment)
        assertEquals(listOf("exp-1", "exp-2"), copy.experienceKeys)
        assertEquals(listOf("feat-a"), copy.featureKeys)
        assertEquals(true, copy.trackingEnabled)
    }
}
