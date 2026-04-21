/*
 * Convert Android SDK — core/data
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.StoreData
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.DataStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Holds the SDK's currently-loaded configuration plus per-visitor
 * [StoreData] (bucketing decisions, goal dedup flags, segments, locations)
 * and notifies subscribers when the global config changes.
 *
 * ### Story 2.1 surface (preserved)
 *
 *  - [data] is the currently-loaded [ConfigResponseData], or `null` when
 *    neither direct-data mode nor a successful config fetch has seeded it.
 *  - [setData] stores the config and fires [SystemEvents.READY] via the
 *    shared [EventManager] so that subscribers (chiefly `ConvertSDK.onReady`)
 *    unblock.
 *  - [hasData] is the cheap `data != null` predicate.
 *
 * ### Story 3.1 surface (visitor state)
 *
 *  - [getStoreData] / [setStoreData] read and write per-visitor state,
 *    transparently caching in memory (LRU capped at [VISITOR_CACHE_CAP])
 *    and persisting to the injected [DataStore] keyed by
 *    `visitor.<sanitized-id>`.
 *  - [updateBucketing] / [updateGoal] are thin helpers: read the current
 *    [StoreData], produce an updated copy, persist it.
 *  - Corrupted on-disk state (a decode failure) is **removed** from the
 *    store and replaced with fresh empty [StoreData] — NFR10/NFR13: the
 *    SDK never crashes on corrupted state.
 *  - Non-alphanumeric characters in visitor IDs are sanitized to `_` in
 *    the SharedPreferences key space. UUIDs (all hex + dashes) pass
 *    through unchanged; dashes are preserved.
 *
 * ### LRU cache
 *
 * A pure-JVM [LinkedHashMap] with `accessOrder = true` + `removeEldestEntry`
 * override provides O(1) get/put with automatic eviction once the cache
 * exceeds [VISITOR_CACHE_CAP] entries. Pure-JVM avoids the
 * `androidx.collection.LruCache` dependency the story originally
 * suggested — the core module is a pure Kotlin/JVM module without an
 * Android classpath.
 *
 * All cache mutations hold a single intrinsic lock on the map. Access is
 * rare enough (once per visitor method call) that a broader lock is
 * simpler than a segmented scheme and still plenty fast; the guarantee
 * matters more than micro-throughput here.
 *
 * Eviction from the in-memory cache does **not** delete the on-disk
 * entry; a subsequent [getStoreData] for an evicted visitor reloads
 * from the [DataStore]. SharedPreferences pruning is deferred (see
 * Story 3.1 Dev Notes, Gotcha 5).
 *
 * ### Thread safety
 *
 * [setData] is called at most once per event-loop tick under current
 * wiring (direct-data path in `Builder.build()` or the config fetch
 * completion — itself serialised by the SDK scope). A `var data`
 * without a lock is therefore safe.
 *
 * The visitor-state path serialises every cache read/write through the
 * intrinsic map lock; [setStoreData] also issues `dataStore.set(...)`
 * while holding the lock so that a cache hit and a disk read can never
 * observe inconsistent states. The on-disk write is small (~100 bytes
 * per StoreData), so the critical section stays short.
 *
 * @property eventManager the shared event bus to publish READY events on.
 * @property environment the active environment (e.g. `"staging"`, `"prod"`)
 *   — included in the READY payload so subscribers can branch on it.
 * @property dataStore the per-visitor persistence store. Defaults to a
 *   no-op so pure-JVM tests that only exercise the Story 2.1 surface
 *   (`setData` / `hasData`) stay source-compatible.
 * @property json the [Json] codec used to serialise [StoreData]. Defaults
 *   to a lenient codec (`ignoreUnknownKeys = true`, `explicitNulls = false`)
 *   matching the Builder's shared instance — so forward-compatible schema
 *   changes do not break on-disk state (NFR12).
 */
public class DataManager(
    private val eventManager: EventManager,
    private val environment: String,
    private val dataStore: DataStore = NoOpDataStore,
    private val json: Json = DEFAULT_JSON,
) {

    public var data: ConfigResponseData? = null
        private set

    /**
     * LRU cache of per-visitor [StoreData]. [LinkedHashMap] with
     * `accessOrder = true` promotes the touched entry to the tail on
     * every `get`, and [removeEldestEntry] returns `true` once the map
     * size exceeds [VISITOR_CACHE_CAP] — the head (least-recently-used)
     * entry is then auto-removed. Access is serialised via the intrinsic
     * lock on [visitorLock].
     */
    private val visitorCache: LinkedHashMap<String, StoreData> =
        object : LinkedHashMap<String, StoreData>(
            VISITOR_CACHE_INITIAL_CAPACITY,
            VISITOR_CACHE_LOAD_FACTOR,
            true, // accessOrder
        ) {
            override fun removeEldestEntry(eldest: Map.Entry<String, StoreData>): Boolean =
                size > VISITOR_CACHE_CAP
        }

    /** Intrinsic lock for every [visitorCache] + [dataStore] visitor-state mutation. */
    private val visitorLock: Any = Any()

    /**
     * Stores the supplied [data] and fires [SystemEvents.READY] with a
     * payload of `{ "environment": environment }`.
     *
     * Calling [setData] a second time is legal — the stored config is
     * overwritten and READY fires again. Story 2.3's refresh loop will
     * switch this second fire to [SystemEvents.CONFIG_UPDATED] once
     * deferred-replay is in place.
     */
    public fun setData(data: ConfigResponseData) {
        this.data = data
        eventManager.fire(
            event = SystemEvents.READY,
            data = mapOf("environment" to environment),
        )
    }

    /**
     * Returns `true` once [setData] has seeded this manager with a
     * configuration, `false` on a fresh instance.
     */
    public fun hasData(): Boolean = data != null

    /**
     * Reads the [StoreData] for [visitorId], loading from [dataStore] on
     * cache miss and returning a fresh empty [StoreData] when the store
     * has no entry for this visitor.
     *
     * Corrupted on-disk state — any [SerializationException] thrown by
     * [Json.decodeFromString] — triggers a cleanup: the corrupt key is
     * removed from [dataStore] and an empty [StoreData] is inserted into
     * the cache. NFR13 corruption recovery.
     *
     * [IllegalArgumentException] is also caught because the
     * `kotlinx.serialization` lenient parser occasionally throws it on
     * malformed root tokens (e.g. `"not json"` — the lexer rejects at the
     * argument-validation layer before hitting the decoder). Catching
     * both keeps the SDK's "never crash on corrupt cache" promise.
     *
     * @param visitorId any string — sanitized internally before keying.
     * @return the current per-visitor [StoreData], never `null`.
     */
    public fun getStoreData(visitorId: String): StoreData = synchronized(visitorLock) {
        visitorCache[visitorId]?.let { return it }
        val key = storeKey(visitorId)
        val raw = dataStore.get(key)
        val resolved = when {
            raw == null -> StoreData()
            else -> decodeOrDrop(raw, key)
        }
        visitorCache[visitorId] = resolved
        resolved
    }

    /**
     * Replaces the [StoreData] for [visitorId] in both the in-memory
     * cache and the persistent [dataStore].
     *
     * @param visitorId any string — sanitized internally before keying.
     * @param data the replacement state.
     */
    public fun setStoreData(visitorId: String, data: StoreData): Unit = synchronized(visitorLock) {
        visitorCache[visitorId] = data
        dataStore.set(storeKey(visitorId), json.encodeToString(StoreData.serializer(), data))
    }

    /**
     * Appends a single `experienceKey -> variationId` entry to the
     * visitor's bucketing map and persists the result. Every other
     * [StoreData] field is preserved.
     *
     * Story 3.2 will add additional bucketing side-effects (tracking
     * events); this method's contract is currently only "persist the
     * decision".
     */
    public fun updateBucketing(
        visitorId: String,
        experienceKey: String,
        variationId: String,
    ) {
        val current = getStoreData(visitorId)
        val merged = (current.bucketing ?: emptyMap()) + (experienceKey to variationId)
        setStoreData(visitorId, current.copy(bucketing = merged))
    }

    /**
     * Sets the goal-dedup flag for the given [goalKey] on the visitor's
     * goals map and persists the result. Used by Story 4.3 to prevent
     * duplicate goal reports per visitor/goal pair.
     */
    public fun updateGoal(
        visitorId: String,
        goalKey: String,
        tracked: Boolean,
    ) {
        val current = getStoreData(visitorId)
        val merged = (current.goals ?: emptyMap()) + (goalKey to tracked)
        setStoreData(visitorId, current.copy(goals = merged))
    }

    /**
     * Decodes [raw] into [StoreData]; on any
     * [SerializationException] / [IllegalArgumentException] — both of
     * which the `kotlinx.serialization` Json parser can throw on
     * malformed input — drops the key and returns a fresh empty state.
     *
     * Extracted into a private helper so [getStoreData] stays within
     * detekt's `ReturnCount` / `LongMethod` ceilings.
     */
    private fun decodeOrDrop(raw: String, key: String): StoreData =
        try {
            json.decodeFromString(StoreData.serializer(), raw)
        } catch (_: SerializationException) {
            dataStore.remove(key)
            StoreData()
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization's lenient parser occasionally throws
            // IllegalArgumentException from the lexer on malformed root
            // tokens (e.g. the whole blob isn't valid JSON at all).
            // Treat it the same as SerializationException so the SDK's
            // "never crash on a corrupt cache" contract holds.
            dataStore.remove(key)
            StoreData()
        }

    public companion object {
        /**
         * Maximum number of visitors held in memory at once. NFR4 — once
         * exceeded, the least-recently-used visitor is evicted from the
         * in-memory cache (but NOT from the persistent [DataStore]).
         */
        public const val VISITOR_CACHE_CAP: Int = 1000

        /**
         * `LinkedHashMap` constructor inputs chosen so the map never
         * resizes while under the cap. Initial capacity is
         * `ceil(cap / loadFactor) + 1` so the growth trigger sits above
         * [VISITOR_CACHE_CAP].
         */
        private const val VISITOR_CACHE_INITIAL_CAPACITY: Int = 1334

        /**
         * Tuned so that the cache never needs to resize before it hits
         * the eviction cap (1000 / 0.75 ≈ 1334 — matches
         * [VISITOR_CACHE_INITIAL_CAPACITY]).
         */
        private const val VISITOR_CACHE_LOAD_FACTOR: Float = 0.75f

        /**
         * Pattern matching a single character that is **not** allowed in
         * a SharedPreferences key. We keep `a-z A-Z 0-9 -` so UUID v4
         * strings (hex + dashes) pass through unchanged; anything else
         * is replaced with `_`.
         */
        private val SANITIZE_PATTERN: Regex = Regex("[^A-Za-z0-9-]")

        /** Key prefix under which per-visitor state is stored. */
        private const val VISITOR_KEY_PREFIX: String = "visitor."

        /**
         * Shared default JSON codec. Mirrors the instance the Builder
         * hands to [com.convert.sdk.core.api.ApiManager] so every cache
         * path reads and writes with identical lenience settings.
         */
        @JvmStatic
        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * Computes the [DataStore] key for the given visitor id by
         * prefixing `visitor.` and replacing any non-alphanumeric (non
         * dash) character with `_`.
         */
        private fun storeKey(visitorId: String): String =
            VISITOR_KEY_PREFIX + SANITIZE_PATTERN.replace(visitorId, "_")
    }

    /**
     * No-op [DataStore] — used as the default when the DataManager is
     * constructed without an explicit store (pure-JVM tests that only
     * exercise the Story 2.1 surface). Writes silently drop; reads
     * always return `null`.
     */
    private object NoOpDataStore : DataStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) { /* intentional no-op */ }
        override fun remove(key: String) { /* intentional no-op */ }
        override fun clear() { /* intentional no-op */ }
    }
}
