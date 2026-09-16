/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * CAP-1 / CAP-3 (SPEC-per-call-bucketing-attributes) RED-phase binary
 * compatibility guard.
 *
 * `@JvmOverloads` is the only thing regenerating a widened `public fun`'s
 * original JVM descriptor, and this repo has no binary-compatibility
 * validator — this file is the only check on it.
 *
 * `runExperience`/`runExperiences` are the positive control: if they go
 * red too, the failure is environmental, not the new arities.
 */
internal class ConvertContextPublicArityTest {

    @ParameterizedTest(name = "{2}")
    @MethodSource("descriptorCases")
    fun `ConvertContext declares the public descriptor an app on the published AAR may call`(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        why: String,
    ) {
        val method = try {
            ConvertContext::class.java.getDeclaredMethod(methodName, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            throw AssertionError(
                "ConvertContext.$methodName(${parameterTypes.joinToString { it.simpleName }}) " +
                    "is missing its JVM descriptor. $why An app compiled against the " +
                    "published AAR that calls this overload will crash at run time with " +
                    "NoSuchMethodError once this SDK version ships.",
                e,
            )
        }
        assertNotNull(method, "reflection lookup unexpectedly returned null for $methodName")
    }

    private companion object {
        @JvmStatic
        fun descriptorCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "runExperience",
                arrayOf<Class<*>>(String::class.java),
                "positive control — already shipped, must survive CAP-1",
            ),
            Arguments.of(
                "runExperience",
                arrayOf<Class<*>>(String::class.java, java.lang.Boolean.TYPE),
                "positive control — already shipped via @JvmOverloads, must survive CAP-1",
            ),
            Arguments.of(
                "runExperiences",
                arrayOf<Class<*>>(),
                "positive control — already shipped, must survive CAP-1",
            ),
            Arguments.of(
                "runExperiences",
                arrayOf<Class<*>>(java.lang.Boolean.TYPE),
                "positive control — already shipped via @JvmOverloads, must survive CAP-1",
            ),
            Arguments.of(
                "runFeature",
                arrayOf<Class<*>>(String::class.java),
                "existing arity — must survive the CAP-1 signature widening",
            ),
            Arguments.of(
                "runFeature",
                arrayOf<Class<*>>(String::class.java, java.lang.Boolean.TYPE),
                "CAP-1's new overload — needs @JvmOverloads on the widened runFeature",
            ),
            Arguments.of(
                "runFeatures",
                arrayOf<Class<*>>(),
                "existing arity — must survive the CAP-1 signature widening",
            ),
            Arguments.of(
                "runFeatures",
                arrayOf<Class<*>>(java.lang.Boolean.TYPE),
                "CAP-1's new overload — needs @JvmOverloads on the widened runFeatures",
            ),
        )
    }
}
