/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [BucketingAllocation] — Story 3.2 AC-5.
 *
 * The data class mirrors the JS SDK's `BucketingAllocation`
 * (`javascript-sdk/packages/types/src/BucketingAllocation.ts`): two fields
 * exactly — `variationId` (String) and `bucketingAllocation` (Int). No
 * nullable fields — the allocation is only constructed at the instant the
 * bucketing engine resolved a variation, so both fields are guaranteed
 * non-null at creation time.
 */
internal class BucketingAllocationTest {

    @Test
    fun `construct with variationId and bucketingAllocation`() {
        val allocation = BucketingAllocation(variationId = "var_42", bucketingAllocation = 5000)

        assertEquals("var_42", allocation.variationId)
        assertEquals(5000, allocation.bucketingAllocation)
    }

    @Test
    fun `equals is structural over both fields`() {
        val a = BucketingAllocation(variationId = "v", bucketingAllocation = 100)
        val b = BucketingAllocation(variationId = "v", bucketingAllocation = 100)
        val c = BucketingAllocation(variationId = "v", bucketingAllocation = 200)
        val d = BucketingAllocation(variationId = "other", bucketingAllocation = 100)

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun `hashCode matches for structurally equal instances`() {
        val a = BucketingAllocation(variationId = "v", bucketingAllocation = 100)
        val b = BucketingAllocation(variationId = "v", bucketingAllocation = 100)

        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy overrides selected fields`() {
        val original = BucketingAllocation(variationId = "v", bucketingAllocation = 100)

        val withNewId = original.copy(variationId = "w")
        val withNewAllocation = original.copy(bucketingAllocation = 200)

        assertEquals(BucketingAllocation("w", 100), withNewId)
        assertEquals(BucketingAllocation("v", 200), withNewAllocation)
    }

    @Test
    fun `round-trips through kotlinx serialization`() {
        val original = BucketingAllocation(variationId = "var_99", bucketingAllocation = 3141)

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<BucketingAllocation>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `json payload contains both fields`() {
        val allocation = BucketingAllocation(variationId = "var_7", bucketingAllocation = 9999)

        val json = testJson.encodeToString(allocation)

        assertTrue(json.contains("\"variationId\":\"var_7\""), "missing variationId in $json")
        assertTrue(
            json.contains("\"bucketingAllocation\":9999"),
            "missing bucketingAllocation in $json",
        )
    }
}
