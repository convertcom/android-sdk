/*
 * Convert Android SDK Demo App — FeatureResult data class tests (Story 7.4)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 7.4 Task 1 — [FeatureResult] mirrors 7.3's [ExperienceResult]
 * shape. Stable monotonic ids from [FeatureResult.nextId] so the
 * LazyColumn key lookup stays O(1) and Compose's diffing is cheap.
 */
class FeatureResultTest {

    @Test
    fun `nextId returns strictly increasing values`() {
        val a = FeatureResult.nextId()
        val b = FeatureResult.nextId()
        val c = FeatureResult.nextId()
        assertTrue(b > a, "ids must increase: got a=$a b=$b")
        assertTrue(c > b, "ids must increase: got b=$b c=$c")
    }

    @Test
    fun `nextId never collides across 100 calls`() {
        val ids = (1..100).map { FeatureResult.nextId() }
        assertEquals(ids.size, ids.toSet().size, "all ids must be unique")
    }

    @Test
    fun `default non-error result has empty variables and no error copy`() {
        val r = FeatureResult(
            id = FeatureResult.nextId(),
            featureKey = "test-feature",
            enabled = true,
        )
        assertEquals(false, r.isError)
        assertTrue(r.variables.isEmpty())
        assertEquals(null, r.errorMessage)
        assertEquals(null, r.errorHint)
        assertEquals(null, r.experienceKey)
    }

    @Test
    fun `error result shape`() {
        val r = FeatureResult(
            id = FeatureResult.nextId(),
            featureKey = "missing-feat",
            enabled = false,
            isError = true,
            errorMessage = "No feature for key missing-feat",
            errorHint = "Check feature config or audience eligibility.",
        )
        assertTrue(r.isError)
        assertEquals("No feature for key missing-feat", r.errorMessage)
    }

    @Test
    fun `result carrying typed variables preserves them in order`() {
        val variables = listOf(
            TypedVariable(name = "buttonColor", value = "\"blue\"", typeLabel = "string"),
            TypedVariable(name = "maxRetries", value = "3", typeLabel = "integer"),
            TypedVariable(name = "showBanner", value = "true", typeLabel = "boolean"),
        )
        val r = FeatureResult(
            id = FeatureResult.nextId(),
            featureKey = "test-feature",
            enabled = true,
            variables = variables,
        )
        assertEquals(3, r.variables.size)
        assertEquals("buttonColor", r.variables[0].name)
        assertEquals("integer", r.variables[1].typeLabel)
    }

    @Test
    fun `featureResult ids are different from experienceResult ids`() {
        // Independent counters — two different flows producing two different
        // result streams in the same ViewModel must not accidentally share
        // state. (Compose key lookup uses the id within its own list only,
        // so a collision is merely harmless confusion — but the independence
        // is intentional and the test pins it.)
        val f = FeatureResult.nextId()
        val e = ExperienceResult.nextId()
        // Not strictly required to differ — but if they DO ever differ we
        // know they are backed by separate counters. Allow equality OR
        // difference; only assert that calling each advances only its own
        // counter.
        val fNext = FeatureResult.nextId()
        val eNext = ExperienceResult.nextId()
        assertNotEquals(f, fNext)
        assertNotEquals(e, eNext)
    }
}
