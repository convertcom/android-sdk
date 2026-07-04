/*
 * Convert Android SDK — core/bucketing tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.util.stream.Stream

/**
 * # HARD CI GATE — DO NOT WEAKEN OR SKIP
 *
 * Cross-SDK anchored-bucketing-layout parity test — qs-01 / contract v12.
 * Loads `cross-sdk-bucketing-vectors.json` (copied byte-for-byte from the
 * JS SDK reference, see [com.convert.sdk.core.bucketing.HashParityTest] for
 * the sibling hash-only fixture) and, for every vector, asserts that
 * [BucketingManager.resolveVariationId] — the single version-gated
 * decision seam shared with [com.convert.sdk.android.ConvertContext]
 * (Phase 2) — selects the same variation id the JS SDK reference selected.
 *
 * A vector carries `version: 11` (packed, byte-for-byte unchanged, AC6) or
 * `version: 12` (anchored, the new qs-01 layout, AC1-AC5/AC7). Both routes
 * flow through [BucketingManager.resolveVariationId], so a single
 * parameterised test covers the whole version-gated decision.
 *
 * ## Failure remediation
 *
 * If a vector fails:
 *   1. **DO NOT** regenerate or hand-edit the vectors file to "fix" the
 *      discrepancy — the fixture IS the cross-SDK contract (qs-01
 *      "Golden-vector fixture (consume — do NOT recompute)").
 *   2. **DO** read the failing vector's description — it names the exact
 *      layout, coverage percentages, and visitor under test — and diagnose
 *      why [BucketingManager.resolveVariationId] (or the
 *      `BucketingLayoutResolver.kt` functions it delegates to) diverges
 *      from the JS reference for that input class.
 *   3. **FIX the Kotlin side** to restore parity, then confirm the test
 *      passes without changing the vectors.
 *
 * ## Vector file shape
 *
 * The JSON is a top-level array of objects:
 * `{description, experienceId, visitorId, version, variations: [{id,
 * traffic_allocation, status?}], expected}`. `variations` deserialises
 * directly into the real generated [ExperienceVariationConfig] — the exact
 * production type [BucketingLayoutResolver.kt] consumes — so this test
 * exercises the real wire-shape coercion (e.g. `traffic_allocation` as a
 * `@Contextual BigDecimal`), not a hand-rolled parallel model.
 */
internal class AnchoredBucketingParityTest {

    /**
     * One golden vector as parsed from `cross-sdk-bucketing-vectors.json`.
     *
     * @property version the experience's `version` field. Always a plain
     *   numeric literal in this fixture (`11` or `12`); AC1's non-numeric /
     *   fractional / missing gate cases are covered separately by
     *   [AnchoredBucketingAcceptanceTest], not by this shared fixture.
     * @property variations decodes straight into the real generated
     *   [ExperienceVariationConfig] list — config declaration order is
     *   preserved by [kotlinx.serialization]'s `List` decoding.
     * @property expected the JS-SDK-computed variation id, or `null` when
     *   the JS reference did not bucket this visitor.
     */
    @Serializable
    internal data class GoldenBucketingVector(
        val description: String,
        val experienceId: String,
        val visitorId: String,
        @Contextual val version: BigDecimal,
        val variations: List<ExperienceVariationConfig>,
        val expected: String? = null,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenVectors")
    fun `golden vector selects expected variation`(
        description: String,
        vector: GoldenBucketingVector,
    ) {
        val manager = BucketingManager(ConvertConfig(), Logger.NoOp)

        val actual = manager.resolveVariationId(
            version = vector.version,
            variations = vector.variations,
            visitorId = vector.visitorId,
            experienceId = vector.experienceId,
        )?.variationId

        assertEquals(
            vector.expected,
            actual,
            "Vector \"${vector.description}\": expected=${vector.expected}, got=$actual " +
                "(version=${vector.version}, visitorId=${vector.visitorId}, " +
                "experienceId=${vector.experienceId})",
        )
    }

    companion object {
        /**
         * Resource path relative to the classpath root — the same fixture
         * file [HashParityTest] documents the regeneration procedure for,
         * imported verbatim from the JS SDK reference (qs-01 AND-1).
         */
        private const val VECTORS_RESOURCE: String = "/cross-sdk-bucketing-vectors.json"

        /** Fixture is imported at exactly 59 vectors ({11: 19, 12: 40}) — qs-01 AND-1. */
        private const val EXPECTED_VECTOR_COUNT: Int = 59

        /**
         * [sharedSerializersModule] registers the `@Contextual BigDecimal`
         * serializer the generated [ExperienceVariationConfig] and
         * [GoldenBucketingVector.version] fields both rely on.
         */
        private val json: Json = Json {
            ignoreUnknownKeys = true
            serializersModule = sharedSerializersModule
        }

        private fun loadVectors(): List<GoldenBucketingVector> {
            val stream = AnchoredBucketingParityTest::class.java.getResourceAsStream(VECTORS_RESOURCE)
                ?: error(
                    "Missing test resource $VECTORS_RESOURCE — copy verbatim from the JS SDK " +
                        "reference branch `feat/anchored-bucketing-layout`, file " +
                        "`packages/bucketing/tests/cross-sdk-bucketing-vectors.json` (qs-01 AND-1).",
                )
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return json.decodeFromString(text)
        }

        @JvmStatic
        fun goldenVectors(): Stream<Arguments> {
            val vectors = loadVectors()
            check(vectors.size == EXPECTED_VECTOR_COUNT) {
                "Expected exactly $EXPECTED_VECTOR_COUNT golden vectors (qs-01 AND-1), " +
                    "got ${vectors.size}. Check cross-sdk-bucketing-vectors.json import."
            }
            return vectors.stream().map { vector -> Arguments.of(vector.description, vector) }
        }
    }
}
