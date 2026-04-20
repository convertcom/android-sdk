/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.android

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Smoke test for the `packages/sdk` module.
 *
 * Story 1.3 requires at least one test per module so Kover's `koverVerify`
 * has a non-zero coverage baseline to check against — the 0.9.x behaviour on
 * a module with zero tests is version-dependent and occasionally reports
 * `NaN`. This test runs on the JVM via the `testDebugUnitTest` task; it
 * deliberately touches only class references so Robolectric is not needed.
 *
 * Real functional tests arrive with Story 2.1 onwards when `ConvertSDK`
 * gains real behaviour.
 */
internal class SmokeTest {

    @Test
    fun `sdk public types are on the classpath`() {
        assertNotNull(ConvertSDK::class)
        assertNotNull(ConvertContext::class)
        assertNotNull(EventCallback::class)
    }
}
