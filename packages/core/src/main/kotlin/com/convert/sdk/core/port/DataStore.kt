/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Port abstraction for synchronous key/value persistence.
 *
 * Operations are synchronous because Android's `SharedPreferences` reads on
 * cold start are synchronous, and the architecture requires synchronous visitor
 * ID reads during SDK initialization (see Architecture §Data Architecture).
 * Writes are also synchronous from the caller's perspective — adapters may
 * persist asynchronously on a background thread if they wish, but
 * [`DataStore.get`] after a [`DataStore.set`] must reflect the new value.
 */
internal interface DataStore {

    /**
     * Returns the value previously stored under [key], or `null` if no value is
     * stored.
     */
    fun get(key: String): String?

    /**
     * Stores [value] under [key], overwriting any previous value.
     */
    fun set(key: String, value: String)

    /**
     * Removes the entry stored under [key]. A no-op if no value is stored.
     */
    fun remove(key: String)

    /**
     * Removes every entry held by the store.
     */
    fun clear()
}
