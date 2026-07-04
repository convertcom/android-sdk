/*
 * Convert Android SDK — core/bucketing tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.VariationAllocation
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.VariationStatuses
import com.convert.sdk.core.port.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.util.stream.Stream

/**
 * Focused AC1-AC5 coverage for the anchored bucketing layout — qs-01 /
 * contract v12. Complements [AnchoredBucketingParityTest] (which drives the
 * shared cross-SDK golden vectors end-to-end): this file isolates each
 * acceptance criterion against the individual `BucketingLayoutResolver.kt`
 * / [BucketingManager] anchored functions so a failure points at the exact
 * broken primitive rather than only a vector's final id.
 *
 * AC6 (packed regression lock) and AC7 (golden vectors) are covered by
 * [AnchoredBucketingParityTest] plus the pre-existing, unmodified
 * [HashParityTest] / [BucketingManagerTest] suites — no new assertions
 * needed here. AC8 (stored-decision precedence) and AC9 (event/API
 * stability) are [com.convert.sdk.android.ConvertContext] concerns with NO
 * code changes in this qs-01 pass (`resolveSticky` short-circuits before
 * the gate; event/return shapes are untouched) — already exercised by the
 * existing `ConvertContextRunExperienceTest` suite, so no new test is
 * added for them either.
 *
 * ## Phase 1 (RED) note
 *
 * `isAnchoredLayout`, `buildVariationAllocations`, [BucketingManager.getBucketRanges],
 * and [BucketingManager.selectBucketAnchored] are Phase-1 stubs (always
 * `false` / empty / `null`). Assertions below that require the REAL
 * anchored arithmetic are expected to FAIL until Phase 2 fills in the
 * bodies; assertions whose expected value happens to equal the stub's
 * sentinel may pass early — that is expected and documented per-case.
 */
internal class AnchoredBucketingAcceptanceTest {

    // --- AC1: gate branching -----------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("gateVectors")
    fun `AC1 gate branches on version`(description: String, version: BigDecimal?, expectedAnchored: Boolean) {
        assertEquals(expectedAnchored, isAnchoredLayout(version), description)
    }

    // --- AC2: raise is a superset (exact 15% -> 25% 3-arm table) -----------

    @Test
    fun `AC2 raising 15pct to 25pct keeps every already-bucketed visitor's arm`() {
        val ranges15 = BucketingManager(TEST_CONFIG, TEST_LOGGER).getBucketRanges(threeEqualArms(FIFTEEN_PCT))
        val ranges25 = BucketingManager(TEST_CONFIG, TEST_LOGGER).getBucketRanges(threeEqualArms(TWENTY_FIVE_PCT))

        // Spec table (qs-01 "Layouts at 15% -> 25%, 3 equal arms"):
        // anchored 15%: O [0,500) V1 [3333.33,3833.33) V2 [6666.67,7167)
        // anchored 25%: O [0,833.33) V1 [3333.33,4166.67) V2 [6666.67,7500)
        assertRange(ranges15, id = "O", anchor = 0.0, width = 500.0)
        assertRange(ranges15, id = "V1", anchor = THIRD_OF_10000, width = 500.0)
        assertRange(ranges15, id = "V2", anchor = TWO_THIRDS_OF_10000, width = 500.0)
        assertRange(ranges25, id = "O", anchor = 0.0, width = 833.3333333333334)
        assertRange(ranges25, id = "V1", anchor = TWENTY_FIVE_PCT_THIRD_OF_10000, width = 833.3333333333334)
        assertRange(ranges25, id = "V2", anchor = TWENTY_FIVE_PCT_TWO_THIRDS_OF_10000, width = 833.3333333333334)

        // A visitor at value 3500 sits inside V1's 15% band AND inside V1's
        // (superset) 25% band -> same arm both times, never reassigned.
        val manager = BucketingManager(TEST_CONFIG, TEST_LOGGER)
        assertEquals("V1", manager.selectBucketAnchored(ranges15, value = SAMPLE_V1_VALUE))
        assertEquals("V1", manager.selectBucketAnchored(ranges25, value = SAMPLE_V1_VALUE))
    }

    // --- AC3: lower ejects evenly and never flips ---------------------------

    @Test
    fun `AC3 lowering 25pct to 15pct ejects out-of-range visitors without reassigning them`() {
        val manager = BucketingManager(TEST_CONFIG, TEST_LOGGER)
        val ranges25 = manager.getBucketRanges(threeEqualArms(TWENTY_FIVE_PCT))
        val ranges15 = manager.getBucketRanges(threeEqualArms(FIFTEEN_PCT))

        // value 4000 is inside V1's 25% band [3333.33,4166.67) but OUTSIDE
        // V1's 15% band [3333.33,3833.33) -> ejected to not-bucketed, never
        // reassigned to O or V2 (the packed-layout flip this qs-01 fixes).
        assertEquals("V1", manager.selectBucketAnchored(ranges25, value = EJECTED_VALUE))
        assertNull(manager.selectBucketAnchored(ranges15, value = EJECTED_VALUE))
    }

    // --- AC4: stops / explicit ta:0 zero the arm's width, others unchanged -

    @Test
    fun `AC4 stopping one arm zero-widths only that arm, other anchors byte-identical`() {
        val manager = BucketingManager(TEST_CONFIG, TEST_LOGGER)
        val allRunning = threeEqualArms(FIFTEEN_PCT)
        val v1Stopped = listOf(
            VariationAllocation(id = "O", allocation = FIFTEEN_PCT, active = true),
            VariationAllocation(id = "V1", allocation = FIFTEEN_PCT, active = false),
            VariationAllocation(id = "V2", allocation = FIFTEEN_PCT, active = true),
        )

        val rangesRunning = manager.getBucketRanges(allRunning)
        val rangesStopped = manager.getBucketRanges(v1Stopped)

        // O and V2 keep IDENTICAL anchors + widths (weight preserved by the
        // stopped V1 arm) — only V1's width drops to zero.
        assertEquals(rangesRunning.first { it.id == "O" }, rangesStopped.first { it.id == "O" })
        assertEquals(rangesRunning.first { it.id == "V2" }, rangesStopped.first { it.id == "V2" })
        val stoppedV1 = rangesStopped.first { it.id == "V1" }
        assertEquals(THIRD_OF_10000, stoppedV1.anchor)
        assertEquals(0.0, stoppedV1.width)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inactiveArmVectors")
    fun `AC4 inactive arms keep their weight but lose active status`(
        description: String,
        variation: ExperienceVariationConfig,
        expectedAllocation: Double,
    ) {
        val allocations = buildVariationAllocations(listOf(variation))
        val entry = allocations.first { it.id == variation.id }

        assertEquals(expectedAllocation, entry.allocation, "$description: allocation (weight) must be preserved")
        assertEquals(false, entry.active, "$description: an inactive arm is never active regardless of weight")
    }

    // --- AC5: defaults + boundaries -----------------------------------------

    @Test
    fun `AC5 absent traffic_allocation defaults to 100pct weight`() {
        val variation = ExperienceVariationConfig(id = "SOLO", trafficAllocation = null, status = null)

        val allocations = buildVariationAllocations(listOf(variation))

        assertEquals(1, allocations.size)
        assertEquals(100.0, allocations.first().allocation)
        assertEquals(true, allocations.first().active)
    }

    @Test
    fun `AC5 totalWeight of zero or less is not bucketed`() {
        val manager = BucketingManager(TEST_CONFIG, TEST_LOGGER)
        val allZero = listOf(
            VariationAllocation(id = "O", allocation = 0.0, active = false),
            VariationAllocation(id = "V1", allocation = 0.0, active = false),
        )

        val ranges = manager.getBucketRanges(allZero)

        assertEquals(emptyList<BucketAnchoredRange>(), ranges)
        assertNull(manager.selectBucketAnchored(ranges, value = 0))
    }

    @Test
    fun `AC5 boundary value equal to anchor is in, equal to anchor plus width is out`() {
        val manager = BucketingManager(TEST_CONFIG, TEST_LOGGER)
        val ranges = listOf(BucketAnchoredRange(id = "X", anchor = BOUNDARY_ANCHOR, width = BOUNDARY_WIDTH))

        assertEquals("X", manager.selectBucketAnchored(ranges, value = BOUNDARY_ANCHOR.toInt()))
        assertNull(manager.selectBucketAnchored(ranges, value = (BOUNDARY_ANCHOR + BOUNDARY_WIDTH).toInt()))
    }

    // --- shared fixtures / helpers ------------------------------------------

    private fun threeEqualArms(pctEach: Double): List<VariationAllocation> = listOf(
        VariationAllocation(id = "O", allocation = pctEach, active = true),
        VariationAllocation(id = "V1", allocation = pctEach, active = true),
        VariationAllocation(id = "V2", allocation = pctEach, active = true),
    )

    private fun assertRange(ranges: List<BucketAnchoredRange>, id: String, anchor: Double, width: Double) {
        val range = ranges.first { it.id == id }
        assertEquals(anchor, range.anchor, "$id anchor")
        assertEquals(width, range.width, "$id width")
    }

    companion object {
        private val TEST_LOGGER = Logger.NoOp
        private val TEST_CONFIG = ConvertConfig()

        private const val FIFTEEN_PCT: Double = 5.0
        private const val TWENTY_FIVE_PCT: Double = 8.333333333333334
        private const val THIRD_OF_10000: Double = 3333.333333333333
        private const val TWO_THIRDS_OF_10000: Double = 6666.666666666666

        /**
         * `ranges25`'s V1/V2 anchors are NOT bit-identical to [THIRD_OF_10000] /
         * [TWO_THIRDS_OF_10000] — [TWENTY_FIVE_PCT] (8.333333333333334, the
         * per-arm share of 25%) is itself an imprecise double, so
         * `(cumWeight / totalWeight) * 10000` rounds to a different ULP than
         * the [FIFTEEN_PCT]-derived case. Verified against the JS SDK oracle
         * (`packages/bucketing/src/bucketing-manager.ts` `getBucketRanges`)
         * executed directly in Node — `node -e` with the identical
         * `(cumWeight/totalWeight)*10000` walk over three
         * `8.333333333333334`-weighted arms prints
         * `{"id":"V1","anchor":3333.3333333333335,...}` /
         * `{"id":"V2","anchor":6666.666666666667,...}`, confirming this is a
         * genuine floating-point-parity fact, not a Kotlin-side defect.
         */
        private const val TWENTY_FIVE_PCT_THIRD_OF_10000: Double = 3333.3333333333335
        private const val TWENTY_FIVE_PCT_TWO_THIRDS_OF_10000: Double = 6666.666666666667
        private const val SAMPLE_V1_VALUE: Int = 3500
        private const val EJECTED_VALUE: Int = 4000
        private const val BOUNDARY_ANCHOR: Double = 1000.0
        private const val BOUNDARY_WIDTH: Double = 500.0

        @JvmStatic
        fun gateVectors(): Stream<Arguments> = Stream.of(
            Arguments.of("version 11 (production stamp) -> packed", BigDecimal("11"), false),
            Arguments.of("version 11.9 -> anchored", BigDecimal("11.9"), true),
            Arguments.of("version 12 -> anchored", BigDecimal("12"), true),
            Arguments.of("missing version (null) -> packed", null, false),
        )

        @JvmStatic
        fun inactiveArmVectors(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "explicit ta:0, status running -> zero weight, never defaults to 100",
                ExperienceVariationConfig(
                    id = "ZERO_TA",
                    trafficAllocation = BigDecimal.ZERO,
                    status = VariationStatuses.RUNNING,
                ),
                0.0,
            ),
            Arguments.of(
                "stopped status, ta preserved at 5 -> weight kept for anchor stability",
                ExperienceVariationConfig(
                    id = "STOPPED",
                    trafficAllocation = BigDecimal("5"),
                    status = VariationStatuses.STOPPED,
                ),
                5.0,
            ),
        )
    }
}
