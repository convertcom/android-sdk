/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
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
import com.convert.sdk.core.model.ConfigResponseData
import com.convert.sdk.core.model.LogLevel
import java.util.UUID

/**
 * Top-level Convert Android SDK entry point.
 *
 * Instances are created through [ConvertSDK.builder]; the primary constructor
 * is `internal` so consumers cannot bypass the Builder. All public methods on
 * [ConvertSDK] are synchronous to match the JS SDK ergonomics — actual I/O is
 * performed by internal managers running on coroutines (Story 2.1 onward).
 *
 * This is the skeleton landed by Story 1.2. Calls to [createContext], [on],
 * and related methods currently return placeholder values; real behavior is
 * wired up in subsequent stories.
 */
public class ConvertSDK internal constructor(
    internal val config: ConvertConfig,
) {

    /**
     * Creates a new [ConvertContext] with an auto-generated visitor ID.
     *
     * The generated ID is currently in-memory only. Story 3.1 adds
     * `SharedPreferences` persistence so the ID is stable across launches.
     *
     * @return a fresh [ConvertContext] bound to a random UUID.
     */
    // TODO(Story 3.1): persist the visitor ID to SharedPreferences so it
    //  survives process death.
    // `@JvmOverloads` intentionally omitted — the method has no default
    // parameters, so Kotlin would emit a no-op warning (zero-warnings policy).
    public fun createContext(): ConvertContext {
        return ConvertContext(visitorId = UUID.randomUUID().toString())
    }

    /**
     * Creates a new [ConvertContext] bound to the supplied [visitorId].
     *
     * @param visitorId caller-supplied stable visitor identifier.
     */
    public fun createContext(visitorId: String): ConvertContext {
        return ConvertContext(visitorId = visitorId)
    }

    /**
     * Creates a new [ConvertContext] with caller-supplied [visitorId] and
     * attributes.
     *
     * @param visitorId caller-supplied stable visitor identifier.
     * @param attributes optional visitor attributes used during bucketing.
     */
    public fun createContext(
        visitorId: String,
        attributes: Map<String, Any?>?,
    ): ConvertContext {
        val ctx = ConvertContext(visitorId = visitorId)
        if (attributes != null) {
            ctx.setAttributes(attributes)
        }
        return ctx
    }

    /**
     * Registers a callback to be invoked once the SDK has loaded its
     * configuration and is ready to bucket visitors.
     *
     * The parameter type is `Runnable` rather than `() -> Unit` so Java
     * callers can pass a lambda directly.
     *
     * @return this SDK instance, to allow chaining.
     */
    // TODO(Story 2.1): wire to DataManager.ready() future.
    public fun onReady(callback: Runnable): ConvertSDK {
        return this
    }

    /**
     * Subscribes [callback] to the named [event].
     *
     * @param event SDK-defined event identifier.
     * @param callback handler invoked when the event fires.
     * @return this SDK instance, to allow chaining.
     */
    // TODO(Story 2.4): wire to the internal EventBus.
    public fun on(event: String, callback: EventCallback): ConvertSDK {
        return this
    }

    /**
     * Removes [callback] previously registered for [event].
     *
     * @param event SDK-defined event identifier.
     * @param callback handler previously passed to [on].
     * @return this SDK instance, to allow chaining.
     */
    // TODO(Story 2.4): wire to the internal EventBus.
    public fun off(event: String, callback: EventCallback): ConvertSDK {
        return this
    }

    /**
     * Fluent builder for [ConvertSDK].
     *
     * Instances are created through [ConvertSDK.builder]; the constructor is
     * `internal` so consumers cannot bypass the factory. Every setter mutates
     * in-place and returns `this` to allow chaining.
     */
    public class Builder internal constructor(internal val appContext: Context) {

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

        // All Builder setters take a single non-default parameter. The story
        // Gotcha 9 calls `@JvmOverloads` on single-arg setters a harmless
        // no-op, but Kotlin emits a warning when the annotation has no effect
        // and the zero-warnings policy (AC-7 / detekt buildUponDefaultConfig)
        // promotes that to a build failure. The annotation is omitted here
        // deliberately; once a setter gains a default parameter the
        // annotation should be restored.

        /** Sets the public SDK key for this project. */
        public fun sdkKey(value: String): Builder {
            this.sdkKey = value
            return this
        }

        /** Sets the secret SDK key for this project. */
        public fun sdkKeySecret(value: String): Builder {
            this.sdkKeySecret = value
            return this
        }

        /** Sets a preloaded configuration, bypassing the remote fetch. */
        public fun data(config: ConfigResponseData): Builder {
            this.data = config
            return this
        }

        /** Sets the backend environment identifier (e.g., `"prod"`, `"staging"`). */
        public fun environment(value: String): Builder {
            this.environment = value
            return this
        }

        /** Overrides the config endpoint URL. */
        public fun configEndpoint(url: String): Builder {
            this.configEndpoint = url
            return this
        }

        /** Overrides the tracking endpoint URL. */
        public fun trackEndpoint(url: String): Builder {
            this.trackEndpoint = url
            return this
        }

        /** Sets the config-refresh interval in milliseconds. */
        public fun dataRefreshInterval(millis: Long): Builder {
            this.dataRefreshInterval = millis
            return this
        }

        /** Sets the number of events per outbound batch. */
        public fun batchSize(size: Int): Builder {
            this.batchSize = size
            return this
        }

        /** Sets the interval between partial-batch flushes, in milliseconds. */
        public fun releaseInterval(millis: Long): Builder {
            this.releaseInterval = millis
            return this
        }

        /** Sets the bucketing hash seed. */
        public fun hashSeed(seed: Int): Builder {
            this.hashSeed = seed
            return this
        }

        /** Sets the bucketing traffic ceiling representing 100%. */
        public fun maxTraffic(maxTraffic: Int): Builder {
            this.maxTraffic = maxTraffic
            return this
        }

        /** Sets the logger verbosity floor. */
        public fun logLevel(level: LogLevel): Builder {
            this.logLevel = level
            return this
        }

        /** Enables or disables outbound tracking. */
        public fun trackingEnabled(enabled: Boolean): Builder {
            this.trackingEnabled = enabled
            return this
        }

        /** Sets the cache aggressiveness level (e.g., `"default"`, `"low"`). */
        public fun cacheLevel(level: String): Builder {
            this.cacheLevel = level
            return this
        }

        /**
         * Assembles a [ConvertConfig] from the setters invoked so far and
         * returns a new [ConvertSDK] bound to it.
         *
         * No network or coroutine work is kicked off in this story — Story 2.1
         * wires the real startup sequence.
         */
        public fun build(): ConvertSDK {
            val api = if (configEndpoint != null || trackEndpoint != null) {
                ApiConfig(endpoint = ApiEndpoint(config = configEndpoint, track = trackEndpoint))
            } else {
                null
            }
            val bucketing = if (hashSeed != null || maxTraffic != null) {
                BucketingConfig(hashSeed = hashSeed, maxTraffic = maxTraffic)
            } else {
                null
            }
            val events = if (batchSize != null || releaseInterval != null) {
                EventsConfig(batchSize = batchSize, releaseInterval = releaseInterval)
            } else {
                null
            }
            val logger = if (logLevel != null) {
                LoggerConfig(logLevel = logLevel)
            } else {
                null
            }
            val network = if (trackingEnabled != null || cacheLevel != null) {
                NetworkConfig(tracking = trackingEnabled, cacheLevel = cacheLevel)
            } else {
                null
            }
            val baseConfig = ConvertConfig(
                sdkKey = sdkKey,
                sdkKeySecret = sdkKeySecret,
                data = data,
                environment = environment ?: "prod",
                api = api,
                bucketing = bucketing,
                events = events,
                logger = logger,
                network = network,
            )
            val config = dataRefreshInterval?.let { baseConfig.copy(dataRefreshInterval = it) }
                ?: baseConfig
            return ConvertSDK(config = config)
        }
    }

    public companion object {

        /**
         * Returns a fresh [Builder].
         *
         * @param context any Android [Context]; only the application context is
         *  retained, so callers may safely pass an `Activity`.
         */
        @JvmStatic
        public fun builder(context: Context): Builder = Builder(context.applicationContext)
    }
}
