/*
 * Convert Android SDK — core/bucketing tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.port.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Micro-parity test for [BucketingManager]'s hash primitive — Story 3.2 AC-2 / AC-11.
 *
 * ### Vectors
 *
 * The five expected hashes below were computed against the JS SDK's
 * `murmurhash` npm package (version 2.0.1, `Murmurhash.v3(input, seed)`):
 *
 * ```
 * node -e 'const M = require("murmurhash"); console.log(M.v3("exp_1visitor_abc", 9999))'
 * // → 357799817
 * ```
 *
 * The Kotlin library `com.goncalossilva:murmurhash` must produce the exact
 * same unsigned 32-bit integer for the identical byte sequence — Story 3.5
 * extends this micro-parity test to 50+ vectors, including multi-byte UTF-8
 * inputs, long strings, and edge-case seeds.
 *
 * ### Why we drive the hash through [BucketingManager] rather than
 * [com.goncalossilva.murmurhash.MurmurHash3] directly
 *
 * The `BucketingManager.getValueVisitorBased` call site fixes the critical
 * parity knobs — input-string assembly (`experienceId + visitorId`, no
 * separator), UTF-8 encoding, seed plumbing — that an isolated hash call
 * could not validate. Verifying the full `getValueVisitorBased` output is
 * stricter than checking the raw 32-bit hash: if either the encoding, the
 * seed wiring, or the unsigned-mask step regresses, these tests will fail.
 *
 * ### Back-computing expected values
 *
 * For each vector, the expected value is
 *
 * ```
 * val = (hash / 4_294_967_296.0) * maxTraffic
 * result = val.toInt()  // truncation (matches parseInt(String(val), 10) in JS)
 * ```
 *
 * Vectors are chosen to cover: default seed, zero seed, empty input,
 * medium-length UUID-style input, single-char input with a non-default seed.
 */
internal class HashAlgorithmTest {

    private val logger: Logger = Logger.NoOp

    @Test
    fun `vector 1 — default seed with experience+visitor pair`() {
        // hash("exp_1visitor_abc", 9999) = 357799817
        // val = (357799817 / 2^32) * 10000 = 833.0676...
        // result = 833
        val manager = BucketingManager(ConvertConfig(), logger)
        val value = manager.getValueVisitorBased(
            visitorId = "visitor_abc",
            experienceId = "exp_1",
        )
        assertEquals(833, value)
    }

    @Test
    fun `vector 2 — empty input string`() {
        // hash("", 9999) = 3523940263
        // val = (3523940263 / 2^32) * 10000 = 8204.8128...
        // result = 8204
        val manager = BucketingManager(ConvertConfig(), logger)
        val value = manager.getValueVisitorBased(
            visitorId = "",
            experienceId = "",
        )
        assertEquals(8204, value)
    }

    @Test
    fun `vector 3 — zero seed treated as valid (not as null fallback)`() {
        // hash("visitor_42", 0) = 451819425
        // val = (451819425 / 2^32) * 10000 = 1051.9740...
        // result = 1051
        val manager = BucketingManager(ConvertConfig(), logger)
        val value = manager.getValueVisitorBased(
            visitorId = "visitor_42",
            experienceId = "",
            seed = 0,
        )
        assertEquals(1051, value)
    }

    @Test
    fun `vector 4 — long UUID-style input with default seed`() {
        // hash("exp_bucketexperience-visitor-uuid-1234-5678-90abcdef", 9999) = 1561582017
        // val = (1561582017 / 2^32) * 10000 = 3635.8414...
        // result = 3635
        val manager = BucketingManager(ConvertConfig(), logger)
        val value = manager.getValueVisitorBased(
            visitorId = "experience-visitor-uuid-1234-5678-90abcdef",
            experienceId = "exp_bucket",
        )
        assertEquals(3635, value)
    }

    @Test
    fun `vector 5 — single-char input with non-default seed`() {
        // hash("a", 12345) = 2503289909
        // val = (2503289909 / 2^32) * 10000 = 5828.4260...
        // result = 5828
        val manager = BucketingManager(ConvertConfig(), logger)
        val value = manager.getValueVisitorBased(
            visitorId = "a",
            experienceId = "",
            seed = 12345,
        )
        assertEquals(5828, value)
    }
}
