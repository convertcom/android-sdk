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
 * All writes use [SharedPreferences.Editor.apply] (asynchronous in-process
 * commit with a synchronous in-memory update), not [SharedPreferences.Editor.commit]
 * (synchronous disk write). This matches the [DataStore] port's "safe to
 * call from any coroutine context" contract — `apply()` never blocks the
 * caller, and subsequent reads through the same [SharedPreferences]
 * instance see the new value immediately.
 *
 * The adapter is `internal` to `:packages:sdk`; consumers obtain a
 * [DataStore] via [com.convert.sdk.android.ConvertSDK.builder] wiring, not
 * directly.
 *
 * @property prefs the underlying SharedPreferences instance. The adapter
 *   does not own the instance — the caller (typically
 *   [com.convert.sdk.android.ConvertSDK.Builder.build]) obtains it via
 *   `context.getSharedPreferences("com.convert.sdk.visitor", MODE_PRIVATE)`.
 */
internal class SharedPrefsDataStore(
    private val prefs: SharedPreferences,
) : DataStore {

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
