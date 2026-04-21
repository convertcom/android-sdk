/*
 * Convert Android SDK — core/bucketing tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * # HARD CI GATE — DO NOT WEAKEN OR SKIP
 *
 * Cross-SDK bucketing parity test. Loads `hash-parity-vectors.json` from
 * the classpath and, for every vector, asserts that the shipped Kotlin
 * [BucketingManager] produces BYTE-IDENTICAL output to the JS SDK
 * `@convertcom/js-sdk-bucketing` that generated the vectors.
 *
 * A **single** failure here means: a visitor already bucketed by one SDK
 * would land in a different variation when re-bucketed by the Android
 * SDK, silently splitting sticky assignments and ruining experiment
 * integrity. This is the worst possible class of cross-SDK bug.
 *
 * ## Failure remediation
 *
 * If a vector fails:
 *   1. **DO NOT** regenerate the vectors file to "fix" the discrepancy
 *      — that papers over the bug and perpetuates the parity break.
 *   2. **DO** read the failing vector's description and diagnose why
 *      [BucketingManager.getValueVisitorBased] diverges from the JS
 *      output for that input class (UTF-8 encoding? seed plumbing?
 *      unsigned-hash masking? truncation rounding?).
 *   3. **FIX the Kotlin side** to restore parity, then confirm the
 *      test passes without changing the vectors.
 *
 * ## Regenerating vectors (legitimate cases)
 *
 * Regenerate ONLY when adding new test inputs (not to paper over bugs):
 *
 * ```
 * cd android-sdk
 * yarn generate:parity-vectors
 * ```
 *
 * This writes to `packages/core/src/test/resources/hash-parity-vectors.json`.
 * Review the diff carefully — any existing vector whose `expectedValue`
 * or `expectedVariationId` changed is a BREAKING cross-SDK coordination
 * event (all SDKs must agree on the new hash, typically requiring a
 * major-version bump synchronized across SDKs).
 *
 * ## Performance
 *
 * Spec target (AC-9): full parity test completes in <2 seconds.
 * Parsing the JSON once in a `@JvmStatic` companion method + a
 * sub-microsecond hash per vector gives us ~69 * few-µs = well under
 * the budget even on a cold JVM.
 *
 * ## Why `@ParameterizedTest` + `@MethodSource` over a single `@Test`
 *
 * With `@ParameterizedTest` every vector is a separately-named test
 * case in the IDE / CI report. A failure pinpoints the exact
 * problematic vector by its human-readable `description` without
 * drowning developers in grep output.
 *
 * ## Vector file shape
 *
 * The JSON is a top-level array of [ParityVector] objects — see AC-1
 * and AC-4 of Story 3.5 for the canonical schema.
 */
internal class HashParityTest {

    /**
     * One parity vector as parsed from `hash-parity-vectors.json`.
     *
     * @property description human-readable label used as the test case
     *   name in CI output. Format: `"<Category>: <Detail>"`.
     * @property visitorId the visitor input fed into [BucketingManager.getValueVisitorBased].
     * @property experienceId the experience input fed into [BucketingManager.getValueVisitorBased].
     * @property seed the MurmurHash3 seed.
     * @property expectedHash OPTIONAL raw 32-bit hash — recorded for
     *   debug diagnostics only, NOT asserted. The Kotlin `murmurhash`
     *   library returns [kotlin.UInt] while JS returns a Number; the
     *   reliable gate is the derived value comparison. Omitted from
     *   the current vectors file.
     * @property expectedValue integer in `[0, max_traffic)` produced by
     *   `(hashUnsigned / 2^32) * max_traffic` truncated toward zero.
     *   MUST match the Kotlin output exactly.
     * @property expectedVariationId variation id selected by
     *   [BucketingManager.selectBucket] from the [buckets] wheel at
     *   the computed value; `null` when the value falls beyond the
     *   sum of all bucket boundaries.
     * @property buckets ordered map of variation id → percentage. Fixed
     *   to `{varA: 50.0, varB: 50.0}` for every vector so parity
     *   failures isolate the hash, not the bucket-selection algorithm.
     */
    @Serializable
    internal data class ParityVector(
        val description: String,
        val visitorId: String,
        val experienceId: String,
        val seed: Int,
        val expectedHash: Long? = null,
        val expectedValue: Int,
        val expectedVariationId: String?,
        val buckets: Map<String, Double>,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("parityVectors")
    fun `parity vector passes`(description: String, vector: ParityVector) {
        val manager = BucketingManager(ConvertConfig(), Logger.NoOp)

        val actualValue = manager.getValueVisitorBased(
            visitorId = vector.visitorId,
            experienceId = vector.experienceId,
            seed = vector.seed,
        )
        assertEquals(
            vector.expectedValue,
            actualValue,
            "Vector \"${vector.description}\": expected value=${vector.expectedValue}, got=$actualValue " +
                "(visitorId=${vector.visitorId.take(40)}..., experienceId=${vector.experienceId}, seed=${vector.seed})",
        )

        val actualVariation = manager.selectBucket(
            buckets = vector.buckets,
            value = actualValue,
        )
        assertEquals(
            vector.expectedVariationId,
            actualVariation,
            "Vector \"${vector.description}\": expected variation=${vector.expectedVariationId}, got=$actualVariation",
        )
    }

    companion object {
        /**
         * Resource path relative to the classpath root. kotlinx.serialization
         * is configured with [Json.ignoreUnknownKeys]=true so a future
         * vector file carrying additional fields stays forward-compatible
         * with older test runs (Gotcha 4 in Story 3.5 Dev Notes).
         */
        private const val VECTORS_RESOURCE: String = "/hash-parity-vectors.json"

        private val json: Json = Json { ignoreUnknownKeys = true }

        /**
         * Loaded once on first call, then cached by JUnit 5's MethodSource
         * lifecycle (it reads the Stream per test class invocation).
         * Parsing ~69 vectors from JSON takes a few milliseconds — well
         * under the AC-9 2-second budget for the full suite.
         */
        private fun loadVectors(): List<ParityVector> {
            val stream = HashParityTest::class.java.getResourceAsStream(VECTORS_RESOURCE)
                ?: error(
                    "Missing test resource $VECTORS_RESOURCE — run " +
                        "`yarn generate:parity-vectors` from the android-sdk root " +
                        "to regenerate it from the JS SDK reference implementation.",
                )
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return json.decodeFromString(text)
        }

        /**
         * JUnit 5 MethodSource: returns a [Stream] of `Arguments` where
         * the first element is the vector's `description` (used as the
         * test case display name via `@ParameterizedTest(name = "{0}")`)
         * and the second is the full [ParityVector] object passed into
         * the parameterised test method.
         */
        @JvmStatic
        fun parityVectors(): Stream<Arguments> {
            val vectors = loadVectors()
            check(vectors.size >= 50) {
                "Expected at least 50 parity vectors (Story 3.5 AC-1), got ${vectors.size}. " +
                    "Check `hash-parity-vectors.json` regeneration."
            }
            return vectors.stream().map { vector ->
                Arguments.of(vector.description, vector)
            }
        }
    }
}
