/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Robolectric-backed tests for Story 3.1 createContext persistence
 * (AC-1 / AC-2 / AC-3 / AC-4 / AC-10).
 *
 * The tests use [ApplicationProvider.getApplicationContext] which
 * Robolectric keeps backed by the same SharedPreferences mock file
 * across repeated invocations in the same class — that backing is how
 * we simulate a "process restart": two separate `ConvertSDK.build()`
 * calls against the same application context share the underlying
 * `com.convert.sdk.visitor` SharedPreferences file, so the auto-UUID
 * persisted by instance 1 is read back by instance 2.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKContextTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        // Clear the SharedPreferences backing between tests so each test
        // starts from a pristine "never called no-arg before" state.
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun buildSdk(): ConvertSDK =
        ConvertSDK.builder(appContext).data(ConfigResponseData()).build()

    // --- AC-1: no-arg generates + persists UUID ---------------------------

    @Test
    fun `createContext with no args generates and persists UUID`() {
        val sdk = buildSdk()

        val ctx = sdk.createContext()

        // Must be a UUID-string that parses without throwing.
        val parsed = UUID.fromString(ctx.visitorId)
        assertEquals(
            "UUID.randomUUID is v4 by contract",
            4,
            parsed.version(),
        )

        // Assert the key was actually written to SharedPreferences (not
        // just held in memory).
        val prefs = appContext.getSharedPreferences(
            "com.convert.sdk.visitor",
            Context.MODE_PRIVATE,
        )
        assertEquals(ctx.visitorId, prefs.getString("visitor_id", null))
    }

    @Test
    fun `createContext called twice in same SDK instance returns same persisted UUID`() {
        val sdk = buildSdk()

        val a = sdk.createContext()
        val b = sdk.createContext()

        assertEquals(a.visitorId, b.visitorId)
    }

    @Test
    fun `createContext across process restarts returns same UUID`() {
        // "Process restart" — two separate Builder.build() calls, same
        // application context (and therefore same SharedPreferences file).
        val firstInstance = buildSdk()
        val id1 = firstInstance.createContext().visitorId

        val secondInstance = buildSdk()
        val id2 = secondInstance.createContext().visitorId

        assertEquals(id1, id2)
    }

    @Test
    fun `createContext no-arg produces a UUID v4 format`() {
        val sdk = buildSdk()

        val ctx = sdk.createContext()

        val regex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        assertTrue(
            "expected UUID v4 format, got: ${ctx.visitorId}",
            regex.matches(ctx.visitorId),
        )
    }

    // --- AC-2: explicit ID bypasses persisted UUID ------------------------

    @Test
    fun `createContext with explicit ID does not read persisted UUID`() {
        val sdk = buildSdk()

        val ctx = sdk.createContext("explicit-visitor")

        assertEquals("explicit-visitor", ctx.visitorId)

        // AC-2: the persisted UUID is NEITHER read NOR overwritten — in
        // particular, the explicit-ID variant must not have auto-generated
        // a fallback UUID.
        val prefs = appContext.getSharedPreferences(
            "com.convert.sdk.visitor",
            Context.MODE_PRIVATE,
        )
        assertNull(
            "explicit-ID path should not have written visitor_id",
            prefs.getString("visitor_id", null),
        )
    }

    @Test
    fun `createContext with explicit ID does not overwrite persisted UUID`() {
        val sdk = buildSdk()

        // Seed a persisted UUID via a no-arg call.
        val autoId = sdk.createContext().visitorId

        // Call with an explicit ID — must not mutate the persisted key.
        sdk.createContext("some-other-visitor")

        // Next no-arg call returns the ORIGINAL auto-UUID, not the explicit one.
        val thirdCall = sdk.createContext()
        assertEquals(autoId, thirdCall.visitorId)
        assertNotEquals("some-other-visitor", thirdCall.visitorId)
    }

    // --- AC-4: multi-visitor independence ---------------------------------

    @Test
    fun `createContext with multiple IDs returns independent contexts`() {
        val sdk = buildSdk()

        val a = sdk.createContext("visitor-A").setAttributes(mapOf("x" to 1))
        val b = sdk.createContext("visitor-B").setAttributes(mapOf("y" to 2))

        assertEquals("visitor-A", a.visitorId)
        assertEquals("visitor-B", b.visitorId)
        assertNotEquals(a.visitorId, b.visitorId)

        // Mutating one does not touch the other.
        assertEquals(mapOf("x" to JsonPrimitive(1)), a.currentAttributes())
        assertEquals(mapOf("y" to JsonPrimitive(2)), b.currentAttributes())
    }

    @Test
    fun `createContext multiple explicit IDs do not consult persisted UUID`() {
        val sdk = buildSdk()

        sdk.createContext("visitor-A")
        sdk.createContext("visitor-B")

        // Neither call touched SharedPreferences — visitor_id key still absent.
        val prefs = appContext.getSharedPreferences(
            "com.convert.sdk.visitor",
            Context.MODE_PRIVATE,
        )
        assertNull(prefs.getString("visitor_id", null))
    }

    // --- AC-3: createContext(visitorId, attributes) ----------------------

    @Test
    fun `createContext with attributes applies them via setAttributes`() {
        val sdk = buildSdk()

        val ctx = sdk.createContext("v", mapOf("plan" to "premium"))

        assertEquals(
            mapOf("plan" to JsonPrimitive("premium")),
            ctx.currentAttributes(),
        )
    }

    @Test
    fun `createContext with null attributes yields empty attributes`() {
        val sdk = buildSdk()

        val ctx = sdk.createContext("v", null)

        assertEquals(emptyMap<String, JsonPrimitive>(), ctx.currentAttributes())
    }

    @Test
    fun `createContext with attributes does not read persisted UUID`() {
        val sdk = buildSdk()

        sdk.createContext("v", mapOf("plan" to "free"))

        val prefs = appContext.getSharedPreferences(
            "com.convert.sdk.visitor",
            Context.MODE_PRIVATE,
        )
        assertNull(prefs.getString("visitor_id", null))
    }

    // --- sanity: context ref isn't null ----------------------------------

    @Test
    fun `all three createContext overloads return non-null contexts`() {
        val sdk = buildSdk()

        assertNotNull(sdk.createContext())
        assertNotNull(sdk.createContext("explicit"))
        assertNotNull(sdk.createContext("explicit", mapOf("a" to 1)))
    }
}
