/*
 * Convert Android SDK — core/preview tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.preview

import com.convert.sdk.core.model.Variation
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ExperienceStatuses
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.VariationStatuses
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.util.stream.Stream

/**
 * Table-driven bypass matrix for [PreviewDecision.resolve] — qs-02 / AND-3
 * (AC4, AC5). Every case constructs an experience where the target variation
 * would be excluded (or never selected) by normal bucketing, then asserts
 * [PreviewDecision.resolve] still returns it, with the SAME field shape a
 * normal bucketed decision would carry (`bucketingAllocation = null`,
 * matching the sticky-path convention).
 */
internal class PreviewDecisionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("bypassCases")
    fun `resolve forces the target variation despite every normal gate`(
        name: String,
        experience: ConfigExperience,
        variationId: String,
        expected: Variation,
    ) {
        val actual = PreviewDecision.resolve(experience, variationId)
        assertEquals(expected, actual, "case=\"$name\"")
    }

    @Test
    fun `resolve returns null when the variation id is not in the experience`() {
        val experience = experienceOf(
            status = ExperienceStatuses.ACTIVE,
            environment = "production",
            variations = listOf(natural(), target()),
        )
        assertNull(PreviewDecision.resolve(experience, "unknown-variation-id"))
    }

    companion object {

        @JvmStatic
        fun bypassCases(): Stream<Arguments> {
            val cases = listOf(
                Triple(
                    "bypasses a draft experience status",
                    experienceOf(status = ExperienceStatuses.DRAFT, variations = listOf(natural(), target())),
                    TARGET_ID,
                ),
                Triple(
                    "bypasses a paused experience status",
                    experienceOf(status = ExperienceStatuses.PAUSED, variations = listOf(natural(), target())),
                    TARGET_ID,
                ),
                Triple(
                    "bypasses a mismatched environment value",
                    experienceOf(
                        status = ExperienceStatuses.ACTIVE,
                        environment = "staging-only",
                        variations = listOf(natural(), target()),
                    ),
                    TARGET_ID,
                ),
                Triple(
                    "bypasses a non-running (stopped) target variation",
                    experienceOf(
                        variations = listOf(natural(), target(status = VariationStatuses.STOPPED)),
                    ),
                    TARGET_ID,
                ),
                Triple(
                    "bypasses a zero-traffic target variation",
                    experienceOf(
                        variations = listOf(natural(), target(trafficAllocation = BigDecimal.ZERO)),
                    ),
                    TARGET_ID,
                ),
                Triple(
                    "bypasses the bucketing hash — returns the low-traffic target rather than " +
                        "the near-certain-to-be-picked (and would-be-sticky) high-traffic variation",
                    experienceOf(
                        variations = listOf(
                            natural(trafficAllocation = BigDecimal("9999")),
                            target(trafficAllocation = BigDecimal("1")),
                        ),
                    ),
                    TARGET_ID,
                ),
            )
            return cases.stream().map { (name, experience, variationId) ->
                Arguments.of(name, experience, variationId, expectedVariationFor(experience, variationId))
            }
        }

        private const val TARGET_ID: String = "v-target"
        private const val NATURAL_ID: String = "v-natural"

        /** The variation normal bucketing / a sticky decision would land on. */
        private fun natural(
            trafficAllocation: BigDecimal = BigDecimal("10000"),
            status: VariationStatuses = VariationStatuses.RUNNING,
        ): ExperienceVariationConfig = ExperienceVariationConfig(
            id = NATURAL_ID,
            name = "Natural",
            key = "natural",
            trafficAllocation = trafficAllocation,
            status = status,
        )

        /** The variation under preview — status/traffic vary per bypass case. */
        private fun target(
            trafficAllocation: BigDecimal = BigDecimal("10000"),
            status: VariationStatuses = VariationStatuses.RUNNING,
        ): ExperienceVariationConfig = ExperienceVariationConfig(
            id = TARGET_ID,
            name = "Target",
            key = "target",
            trafficAllocation = trafficAllocation,
            status = status,
        )

        private fun experienceOf(
            status: ExperienceStatuses? = ExperienceStatuses.ACTIVE,
            environment: String? = "production",
            variations: List<ExperienceVariationConfig>,
        ): ConfigExperience = ConfigExperience(
            id = "exp-1",
            name = "Experience One",
            key = "exp-key",
            status = status,
            environment = environment,
            variations = variations,
        )

        private fun expectedVariationFor(experience: ConfigExperience, variationId: String): Variation {
            val variation = experience.variations!!.first { it.id == variationId }
            return Variation(
                id = variation.id,
                key = variation.key,
                name = variation.name,
                experienceId = experience.id,
                experienceKey = experience.key,
                experienceName = experience.name,
                bucketingAllocation = null,
                changes = null,
            )
        }
    }
}
