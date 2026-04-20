/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.SharedPreferences
import com.convert.sdk.core.port.DataStore

/**
 * [DataStore] adapter that wraps Android's [SharedPreferences].
 *
 * Story 2.1 RED stub — every method returns a placeholder value so the test
 * file (`SharedPrefsDataStoreTest`) can compile and fail at runtime.
 */
internal class SharedPrefsDataStore(
    @Suppress("UnusedPrivateProperty") private val prefs: SharedPreferences,
) : DataStore {

    override fun get(key: String): String? = null
    override fun set(key: String, value: String) = Unit
    override fun remove(key: String) = Unit
    override fun clear() = Unit
}
