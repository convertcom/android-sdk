/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for the Story 3.1 real setter bodies on [ConvertContext].
 *
 * Scope:
 *  - replace-not-merge semantics on all four setters (AC-5)
 *  - fluent `this`-returning for chaining
 *  - internal JsonElement coercion accessors (used by Stories 3.4 / 5.1)
 *  - @Volatile thread-safety (AC-6)
 *
 * Pure Kotlin — no Robolectric; [ConvertContext] has no Android
 * dependencies.
 */
internal class ConvertContextTest {

    // --- replace-not-merge semantics (AC-5) ------------------------------

    @Test
    fun `setAttributes replaces not merges`() {
        val ctx = ConvertContext("v")
        ctx.setAttributes(mapOf("plan" to "premium"))
        ctx.setAttributes(mapOf("tier" to "gold"))

        val attrs = ctx.currentAttributes()
        assertEquals(mapOf("tier" to JsonPrimitive("gold")), attrs)
        assertTrue(attrs["plan"] == null) { "expected 'plan' key to be dropped after replace" }
    }

    @Test
    fun `setLocationProperties replaces not merges`() {
        val ctx = ConvertContext("v")
        ctx.setLocationProperties(mapOf("country" to "US"))
        ctx.setLocationProperties(mapOf("city" to "NYC"))

        val props = ctx.currentLocationProperties()
        assertEquals(mapOf("city" to JsonPrimitive("NYC")), props)
        assertTrue(props["country"] == null)
    }

    @Test
    fun `setDefaultSegments replaces not merges`() {
        val ctx = ConvertContext("v")
        ctx.setDefaultSegments(mapOf("plan" to "free"))
        ctx.setDefaultSegments(mapOf("tier" to "gold"))

        val segs = ctx.currentDefaultSegments()
        assertEquals(mapOf("tier" to "gold"), segs)
        assertTrue(segs["plan"] == null)
    }

    @Test
    fun `setCustomSegments replaces not merges`() {
        val ctx = ConvertContext("v")
        ctx.setCustomSegments(mapOf("score" to 42))
        ctx.setCustomSegments(mapOf("grade" to "A"))

        val segs = ctx.currentCustomSegments()
        assertEquals(mapOf("grade" to JsonPrimitive("A")), segs)
        assertTrue(segs["score"] == null)
    }

    // --- fluent chaining --------------------------------------------------

    @Test
    fun `setters return this for chaining`() {
        val ctx = ConvertContext("v")
        val result = ctx
            .setAttributes(mapOf("a" to 1))
            .setLocationProperties(mapOf("l" to "here"))
            .setDefaultSegments(mapOf("s" to "x"))
            .setCustomSegments(mapOf("c" to 2))

        assertSame(ctx, result)
    }

    // --- internal JsonElement coercion accessors -------------------------

    @Test
    fun `currentAttributes coerces primitives to JsonPrimitive`() {
        val ctx = ConvertContext("v")
        ctx.setAttributes(
            mapOf(
                "s" to "hello",
                "i" to 42,
                "b" to true,
                "n" to null,
            ),
        )

        val result = ctx.currentAttributes()
        assertEquals(JsonPrimitive("hello"), result["s"])
        assertEquals(JsonPrimitive(42), result["i"])
        assertEquals(JsonPrimitive(true), result["b"])
        assertEquals(JsonNull, result["n"])
    }

    @Test
    fun `currentAttributes coerces arbitrary object via toString`() {
        val ctx = ConvertContext("v")
        val obj = MyTestClass("payload")
        ctx.setAttributes(mapOf("o" to obj))

        val result = ctx.currentAttributes()
        assertEquals(JsonPrimitive(obj.toString()), result["o"])
    }

    @Test
    fun `currentAttributes returns empty when unset`() {
        val ctx = ConvertContext("v")
        assertEquals(emptyMap<String, Any>(), ctx.currentAttributes())
    }

    @Test
    fun `currentAttributes passes through JsonElement verbatim`() {
        val ctx = ConvertContext("v")
        val primitive = JsonPrimitive("already-encoded")
        ctx.setAttributes(mapOf("e" to primitive))

        val result = ctx.currentAttributes()
        assertSame(primitive, result["e"])
    }

    @Test
    fun `currentCustomSegments coerces same as attributes`() {
        val ctx = ConvertContext("v")
        ctx.setCustomSegments(mapOf("x" to 7, "y" to "seven"))

        val result = ctx.currentCustomSegments()
        assertEquals(JsonPrimitive(7), result["x"])
        assertEquals(JsonPrimitive("seven"), result["y"])
    }

    @Test
    fun `currentDefaultSegments returns empty when unset`() {
        val ctx = ConvertContext("v")
        assertEquals(emptyMap<String, String>(), ctx.currentDefaultSegments())
    }

    // --- @Volatile thread-safety (AC-6) ----------------------------------

    @Test
    fun `concurrent setter calls do not corrupt state`() {
        val ctx = ConvertContext("v")
        val threadCount = 100
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            pool.submit {
                ready.countDown()
                go.await()
                ctx.setAttributes(mapOf("t" to t))
                done.countDown()
            }
        }
        ready.await()
        go.countDown()
        done.await(5, TimeUnit.SECONDS)
        pool.shutdownNow()

        // After all threads finish, attributes must contain exactly one key
        // (the replace-semantics winner) — not a corrupt mixture.
        val attrs = ctx.currentAttributes()
        assertEquals(1, attrs.size, "expected exactly one key after concurrent replaces; got $attrs")
        assertTrue(attrs.containsKey("t"))
    }

    // --- helper class for toString coercion ------------------------------

    private class MyTestClass(val label: String) {
        override fun toString(): String = "MyTestClass($label)"
    }
}
