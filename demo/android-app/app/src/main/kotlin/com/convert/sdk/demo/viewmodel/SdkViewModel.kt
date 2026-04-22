/*
 * Convert Android SDK Demo App — SdkViewModel
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import androidx.lifecycle.ViewModel
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.Variation
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Story 7.1 AC-9 / Story 7.2 — shared ViewModel that aggregates SDK
 * state for every demo screen.
 *
 * ### Responsibilities
 *
 * - Subscribes to the SDK's internal pub/sub for the five observable
 *   system events ([SystemEvents.READY], [SystemEvents.CONFIG_UPDATED],
 *   [SystemEvents.BUCKETING], [SystemEvents.CONVERSION],
 *   [SystemEvents.API_QUEUE_RELEASED]) and exposes a newest-first
 *   [events] flow for the Events tab.
 *
 * - Publishes a [Logger] handle (backed by the internal [demoLogger])
 *   that fan-outs every log call to the SDK's normal Logcat adapter
 *   AND to the Logs tab's [logs] flow — full-fidelity "what the SDK
 *   sees" for developers.
 *
 * - Tracks online / offline state in [networkOnline] for the Offline
 *   screen's status indicator. Story 7.1 leaves the actual connectivity
 *   observation as a stub — [setNetworkOnline] is called by
 *   [com.convert.sdk.demo.ui.screen.OfflineScreen] in Story 7.5 when
 *   the developer toggles airplane mode; the initial value is a
 *   caller-supplied default.
 *
 * - (Story 7.2) Tracks each networked event's delivery lifecycle
 *   ([EventLifecycle]): `bucketing` and `conversion` start QUEUED
 *   when observed; a 2xx `api.queue.released` transitions every
 *   currently-QUEUED event to DELIVERED. Non-networked events
 *   (`ready`, `config.updated`) carry [EventLifecycle.NONE] and
 *   render without a status badge.
 *
 * - (Story 7.2) Holds the active inspector [InspectorTab] so the
 *   developer's tab choice survives screen navigation (the
 *   [EventInspectorSheet] is recomposed on every route change, so a
 *   `remember {}` here would reset to the default every time).
 *
 * ### Testing
 *
 * This class is constructable with a fake [EventSubscriber] so tests
 * avoid the Android [android.content.Context] dependency required by
 * [com.convert.sdk.android.ConvertSDK.builder]. The real factory lives
 * in [com.convert.sdk.demo.DemoApplication] which wires a production
 * subscriber against the SDK singleton.
 *
 * @property eventSubscriber the subscription surface — production
 *   wraps the real SDK, tests provide an in-memory fake.
 * @property initialNetworkOnline seed value for [networkOnline].
 *   Default `true` because the app is expected to launch with
 *   connectivity; the Offline screen drives subsequent updates.
 */
class SdkViewModel(
    eventSubscriber: EventSubscriber,
    initialNetworkOnline: Boolean = true,
    private val experienceRunner: ExperienceRunner = NoOpExperienceRunner,
    private val featureRunner: FeatureRunner = NoOpFeatureRunner,
    private val conversionTracker: ConversionTracker = NoOpConversionTracker,
    private val configSnapshotProvider: ConfigSnapshotProvider = NoOpConfigSnapshotProvider,
) : ViewModel() {

    private val _events = MutableStateFlow<List<InspectorEvent>>(emptyList())

    /**
     * Newest-first list of events captured from the SDK bus. Consumed
     * by [com.convert.sdk.demo.ui.component.EventInspectorSheet]'s
     * Events tab.
     */
    val events: StateFlow<List<InspectorEvent>> = _events.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())

    /**
     * Newest-first list of log entries captured from the SDK's
     * [Logger] adapter. Consumed by the Logs tab.
     */
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _networkOnline = MutableStateFlow(initialNetworkOnline)

    /**
     * Current online/offline indicator state. The Offline screen's
     * "Online ✓ / Offline ✗" banner binds to this.
     */
    val networkOnline: StateFlow<Boolean> = _networkOnline.asStateFlow()

    private val _selectedTab = MutableStateFlow(InspectorTab.EVENTS)

    /**
     * Story 7.2 AC-2 — active inspector tab. Stored in the ViewModel
     * so it survives screen navigation; a `remember {}` inside the
     * composable would reset whenever the user changes screens.
     */
    val selectedTab: StateFlow<InspectorTab> = _selectedTab.asStateFlow()

    private val _results = MutableStateFlow<List<ExperienceResult>>(emptyList())

    /**
     * Story 7.3 — newest-first list of Experience-screen outcomes.
     * Each entry is the result of a single `Run Experience` /
     * `Run Experiences` tap. Capped at [RESULTS_CAP]; older entries
     * roll off the tail when a new one is prepended.
     */
    val results: StateFlow<List<ExperienceResult>> = _results.asStateFlow()

    private val _featureResults = MutableStateFlow<List<FeatureResult>>(emptyList())

    /**
     * Story 7.4 — newest-first list of Features-screen outcomes.
     * Independent of [results] so the developer can freely tap
     * either screen's buttons without the two lists interfering.
     * Capped at [RESULTS_CAP]; older entries roll off the tail.
     */
    val featureResults: StateFlow<List<FeatureResult>> = _featureResults.asStateFlow()

    private val _conversionResults = MutableStateFlow<List<ConversionResult>>(emptyList())

    /**
     * Story 7.5 — newest-first list of Conversions-screen outcomes.
     * Independent of [results] and [featureResults] so the three
     * screens' card lists do not interfere. Capped at [RESULTS_CAP];
     * older entries roll off the tail.
     */
    val conversionResults: StateFlow<List<ConversionResult>> = _conversionResults.asStateFlow()

    private val _configState = MutableStateFlow<ConfigState>(ConfigState.Loading)

    /**
     * Story 7.6 AC-5 / AC-6 / AC-7 — the Config screen's three-branch
     * rendering state.
     *
     * Transitions:
     *  - [ConfigState.Loading] → [ConfigState.Loaded] on the first
     *    `ready` event (or any subsequent `config.updated`).
     *  - [ConfigState.Loading] → [ConfigState.Failed] when a WARN or
     *    ERROR log accumulates BEFORE any `ready` fire — per
     *    Story 7.6 Gotcha 3, the UI infers the error from the log
     *    stream because the SDK does not expose a typed error event.
     *  - Once [ConfigState.Loaded] is reached, subsequent WARN/ERROR
     *    logs do NOT downgrade state. The demo already has a usable
     *    config in memory; a stale-refresh WARN is not a regression.
     */
    val configState: StateFlow<ConfigState> = _configState.asStateFlow()

    /**
     * Story 7.5 — display-only memory of which goal keys this ViewModel
     * has already asked the SDK to track in the current process
     * lifetime. The SDK owns the authoritative dedup guard per Story
     * 4.3 AC-6 (per-visitor, persisted across app restarts); this set
     * exists purely so the screen can render a "Conversion already
     * tracked (dedup)" ResultCard on the second tap (AC-3).
     *
     * Survives [clearConversionResults] — clearing the UI list must not
     * reset the tracked-keys set, otherwise a follow-up tap after a
     * clear would show a spurious non-dedup card while the SDK silently
     * skips the underlying tracking call.
     */
    private val trackedGoalKeys: MutableSet<String> = mutableSetOf()

    /**
     * Internal logger that both forwards to any delegate the caller
     * wired AND appends to the [logs] flow. Production wires the
     * SDK's [com.convert.sdk.android.adapter.AndroidLogger] as the
     * delegate; tests typically pass no delegate and just observe the
     * flow.
     */
    private val demoLogger: Logger = object : Logger {
        override fun error(message: String, throwable: Throwable?, tag: String?) {
            appendLog(LogLevel.ERROR, message, tag, throwable)
        }

        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            appendLog(LogLevel.WARN, message, tag, throwable)
        }

        override fun info(message: String, tag: String?) {
            appendLog(LogLevel.INFO, message, tag)
        }

        override fun debug(message: String, tag: String?) {
            appendLog(LogLevel.DEBUG, message, tag)
        }
    }

    /**
     * Public [Logger] handle — the real demo's
     * [com.convert.sdk.demo.logger.DemoLogger] delegates to this so
     * every SDK log call surfaces in the Logs tab.
     */
    val logger: Logger get() = demoLogger

    private val subscriptionTokens: MutableList<AutoCloseable> = mutableListOf()

    init {
        // Subscribe to the five system events named in AC-9. We keep
        // the AutoCloseable tokens alive for the lifetime of the
        // ViewModel — `onCleared()` tears them down. Tests exercise
        // the subscription side-effect via FakeEventSubscriber.
        OBSERVED_EVENTS.forEach { event ->
            val token = eventSubscriber.subscribe(event) { payload ->
                onSdkEvent(event, payload)
            }
            subscriptionTokens += token
        }
    }

    /**
     * Called by the Offline screen (Story 7.5) when connectivity
     * changes. Story 7.1 only wires the API; the actual
     * [android.net.ConnectivityManager] observation lands later.
     */
    fun setNetworkOnline(online: Boolean) {
        _networkOnline.value = online
    }

    /** Story 7.2 AC-2 — updates the active inspector tab. */
    fun selectTab(tab: InspectorTab) {
        _selectedTab.value = tab
    }

    /**
     * Story 7.3 AC-2 / AC-4 — buckets [experienceKey] via the injected
     * [ExperienceRunner] and prepends an [ExperienceResult] to
     * [results].
     *
     * A non-null return → non-error result carrying [Variation.key] as
     * the displayed variation. A `null` return → error result whose
     * title tells the developer the experience produced no variation
     * and whose hint nudges them to check the experience config or
     * audience rules.
     *
     * Side-effect: the underlying SDK fires
     * [SystemEvents.BUCKETING] on the pub/sub bus which this same
     * ViewModel is subscribed to — so the Events tab lights up
     * automatically. No manual `addEvent` call is needed.
     */
    fun runSingleExperience(experienceKey: String) {
        val variation = experienceRunner.runExperience(experienceKey)
        val result = if (variation != null) {
            ExperienceResult(
                id = ExperienceResult.nextId(),
                experienceKey = experienceKey,
                variationKey = variation.key,
            )
        } else {
            ExperienceResult(
                id = ExperienceResult.nextId(),
                experienceKey = experienceKey,
                isError = true,
                errorMessage = "No variation for experience $experienceKey",
                errorHint = "Check experience config or audience eligibility.",
            )
        }
        appendResult(result)
    }

    /**
     * Story 7.3 AC-3 / AC-4 — buckets every eligible experience via
     * the injected [ExperienceRunner] and prepends one
     * [ExperienceResult] per resolved [Variation].
     *
     * An empty return (no eligible experiences / config not ready)
     * yields a single hint result so the developer sees an
     * actionable card instead of a silent no-op.
     *
     * Results are prepended in emission order, which means the last
     * variation in the returned list ends up at the head of [results]
     * (newest-first convention — matches the Events inspector).
     */
    fun runAllExperiences() {
        val variations = experienceRunner.runExperiences()
        if (variations.isEmpty()) {
            appendResult(
                ExperienceResult(
                    id = ExperienceResult.nextId(),
                    experienceKey = "(none)",
                    isError = true,
                    errorMessage = "No eligible experiences",
                    errorHint = "Visitor did not match any experience's audience " +
                        "or config has not loaded yet.",
                ),
            )
            return
        }
        variations.forEach { variation ->
            appendResult(
                ExperienceResult(
                    id = ExperienceResult.nextId(),
                    experienceKey = variation.experienceKey ?: "(unknown)",
                    variationKey = variation.key,
                ),
            )
        }
    }

    /** Story 7.3 — drops every ExperienceResult card from the screen. */
    fun clearResults() {
        _results.value = emptyList()
    }

    private fun appendResult(result: ExperienceResult) {
        _results.update { current ->
            val prepended = listOf(result) + current
            if (prepended.size <= RESULTS_CAP) prepended else prepended.take(RESULTS_CAP)
        }
    }

    /**
     * Story 7.4 AC-2 / AC-5 — evaluates [featureKey] via the injected
     * [FeatureRunner] and prepends a [FeatureResult] to [featureResults].
     *
     * A non-null return (ENABLED or DISABLED, both valid outcomes) → a
     * non-error result carrying the evaluated state, the resolving
     * experience key when present, and every typed variable mapped to
     * a [TypedVariable] row via [TypedVariable.fromJson].
     *
     * A `null` return → error result (AC-5) whose title tells the
     * developer the feature produced no evaluation and whose hint
     * nudges them to check feature config or audience rules.
     *
     * Side-effect: the underlying SDK fires [SystemEvents.BUCKETING]
     * on its pub/sub bus (features resolve through experience
     * bucketing per Story 4.1 AC-3) which this same ViewModel already
     * subscribes to — so the Events tab lights up automatically. No
     * manual [onSdkEvent] call is needed from this method.
     */
    fun runFeature(featureKey: String) {
        val feature = featureRunner.runFeature(featureKey)
        val result = buildFeatureResult(requestedKey = featureKey, feature = feature)
        appendFeatureResult(result)
    }

    /**
     * Story 7.4 AC-3 — evaluates every configured feature for this
     * visitor via the injected [FeatureRunner] and prepends one
     * [FeatureResult] per returned [Feature].
     *
     * An empty return (no features / config not ready) yields a
     * single hint result so the developer sees an actionable card
     * rather than a silent no-op — symmetric with
     * [runAllExperiences]'s empty-list handling.
     *
     * Results are prepended in emission order, which means the last
     * feature in the returned list ends up at the head of
     * [featureResults] (newest-first convention — matches the Events
     * inspector and the Experiences screen).
     */
    fun runFeatures() {
        val features = featureRunner.runFeatures()
        if (features.isEmpty()) {
            appendFeatureResult(
                FeatureResult(
                    id = FeatureResult.nextId(),
                    featureKey = "(none)",
                    enabled = false,
                    isError = true,
                    errorMessage = "No eligible features",
                    errorHint = "Visitor did not match any feature's audience " +
                        "or config has not loaded yet.",
                ),
            )
            return
        }
        features.forEach { feature ->
            appendFeatureResult(
                buildFeatureResult(
                    requestedKey = feature.key ?: "(unknown)",
                    feature = feature,
                ),
            )
        }
    }

    /** Story 7.4 — drops every FeatureResult card from the screen. */
    fun clearFeatureResults() {
        _featureResults.value = emptyList()
    }

    /**
     * Story 7.5 AC-1 / AC-2 / AC-3 — tracks the hardcoded "purchase-goal"
     * conversion via the injected [ConversionTracker] and prepends a
     * [ConversionResult] to [conversionResults].
     *
     * The SDK call uses the AC-1 payload:
     *   `[GoalData(AMOUNT, JsonPrimitive(10.3)),
     *     GoalData(PRODUCTS_COUNT, JsonPrimitive(2))]`
     *
     * Dedup handling (AC-3): the ViewModel consults its local
     * [trackedGoalKeys] set BEFORE calling the tracker. If the key is
     * already present, this is a repeat tap:
     *  - append a dedup [ConversionResult] so the card renders
     *    `"Conversion already tracked (dedup)"`;
     *  - emit a DEBUG log via [demoLogger] using the SDK's literal
     *    `"Goal '<key>' already tracked for visitor, skipping"` so the
     *    Logs tab shows the same message a real dedup would surface
     *    (the SDK's own log only fires when a real SDK is wired; the
     *    demo unit-test path uses a fake tracker).
     *  - STILL call the tracker so the CONVERSION/bucketing event
     *    pipeline is exercised end-to-end. The SDK itself (Story 4.3
     *    AC-6) performs the atomic dedup guard — no CONVERSION event
     *    fires on the skipped path, which is what AC-3 asserts.
     *
     * First-tap handling: record the goal key in [trackedGoalKeys],
     * call the tracker, append a non-dedup [ConversionResult] carrying
     * the AC-1 amount (10.3) and productsCount (2).
     *
     * Side-effect: the underlying SDK fires [SystemEvents.CONVERSION]
     * on its pub/sub bus which this ViewModel already subscribes to —
     * so the Events tab lights up automatically on the first call and
     * stays silent on dedup calls (per SDK dedup semantics).
     */
    fun trackPurchaseConversion() {
        val goalKey = DEFAULT_GOAL_KEY

        // Goal-existence pre-check (F-174). The SDK's trackConversion
        // silently WARN-logs and drops an unknown goal, so without this
        // guard the screen would show a "Conversion tracked" success card
        // for a goal the SDK never recorded. Surface the unknown goal
        // honestly via the red Conversion error card instead.
        if (!conversionTracker.hasGoal(goalKey)) {
            appendConversionResult(
                ConversionResult(
                    id = ConversionResult.nextId(),
                    goalKey = goalKey,
                    isError = true,
                    errorMessage = "Goal not found: \"$goalKey\"",
                    errorHint = "No goal with this key exists in the fetched " +
                        "config (or the SDK has not loaded it yet). Verify the " +
                        "goal is configured in your Convert project.",
                ),
            )
            return
        }

        val goalData: List<GoalData> = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(DEFAULT_AMOUNT)),
            GoalData(key = GoalDataKey.PRODUCTS_COUNT, value = JsonPrimitive(DEFAULT_PRODUCTS_COUNT)),
        )
        val isDedup = !trackedGoalKeys.add(goalKey)

        // Always call the tracker — the SDK owns the authoritative
        // dedup guard; the demo must not short-circuit it or the
        // inspector observability contract breaks.
        conversionTracker.trackConversion(goalKey = goalKey, goalData = goalData)

        val result = if (isDedup) {
            // Surface the dedup in the Logs tab using the SDK's verbatim
            // literal so the demo matches the log line a real SDK emits
            // on the skipped path (ConvertContext.kt:662).
            demoLogger.debug(
                message = "Goal '$goalKey' already tracked for visitor, skipping",
            )
            ConversionResult(
                id = ConversionResult.nextId(),
                goalKey = goalKey,
                amount = null,
                productsCount = null,
                isDedup = true,
            )
        } else {
            ConversionResult(
                id = ConversionResult.nextId(),
                goalKey = goalKey,
                amount = DEFAULT_AMOUNT,
                productsCount = DEFAULT_PRODUCTS_COUNT,
            )
        }
        appendConversionResult(result)
    }

    /**
     * Story 7.5 — drops every [ConversionResult] card from the screen.
     *
     * Does NOT reset [trackedGoalKeys]: clearing the UI list must not
     * undo the dedup memory, or the next tap would look like a
     * non-dedup (showing a fresh card) while the SDK silently skipped
     * it. That would confuse developers reading the inspector.
     */
    fun clearConversionResults() {
        _conversionResults.value = emptyList()
    }

    private fun appendConversionResult(result: ConversionResult) {
        _conversionResults.update { current ->
            val prepended = listOf(result) + current
            if (prepended.size <= RESULTS_CAP) prepended else prepended.take(RESULTS_CAP)
        }
    }

    private fun appendFeatureResult(result: FeatureResult) {
        _featureResults.update { current ->
            val prepended = listOf(result) + current
            if (prepended.size <= RESULTS_CAP) prepended else prepended.take(RESULTS_CAP)
        }
    }

    /**
     * Shared mapping: `Feature?` (SDK shape) → `FeatureResult` (demo UI
     * shape). Centralised so [runFeature] and [runFeatures] produce
     * identical-looking cards for the same underlying feature.
     *
     * [requestedKey] is the key the developer asked about — used as
     * the card title when [feature] is null (AC-5) and as a fallback
     * when [Feature.key] is absent (null) in the API response.
     */
    private fun buildFeatureResult(requestedKey: String, feature: Feature?): FeatureResult {
        if (feature == null) {
            return FeatureResult(
                id = FeatureResult.nextId(),
                featureKey = requestedKey,
                enabled = false,
                isError = true,
                errorMessage = "No feature for key $requestedKey",
                errorHint = "Check feature config or audience eligibility.",
            )
        }
        // Preserve insertion order — LinkedHashMap iteration matches
        // the Convert config's declared order when the API uses one.
        val typedVariables = feature.variables.orEmpty().map { (name, element) ->
            TypedVariable.fromJson(name = name, element = element)
        }
        return FeatureResult(
            id = FeatureResult.nextId(),
            featureKey = requestedKey,
            enabled = feature.enabled,
            experienceKey = feature.experienceKey,
            variables = typedVariables,
        )
    }

    private fun onSdkEvent(event: String, payload: Map<String, Any?>) {
        // Route API_QUEUE_RELEASED through the resolver — it is a
        // meta-signal (not user-visible content) that drives lifecycle
        // transitions on previously-queued events.
        if (event == SystemEvents.API_QUEUE_RELEASED) {
            onApiQueueReleased(payload)
            return
        }
        // Story 7.6 AC-5 — both ready and config.updated refresh the
        // configState to Loaded with a new snapshot + timestamp. Doing
        // this BEFORE the inspector capture keeps the Config screen in
        // sync with the most recent event even if a later subscriber
        // (hypothetically) throws.
        if (event == SystemEvents.READY || event == SystemEvents.CONFIG_UPDATED) {
            refreshConfigSnapshot()
        }
        val lifecycle = when (event) {
            SystemEvents.BUCKETING, SystemEvents.CONVERSION -> EventLifecycle.QUEUED
            else -> EventLifecycle.NONE
        }
        val captured = InspectorEvent(
            id = InspectorEvent.nextId(),
            eventName = event,
            payload = payload,
            lifecycle = lifecycle,
        )
        _events.update { listOf(captured) + it }
    }

    /**
     * Story 7.6 AC-5 — pulls a fresh snapshot from [configSnapshotProvider]
     * and publishes a new [ConfigState.Loaded] stamped with the
     * current wall-clock time. Called on `ready` and `config.updated`.
     *
     * Any exception from the provider is swallowed — the ViewModel
     * must never crash the host on a snapshot failure. The state stays
     * at whatever value it had (typically Loading on the very first
     * ready after a provider bug), and the user sees the caller's
     * error path through the ordinary log stream.
     */
    private fun refreshConfigSnapshot() {
        val snapshot = runCatching { configSnapshotProvider.snapshot() }.getOrNull() ?: return
        _configState.value = ConfigState.Loaded(
            snapshot = snapshot,
            lastFetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Story 7.2 AC-7 — when `api.queue.released` fires with a 2xx
     * status, every currently-QUEUED networked event is considered
     * delivered; transition them in place. Non-2xx statuses leave the
     * events in QUEUED so the developer can still see what was in
     * flight (the next successful flush will resolve them).
     */
    private fun onApiQueueReleased(payload: Map<String, Any?>) {
        val status = (payload["statusCode"] as? Number)?.toInt() ?: return
        if (status !in 200..299) return
        _events.update { list ->
            list.map { event ->
                if (event.lifecycle == EventLifecycle.QUEUED) {
                    event.copy(lifecycle = EventLifecycle.DELIVERED)
                } else {
                    event
                }
            }
        }
    }

    private fun appendLog(
        level: LogLevel,
        message: String,
        tag: String?,
        throwable: Throwable? = null,
    ) {
        val entry = LogEntry(
            id = LogEntry.nextId(),
            level = level,
            message = message,
            tag = tag,
            throwable = throwable,
        )
        _logs.update { listOf(entry) + it }
        // Story 7.6 AC-7 — a WARN or ERROR log that fires BEFORE the
        // SDK has emitted its first `ready` signals the config-fetch
        // path failed AND there is no cached config to fall back on.
        // Transition the Config screen to Failed so the user sees the
        // reason + hint instead of an indefinite spinner. Post-ready
        // WARN/ERROR does NOT downgrade — the app already has usable
        // config in memory (see configState KDoc).
        if (level == LogLevel.WARN || level == LogLevel.ERROR) {
            _configState.update { current ->
                if (current is ConfigState.Loading) {
                    ConfigState.Failed(
                        reason = message,
                        hint = CONFIG_FAILURE_HINT,
                    )
                } else {
                    current
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        subscriptionTokens.forEach { runCatching { it.close() } }
        subscriptionTokens.clear()
    }

    private companion object {
        /**
         * The five system events AC-9 requires the inspector to
         * observe. Reads the JS-SDK-canon string values directly from
         * [SystemEvents] rather than duplicating the constants here.
         */
        val OBSERVED_EVENTS: List<String> = listOf(
            SystemEvents.READY,
            SystemEvents.CONFIG_UPDATED,
            SystemEvents.BUCKETING,
            SystemEvents.CONVERSION,
            SystemEvents.API_QUEUE_RELEASED,
        )

        /**
         * Story 7.3 Gotcha 2 — bound on the results list so a developer
         * mashing the run buttons cannot create an unbounded `LazyColumn`.
         */
        const val RESULTS_CAP: Int = 20

        /**
         * Story 7.5 AC-1 — hardcoded goal key the Conversions screen's
         * "Buy" button tracks. Documented in `demo/android-app/README.md`.
         */
        const val DEFAULT_GOAL_KEY: String = "purchase-goal"

        /**
         * Story 7.5 AC-1 — hardcoded AMOUNT goal-data value sent with
         * every `trackPurchaseConversion` call.
         */
        const val DEFAULT_AMOUNT: Double = 10.3

        /**
         * Story 7.5 AC-1 — hardcoded PRODUCTS_COUNT goal-data value
         * sent with every `trackPurchaseConversion` call.
         */
        const val DEFAULT_PRODUCTS_COUNT: Int = 2

        /**
         * Story 7.6 AC-7 — fixed remediation hint rendered in the
         * Config screen's error card. The story names this literal
         * verbatim ("Check network + SDK key") so a future
         * UX-copy refresh can find and replace it in one place.
         */
        const val CONFIG_FAILURE_HINT: String = "Check network + SDK key"
    }
}

/**
 * Story 7.3 — default [ExperienceRunner] used by the production
 * constructor when the caller does not supply one. In real use
 * [com.convert.sdk.demo.DemoApplication] passes a runner backed by
 * the SDK; this no-op lets the Robolectric
 * [com.convert.sdk.demo.ui.EventInspectorSheetTest] keep its
 * two-arg constructor call working unchanged.
 */
private object NoOpExperienceRunner : ExperienceRunner {
    override fun runExperience(experienceKey: String): Variation? = null
    override fun runExperiences(): List<Variation> = emptyList()
}

/**
 * Story 7.4 — default [FeatureRunner] used by the production
 * constructor when the caller does not supply one. Mirrors
 * [NoOpExperienceRunner] exactly — the real SDK-backed impl is
 * wired in [com.convert.sdk.demo.DemoApplication]; the no-op
 * exists so existing tests (e.g. [com.convert.sdk.demo.ui.EventInspectorSheetTest])
 * keep working unchanged and the [SdkViewModel] stays constructable
 * with a minimal parameter list.
 */
private object NoOpFeatureRunner : FeatureRunner {
    override fun runFeature(featureKey: String): Feature? = null
    override fun runFeatures(): List<Feature> = emptyList()
}

/**
 * Story 7.5 — default [ConversionTracker] used by the production
 * constructor when the caller does not supply one. Mirrors
 * [NoOpExperienceRunner] / [NoOpFeatureRunner] — the real SDK-backed
 * impl is wired in [com.convert.sdk.demo.DemoApplication]; the no-op
 * exists so existing tests (e.g. [com.convert.sdk.demo.ui.EventInspectorSheetTest])
 * keep working unchanged and the [SdkViewModel] stays constructable
 * with a minimal parameter list.
 */
private object NoOpConversionTracker : ConversionTracker {
    override fun trackConversion(goalKey: String, goalData: List<GoalData>) {
        // No-op — matches the NoOpExperienceRunner / NoOpFeatureRunner
        // pattern. A real demo wires ConvertContext.trackConversion.
    }

    // Reports the goal as present so the default (no real SDK wired)
    // ViewModel keeps the happy-path tracking behaviour the existing
    // tests exercise; the real goal-existence check lives in the
    // DemoApplication-wired tracker backed by ConvertContext.hasGoal.
    override fun hasGoal(goalKey: String): Boolean = true
}

/**
 * Story 7.6 — default [ConfigSnapshotProvider] used by the production
 * constructor when the caller does not supply one. Returns an empty
 * snapshot with `trackingEnabled = null` so the ConfigScreen renders
 * sensible defaults (masked key `""`, `"—"` for tracking) rather than
 * a crash. The real SDK-backed impl is wired in
 * [com.convert.sdk.demo.DemoApplication].
 */
private object NoOpConfigSnapshotProvider : ConfigSnapshotProvider {
    override fun snapshot(): ConfigSnapshot = ConfigSnapshot(
        sdkKey = "",
        environment = null,
        experienceKeys = emptyList(),
        featureKeys = emptyList(),
        trackingEnabled = null,
    )
}
