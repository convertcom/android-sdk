/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import com.convert.sdk.android.adapter.AndroidLogger
import com.convert.sdk.android.adapter.FileConfigCache
import com.convert.sdk.android.adapter.OkHttpClientAdapter
import com.convert.sdk.android.adapter.SharedPrefsDataStore
import com.convert.sdk.android.lifecycle.SdkLifecycleObserver
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.BucketingConfig
import com.convert.sdk.core.config.ConfigDefaults
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.config.LoggerConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.config.RulesConfig
import com.convert.sdk.core.data.DataManager
import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.internal.bigDecimalSerializersModule
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.DataStore
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Public SDK entry point.
 *
 * Instances are created exclusively via [ConvertSDK.builder]; the primary
 * constructor is `internal` so that consumer code cannot bypass the
 * Builder's invariant setup. The assembled [ConvertConfig] is kept
 * `internal` so later stories (2.1+) can read it when wiring the real
 * managers without re-exposing it through the public API.
 *
 * ### Story 2.1 wiring
 *
 * Story 2.1 fleshes out every stubbed field from Story 1.2:
 *
 *  - The internal constructor now accepts the fully-assembled [ConvertConfig]
 *    plus the five SDK-scope collaborators the Builder bootstraps:
 *    [appContext], [logger], [dataStore], [httpClient], [dataManager],
 *    [eventManager]. Each parameter has a safe default so the test
 *    `SmokeTest` that constructs a bare `ConvertSDK(config)` keeps
 *    compiling — the Builder path always supplies real instances.
 *  - [scope] owns the SDK's coroutine lifecycle: `SupervisorJob` so one
 *    failing child (e.g. a future config fetch) does not cancel its
 *    siblings (event flush, refresh), `Dispatchers.Default` for
 *    general-purpose background work (Story 2.2's config fetch switches
 *    to `Dispatchers.IO` at the call site), and a
 *    `CoroutineExceptionHandler` that logs uncaught exceptions through
 *    [logger]. The scope is intentionally **not** cancelled — the SDK
 *    lives for the app's lifetime; a future `shutdown()` API (Story 5.3)
 *    would cancel it then.
 *  - [onReady] / [on] / [off] delegate to [eventManager] directly; the
 *    legacy Story 1.2 in-class lists are gone. [off]'s identity-based
 *    matching is preserved by stashing the lambda that wraps each
 *    [EventCallback] in a ConcurrentHashMap keyed by `(event, callback)`.
 *
 * @property config the fully-assembled configuration passed in by
 *   [Builder.build]. Kept `internal` so only SDK internals read it.
 * @property appContext application-scoped context retained for adapter
 *   wiring; the passed ref is already reduced to `applicationContext` by
 *   [Builder] (NFR: no Activity/Fragment refs). Kept `internal` so tests
 *   can inspect it without re-exposing the Android coupling publicly.
 *   Nullable because the single-arg secondary constructor (used by pure-
 *   JVM smoke tests) has no Android context available; the Builder path
 *   always supplies a non-null value.
 * @property logger the shared [Logger] port.
 * @property dataStore the shared [DataStore] port; the Builder injects
 *   [SharedPrefsDataStore], pure-JVM tests fall back to the in-memory
 *   default.
 * @property httpClient the shared [HttpClient] port; Story 2.2 drives the
 *   config fetch through this. Nullable because pure-JVM smoke tests
 *   don't need it; the Builder path always supplies [OkHttpClientAdapter].
 * @property dataManager the shared [DataManager]; owns the currently
 *   loaded [ConfigResponseData] and fires [SystemEvents.READY] when
 *   [DataManager.setData] is called.
 * @property eventManager the shared [EventManager]; all `on`/`off`/`fire`
 *   traffic flows through it.
 * @property scope the SDK-scoped [CoroutineScope]; all internal async
 *   work launches on this scope so the SupervisorJob isolates failures.
 */
@Suppress("LongParameterList")
public class ConvertSDK internal constructor(
    internal val config: ConvertConfig,
    internal val appContext: Context? = null,
    internal val logger: Logger = Logger.NoOp,
    internal val dataStore: DataStore = InMemoryDataStore(),
    internal val httpClient: HttpClient? = null,
    internal val eventManager: EventManager = EventManager(logger = Logger.NoOp),
    initialDataManager: DataManager? = null,
    internal val apiManager: ApiManager? = null,
    internal val fileConfigCache: FileConfigCache? = null,
    scope: CoroutineScope? = null,
) {

    /**
     * Shared [DataManager]; owns the currently-loaded [ConfigResponseData]
     * and fires [SystemEvents.READY] when [DataManager.setData] is called.
     *
     * The primary-constructor parameter takes a nullable [initialDataManager]
     * so the Kotlin default `dataManager = DataManager(eventManager, …)`
     * expression can reference [eventManager] (which must already be
     * bound). The non-null resolved reference is exposed via this
     * property: when the caller supplied a `DataManager` we use it,
     * otherwise we instantiate one wired to the same [eventManager].
     *
     * Result: the Builder path injects an explicit DataManager built from
     * the shared EventManager; the single-arg test path gets a fresh one
     * tied to the same test-local EventManager — no stale-bus surprise.
     */
    internal val dataManager: DataManager = initialDataManager ?: DataManager(
        eventManager = eventManager,
        environment = config.environment,
    )

    /**
     * SDK-scoped [CoroutineScope] owning every background coroutine the
     * SDK launches. `SupervisorJob` keeps sibling coroutines alive when
     * one fails; the exception handler routes otherwise-uncaught throws
     * through [logger] instead of letting them crash the JVM.
     *
     * Story 2.3 allows a test-supplied [scope] override (typically a
     * `TestScope` backed by a `TestCoroutineScheduler`) so refresh-loop
     * tests can drive virtual time via `advanceTimeBy`. Production always
     * leaves [scope] at `null` and receives the real
     * `SupervisorJob + Dispatchers.Default` scope.
     */
    internal val scope: CoroutineScope = scope ?: CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error(
                    message = "SDK scope caught exception",
                    throwable = throwable,
                    tag = TAG,
                )
            },
    )

    /**
     * Holds the currently-running refresh coroutine, or `null` when no loop
     * is active. [AtomicReference.compareAndSet] could be used for a
     * stronger double-start guard; AC-9 only requires that `isActive`
     * checks on the held job stop a second start from spinning up a
     * parallel loop.
     */
    private val refreshJob: AtomicReference<Job?> = AtomicReference(null)

    /**
     * Off-lookup table: an [EventCallback] registered via [on] is wrapped
     * in a lambda before it hits the [EventManager]. [off] needs the
     * exact lambda reference to unregister it, so we stash `(event, cb) →
     * lambda` here. The map is keyed by callback identity via
     * [System.identityHashCode] + the callback reference itself inside a
     * [EventCallbackKey] — see below.
     *
     * `ConcurrentHashMap` is sufficient here because the per-slot write
     * is a single `put`/`remove`; there is no read-modify-write across
     * multiple slots that would need a broader lock.
     */
    private val callbackLambdas: ConcurrentHashMap<EventCallbackKey, (Map<String, Any?>) -> Unit> =
        ConcurrentHashMap()

    /**
     * Identity-based composite key used by [callbackLambdas]. Two distinct
     * [EventCallback] instances produce distinct keys even if they happen
     * to capture the same state (aligns with EventManager.off's identity
     * equality — Gotcha 4).
     */
    private data class EventCallbackKey(val event: String, val callback: EventCallback)

    init {
        // Story 2.3 AC-4: the refresh loop must not poll until the SDK has a
        // usable config in memory (hasData == true). Two states to cover:
        //
        //  1. hasData is already true at construction time — the Builder's
        //     direct-data path seeds DataManager on [scope] before this init
        //     block runs under the test/tight-loop race; and on resume the
        //     persisted data seed also runs synchronously. In that case we
        //     register the lifecycle observer immediately so a subsequent
        //     ON_START starts the loop.
        //  2. hasData is false — subscribe to READY; once it fires,
        //     register the observer (still on the main thread) so the
        //     current app foreground state can kick the loop.
        //
        // Either way, observer registration happens exactly once. The
        // observer then routes ON_START / ON_STOP to startRefreshLoop /
        // stopRefreshLoop, which themselves guard against the "no data
        // yet" case — belt-and-suspenders so the contract survives an
        // out-of-order ON_START fired during the initial READY burst.
        if (appContext != null && apiManager != null) {
            registerLifecycleObserverWhenReady()
        }
    }

    /**
     * Subscribes to READY (if needed) and posts the lifecycle-observer
     * registration onto the main thread.
     *
     * `ProcessLifecycleOwner.get().lifecycle.addObserver(...)` is
     * main-thread-only; the SDK is typically constructed off-main
     * (Builder is safe to call from any thread) so we bridge via
     * `Handler(Looper.getMainLooper()).post { ... }` (Gotcha 1).
     *
     * When READY has already been delivered by the time this runs, we
     * skip the subscription and register immediately — `DataManager.hasData`
     * is the monotonic "first config loaded" flag.
     */
    private fun registerLifecycleObserverWhenReady() {
        if (dataManager.hasData()) {
            postObserverRegistration()
            return
        }
        // One-shot: register once, then self-unsubscribe. Using `val` +
        // explicit off() lets us keep `lateinit`-free state.
        val handler = object : (Map<String, Any?>) -> Unit {
            override fun invoke(p1: Map<String, Any?>) {
                eventManager.off(SystemEvents.READY, this)
                postObserverRegistration()
            }
        }
        eventManager.on(SystemEvents.READY, handler)
    }

    /**
     * Posts an [SdkLifecycleObserver] install onto the main-thread handler.
     * The observer routes ON_START / ON_STOP to [startRefreshLoop] and
     * [stopRefreshLoop] respectively.
     */
    private fun postObserverRegistration() {
        Handler(Looper.getMainLooper()).post {
            val observer = SdkLifecycleObserver(
                onStart = { startRefreshLoop() },
                onStop = { stopRefreshLoop() },
            )
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    /**
     * Starts the foreground config-refresh loop if one is not already
     * running (AC-2, AC-9). Every [ConvertConfig.dataRefreshInterval]
     * milliseconds the loop:
     *
     *  1. Calls [ApiManager.fetchConfig]. On success:
     *     - Updates [dataManager] via `setData(...)`
     *     - Fires [SystemEvents.CONFIG_UPDATED] with a `timestamp` payload
     *     - Spawns a fire-and-forget cache write
     *  2. On failure (null return), logs no additional WARN here —
     *     [ApiManager] already logged the transport/parse failure; the
     *     loop must continue (AC-6) so a transient blip doesn't stop
     *     refresh forever.
     *
     * If [apiManager] is `null` (pure-JVM smoke-test path), the method is
     * a no-op — there's no fetch machinery to spin.
     *
     * The loop body exits cleanly when [refreshJob] is cancelled
     * (`while (isActive)` becomes false; cancellable `delay` returns
     * immediately).
     *
     * `internal` so tests can drive the state machine directly (also
     * exposed as [startRefreshLoopForTest] with a more emphatic name
     * for test readability).
     */
    internal fun startRefreshLoop() {
        val api = apiManager
        val existing = refreshJob.get()
        // Three guards folded into one boolean so the function stays within
        // detekt's ReturnCount ceiling: missing ApiManager (pure-JVM test
        // path), a loop already active (AC-9 idempotence), or no seeded
        // config yet (AC-4 pre-READY gate).
        val canStart = api != null &&
            existing?.isActive != true &&
            dataManager.hasData()
        if (!canStart) return

        // After the canStart check, api is guaranteed non-null (smart-cast
        // survives the fold); capture it into a local so the type is
        // visible inside the scope.launch block below.
        val checkedApi: ApiManager = api

        val job = scope.launch {
            while (isActive) {
                delay(config.dataRefreshInterval)
                val fetched = checkedApi.fetchConfig()
                if (fetched != null) {
                    dataManager.setData(fetched)
                    eventManager.fire(
                        event = SystemEvents.CONFIG_UPDATED,
                        data = mapOf("timestamp" to System.currentTimeMillis()),
                    )
                    // Fire-and-forget cache refresh. Failures inside write()
                    // are absorbed by FileConfigCache's own try/catch.
                    fileConfigCache?.let { cache ->
                        scope.launch { cache.write(fetched) }
                    }
                }
            }
        }
        refreshJob.set(job)
    }

    /**
     * Cancels the running refresh loop (if any) and clears the slot
     * (AC-3). Calling [stopRefreshLoop] without a running loop is a no-op.
     */
    internal fun stopRefreshLoop() {
        refreshJob.getAndSet(null)?.cancel()
    }

    /**
     * Test-only alias for [startRefreshLoop]. Exists so the refresh
     * tests read clearly and so the production-path callers
     * ([postObserverRegistration]) can keep the shorter name.
     *
     * Do NOT call this from production code.
     */
    internal fun startRefreshLoopForTest() {
        startRefreshLoop()
    }

    /**
     * Test-only alias for [stopRefreshLoop]. See [startRefreshLoopForTest].
     */
    internal fun stopRefreshLoopForTest() {
        stopRefreshLoop()
    }

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
     * SharedPreferences-backed persistence via [dataStore]; today every
     * call produces a new id, which is acceptable for the skeleton
     * (Story 1.2 Gotcha 7 / Story 2.1 Gotcha 12).
     *
     * @return a context scoped to the generated visitor id.
     */
    public fun createContext(): ConvertContext {
        // TODO(Story 3.1): persist to dataStore for stable visitor ID across launches
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
     * Declared to take a [Runnable] so Java lambdas map cleanly.
     *
     * ### Late-subscriber semantics (Story 2.1 / 2.4 interplay)
     *
     * Story 2.1 combines Gotcha 8 options (a) and (b) to guarantee that
     * every registered callback fires exactly once:
     *
     *  - The Builder's direct-data path launches `dataManager.setData(...)`
     *    on [scope] (option b): consumers calling [onReady] synchronously
     *    after `build()` returns beat the coroutine dispatch and are
     *    reached by the single broadcast [SystemEvents.READY] fire.
     *  - This method additionally checks [DataManager.hasData] — if READY
     *    has already been delivered (late subscriber, or the scope.launch
     *    won the race), the callback is dispatched onto [scope]
     *    **without broadcasting** a second READY. Existing subscribers
     *    therefore do not see a duplicate, and the new subscriber still
     *    receives the event. Proper event replay for any event is a
     *    Story 2.4 feature; this is the minimum needed to satisfy AC-4.
     *
     * ### Dispatch thread
     *
     * - Late-subscriber path (hasData == true at registration): dispatched
     *   on [scope] (`Dispatchers.Default`) via `scope.launch`.
     * - Broadcast path (hasData == false at registration): dispatched on
     *   whatever thread calls [DataManager.setData] and therefore
     *   [EventManager.fire]. In Story 2.1/2.2 every setData call happens
     *   inside `sdk.scope.launch { ... }` — direct-data mode via the
     *   Builder, sdk-key mode via Story 2.2's `ApiManager` — so the
     *   broadcast path also runs on [scope]'s `Dispatchers.Default`
     *   thread pool. Consumers should nonetheless not assume a specific
     *   thread; the architecture's contract is "never inline on the
     *   call site of `onReady`", which both paths honour.
     *
     * Story 2.2 will launch the sdk-key-mode config fetch on [scope]
     * which, on success, calls [DataManager.setData] and triggers the
     * same broadcast. Until then, sdk-key mode never fires READY.
     *
     * @param callback invoked when the SDK becomes ready.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun onReady(callback: Runnable): ConvertSDK {
        // READY is a once-per-SDK-lifetime event in Story 2.1 / 2.2:
        // [DataManager.setData] fires it exactly once when the config is
        // first seeded (either by direct-data mode or by Story 2.2's
        // fetch). Story 2.3's refresh loop fires CONFIG_UPDATED, not
        // READY. So the late-subscriber path needs to cover "READY
        // already fired" but never "READY fires again after replay".
        //
        // Branch on [DataManager.hasData] to decide which path this
        // callback takes:
        //  - `false` → subscribe to the EventManager broadcast; when
        //    `setData` fires READY later, the subscriber is delivered
        //    in registration order along with every other subscriber
        //    that also missed the fire window.
        //  - `true` → the broadcast already happened (direct-data mode
        //    won the scope.launch race, or Story 2.2's fetch resolved).
        //    Do NOT subscribe — the broadcast won't fire again and the
        //    extra subscription would leak. Instead dispatch a private
        //    invocation on [scope] so the consumer sees READY exactly
        //    once on a background thread.
        if (dataManager.hasData()) {
            scope.launch { callback.run() }
        } else {
            eventManager.on(SystemEvents.READY) { _ -> callback.run() }
        }
        return this
    }

    /**
     * Subscribes [callback] to the named [event].
     *
     * The [EventCallback] is wrapped in a lambda that forwards the event
     * payload to [EventCallback.onEvent]; the wrapping lambda is stashed
     * in [callbackLambdas] so [off] can remove the identical reference
     * from the [eventManager].
     *
     * Re-registering the same `(event, callback)` pair replaces the
     * previously-stashed wrapper lambda AND removes the previous wrapper
     * from the [eventManager] to avoid leaking orphaned subscriptions:
     * without this, a double-`on` followed by a single `off` would leave
     * the first wrapper subscribed forever because `off` only knows about
     * the stashed (most recent) reference.
     *
     * @param event well-known event name.
     * @param callback listener invoked when [event] is emitted.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun on(event: String, callback: EventCallback): ConvertSDK {
        val lambda: (Map<String, Any?>) -> Unit = { data -> callback.onEvent(data) }
        val key = EventCallbackKey(event, callback)
        // Replace any prior wrapper for this (event, callback) — and
        // unsubscribe it from the EventManager — so a subsequent off()
        // can cleanly tear down the registration without orphaning the
        // old wrapper in the subscriber list.
        val previous = callbackLambdas.put(key, lambda)
        if (previous != null) {
            eventManager.off(event, previous)
        }
        eventManager.on(event, lambda)
        return this
    }

    /**
     * Removes a previously-registered [callback] for [event].
     *
     * Looks up the wrapping lambda by `(event, callback)` identity. A
     * callback that was never registered is silently ignored per
     * architecture's "never throw from public API" rule.
     *
     * @param event the event name the callback was registered against.
     * @param callback the listener to unregister.
     * @return this SDK instance, enabling fluent chaining.
     */
    public fun off(event: String, callback: EventCallback): ConvertSDK {
        val lambda = callbackLambdas.remove(EventCallbackKey(event, callback)) ?: return this
        eventManager.off(event, lambda)
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

    /**
     * Returns a privacy-safe string representation of this SDK instance.
     *
     * AC-5 / NFR6: [ConvertConfig] is not embedded in the output — even
     * though its own `toString()` already redacts the secret, including
     * the full config would risk exposing URLs, keys, and other sensitive
     * fields. Only the environment and the tracking flag are rendered.
     */
    override fun toString(): String =
        "ConvertSDK(environment=${config.environment}, trackingEnabled=$trackingEnabled)"

    public companion object {
        private const val TAG: String = "ConvertSDK"

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
     * values, constructs the collaborator graph
     * ([Logger] → [DataStore] → [HttpClient] → [DataManager] /
     * [EventManager]), and hands the lot to [ConvertSDK]'s internal
     * constructor.
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
     *   adapter wiring. Reduced to `applicationContext` at
     *   [ConvertSDK.builder] entry time.
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
        private var excludeExperienceIdHash: Boolean? = null
        private var logLevel: LogLevel? = null
        private var trackingEnabled: Boolean? = null
        private var cacheLevel: String? = null
        private var rulesKeysCaseSensitive: Boolean? = null
        private var rulesNegation: String? = null
        private var rulesComparisonProcessor: String? = null
        private var networkSource: String? = null
        private var mapper: Any? = null

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

        /**
         * Opts out of including the experience id in the bucketing hash
         * input. JS SDK parity for legacy accounts whose bucketing was
         * defined before experience ids were part of the hash.
         */
        public fun excludeExperienceIdHash(exclude: Boolean): Builder = apply {
            excludeExperienceIdHash = exclude
        }

        /** Sets the minimum log severity emitted by the logger. */
        public fun logLevel(level: LogLevel): Builder = apply { logLevel = level }

        /** Enables or disables outbound tracking HTTP requests. */
        public fun trackingEnabled(enabled: Boolean): Builder = apply { trackingEnabled = enabled }

        /** Sets the HTTP cache directive hint (`"default"` / `"low"`). */
        public fun cacheLevel(level: String): Builder = apply { cacheLevel = level }

        /**
         * Enables case-sensitive comparison of rule keys (JS SDK default: `true`).
         */
        public fun rulesKeysCaseSensitive(caseSensitive: Boolean): Builder = apply {
            rulesKeysCaseSensitive = caseSensitive
        }

        /**
         * Sets the default negation semantics for rule matching; see the
         * JS SDK rules engine for allowed values.
         */
        public fun rulesNegation(negation: String): Builder = apply {
            rulesNegation = negation
        }

        /**
         * Sets the rule-engine comparison processor identifier
         * (`rules.comparisonProcessor` in the JS SDK). The string is
         * captured for FR2 parity; the rule engine introduced in a later
         * story consumes it.
         */
        public fun rulesComparisonProcessor(value: String): Builder = apply {
            rulesComparisonProcessor = value
        }

        /**
         * Sets the platform `network.source` identifier appended to
         * tracking payloads (e.g. `"android"`, `"web"`). Distinct from
         * FR2's `network.source` "custom HTTP transport" hook which is
         * out of scope for v1.0 (see AC-2 deferred list).
         */
        public fun networkSource(value: String): Builder = apply {
            networkSource = value
        }

        /**
         * Sets the JS-SDK `mapper` parity hook — an opaque consumer-
         * supplied object that future bucketing / response-mapping stories
         * will route data through. The Builder captures the reference so
         * AC-2's full FR2 surface is satisfied; no Story 2.1 code path
         * reads the value.
         */
        public fun mapper(value: Any?): Builder = apply {
            mapper = value
        }

        /**
         * Assembles a [ConvertConfig] from the collected builder values,
         * constructs the SDK's collaborators, and returns a fresh
         * [ConvertSDK].
         *
         * Manager construction order (AC-7):
         *   1. [Logger] — needs `config.logger?.logLevel`.
         *   2. [DataStore] — needs [appContext].
         *   3. [HttpClient] — needs the shared OkHttpClient + [Logger].
         *   4. [EventManager] — needs [Logger].
         *   5. [DataManager] — needs [EventManager] + `config.environment`.
         *   6. Return `ConvertSDK(config, appContext, logger, dataStore, httpClient,
         *      dataManager, eventManager)`.
         *
         * Validation (Gotcha 5 / AC-3 / Task 1): emit a WARN through the
         * just-constructed [Logger] if both `sdkKey` and `data` are set
         * (prefer `data` — it is the stronger override since it bypasses
         * the network fetch entirely) or if neither is set (no-op SDK,
         * downstream calls will return null). Never throw.
         *
         * Direct-data mode (AC-3, Gotcha 8): `scope.launch {
         * dataManager.setData(config.data!!) }` — delaying the seed onto
         * the coroutine scope gives the consumer a frame to register
         * `onReady { ... }` after `build()` returns.
         *
         * @return a new [ConvertSDK] ready for use.
         */
        public fun build(): ConvertSDK {
            val assembled = assembleConfig()

            // 1. Logger — depends only on the assembled config.
            val resolvedLogLevel = assembled.logger?.logLevel ?: ConfigDefaults.DEFAULT_LOG_LEVEL
            val logger: Logger = AndroidLogger(level = resolvedLogLevel)

            // Validation: warn if ambiguous, but never throw. Extracted
            // helper keeps build() under detekt's LongMethod ceiling;
            // lives at file scope to keep Builder's function count under
            // detekt's TooManyFunctions threshold (same rationale as
            // launchInitialDataSeed).
            warnIfBuilderStateAmbiguous(
                logger = logger,
                sdkKey = sdkKey,
                hasPrefetchedData = data != null,
            )

            // 2. DataStore — wraps the process-private SharedPreferences file.
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dataStore: DataStore = SharedPrefsDataStore(prefs)

            // 3. HttpClient — one OkHttpClient per SDK instance (Gotcha 10).
            val okHttp = OkHttpClientAdapter.defaultOkHttpClient()
            val httpClient: HttpClient = OkHttpClientAdapter(okHttp, logger)

            // 4. EventManager — shared bus.
            val eventManager = EventManager(logger = logger)

            // 5. DataManager — holds the loaded config, fires READY on setData.
            val dataManager = DataManager(
                eventManager = eventManager,
                environment = assembled.environment,
            )

            // 6. Story 2.2 collaborators: ApiManager for the CDN fetch,
            // FileConfigCache for the offline cold-start fallback. The
            // shared Json instance has `ignoreUnknownKeys = true` +
            // `explicitNulls = false` so that the fetch and the cache see
            // the same set of fields (NFR12: forward-compatible schemas).
            //
            // Story 2.2 AC-12 (F-172): the shared Json must register
            // [bigDecimalSerializersModule] so that the encode path on
            // FileConfigCache.write does not throw
            // `kotlinx.serialization.SerializationException: Serializer
            // for class 'BigDecimal' is not found` when ConfigResponseData
            // carries a non-null `@Contextual java.math.BigDecimal?` field
            // (e.g. ConfigProjectSettings.minOrderValue / maxOrderValue).
            // FileConfigCache MUST receive this Json by injection — it
            // must NOT instantiate its own.
            val sharedJson = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                serializersModule = bigDecimalSerializersModule
            }
            val apiManager = ApiManager(
                httpClient = httpClient,
                logger = logger,
                config = assembled,
                json = sharedJson,
            )
            val fileConfigCache = FileConfigCache(
                context = appContext,
                logger = logger,
                json = sharedJson,
            )

            val sdk = ConvertSDK(
                config = assembled,
                appContext = appContext,
                logger = logger,
                dataStore = dataStore,
                httpClient = httpClient,
                eventManager = eventManager,
                initialDataManager = dataManager,
                // Story 2.3: hand the already-constructed ApiManager and
                // FileConfigCache to the SDK so its init block can wire a
                // refresh loop on top of the same collaborators the
                // initial seed uses. Passing them in (rather than
                // re-instantiating inside ConvertSDK) keeps the dependency
                // graph assembly in one place — Builder.build().
                apiManager = apiManager,
                fileConfigCache = fileConfigCache,
            )

            // Direct-data mode (AC-3) or sdk-key mode (AC-5/6/7) — delegate
            // to the file-private seeding helper so that `build()` stays
            // under the detekt LongMethod threshold and the seeding logic
            // is readable as a single unit. The helper lives at file scope
            // (not inside Builder) so the Builder's function count stays
            // under the TooManyFunctions threshold with headroom.
            launchInitialDataSeed(
                sdk = sdk,
                dataManager = dataManager,
                apiManager = apiManager,
                fileConfigCache = fileConfigCache,
                logger = logger,
                assembled = assembled,
            )

            return sdk
        }

        /**
         * Reads all builder fields into a [ConvertConfig], falling back to
         * [ConfigDefaults] for unset fields that ship with a JS-SDK-
         * derived default value.
         */
        private fun assembleConfig(): ConvertConfig {
            val apiConfig = if (configEndpoint != null || trackEndpoint != null) {
                ApiConfig(endpoint = ApiEndpoint(config = configEndpoint, track = trackEndpoint))
            } else {
                null
            }
            val bucketingConfig = if (
                hashSeed != null || maxTraffic != null || excludeExperienceIdHash != null
            ) {
                BucketingConfig(
                    hashSeed = hashSeed,
                    maxTraffic = maxTraffic,
                    excludeExperienceIdHash = excludeExperienceIdHash,
                )
            } else {
                null
            }
            val eventsConfig = if (batchSize != null || releaseInterval != null) {
                EventsConfig(batchSize = batchSize, releaseInterval = releaseInterval)
            } else {
                null
            }
            val loggerConfig = logLevel?.let { LoggerConfig(logLevel = it) }
            val networkConfig = if (
                trackingEnabled != null || cacheLevel != null || networkSource != null
            ) {
                NetworkConfig(
                    tracking = trackingEnabled,
                    cacheLevel = cacheLevel,
                    source = networkSource,
                )
            } else {
                null
            }
            val rulesConfig = if (
                rulesKeysCaseSensitive != null ||
                rulesNegation != null ||
                rulesComparisonProcessor != null
            ) {
                RulesConfig(
                    keysCaseSensitive = rulesKeysCaseSensitive,
                    negation = rulesNegation,
                    comparisonProcessor = rulesComparisonProcessor,
                )
            } else {
                null
            }
            val defaults = ConvertConfig()
            return ConvertConfig(
                sdkKey = sdkKey,
                sdkKeySecret = sdkKeySecret,
                data = data,
                environment = environment ?: defaults.environment,
                api = apiConfig,
                bucketing = bucketingConfig,
                dataRefreshInterval = dataRefreshInterval ?: defaults.dataRefreshInterval,
                events = eventsConfig,
                rules = rulesConfig,
                logger = loggerConfig,
                network = networkConfig,
                mapper = mapper,
            )
        }

        internal companion object {
            /**
             * SharedPreferences file name for per-visitor state. Matches
             * the story's AC-7 literal `com.convert.sdk.visitor`.
             */
            const val PREFS_NAME: String = "com.convert.sdk.visitor"
        }
    }

    /**
     * In-memory [DataStore] fallback used when the internal constructor
     * is invoked without an explicit store — only pure-JVM tests should
     * hit this path. The Builder path always wires [SharedPrefsDataStore].
     */
    private class InMemoryDataStore : DataStore {
        private val map: ConcurrentHashMap<String, String> = ConcurrentHashMap()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun clear() {
            map.clear()
        }
    }
}

/**
 * Schedules the SDK's initial configuration seed on the SDK scope.
 *
 * Three branches per the Story 2.2 wiring:
 *  - Direct-data (`data != null`): launch `setData(prefetched)` so
 *    consumers can register `onReady { ... }` after `build()` returns
 *    and still see the READY broadcast (Story 2.1 Gotcha 8, option b).
 *  - sdk-key (`sdkKey != null`, `data == null`): launch the network
 *    fetch; on success seed + write-cache fire-and-forget; on failure
 *    try the local cache; if both fail, log a WARN and stay unready
 *    (AC-7).
 *  - Neither: no-op — the Builder already logged a WARN. The SDK stays
 *    unready; consumer code will see null returns.
 *
 * ### Why file-scope private
 *
 * Extracted out of `Builder.build()` to keep the `build` method under
 * detekt's `LongMethod` ceiling (60 lines). Kept at file scope (not
 * inside Builder) so Builder stays under the `TooManyFunctions`
 * threshold with headroom; the helper is conceptually SDK-scope — it
 * operates on an already-built `ConvertSDK` — and Builder's 17+ fluent
 * setters are the only reason the class sits near the function limit at
 * all.
 */
@Suppress("LongParameterList")
private fun launchInitialDataSeed(
    sdk: ConvertSDK,
    dataManager: DataManager,
    apiManager: ApiManager,
    fileConfigCache: FileConfigCache,
    logger: Logger,
    assembled: ConvertConfig,
) {
    val prefetched = assembled.data
    if (prefetched != null) {
        sdk.scope.launch {
            dataManager.setData(prefetched)
        }
        return
    }
    if (assembled.sdkKey == null) return

    sdk.scope.launch {
        val fetched = apiManager.fetchConfig()
        if (fetched != null) {
            dataManager.setData(fetched)
            // Fire-and-forget cache write — the cache is strictly a
            // fallback, so a failure here must not block the main flow
            // (AC-5).
            sdk.scope.launch { fileConfigCache.write(fetched) }
        } else {
            val cached = fileConfigCache.read()
            if (cached != null) {
                // AC-6 — mirror the story's literal log phrasing so
                // operators grep consistently across SDK versions.
                logger.info(
                    message = "ApiManager: network fetch failed, loaded config from cache",
                    tag = INIT_SEED_TAG,
                )
                dataManager.setData(cached)
            } else {
                // AC-7 — again, matching the story's literal phrasing so
                // this canonical warning is easy to find in aggregated
                // logs.
                logger.warn(
                    message = "ApiManager: no cached config available, SDK will return null " +
                        "from public methods until network fetch succeeds",
                    tag = INIT_SEED_TAG,
                )
            }
        }
    }
}

/** Log tag for [launchInitialDataSeed] messages. */
private const val INIT_SEED_TAG: String = "ConvertSDK"

/**
 * Emits a WARN through [logger] if the Builder's state is ambiguous:
 * both `sdkKey` and `hasPrefetchedData` (prefer `data` — it's the
 * stronger override since it bypasses the network fetch entirely), or
 * neither set (no-op SDK — downstream calls will return null).
 *
 * Extracted out of [ConvertSDK.Builder.build] and kept at file scope
 * to keep the Builder under detekt's `TooManyFunctions` ceiling
 * (same rationale as [launchInitialDataSeed]). Never throws.
 */
private fun warnIfBuilderStateAmbiguous(
    logger: Logger,
    sdkKey: String?,
    hasPrefetchedData: Boolean,
) {
    if (sdkKey != null && hasPrefetchedData) {
        logger.warn(
            message = "Builder: both sdkKey and data set — preferring data. " +
                "data() is the stronger override.",
            tag = BUILDER_TAG,
        )
    } else if (sdkKey == null && !hasPrefetchedData) {
        logger.warn(
            message = "Builder: neither sdkKey nor data set — SDK calls will be no-ops " +
                "until Story 2.2 wires config fetch or until data() is supplied.",
            tag = BUILDER_TAG,
        )
    }
}

/** Log tag used by file-scope Builder helpers that need a distinct tag. */
private const val BUILDER_TAG: String = "ConvertSDK.Builder"
