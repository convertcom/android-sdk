/*
 * Convert Android SDK — core/port
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Key-value store abstraction for per-visitor persistent state.
 *
 * Operations are **synchronous** because the SDK needs to read the visitor
 * ID on cold start without blocking initialization on a coroutine dispatch.
 * On Android the concrete adapter is `SharedPrefsDataStore` (Story 2.1),
 * whose reads are synchronous in practice. Pure-JVM test adapters should
 * back this with a `ConcurrentHashMap`.
 *
 * Declared `public` so that `:packages:sdk` adapters — which live in a
 * separate Gradle module — can implement this interface. Application
 * code should not implement this port directly (Story 2.1).
 */
public interface DataStore {

    /**
     * Reads the value stored under [key].
     *
     * @param key the lookup key.
     * @return the stored value, or `null` if the key is absent.
     */
    public fun get(key: String): String?

    /**
     * Writes [value] under [key], overwriting any previous value.
     *
     * @param key the lookup key.
     * @param value the value to persist.
     */
    public fun set(key: String, value: String)

    /**
     * Removes the entry stored under [key]. No-op if the key is absent.
     *
     * @param key the lookup key.
     */
    public fun remove(key: String)

    /**
     * Clears all entries owned by this store. Callers should use this only
     * for reset flows (e.g. privacy opt-out).
     */
    public fun clear()
}
