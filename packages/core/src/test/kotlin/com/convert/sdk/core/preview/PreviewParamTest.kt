/*
 * Convert Android SDK — core/preview tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Table-driven parity matrix for [PreviewParam.parse] — qs-02 AC9. Mirrors
 * the JS SDK's `parsePreviewParam` test matrix
 * (`packages/js-sdk/tests/parse-preview-param.tests.ts`), minus the
 * non-string-input cases that Kotlin's type system already rejects at
 * compile time.
 */
internal class PreviewParamTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `parse matches the expected pair or null`(name: String, input: String, expected: Pair<String, String>?) {
        val actual = PreviewParam.parse(input)
        if (expected == null) {
            assertNull(actual, "case=\"$name\" input=\"$input\"")
        } else {
            assertEquals(expected, actual, "case=\"$name\" input=\"$input\"")
        }
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "parses a well-formed numeric experienceId.variationId pair",
                "123.456",
                "123" to "456",
            ),
            Arguments.of("returns null for an empty string", "", null),
            Arguments.of("returns null when there is no dot separator", "123", null),
            Arguments.of("returns null when the variationId segment is empty", "123.", null),
            Arguments.of("returns null when the experienceId segment is empty", ".456", null),
            Arguments.of("returns null when both segments are non-numeric", "a.b", null),
            Arguments.of("returns null when a segment is partially non-numeric", "12a.34", null),
            Arguments.of("returns null when there is more than one dot", "1.2.3", null),
            Arguments.of("returns null for a whitespace-only string", "   ", null),
            Arguments.of("returns null for a negative-number segment", "-1.2", null),
            Arguments.of("returns null for leading whitespace around a valid pair", " 123.456", null),
            Arguments.of("returns null for trailing whitespace around a valid pair", "123.456 ", null),
        )
    }
}
