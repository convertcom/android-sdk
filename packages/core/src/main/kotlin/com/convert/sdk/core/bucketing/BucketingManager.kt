/*
 * Convert Android SDK — core/bucketing
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.ConfigDefaults
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.BucketingAllocation
import com.convert.sdk.core.port.Logger
import com.goncalossilva.murmurhash.MurmurHash3

/**
 * Deterministic bucketing engine that hashes visitor + experience identity
 * into a 0..`maxTraffic` integer and selects the matching variation from a
 * cumulative-percentage wheel.
 *
 * ### Cross-SDK parity (Story 3.2 AC-1, AC-2)
 *
 * Every byte-level knob that determines the output is fixed to match the
 * JS SDK (`javascript-sdk/packages/bucketing/src/bucketing-manager.ts` at
 * `BucketingManager.getValueVisitorBased`):
 *
 *  1. **Input assembly:** `experienceId + visitorId` (string concatenation,
 *     no separator). An empty `experienceId` is legal — matches JS SDK
 *     `generateHash(experienceId + String(visitorId), seed)` when the
 *     `excludeExperienceIdHash` option is set.
 *  2. **Encoding:** UTF-8 via [String.toByteArray]. The JS SDK's
 *     `murmurhash` package encodes JS strings as UTF-8 by default; pinning
 *     the Kotlin side to [Charsets.UTF_8] eliminates the only place a
 *     non-ASCII visitor id could diverge.
 *  3. **Hash:** `MurmurHash3(seed).hash32x86(bytes)` — MurmurHash3 32-bit
 *     variant, returning a [kotlin.UInt]. Converting via [UInt.toLong]
 *     gives the unsigned 0..2^32-1 value the JS SDK passes to its
 *     division step unchanged.
 *  4. **Value math:** `val = (hashAsDouble / 4_294_967_296.0) * maxTraffic`.
 *     The `/2^32` normalises the hash into `[0.0, 1.0)`; multiplying by
 *     `maxTraffic` (default 10000) gives the bucket space.
 *  5. **Truncation:** `val.toInt()` truncates toward zero — byte-identical
 *     to JS SDK `parseInt(String(val), 10)` (which also truncates rather
 *     than rounding).
 *
 * Story 3.5 extends [HashAlgorithmTest]'s five vectors to 50+ shared
 * test cases that exercise UTF-8 multi-byte characters, long strings,
 * and edge-case seeds. The five vectors in this story lock the hash
 * path for the common cases while Story 3.5 chases the long tail.
 *
 * ### Bucket selection (AC-3)
 *
 * `selectBucket` walks the `buckets` map in insertion order, accumulating
 * `pct * 100 + redistribute` into `prev` (typed as [Double] to preserve
 * fractional boundaries for non-integer percentages — JS SDK parity). The
 * first bucket whose running total strictly exceeds `value` wins. A value
 * at or past the sum of all boundaries returns `null`, signalling "not
 * bucketed" (common when `max_traffic < 10000`).
 *
 * ### Why insertion order matters
 *
 * Kotlin's `mapOf` + `linkedMapOf` preserve insertion order; the
 * generated `ExperienceVariationConfig` list preserves backend-declared
 * order; `kotlinx.serialization` preserves JSON object order for Map
 * deserialisation. A caller that produces the wheel from
 * `experience.variations` must maintain that order end-to-end — swapping
 * variation order is a backwards-incompatible bucketing change.
 *
 * ### Non-nullable inputs
 *
 * All public-surface methods take non-nullable visitor / experience ids.
 * Callers are responsible for short-circuiting on null experiences
 * before calling into the manager (the story's [ConvertContext] call
 * site does this — AC-6 step 2).
 *
 * ### Visibility
 *
 * Declared `public` so that `:packages:sdk` — which lives in a separate
 * Gradle module — can reference the type when wiring
 * `ConvertSDK.bucketingManager`. Consumers of the published `sdk-core`
 * artifact should treat this as SDK-internal: `ConvertContext` is the
 * consumer-facing surface for bucketing.
 *
 * @property config the SDK's assembled configuration; read for
 *   `bucketing.hashSeed` and `bucketing.maxTraffic`.
 * @property logger the shared [Logger] port; debug traces every bucket
 *   selection call.
 */
public class BucketingManager(
    private val config: ConvertConfig,
    private val logger: Logger,
) {

    /**
     * MurmurHash3 seed resolved at construction time:
     * `config.bucketing?.hashSeed ?: DEFAULT`. Elvis on a nullable
     * `Int?` — so an explicit `hash_seed: 0` from a config payload is
     * treated as a valid zero seed, NOT coerced to the default (Gotcha 4
     * in the story Dev Notes).
     */
    private val hashSeed: Int =
        config.bucketing?.hashSeed ?: ConfigDefaults.DEFAULT_BUCKETING_HASH_SEED

    /**
     * Total basis points spanning all variations in a bucketing wheel.
     * Default 10000 (JS SDK parity); merchants with a `max_traffic < 10000`
     * reserve the upper tail as "unbucketed".
     */
    private val maxTraffic: Int =
        config.bucketing?.maxTraffic ?: ConfigDefaults.DEFAULT_BUCKETING_MAX_TRAFFIC

    /**
     * Computes the bucket value for the visitor/experience pair.
     *
     * @param visitorId the visitor's opaque stable identifier.
     * @param experienceId the experience's stable identifier; pass an
     *   empty string when the `excludeExperienceIdHash` config flag is
     *   set (matches JS SDK behaviour when that knob is enabled).
     * @param seed the hash seed; defaults to [hashSeed].
     * @return a truncated integer in `0..maxTraffic` suitable for
     *   [selectBucket].
     */
    public fun getValueVisitorBased(
        visitorId: String,
        experienceId: String = "",
        seed: Int = hashSeed,
    ): Int {
        val input = experienceId + visitorId
        // UTF-8 is pinned explicitly — the default on JVM is UTF-8 but
        // a future toolchain change mustn't silently swap that out.
        val bytes = input.toByteArray(Charsets.UTF_8)

        // MurmurHash3(seed).hash32x86(bytes) returns UInt (JVM name-mangled
        // to hash32x86-OGnWXxg). Convert to Long via toLong() which
        // zero-extends the 32-bit unsigned value into the 64-bit signed
        // Long domain — no manual masking needed (the library's UInt
        // return type already represents the unsigned interpretation).
        val hashUnsigned: Long = MurmurHash3(seed.toUInt()).hash32x86(bytes).toLong()

        // `/2^32 * maxTraffic` normalises + rescales. Using Double
        // throughout matches JS Number semantics (double-precision
        // float) — Float would lose precision on large hashes (Gotcha 3).
        val value = (hashUnsigned.toDouble() / MAX_HASH) * maxTraffic

        // toInt() truncates toward zero, matching JS parseInt(String(val), 10).
        val result = value.toInt()

        logger.debug(
            message = "BucketingManager.getValueVisitorBased() visitorId=$visitorId " +
                "experienceId=$experienceId seed=$seed val=$value result=$result",
            tag = TAG,
        )
        return result
    }

    /**
     * Selects a variation id from the [buckets] wheel given [value].
     *
     * @param buckets ordered map of variation id → traffic percentage (0..100).
     *   Iteration order drives selection; callers must preserve insertion
     *   order across the call chain.
     * @param value the bucket value, typically produced by [getValueVisitorBased].
     * @param redistribute additive per-bucket adjustment in basis points;
     *   used when traffic allocation rules shift a slice from an inactive
     *   variation back into the active pool. Defaults to `0`.
     * @return the matching variation id, or `null` when [value] exceeds
     *   the sum of all boundaries (visitor not bucketed).
     */
    public fun selectBucket(
        buckets: Map<String, Double>,
        value: Int,
        redistribute: Int = 0,
    ): String? {
        // prev is Double, not Int — the JS SDK's `prev += buckets[id] * 100 +
        // redistribute` runs on JS Numbers (double-precision floats), so a
        // fractional percentage like `33.333` yields fractional boundaries
        // (3333.3, 6666.6, 9999.9) that an Int accumulator would silently
        // truncate. Preserving Double keeps the comparison `value < prev`
        // byte-identical to the JS SDK output on non-integer percentages.
        var prev = 0.0
        var selected: String? = null
        for ((variationId, pct) in buckets) {
            prev += pct * PERCENTAGE_TO_BASIS_MULTIPLIER + redistribute
            if (value < prev) {
                selected = variationId
                break
            }
        }
        logger.debug(
            message = "BucketingManager.selectBucket() buckets=$buckets " +
                "value=$value redistribute=$redistribute selected=$selected",
            tag = TAG,
        )
        return selected
    }

    /**
     * Convenience combining [getValueVisitorBased] + [selectBucket] into
     * a single call and wrapping the result in a [BucketingAllocation].
     *
     * @param buckets ordered map of variation id → percentage.
     * @param visitorId the visitor's opaque stable identifier.
     * @param redistribute see [selectBucket].
     * @param seed optional seed override; `null` uses [hashSeed].
     * @param experienceId the experience's stable identifier — empty when
     *   `excludeExperienceIdHash` is set.
     * @return a [BucketingAllocation] on success, or `null` when no bucket
     *   matched.
     */
    public fun getBucketForVisitor(
        buckets: Map<String, Double>,
        visitorId: String,
        redistribute: Int = 0,
        seed: Int? = null,
        experienceId: String = "",
    ): BucketingAllocation? {
        val value = getValueVisitorBased(
            visitorId = visitorId,
            experienceId = experienceId,
            seed = seed ?: hashSeed,
        )
        val variationId = selectBucket(
            buckets = buckets,
            value = value,
            redistribute = redistribute,
        ) ?: return null
        return BucketingAllocation(
            variationId = variationId,
            bucketingAllocation = value,
        )
    }

    public companion object {
        private const val TAG: String = "BucketingManager"

        /**
         * 2^32 as a [Double] — the divisor that normalises a 32-bit
         * unsigned hash into `[0.0, 1.0)`. Matches JS SDK's
         * `DEFAULT_MAX_HASH = 4294967296`.
         */
        private const val MAX_HASH: Double = 4_294_967_296.0

        /**
         * Multiplier applied to each bucket's percentage (0..100) to lift
         * it into the `0..10000` basis-point space that [selectBucket]
         * accumulates against. Matches JS SDK `buckets[id] * 100`.
         *
         * Typed as [Double] (not [Int]) so the `pct * MULT + redistribute`
         * expression retains the fractional component for non-integer
         * percentages — see the Double-typed accumulator in [selectBucket].
         */
        private const val PERCENTAGE_TO_BASIS_MULTIPLIER: Double = 100.0
    }
}
