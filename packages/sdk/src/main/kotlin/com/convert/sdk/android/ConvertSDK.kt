/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.BucketingConfig
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.config.LoggerConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.generated.ConfigResponseData
import java.util.UUID

/**
 * Public SDK entry point.
 *
 * Instances are created exclusively via [ConvertSDK.builder]; the primary
 * constructor is `internal` so that consumer code cannot bypass the
 * Builder's invariant setup. The assembled [ConvertConfig] is kept
 * `internal` so later stories (2.1+) can read it when wiring the real
 * managers without re-exposing it through the public API.
 *
 * Every public method on this class is a deliberately stubbed skeleton in
 * Story 1.2 — their real bodies land in Stories 2.1 / 2.4 as referenced by
 * the per-method `TODO(Story X.Y)` comments. The method signatures are
 * frozen now so downstream stories extend rather than churn the surface.
 *
 * @property config the fully-assembled configuration passed in by
 *   [Builder.build]. Kept `internal` so only SDK internals read it.
 */
public class ConvertSDK internal constructor(
    internal val config: ConvertConfig,
) {

    private val readyCallbacks: MutableList<Runnable> = mutableListOf()
    private val eventSubscribers: MutableMap<String, MutableList<EventCallback>> = mutableMapOf()

    /**
     * Runtime tracking-enabled flag. Seeded from
     * [ConvertConfig.network]?.tracking when the SDK is built; the public
     * setter [setTrackingEnabled] flips it at runtime.
     *
     * Story 5.4 wires this flag into the real `ApiManager.setTrackingEnabled`
     * delegation path; for the Story 1.2 skeleton it lives on this class
     * so the public API surface is frozen now. The `true` fallback
     * matches `ConfigDefaults.DEFAULT_TRACKING_ENABLED` (which is
     * `internal` to the core module and cannot be referenced from here).
     */
    private var trackingEnabled: Boolean = config.network?.tracking ?: true

    /**
     * Creates a [ConvertContext] for a freshly minted visitor id.
     *
     * A random UUID stands in for the visitor id until Story 3.1 wires
     * SharedPreferences-backed persistence; today every call produces a
     * new id, which is acceptable for the skeleton.
     *
     * @return a context scoped to the generated visitor id.
     */
    public fun createContext(): ConvertContext {
        // TODO(Story 3.1): persist to SharedPreferences for stable visitor ID across launches
        return ConvertContext(UUID.randomUUID().toString())
    }

    /**
     * Creates a [ConvertContext] for the supplied visitor id.
     *
     * @param visitorId stable visitor identifier supplied by the caller.
     * @return a context scoped to [visitorId].
     */
    public fun createContext(visitorId: String): ConvertContext {
        // TODO(Story 3.1): wire to DataManager-backed context construction
        return ConvertContext(visitorId)
    }

    /**
     * Creates a [ConvertContext] for the supplied visitor id and seeds
     * the initial attribute map.
     *
     * @param visitorId stable visitor identifier supplied by the caller.
     * @param attributes initial attributes forwarded to the new context;
     *   `null` leaves attributes unset.
     * @return a context scoped to [visitorId] with [attributes] applied.
     */
    public fun createContext(
        visitorId: String,
        attributes: Map<String, Any?>?,
    ): ConvertContext {
        val context = ConvertContext(visitorId)
        if (attributes != null) {
            context.setAttributes(attributes)
        }
        return context
    }

    /**
     * Registers a callback to be invoked once the SDK has finished
     * bootstrapping.
     *
     * Declared to take a [Runnable] so Java lambdas map cleanly. Real
     * wiring lands in Story 2.1 (ready state) and Story 2.4 (event bus).
     *
     * @param callback invoked when the SDK becomes ready.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun onReady(callback: Runnable): ConvertSDK {
        // TODO(Story 2.1): invoke on actual ready state once DataManager is wired
        readyCallbacks += callback
        return this
    }

    /**
     * Subscribes [callback] to the named [event].
     *
     * Stubbed in Story 1.2 — the event bus lands in Story 2.4.
     *
     * @param event well-known event name (see Story 2.4 event catalogue).
     * @param callback listener invoked when [event] is emitted.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun on(event: String, callback: EventCallback): ConvertSDK {
        // TODO(Story 2.4): wire to the internal event bus
        eventSubscribers.getOrPut(event) { mutableListOf() }.add(callback)
        return this
    }

    /**
     * Removes a previously-registered [callback] for [event].
     *
     * Mirror of [on]; the two are intentionally symmetric.
     *
     * @param event the event name the callback was registered against.
     * @param callback the listener to unregister. A listener that was
     *   never registered is silently ignored.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun off(event: String, callback: EventCallback): ConvertSDK {
        // TODO(Story 2.4): propagate through the internal event bus
        eventSubscribers[event]?.remove(callback)
        return this
    }

    /**
     * Flips the runtime tracking-enabled flag.
     *
     * Story 5.4 wires this into `ApiManager.setTrackingEnabled` so the
     * flag is read on every enqueue. For the Story 1.2 skeleton the flag
     * is held locally so the public API surface is frozen now and Story
     * 5.4 only swaps the implementation, not the signature.
     *
     * @param enabled `true` to enable outbound tracking, `false` to drop
     *   subsequent events at the enqueue boundary.
     */
    public fun setTrackingEnabled(enabled: Boolean) {
        // TODO(Story 5.4): delegate to apiManager.setTrackingEnabled(enabled)
        trackingEnabled = enabled
    }

    /**
     * Reports whether outbound tracking is currently enabled.
     *
     * @return the current value of the tracking-enabled flag.
     */
    public fun isTrackingEnabled(): Boolean {
        // TODO(Story 5.4): delegate to apiManager.isTrackingEnabled()
        return trackingEnabled
    }

    public companion object {
        /**
         * Creates a new [Builder].
         *
         * The passed [Context] is immediately reduced to its
         * [Context.getApplicationContext] so the SDK never holds onto an
         * Activity or Fragment (architecture NFR: no Activity/Fragment
         * references).
         *
         * @param context any Android context; only its application
         *   context is retained.
         * @return an initialised [Builder] ready for fluent configuration.
         */
        @JvmStatic
        public fun builder(context: Context): Builder =
            Builder(context.applicationContext)
    }

    /**
     * Fluent configurator for [ConvertSDK].
     *
     * Every setter returns this builder so Java and Kotlin consumers can
     * chain calls. [build] assembles a [ConvertConfig] from the collected
     * values and constructs the [ConvertSDK].
     *
     * The constructor is `internal` — instances are obtained via
     * [ConvertSDK.builder].
     *
     * Note on `@JvmOverloads`: Story 1.2 Gotcha 9 explains the annotation
     * is "technically unnecessary" on single-parameter builder setters
     * because there is no Kotlin default argument to elide for Java. The
     * Kotlin compiler additionally flags the annotation as ineffective
     * when applied, which would violate the story's zero-warning build
     * requirement. The annotation is omitted here for that reason and
     * reintroduced only on future methods that carry actual default
     * parameters.
     *
     * @property appContext application-scoped context retained for later
     *   adapter wiring (Story 2.2).
     */
    public class Builder internal constructor(
        internal val appContext: Context,
    ) {

        private var sdkKey: String? = null
        private var sdkKeySecret: String? = null
        private var data: ConfigResponseData? = null
        private var environment: String? = null
        private var configEndpoint: String? = null
        private var trackEndpoint: String? = null
        private var dataRefreshInterval: Long? = null
        private var batchSize: Int? = null
        private var releaseInterval: Long? = null
        private var hashSeed: Int? = null
        private var maxTraffic: Int? = null
        private var logLevel: LogLevel? = null
        private var trackingEnabled: Boolean? = null
        private var cacheLevel: String? = null

        /** Sets the merchant SDK key. */
        public fun sdkKey(value: String): Builder = apply { sdkKey = value }

        /** Sets the confidential SDK secret. */
        public fun sdkKeySecret(value: String): Builder = apply { sdkKeySecret = value }

        /**
         * Provides a pre-fetched configuration blob, skipping the initial HTTP fetch.
         *
         * @param config the pre-fetched configuration response.
         */
        public fun data(config: ConfigResponseData): Builder = apply { data = config }

        /** Sets the deployment environment hint (`"staging"` / `"prod"`). */
        public fun environment(value: String): Builder = apply { environment = value }

        /** Overrides the configuration-fetch endpoint URL. */
        public fun configEndpoint(url: String): Builder = apply { configEndpoint = url }

        /** Overrides the tracking endpoint URL. */
        public fun trackEndpoint(url: String): Builder = apply { trackEndpoint = url }

        /** Sets the configuration refresh interval in milliseconds. */
        public fun dataRefreshInterval(millis: Long): Builder = apply {
            dataRefreshInterval = millis
        }

        /** Sets the maximum batch size before the event queue flushes. */
        public fun batchSize(size: Int): Builder = apply { batchSize = size }

        /** Sets the minimum interval between event-queue flushes in milliseconds. */
        public fun releaseInterval(millis: Long): Builder = apply { releaseInterval = millis }

        /** Sets the MurmurHash3 seed used by the bucketing engine. */
        public fun hashSeed(seed: Int): Builder = apply { hashSeed = seed }

        /** Sets the total traffic basis points for the bucketing engine. */
        public fun maxTraffic(maxTraffic: Int): Builder = apply { this.maxTraffic = maxTraffic }

        /** Sets the minimum log severity emitted by the logger. */
        public fun logLevel(level: LogLevel): Builder = apply { logLevel = level }

        /** Enables or disables outbound tracking HTTP requests. */
        public fun trackingEnabled(enabled: Boolean): Builder = apply { trackingEnabled = enabled }

        /** Sets the HTTP cache directive hint (`"default"` / `"low"`). */
        public fun cacheLevel(level: String): Builder = apply { cacheLevel = level }

        /**
         * Assembles a [ConvertConfig] from the collected builder values and
         * returns a fresh [ConvertSDK].
         *
         * No network, no async work, no manager wiring — just constructs
         * the instance. Manager bootstrapping lands in Story 2.1.
         *
         * @return a new [ConvertSDK] ready for further wiring.
         */
        public fun build(): ConvertSDK {
            val apiConfig = if (configEndpoint != null || trackEndpoint != null) {
                ApiConfig(endpoint = ApiEndpoint(config = configEndpoint, track = trackEndpoint))
            } else {
                null
            }
            val bucketingConfig = if (hashSeed != null || maxTraffic != null) {
                BucketingConfig(hashSeed = hashSeed, maxTraffic = maxTraffic)
            } else {
                null
            }
            val eventsConfig = if (batchSize != null || releaseInterval != null) {
                EventsConfig(batchSize = batchSize, releaseInterval = releaseInterval)
            } else {
                null
            }
            val loggerConfig = logLevel?.let { LoggerConfig(logLevel = it) }
            val networkConfig = if (trackingEnabled != null || cacheLevel != null) {
                NetworkConfig(tracking = trackingEnabled, cacheLevel = cacheLevel)
            } else {
                null
            }
            val defaults = ConvertConfig()
            val assembled = ConvertConfig(
                sdkKey = sdkKey,
                sdkKeySecret = sdkKeySecret,
                data = data,
                environment = environment ?: defaults.environment,
                api = apiConfig,
                bucketing = bucketingConfig,
                dataRefreshInterval = dataRefreshInterval ?: defaults.dataRefreshInterval,
                events = eventsConfig,
                logger = loggerConfig,
                network = networkConfig,
            )
            return ConvertSDK(config = assembled)
        }
    }
}
