/*
 * Convert Android SDK — core/config tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.config

/**
 * Story 2.1 AC-5 / NFR6: verify `ConvertConfig.toString()` redacts
 * `sdkKeySecret` so the value never leaks into logs or stack traces.
 *
 * Kotlin's auto-generated `data class toString()` dumps every property
 * verbatim — the override must replace the secret with `[REDACTED]` when
 * set, and omit it cleanly when null.
 */

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConvertConfigToStringTest {

    @Test
    fun `toString redacts sdkKeySecret when set`() {
        val config = ConvertConfig(
            sdkKey = "pub-key",
            sdkKeySecret = "super-secret-value-xyz",
        )

        val rendered = config.toString()

        assertFalse(
            rendered.contains("super-secret-value-xyz"),
            "secret value must not appear in toString: $rendered",
        )
        assertTrue(
            rendered.contains("[REDACTED]"),
            "toString must indicate redaction occurred: $rendered",
        )
    }

    @Test
    fun `toString redacts debugToken when set (qs-02 AND-1 AC3)`() {
        // qs-02 AND-1 contract §1 / AC3: the debug token must never appear
        // in clear in any rendered representation of this config — mirrors
        // the sdkKeySecret redaction above. RED phase: ConvertConfig's
        // toString() currently passes debugToken through unredacted (see
        // the TODO(AND-1 GREEN) marker in ConvertConfig.kt), so this fails
        // on both assertions until GREEN wires the redaction.
        val config = ConvertConfig(
            sdkKey = "pub-key",
            debugToken = "debug-token-canary-9999",
        )

        val rendered = config.toString()

        assertFalse(
            rendered.contains("debug-token-canary-9999"),
            "debug token value must not appear in toString: $rendered",
        )
        assertTrue(
            rendered.contains("debugToken=[REDACTED]"),
            "toString must indicate debugToken redaction occurred: $rendered",
        )
    }

    @Test
    fun `toString includes non-secret fields verbatim`() {
        val config = ConvertConfig(
            sdkKey = "pub-key",
            sdkKeySecret = "hide-me",
            environment = "prod",
        )

        val rendered = config.toString()

        assertTrue(rendered.contains("pub-key"), "sdkKey should be visible: $rendered")
        assertTrue(rendered.contains("prod"), "environment should be visible: $rendered")
    }

    @Test
    fun `toString when secret is null does not contain REDACTED marker`() {
        val config = ConvertConfig(sdkKey = "pub-key")

        val rendered = config.toString()

        assertFalse(
            rendered.contains("[REDACTED]"),
            "no redaction marker when secret is null: $rendered",
        )
    }

    @Test
    fun `toString contains ConvertConfig class name`() {
        val rendered = ConvertConfig().toString()
        assertTrue(rendered.contains("ConvertConfig"), "expected ConvertConfig: $rendered")
    }
}
