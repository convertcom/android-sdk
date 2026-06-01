/*
 * Convert Android SDK — sdk/adapter tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed tests for [SharedPrefsDataStore]. Story 2.1 AC-7.
 *
 * Verifies every [com.convert.sdk.core.port.DataStore] contract method
 * against Robolectric's in-memory SharedPreferences implementation — no
 * mocks, no spies. This gives highest-confidence evidence that the
 * adapter behaves identically to a real device.
 */
@RunWith(RobolectricTestRunner::class)
internal class SharedPrefsDataStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SharedPrefsDataStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SharedPrefsDataStore(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `get returns null for an absent key`() {
        assertNull(store.get("missing"))
    }

    @Test
    fun `set then get returns the stored value`() {
        store.set("visitor-id", "abc-123")
        assertEquals("abc-123", store.get("visitor-id"))
    }

    @Test
    fun `set overwrites a prior value for the same key`() {
        store.set("k", "first")
        store.set("k", "second")
        assertEquals("second", store.get("k"))
    }

    @Test
    fun `remove deletes the key, returning null on subsequent get`() {
        store.set("key", "value")
        assertEquals("value", store.get("key"))

        store.remove("key")
        assertNull(store.get("key"))
    }

    @Test
    fun `remove of absent key is a no-op`() {
        store.remove("never-was-set")
        assertNull(store.get("never-was-set"))
    }

    @Test
    fun `clear removes every stored entry`() {
        store.set("a", "1")
        store.set("b", "2")
        store.set("c", "3")

        store.clear()

        assertNull(store.get("a"))
        assertNull(store.get("b"))
        assertNull(store.get("c"))
        // Underlying SharedPreferences must reflect the clear too.
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `values persist to the underlying SharedPreferences instance`() {
        store.set("persisted", "hello")

        // Read via the raw SharedPreferences — proves the adapter goes
        // through real storage, not an in-memory shadow of its own.
        assertEquals("hello", prefs.getString("persisted", null))
    }

    @Test
    fun `set uses apply which commits the write synchronously for test purposes`() {
        // In Robolectric, apply() is synchronous. Verify immediate visibility.
        store.set("now", "visible")
        assertEquals("visible", prefs.getString("now", null))
    }

    @Test
    fun `multiple keys can coexist without collision`() {
        store.set("visitor-id", "v-1")
        store.set("segment", "premium")
        store.set("last-sync", "2026-04-20")

        assertEquals("v-1", store.get("visitor-id"))
        assertEquals("premium", store.get("segment"))
        assertEquals("2026-04-20", store.get("last-sync"))
    }

    @Test
    fun `empty-string value is stored and retrievable (not coalesced to null)`() {
        store.set("empty", "")
        assertEquals("", store.get("empty"))
    }

    private companion object {
        // Name the test prefs file so we can target it specifically.
        const val PREFS_NAME = "com.convert.sdk.visitor.test"
    }
}
