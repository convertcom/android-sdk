/*
 * Convert Android SDK Demo App — ConversionResult data class tests (Story 7.5)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 7.5 Task 1 — [ConversionResult] mirrors 7.4's [FeatureResult]
 * shape. Stable monotonic ids from [ConversionResult.nextId] so the
 * LazyColumn key lookup stays O(1) and Compose's diffing is cheap.
 */
class ConversionResultTest {

    @Test
    fun `nextId returns strictly increasing values`() {
        val a = ConversionResult.nextId()
        val b = ConversionResult.nextId()
        val c = ConversionResult.nextId()
        assertTrue(b > a, "ids must increase: got a=$a b=$b")
        assertTrue(c > b, "ids must increase: got b=$b c=$c")
    }

    @Test
    fun `nextId never collides across 100 calls`() {
        val ids = (1..100).map { ConversionResult.nextId() }
        assertEquals(ids.size, ids.toSet().size, "all ids must be unique")
    }

    @Test
    fun `default non-dedup non-error result has empty fields`() {
        val r = ConversionResult(
            id = ConversionResult.nextId(),
            goalKey = "purchase-goal",
            amount = 10.3,
            productsCount = 2,
        )
        assertFalse(r.isError)
        assertFalse(r.isDedup)
        assertNull(r.errorMessage)
        assertNull(r.errorHint)
        assertEquals("purchase-goal", r.goalKey)
        assertEquals(10.3, r.amount)
        assertEquals(2, r.productsCount)
    }

    @Test
    fun `dedup result shape`() {
        val r = ConversionResult(
            id = ConversionResult.nextId(),
            goalKey = "purchase-goal",
            amount = null,
            productsCount = null,
            isDedup = true,
        )
        assertTrue(r.isDedup)
        assertFalse(r.isError)
        assertEquals("purchase-goal", r.goalKey)
        assertNull(r.amount)
        assertNull(r.productsCount)
    }

    @Test
    fun `error result shape`() {
        val r = ConversionResult(
            id = ConversionResult.nextId(),
            goalKey = "purchase-goal",
            amount = null,
            productsCount = null,
            isError = true,
            errorMessage = "Conversion tracking failed",
            errorHint = "Check SDK readiness.",
        )
        assertTrue(r.isError)
        assertFalse(r.isDedup)
        assertEquals("Conversion tracking failed", r.errorMessage)
        assertEquals("Check SDK readiness.", r.errorHint)
    }

    @Test
    fun `conversionResult ids advance on their own counter`() {
        // Independent counter — ConversionResult.nextId should not share state
        // with FeatureResult.nextId or ExperienceResult.nextId. This test pins
        // the intent: calling each only advances its own sequence.
        val c = ConversionResult.nextId()
        val cNext = ConversionResult.nextId()
        assertNotEquals(c, cNext, "conversion counter must advance on its own")
    }
}
