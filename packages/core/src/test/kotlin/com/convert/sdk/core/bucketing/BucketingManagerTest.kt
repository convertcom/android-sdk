/*
 * Convert Android SDK — core/bucketing tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.BucketingConfig
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.BucketingAllocation
import com.convert.sdk.core.port.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [BucketingManager] — Story 3.2 ACs 1-4, 11.
 *
 * Verifies determinism, seed independence, cumulative-bucket selection,
 * and the end-to-end `getBucketForVisitor` pipeline that combines hashing
 * with bucket selection.
 *
 * ### Note on cross-SDK parity
 *
 * [HashAlgorithmTest] locks the hash pipeline to five JS-SDK-computed
 * vectors. This file focuses on the manager's own public surface —
 * `selectBucket`, `getBucketForVisitor` — where the expected values can
 * be computed inline from the already-validated hash vectors.
 */
internal class BucketingManagerTest {

    private val logger: Logger = Logger.NoOp

    // --- getValueVisitorBased determinism / seed behaviour ----------------

    @Test
    fun `same visitor same experience produces same hash`() {
        val manager = BucketingManager(ConvertConfig(), logger)

        val first = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )
        val second = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )

        assertEquals(first, second)
    }

    @Test
    fun `different seeds produce different hashes for same input`() {
        val manager = BucketingManager(ConvertConfig(), logger)

        val a = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
            seed = 9999,
        )
        val b = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
            seed = 0,
        )

        assertNotEquals(a, b)
    }

    @Test
    fun `default seed is taken from ConvertConfig bucketing hashSeed`() {
        // Explicit seed 12345 via config; call site omits seed → config default used.
        val config = ConvertConfig(bucketing = BucketingConfig(hashSeed = 12345))
        val manager = BucketingManager(config, logger)

        // "a" @ seed 12345 → value 5828 (locked by HashAlgorithmTest vector 5)
        val value = manager.getValueVisitorBased(
            visitorId = "a",
            experienceId = "",
        )

        assertEquals(5828, value)
    }

    @Test
    fun `default maxTraffic is taken from ConvertConfig bucketing maxTraffic`() {
        // At maxTraffic=5000, hash 357799817 / 2^32 * 5000 = 416.5338… → 416
        val config = ConvertConfig(bucketing = BucketingConfig(maxTraffic = 5000))
        val manager = BucketingManager(config, logger)

        val value = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )

        assertEquals(416, value)
    }

    @Test
    fun `seed zero from config is respected (not falsy-coerced to default)`() {
        val config = ConvertConfig(bucketing = BucketingConfig(hashSeed = 0))
        val manager = BucketingManager(config, logger)

        // "visitor_42" @ seed 0 → 1051 (HashAlgorithmTest vector 3)
        val value = manager.getValueVisitorBased(
            visitorId = "visitor_42",
            experienceId = "",
        )

        assertEquals(1051, value)
    }

    // --- selectBucket cumulative-percentage selection ---------------------

    @Test
    fun `selectBucket picks first variation when value below first boundary`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // linkedMapOf preserves insertion order (the *100 multiplier converts
        // pct 50.0 → basis 5000, so value 4999 falls below the first boundary)
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 50.0,
            "var_b" to 50.0,
        )

        val selected = manager.selectBucket(buckets, value = 4999)

        assertEquals("var_a", selected)
    }

    @Test
    fun `selectBucket picks second variation when value above first but below second boundary`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 50.0,
            "var_b" to 50.0,
        )

        val selected = manager.selectBucket(buckets, value = 5001)

        assertEquals("var_b", selected)
    }

    @Test
    fun `selectBucket returns null when value exceeds all boundaries`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // Sum of percentages 60 → sum of boundaries 6000. value 6001 is above.
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 30.0,
            "var_b" to 30.0,
        )

        val selected = manager.selectBucket(buckets, value = 6001)

        assertNull(selected)
    }

    @Test
    fun `selectBucket value exactly on boundary falls through to next bucket`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // Boundary check in JS SDK is strictly `<`, not `<=`. value == 5000 is NOT < 5000 so falls past var_a.
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 50.0,
            "var_b" to 50.0,
        )

        val selected = manager.selectBucket(buckets, value = 5000)

        assertEquals("var_b", selected)
    }

    @Test
    fun `selectBucket applies redistribute additively per bucket`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // Each bucket: pct*100 + redistribute = 50*100 + 100 = 5100
        // First boundary prev=5100 covers values < 5100
        // value 5050 < 5100 → var_a; value 5150 < 10200 → var_b
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 50.0,
            "var_b" to 50.0,
        )

        val aSelected = manager.selectBucket(buckets, value = 5050, redistribute = 100)
        val bSelected = manager.selectBucket(buckets, value = 5150, redistribute = 100)

        assertEquals("var_a", aSelected)
        assertEquals("var_b", bSelected)
    }

    @Test
    fun `selectBucket preserves insertion order for parity with JS SDK`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // Same percentages, different insertion order. The iteration order
        // MUST follow insertion order, not sort by variation id, so the
        // same `value` picks different variations depending on declaration
        // order.
        val buckets1: Map<String, Double> = linkedMapOf(
            "var_zzz" to 40.0,
            "var_aaa" to 40.0,
        )
        val buckets2: Map<String, Double> = linkedMapOf(
            "var_aaa" to 40.0,
            "var_zzz" to 40.0,
        )

        val first = manager.selectBucket(buckets1, value = 1000)
        val second = manager.selectBucket(buckets2, value = 1000)

        assertEquals("var_zzz", first)
        assertEquals("var_aaa", second)
    }

    // --- getBucketForVisitor end-to-end pipeline --------------------------

    @Test
    fun `getBucketForVisitor integrates hash and selection correctly`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // value for ("exp_1", "visitor_abc") @ default seed = 833 (see HashAlgorithmTest).
        // Two 50/50 buckets → 833 < 5000 → var_a.
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 50.0,
            "var_b" to 50.0,
        )

        val allocation = manager.getBucketForVisitor(
            buckets = buckets,
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )

        assertNotNull(allocation)
        assertEquals("var_a", allocation?.variationId)
        assertEquals(833, allocation?.bucketingAllocation)
    }

    @Test
    fun `getBucketForVisitor returns null when no bucket matches`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // value 833 for ("exp_1", "visitor_abc") — sum boundaries 500 + 500 = 1000.
        // But buckets contain percentages, and 833 > 500 → falls past var_a, < 1000 → var_b.
        // To get a miss, we need sum < 833 → two 4% variations = 800 boundary total.
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 4.0,
            "var_b" to 4.0,
        )

        val allocation = manager.getBucketForVisitor(
            buckets = buckets,
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )

        assertNull(allocation)
    }

    @Test
    fun `getBucketForVisitor returns BucketingAllocation with correct type shape`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        val buckets: Map<String, Double> = linkedMapOf("var_a" to 100.0)

        val allocation = manager.getBucketForVisitor(
            buckets = buckets,
            visitorId = "v",
            experienceId = "e",
        )

        assertTrue(allocation is BucketingAllocation)
        assertEquals("var_a", allocation?.variationId)
    }

    @Test
    fun `getBucketForVisitor honors redistribute parameter`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        // Same setup as the null-test — two 4% buckets → boundaries 400+400 = 800.
        // value 833 would miss… but redistribute=100 per bucket → boundaries
        // 500+500 = 1000, and 833 < 1000 → hits var_b after passing var_a
        // (first prev=500, 833 >= 500, advance; second prev=1000, 833 < 1000, select).
        val buckets: Map<String, Double> = linkedMapOf(
            "var_a" to 4.0,
            "var_b" to 4.0,
        )

        val allocation = manager.getBucketForVisitor(
            buckets = buckets,
            visitorId = "visitor_abc",
            experienceId = "exp_1",
            redistribute = 100,
        )

        assertNotNull(allocation)
        assertEquals("var_b", allocation?.variationId)
    }

    @Test
    fun `getBucketForVisitor overrides seed per call`() {
        val manager = BucketingManager(ConvertConfig(), logger)
        val buckets: Map<String, Double> = linkedMapOf("var_a" to 100.0)

        // seed=0 for "visitor_42" + "" → value 1051
        val allocation = manager.getBucketForVisitor(
            buckets = buckets,
            visitorId = "visitor_42",
            experienceId = "",
            seed = 0,
        )

        assertEquals(1051, allocation?.bucketingAllocation)
    }
}
