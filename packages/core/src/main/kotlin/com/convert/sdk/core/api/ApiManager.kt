/*
 * Convert Android SDK — core/api
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConfigDefaults
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.TrackingEvent
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.EventQueue
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches project configuration and batches outbound tracking events.
 *
 * ### Story 2.2 — config fetch (unchanged)
 *
 * Single responsibility: build a HTTPS GET request against the configured
 * CDN endpoint, parse a successful response into a [ConfigResponseData],
 * and return `null` on any failure. See [fetchConfig].
 *
 * ### Story 5.1 — event batching & delivery
 *
 * An in-memory queue collects [VisitorEvent]s and ships them to
 * `POST /track/{sdkKey}` when either:
 *
 *  - the queue size reaches `config.events?.batchSize ?:
 *    ConfigDefaults.DEFAULT_EVENTS_BATCH_SIZE` (default 10), OR
 *  - the timer loop (started from the injected [scope]) ticks every
 *    `config.events?.releaseInterval ?:
 *    ConfigDefaults.DEFAULT_EVENTS_RELEASE_INTERVAL_MS` milliseconds
 *    (default 1000ms — matches JS SDK parity).
 *
 * #### Payload shape (AC-4)
 *
 * The outbound JSON body mirrors the JS SDK's `releaseQueue()` exactly —
 * see `javascript-sdk/packages/api/src/api-manager.ts:208-252` and
 * `javascript-sdk/packages/types/src/config/types.gen.ts:2738-2806`:
 *
 * ```
 * {
 *   "accountId": "<from config.data.account_id>",
 *   "projectId": "<from config.data.project.id>",
 *   "enrichData": <true iff no config.data — see note below>,
 *   "source": "<from config.network.source>",
 *   "visitors": [
 *     {
 *       "visitorId": "...",
 *       "segments": { ... },
 *       "events": [
 *         { "eventType": "bucketing",  "data": { "experienceId", "variationId" } },
 *         { "eventType": "conversion", "data": { "goalId", "goalData"? } }
 *       ]
 *     },
 *     ...
 *   ]
 * }
 * ```
 *
 * Multiple enqueues for the same `visitorId` collapse into one `Visitor`
 * object whose `events` array preserves enqueue order. Segments are
 * snapshotted from the LAST enqueue for that visitor (the JS SDK does the
 * same — see `VisitorsQueue.push` which replaces segments when the
 * visitor already exists).
 *
 * #### Why we hand-serialize instead of using SendTrackingEventsRequestData
 *
 * The OpenAPI-generated `ConversionEventGoalDataInnerValue` is an empty
 * placeholder class because the underlying schema types `value` as
 * `number | string | Array<string>` — a union the Kotlin generator can't
 * express. Binding our [GoalData.value] (a [JsonElement]) through it
 * would drop the actual value. We therefore emit the wire JSON directly
 * via [buildJsonObject] at flush time.
 *
 * #### enrichData semantics
 *
 * The JS SDK sets `enrichData = !config.dataStore` — true when the SDK
 * does not keep an in-memory store of loaded config. For the Android SDK,
 * `config.data != null` means we have the loaded CDN config in memory, so
 * `enrichData = false`. When `config.data` is null we shouldn't be able
 * to enqueue anyway (projectId null → flush is skipped), but the field is
 * emitted for wire parity.
 *
 * The supplied [json] instance MUST be configured with
 * `ignoreUnknownKeys = true; explicitNulls = false` (Story 2.2 AC-2,
 * F-138 option a):
 *  - `ignoreUnknownKeys = true` is **required** for forward
 *    compatibility (NFR12). The kotlinx.serialization default is
 *    `false`, which would throw on any new backend field old SDK
 *    versions don't yet know about.
 *  - `explicitNulls = false` is the kotlinx.serialization default and
 *    is retained explicitly to document intent: null fields in
 *    [ConfigResponseData] are omitted from encoded JSON, which keeps
 *    the cache write path's payload tight (see
 *    [com.convert.sdk.android.adapter.FileConfigCache]).
 *
 * Reference: [kotlinx.serialization Json builder defaults](https://kotlinlang.org/api/kotlinx.serialization/).
 *
 * #### Snapshot-and-release concurrency (AC-9)
 *
 * [flush] copies the queue and clears it under [queueLock], then
 * releases the lock before issuing the HTTP POST. This prevents the
 * lock being held for the duration of a slow network call. On failure
 * (non-2xx or thrown exception) the snapshot is prepended back to the
 * queue under the lock so enqueue order is preserved (Story 5.2 adds
 * retry + disk persistence on top of this).
 *
 * ### Story 5.2 — offline persistence + retry
 *
 * The flush path distinguishes two failure modes:
 *
 *  - **True offline** ([IOException] family:
 *    [java.net.UnknownHostException], [java.net.ConnectException]):
 *    no point retrying now — the network is genuinely unreachable.
 *    Persist the snapshot to [eventQueue] and return. The next flush
 *    is triggered by [NetworkObserver][com.convert.sdk.android.lifecycle.NetworkObserver]
 *    calling [reenqueuePersisted] with the persisted events + [flush].
 *  - **Server/transient error** (non-2xx HTTP, [java.net.SocketTimeoutException],
 *    or other non-IO exception): exponential backoff 10s → 20s → 40s,
 *    max 3 retries. After the third failure, persist and give up foreground
 *    retry — NetworkObserver will re-drive the flush.
 *
 * Dedup on re-enqueue: [com.convert.sdk.core.model.VisitorEvent]s coming
 * back through [reenqueuePersisted] are compared against the live queue by
 * `TrackingEvent` content equality (Kotlin data class `equals()`/`hashCode()`
 * over payload fields). Any event already present in the live queue is
 * dropped. Known MVP tradeoff: two legitimately separate but payload-identical
 * events for the same visitor will be deduped. Documented as accepted limitation.
 * [Source: 5-2 patched spec AC-6, F-002/F-014 option c]
 *
 * ### Tracking toggle (Story 5.4)
 *
 * [isTrackingEnabled] / [setTrackingEnabled] gate the enqueue path. When
 * tracking is disabled, [enqueueBucketingEvent] / [enqueueConversionEvent]
 * are no-ops — nothing enters the queue, and nothing is posted.
 *
 * @property httpClient transport port used for the GET (config) and
 *   POST (tracking) calls.
 * @property logger failure logger; all messages carry the [TAG] so
 *   Logcat filtering works.
 * @property config fully assembled SDK configuration.
 * @property json shared `kotlinx.serialization` instance for parsing
 *   config responses.
 * @property eventManager optional — when non-null, a successful flush
 *   fires [SystemEvents.API_QUEUE_RELEASED] with `{reason, result,
 *   visitors}` payload matching JS SDK `api-manager.ts:232-237` (Story 2.4 bus).
 * @property scope optional coroutine scope. When non-null the timer
 *   loop is started automatically; when null the manager operates in a
 *   passive mode (flush must be driven explicitly — primarily for
 *   pure-JVM tests and for the short window between construction and
 *   `ConvertSDK.Builder.build()` wiring).
 * @property ioDispatcher dispatcher used for the HTTP POST. Injected so
 *   `TestScope`-driven tests can pin network I/O onto a
 *   [kotlinx.coroutines.test.StandardTestDispatcher] and drive virtual
 *   time through [kotlinx.coroutines.test.advanceTimeBy].
 * @property eventQueue optional [EventQueue] for offline persistence.
 *   When null, the [flush] failure path simply re-prepends the snapshot
 *   to the in-memory queue — suitable for pure-JVM tests that do not
 *   exercise offline behaviour. The SDK builder always wires
 *   [com.convert.sdk.android.adapter.FileEventQueue] here.
 */
@Suppress("TooManyFunctions", "LongParameterList")
public open class ApiManager(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val config: ConvertConfig,
    private val json: Json,
    private val eventManager: EventManager? = null,
    private val scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val eventQueue: EventQueue? = null,
    private val liveConfigData: () -> ConfigResponseData? = { null },
) {

    /**
     * Returns the effective [ConfigResponseData] — preferring the live
     * value from [liveConfigData] (populated when the SDK has fetched /
     * cached / been seeded with a config after construction), falling back
     * to [config].data (the Builder.data(...) direct-data path).
     *
     * This is the glue that fixes the sdkKey+fetch tracking bug surfaced
     * by Story 5.5's full-chain integration test. Before the fix,
     * `flush()` only worked when [config].data was non-null at Builder
     * time — i.e. only the pre-seeded direct-data path. In the real
     * production flow (merchant supplies sdkKey, SDK fetches config at
     * runtime), [config].data stayed null forever and every flush
     * silently no-opped with "projectId is null, skipping flush". The
     * [liveConfigData] accessor is wired to [com.convert.sdk.core.data.DataManager.data]
     * at Builder time so that once the fetched config lands, ApiManager
     * picks it up on the next flush call.
     */
    internal fun effectiveConfigData(): ConfigResponseData? =
        liveConfigData() ?: config.data

    /**
     * Internal envelope around one queued event: the visitor it belongs
     * to, the visitor's segment snapshot at enqueue time, and the
     * already-built event JSON (eventType + data) ready to drop into the
     * outbound payload. Keeping the event pre-serialised lets [flush]
     * avoid any per-event serialisation work under the lock.
     *
     * Also retains the original [TrackingEvent] so that [reenqueuePersisted]
     * can build a dedup set keyed on payload equality (Story 5.2 AC-6).
     *
     * @property visitorId the visitor id for grouping at flush time.
     * @property segments segment snapshot to attach to this visitor's
     *   entry in the outbound payload. Merged segments produced by
     *   [com.convert.sdk.core.model.VisitorContext.getMergedSegments];
     *   may be empty.
     * @property event pre-built JSON for this event (one of
     *   `{eventType: "bucketing", data: {...}}` or
     *   `{eventType: "conversion", data: {...}}`).
     * @property trackingEvent the original [TrackingEvent] retained for
     *   dedup key construction in [reenqueuePersisted].
     */
    internal data class VisitorEvent(
        val visitorId: String,
        val segments: Map<String, JsonElement>,
        val event: JsonObject,
        val trackingEvent: TrackingEvent,
    )

    private val trackingEnabled: AtomicBoolean = AtomicBoolean(
        config.network?.tracking ?: ConfigDefaults.DEFAULT_TRACKING_ENABLED,
    )

    /**
     * Guards [eventQueueInternal]. Every mutation (enqueue, snapshot, clear,
     * requeue) goes through `synchronized(queueLock)`. JVM monitor lock
     * is chosen over `kotlinx.coroutines.Mutex` because the non-suspend
     * public API ([enqueueBucketingEvent], [enqueueConversionEvent])
     * cannot `withLock { ... }`. Work done inside the lock is always
     * O(snapshot size) and NEVER includes HTTP I/O (see [flush]'s
     * snapshot-and-release pattern — lock is released BEFORE the POST).
     */
    private val queueLock: Any = Any()
    private val eventQueueInternal: MutableList<VisitorEvent> = mutableListOf()

    private val batchSize: Int =
        config.events?.batchSize ?: ConfigDefaults.DEFAULT_EVENTS_BATCH_SIZE
    private val releaseInterval: Long =
        config.events?.releaseInterval ?: ConfigDefaults.DEFAULT_EVENTS_RELEASE_INTERVAL_MS

    private var timerJob: Job? = null

    init {
        // Launch the periodic flush loop if a scope was supplied. We
        // guard against double-start via the nullability of timerJob
        // (Gotcha 3 in the story — reinitialisation must not stack
        // timer loops).
        scope?.let { startTimerLoop(it) }
    }

    /**
     * Returns the current tracking-enabled state for outbound events.
     *
     * Story 5.4 introduced this accessor so that the live server config
     * (`config.network.tracking`) can be consulted at the call site
     * without every caller reaching into [ConvertConfig]. `true` means
     * [enqueueBucketingEvent] and [enqueueConversionEvent] accept new
     * events; `false` means they short-circuit and log at DEBUG.
     *
     * This method does not throw; it performs a single volatile read.
     *
     * @return `true` when tracking is currently enabled, `false` otherwise.
     */
    public fun isTrackingEnabled(): Boolean = trackingEnabled.get()

    /**
     * Updates the tracking-enabled state.
     *
     * Story 5.4 exposes this entry point so that [DataManager] can
     * reapply `config.network.tracking` every time a fresh server config
     * is loaded (the config fetch is the authoritative source of truth).
     * Flipping from `false` to `true` does **not** replay events that
     * were dropped while tracking was disabled — dropped events stay
     * dropped. Flipping from `true` to `false` does **not** flush the
     * live queue; callers drive [flushEvents] explicitly when needed.
     *
     * This method does not throw; it performs a single volatile write.
     *
     * @param enabled `true` to accept new events, `false` to drop them
     *   at enqueue time until the next flip.
     */
    public fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled.set(enabled)
    }

    /**
     * Enqueues a bucketing event for the given visitor.
     *
     * Called by [com.convert.sdk.android.ConvertContext.runExperience] once
     * per successful bucketing decision. The event is appended to the
     * in-memory live queue; delivery to `POST /track/{sdkKey}` happens
     * asynchronously when the queue reaches `config.events.batchSize`
     * events or the release-interval timer ticks (whichever comes first).
     * When tracking is disabled via [setTrackingEnabled], the call is
     * recorded at DEBUG and otherwise becomes a no-op — no event is
     * enqueued and no network activity occurs.
     *
     * This method does not throw; enqueue failures (capacity, lock
     * acquisition) are logged internally and the call returns normally.
     *
     * @param visitorId stable visitor identifier captured on the calling
     *   [com.convert.sdk.android.ConvertContext].
     * @param experienceId the experience the visitor was bucketed into.
     * @param variationId the variation returned from bucketing.
     * @param segments the visitor segment snapshot to attach to the
     *   outbound payload. Defaults to an empty map when the context has
     *   no resolved segments.
     */
    @JvmOverloads
    public open fun enqueueBucketingEvent(
        visitorId: String,
        experienceId: String,
        variationId: String,
        segments: Map<String, JsonElement> = emptyMap(),
    ) {
        if (!trackingEnabled.get()) {
            logger.debug("ApiManager.enqueueBucketingEvent() skipped: tracking disabled", TAG)
            return
        }
        val trackingEvent = BucketingEvent(experienceId = experienceId, variationId = variationId)
        val data = buildJsonObject {
            put(KEY_EXPERIENCE_ID, experienceId)
            put(KEY_VARIATION_ID, variationId)
        }
        val event = buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_BUCKETING)
            put(KEY_DATA, data)
        }
        enqueueInternal(VisitorEvent(visitorId, segments, event, trackingEvent))
    }

    /**
     * Enqueues a conversion event for the given visitor and goal.
     *
     * Called by [com.convert.sdk.android.ConvertContext.trackConversion].
     * Follows the same queuing / batch / tracking-toggle contract as
     * [enqueueBucketingEvent]: appended to the live queue, delivered
     * asynchronously on `batchSize` or `releaseInterval`, skipped with a
     * DEBUG log when [isTrackingEnabled] is `false`.
     *
     * This method does not throw; enqueue failures are logged internally.
     *
     * @param visitorId stable visitor identifier captured on the calling
     *   [com.convert.sdk.android.ConvertContext].
     * @param goalId the goal the visitor converted on.
     * @param goalData optional list of `GoalData` entries (amount,
     *   productsCount, transactionId, customDimension1..5). Nullable
     *   because the tracking API accepts a bare goal id with no data.
     * @param segments the visitor segment snapshot to attach to the
     *   outbound payload. Defaults to an empty map.
     */
    @JvmOverloads
    public open fun enqueueConversionEvent(
        visitorId: String,
        goalId: String,
        goalData: List<GoalData>?,
        segments: Map<String, JsonElement> = emptyMap(),
    ) {
        if (!trackingEnabled.get()) {
            logger.debug("ApiManager.enqueueConversionEvent() skipped: tracking disabled", TAG)
            return
        }
        val trackingEvent = ConversionEvent(goalId = goalId, goalData = goalData)
        val data = buildConversionData(goalId, goalData)
        val event = buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_CONVERSION)
            put(KEY_DATA, data)
        }
        enqueueInternal(VisitorEvent(visitorId, segments, event, trackingEvent))
    }

    private fun buildConversionData(
        goalId: String,
        goalData: List<GoalData>?,
    ): JsonObject {
        if (goalData.isNullOrEmpty()) {
            return buildJsonObject { put(KEY_GOAL_ID, goalId) }
        }
        val goalDataArray = buildJsonArray {
            goalData.forEach { entry ->
                val entryObj = buildJsonObject {
                    entry.key?.let { k ->
                        // Use the @SerialName (camelCase) wire value.
                        put(KEY_KEY, serialNameFor(k))
                    }
                    put(KEY_VALUE, entry.value ?: JsonNull)
                }
                add(entryObj)
            }
        }
        return buildJsonObject {
            put(KEY_GOAL_ID, goalId)
            put(KEY_GOAL_DATA, goalDataArray)
        }
    }

    /**
     * Adds [event] to the in-memory queue and, when the queue size hits
     * [batchSize], triggers an immediate flush on [scope]. When no
     * scope is available the batch threshold is still respected (the
     * caller is expected to drive [flushForTest] in tests, or the timer
     * loop picks it up in production).
     */
    private fun enqueueInternal(event: VisitorEvent) {
        val shouldFlush: Boolean
        synchronized(queueLock) {
            eventQueueInternal += event
            shouldFlush = eventQueueInternal.size >= batchSize
        }
        if (shouldFlush) {
            scope?.launch { flush() }
        }
    }

    /**
     * Rehydrates previously-persisted events back onto the live queue.
     *
     * Called by [com.convert.sdk.android.ConvertSDK] when the
     * [NetworkObserver][com.convert.sdk.android.lifecycle.NetworkObserver]
     * fires on network restore.
     *
     * Dedup (Story 5.2 AC-6, F-002/F-014 option c): events are compared
     * against the live queue by [TrackingEvent] content equality (Kotlin
     * data class `equals()`/`hashCode()` over payload fields). Any event
     * whose [TrackingEvent] is already present in the live queue is silently
     * dropped.
     *
     * Known MVP tradeoff: two legitimately separate but payload-identical
     * events for the same visitor are deduped. Documented as accepted
     * limitation in the patched 5-2 spec Dev Notes.
     *
     * @param events [com.convert.sdk.core.model.VisitorEvent]s read from
     *   the [EventQueue] disk store by the NetworkObserver path. Each
     *   carries a [com.convert.sdk.core.model.TrackingEvent] that is
     *   re-serialized into the ApiManager's internal wire-JSON format.
     */
    public open fun reenqueuePersisted(events: List<com.convert.sdk.core.model.VisitorEvent>) {
        if (events.isEmpty()) return
        // Story 5.4 AC-2 — tracking-flag guard BEFORE the monitor lock.
        // [ConvertSDK.onNetworkAvailable] / [ConvertSDK.onProcessStart]
        // drive this path on connectivity / foreground restore; without
        // the short-circuit, persisted events from before a consent
        // revocation would continue flowing into the live queue.
        if (!trackingEnabled.get()) {
            logger.debug("ApiManager.enqueueAll() skipped: tracking disabled", TAG)
            return
        }
        synchronized(queueLock) {
            // Build a dedup set from the live queue's TrackingEvent payloads.
            val liveSet: MutableSet<TrackingEvent> =
                eventQueueInternal.mapTo(HashSet()) { it.trackingEvent }
            events.forEach { ve ->
                if (liveSet.add(ve.event)) {
                    val internalEvent = toInternalVisitorEvent(ve)
                    eventQueueInternal += internalEvent
                }
            }
        }
    }

    /**
     * Bulk-enqueues events restored from disk on foreground resume (onStart).
     *
     * ### Story 5.3 AC-4 — rehydrate path
     *
     * Called by [com.convert.sdk.android.ConvertSDK.onProcessStart] when the
     * app returns to the foreground. Events previously persisted by
     * [com.convert.sdk.android.ConvertSDK.onProcessStop] (or not yet delivered
     * by [com.convert.sdk.android.worker.EventFlushWorker]) are re-enqueued
     * here so the foreground batch timer can ship them through the normal
     * retry path.
     *
     * Each [com.convert.sdk.core.model.VisitorEvent] carries (visitorId,
     * segments, TrackingEvent) — [ApiManager] appends each to its internal
     * queue, re-serializing the [TrackingEvent] into the internal wire-JSON
     * format.
     *
     * ### Story 5.4 AC-2 — tracking-flag guard
     *
     * The corrected AC-2 requires every queue-adding method to honour the
     * tracking flag. Without this guard, persisted events from before a
     * consent revocation would resume flowing into the live queue on
     * foreground resume. The check runs BEFORE the monitor lock so a
     * disabled-tracking call has zero contention cost.
     *
     * @param events events read from [com.convert.sdk.core.port.EventQueue];
     *   may be empty (caller short-circuits on empty, but a no-op is safe).
     */
    public open fun enqueueAll(events: List<com.convert.sdk.core.model.VisitorEvent>) {
        if (events.isEmpty()) return
        if (!trackingEnabled.get()) {
            logger.debug("ApiManager.enqueueAll() skipped: tracking disabled", TAG)
            return
        }
        synchronized(queueLock) {
            events.forEach { ve ->
                val internalEvent = toInternalVisitorEvent(ve)
                eventQueueInternal += internalEvent
            }
        }
    }

    /**
     * Converts a [com.convert.sdk.core.model.VisitorEvent] (the port's
     * persisted type) into the [VisitorEvent] the internal live queue uses.
     * Re-serializes the [TrackingEvent] sealed subtype into a [JsonObject].
     */
    private fun toInternalVisitorEvent(
        ve: com.convert.sdk.core.model.VisitorEvent,
    ): VisitorEvent {
        val eventJson: JsonObject = when (val te = ve.event) {
            is BucketingEvent -> buildJsonObject {
                put(KEY_EVENT_TYPE, EVENT_TYPE_BUCKETING)
                put(
                    KEY_DATA,
                    buildJsonObject {
                        put(KEY_EXPERIENCE_ID, te.experienceId)
                        put(KEY_VARIATION_ID, te.variationId)
                    },
                )
            }
            is ConversionEvent -> buildJsonObject {
                put(KEY_EVENT_TYPE, EVENT_TYPE_CONVERSION)
                put(KEY_DATA, buildConversionData(te.goalId, te.goalData))
            }
        }
        return VisitorEvent(
            visitorId = ve.visitorId,
            segments = ve.segments ?: emptyMap(),
            event = eventJson,
            trackingEvent = ve.event,
        )
    }

    /**
     * Posts the current queue contents to the tracking endpoint.
     *
     * Snapshot-and-release sequence (AC-9):
     *  1. Under [queueLock], copy + drain [eventQueueInternal] into a local
     *     snapshot. Release the lock.
     *  2. Build the JSON payload from the snapshot (one Visitor entry
     *     per unique visitorId).
     *  3. POST to `{trackEndpoint}/track/{sdkKey}` on [ioDispatcher].
     *  4. On HTTP 2xx: fire [SystemEvents.API_QUEUE_RELEASED] with
     *     `{reason, result, visitors}` matching JS SDK `api-manager.ts:232-237`
     *     and drop the snapshot.
     *  5. On [IOException] family ([java.net.UnknownHostException],
     *     [java.net.ConnectException]): persist snapshot and return immediately.
     *     No retry — rely on NetworkObserver.
     *  6. On [java.net.SocketTimeoutException] or non-2xx HTTP: apply
     *     exponential backoff (10s → 20s → 40s), max 3 retries. After max
     *     retries, persist snapshot and stop foreground retry.
     *
     * The HTTP call runs OUTSIDE the lock — holding a monitor across a
     * network call would starve concurrent enqueues.
     *
     * Skipped with WARN when `sdkKey` or projectId is unresolvable —
     * we must not POST to a partial URL.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod")
    internal suspend fun flush(retryCount: Int = 0) {
        val sdkKey = config.sdkKey
        val projectId = effectiveConfigData()?.project?.id
        if (sdkKey.isNullOrEmpty()) {
            logger.warn("ApiManager.flush(): sdkKey is null, skipping flush", tag = TAG)
            return
        }
        if (projectId.isNullOrEmpty()) {
            logger.warn("ApiManager.flush(): projectId is null, skipping flush", tag = TAG)
            return
        }

        val snapshot: List<VisitorEvent> = synchronized(queueLock) {
            if (eventQueueInternal.isEmpty()) return
            val copy = eventQueueInternal.toList()
            eventQueueInternal.clear()
            copy
        }

        val url = buildTrackUrl(sdkKey, projectId)
        val payload = buildPayload(snapshot)

        val response = try {
            withContext(ioDispatcher) {
                httpClient.post(url, payload, mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON))
            }
        } catch (t: Throwable) {
            handleFlushException(t, snapshot, retryCount)
            return
        }

        if (response.statusCode in HTTP_2XX_RANGE) {
            // Build the visitors array from the snapshot for the API_QUEUE_RELEASED
            // payload — matches JS SDK api-manager.ts:232-237:
            //   { reason, result, visitors: payload.visitors }
            val visitorsPayload = buildVisitorsArray(snapshot)
            eventManager?.fire(
                event = SystemEvents.API_QUEUE_RELEASED,
                data = mapOf(
                    "reason" to "release",
                    "result" to response.statusCode,
                    "visitors" to visitorsPayload,
                ),
            )
        } else {
            logger.warn(
                message = "ApiManager.flush(): ${response.statusCode} ${response.body.take(MAX_BODY_LOG_CHARS)}",
                tag = TAG,
            )
            scheduleRetryOrPersist(snapshot, retryCount)
        }
    }

    /**
     * Dispatches a flush exception into one of the two failure modes:
     *  - [IOException] family (UnknownHostException, ConnectException) →
     *    persist + stop (no foreground retry; wait for NetworkObserver)
     *  - SocketTimeoutException or other → treat as server-side transient;
     *    apply exponential backoff per AC-2 / AC-3 (patched spec F-114 option a)
     */
    private suspend fun handleFlushException(
        t: Throwable,
        snapshot: List<VisitorEvent>,
        retryCount: Int,
    ) {
        val isTrueOffline = t is java.net.UnknownHostException || t is java.net.ConnectException
        if (isTrueOffline) {
            logger.warn(
                message = "ApiManager.flush(): offline (${t::class.simpleName}: ${t.message}) — persisting",
                throwable = t,
                tag = TAG,
            )
            persistOrRequeue(snapshot)
        } else {
            // Includes SocketTimeoutException (slow-server, not true offline)
            // and any other IOException subtype.
            logger.warn(
                message = "ApiManager.flush(): network error: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            scheduleRetryOrPersist(snapshot, retryCount)
        }
    }

    /**
     * Retry scheduling: if we have retries left, re-prepend the snapshot
     * onto the queue and schedule a flush after `RETRY_DELAYS_MS[retryCount]`
     * ms. Otherwise persist and give up foreground retry.
     *
     * Re-prepending (rather than persisting on every retry) keeps the
     * in-memory queue live for the next attempt; the persistence path
     * fires only when we hit the max-retry ceiling.
     */
    private fun scheduleRetryOrPersist(
        snapshot: List<VisitorEvent>,
        retryCount: Int,
    ) {
        if (retryCount >= MAX_FOREGROUND_RETRIES) {
            persistOrRequeue(snapshot)
            return
        }
        requeueFront(snapshot)
        val delayMs = RETRY_DELAYS_MS[retryCount]
        val nextRetry = retryCount + 1
        scope?.launch {
            delay(delayMs)
            flush(retryCount = nextRetry)
        }
        // No scope — passive mode; the test drives flush manually.
        // The snapshot is on the queue; the test's next flushForTest picks it up.
    }

    /**
     * Persists [snapshot] to the [eventQueue] when present, converting the
     * internal [VisitorEvent] format to the port's
     * [com.convert.sdk.core.model.VisitorEvent] format.
     * Falls back to re-prepending when no [EventQueue] is wired.
     */
    private fun persistOrRequeue(snapshot: List<VisitorEvent>) {
        val q = eventQueue
        if (q != null) {
            val persisted = snapshot.map { ve ->
                com.convert.sdk.core.model.VisitorEvent(
                    visitorId = ve.visitorId,
                    segments = ve.segments.ifEmpty { null },
                    event = ve.trackingEvent,
                )
            }
            scope?.launch { q.persist(persisted) }
                ?: requeueFront(snapshot) // scope-less path: keep on in-memory queue
        } else {
            requeueFront(snapshot)
        }
    }

    /**
     * Public wrapper around the retry-aware [flush] — used by
     * [com.convert.sdk.android.ConvertSDK.onNetworkAvailable] (Story 5.2
     * AC-4) to drain re-enqueued persisted events. The retry counter is
     * always reset to 0 so a network-restore-triggered flush gets the
     * full backoff budget rather than inheriting some stale counter.
     */
    public open suspend fun flushNow() {
        flush()
    }

    /**
     * `open` solely so that test doubles in the `:packages:sdk` module
     * can override [snapshotQueue] without instantiating a full Robolectric
     * Android context. Production callers never override.
     *
     * Returns a point-in-time copy of the live in-memory event queue as
     * [com.convert.sdk.core.model.VisitorEvent]s, without draining.
     *
     * ### Story 5.3 AC-4 — onStop persistence path
     *
     * When the host app moves to background, [com.convert.sdk.android.ConvertSDK]
     * calls [flushNow] to attempt an immediate delivery. Whatever is still
     * sitting in the in-memory queue after that attempt (either a flush
     * was mid-retry, the size threshold wasn't reached, or the call simply
     * had nothing to ship) is snapshotted here and handed to
     * [com.convert.sdk.core.port.EventQueue.persist] so that the
     * [com.convert.sdk.android.worker.EventFlushWorker] can pick it up
     * after process death.
     *
     * The returned list is a fresh copy on each call — callers may safely
     * mutate it without affecting the live queue.
     *
     * @return a list of [com.convert.sdk.core.model.VisitorEvent] mirroring
     *   the live queue in enqueue order.
     */
    public open fun snapshotQueue(): List<com.convert.sdk.core.model.VisitorEvent> = synchronized(queueLock) {
        eventQueueInternal.map { ve ->
            com.convert.sdk.core.model.VisitorEvent(
                visitorId = ve.visitorId,
                segments = ve.segments.ifEmpty { null },
                event = ve.trackingEvent,
            )
        }
    }

    internal suspend fun flushForTest() {
        flush()
    }

    /**
     * Test seam — returns a snapshot of the current live queue.
     * Used by [com.convert.sdk.core.api.ApiManagerRetryTest] to verify
     * dedup and queue state without exposing the queue as a public field.
     */
    internal fun snapshotQueueForTest(): List<VisitorEvent> = synchronized(queueLock) {
        eventQueueInternal.toList()
    }

    /**
     * Test seam — cancels the periodic timer job so `runTest`
     * completes without hanging on the infinite `while (isActive)`
     * loop. Production code never calls this; the timer is expected
     * to run for the lifetime of the SDK (Story 5.3 may add an
     * explicit shutdown hook).
     */
    internal fun cancelTimerForTest() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun requeueFront(snapshot: List<VisitorEvent>) {
        synchronized(queueLock) {
            // Preserve original ordering: the retry should flush the
            // oldest event first. addAll(0, ...) prepends, keeping the
            // snapshot's own relative order.
            eventQueueInternal.addAll(0, snapshot)
        }
    }

    private fun buildTrackUrl(sdkKey: String, projectId: String): String {
        val template = config.api?.endpoint?.track ?: ConfigDefaults.DEFAULT_TRACK_ENDPOINT
        val withProject = template.replace(TEMPLATE_PROJECT_ID, projectId)
        val normalised = withProject.trimEnd('/')
        return "$normalised/track/$sdkKey"
    }

    /**
     * Assembles the outbound POST body from [snapshot].
     *
     * Story 5.3: the wire format lives in [TrackingPayloadBuilder] so
     * [com.convert.sdk.android.worker.EventFlushWorker] (which cannot
     * reach into ApiManager) can produce a byte-identical body from the
     * disk-loaded [com.convert.sdk.core.model.VisitorEvent] list.
     * ApiManager converts its internal [VisitorEvent] wrappers to the
     * port's [com.convert.sdk.core.model.VisitorEvent] envelopes and
     * delegates to the shared builder.
     */
    private fun buildPayload(snapshot: List<VisitorEvent>): String {
        val portEvents: List<com.convert.sdk.core.model.VisitorEvent> = snapshot.map { ve ->
            com.convert.sdk.core.model.VisitorEvent(
                visitorId = ve.visitorId,
                segments = ve.segments.ifEmpty { null },
                event = ve.trackingEvent,
            )
        }
        return TrackingPayloadBuilder.build(
            events = portEvents,
            config = config,
            json = json,
            liveData = effectiveConfigData(),
        )
    }

    /**
     * Builds the visitors array from a snapshot for the API_QUEUE_RELEASED
     * event payload. Matches JS SDK `api-manager.ts:232-237` where
     * `payload.visitors` is passed directly — the JS SDK consumer receives
     * the structured visitor list (each entry: `{visitorId, segments?,
     * events: [{eventType, data}]}`), not stringified JSON.
     *
     * Per-event payloads are kept as the underlying [JsonObject]
     * representation so downstream observers (Story 7.2's
     * `SdkViewModel.events` resolver) can read each event's `eventType`
     * and `data` fields without having to re-parse a JSON string.
     */
    private fun buildVisitorsArray(snapshot: List<VisitorEvent>): List<Map<String, Any?>> {
        val grouped: Map<String, List<VisitorEvent>> = snapshot.groupBy { it.visitorId }
        return grouped.map { (visitorId, events) ->
            val lastSegments = events.last().segments
            buildMap<String, Any?> {
                put("visitorId", visitorId)
                if (lastSegments.isNotEmpty()) {
                    put("segments", lastSegments)
                }
                put("events", events.map { it.event })
            }
        }
    }

    private fun startTimerLoop(hostScope: CoroutineScope) {
        if (timerJob?.isActive == true) return
        timerJob = hostScope.launch {
            while (isActive) {
                delay(releaseInterval)
                flush()
            }
        }
    }

    /**
     * Maps a [GoalData.key] enum value to its on-wire string form
     * (camelCase, matching the JS SDK's `GoalDataKey` enum). Kept in
     * sync with [com.convert.sdk.core.model.GoalDataKey]'s `@SerialName`
     * annotations — if you add a new key there, add it here too.
     */
    private fun serialNameFor(key: com.convert.sdk.core.model.GoalDataKey): String = when (key) {
        com.convert.sdk.core.model.GoalDataKey.AMOUNT -> "amount"
        com.convert.sdk.core.model.GoalDataKey.PRODUCTS_COUNT -> "productsCount"
        com.convert.sdk.core.model.GoalDataKey.TRANSACTION_ID -> "transactionId"
        com.convert.sdk.core.model.GoalDataKey.CUSTOM_DIMENSION_1 -> "customDimension1"
        com.convert.sdk.core.model.GoalDataKey.CUSTOM_DIMENSION_2 -> "customDimension2"
        com.convert.sdk.core.model.GoalDataKey.CUSTOM_DIMENSION_3 -> "customDimension3"
        com.convert.sdk.core.model.GoalDataKey.CUSTOM_DIMENSION_4 -> "customDimension4"
        com.convert.sdk.core.model.GoalDataKey.CUSTOM_DIMENSION_5 -> "customDimension5"
    }

    // ---------------------------------------------------------------
    // Story 2.2 — config fetch (unchanged — kept verbatim below).
    // ---------------------------------------------------------------

    /**
     * Fetches the current project configuration from the CDN endpoint.
     *
     * Story 2.2 contract: GET the configured `api.endpoint.config` URL
     * with the visitor-scope `sdkKey` query parameter, parse a 2xx
     * response body into a [ConfigResponseData], and return it. On any
     * error — null `sdkKey`, non-https endpoint, network failure,
     * non-2xx status, malformed JSON — return `null` after logging the
     * reason via the configured [Logger]. Callers (Story 2.3 lifecycle
     * refresh, Story 5.5 full-chain integration) treat `null` as
     * "retain the last known good config" and never surface the failure
     * to consumer code.
     *
     * Runs on [Dispatchers.IO]; this method suspends but does not
     * throw — every caught exception is logged and `null` returned.
     *
     * @return the parsed [ConfigResponseData] on a 2xx response with a
     *   valid body, or `null` if any precondition fails or the fetch
     *   errored out. Never throws.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    public suspend fun fetchConfig(): ConfigResponseData? = withContext(Dispatchers.IO) {
        val url = buildConfigUrl() ?: return@withContext null
        val headers = buildHeaders()

        val response = try {
            httpClient.get(url, headers)
        } catch (t: Throwable) {
            logger.warn(
                message = "ApiManager.fetchConfig(): network error fetching $url: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            return@withContext null
        }

        if (response.statusCode == 0) {
            logger.warn(
                message = "ApiManager.fetchConfig(): network error (statusCode 0) fetching $url",
                tag = TAG,
            )
            return@withContext null
        }

        if (response.statusCode !in HTTP_2XX_RANGE) {
            val bodySnippet = response.body.take(MAX_BODY_LOG_CHARS)
            logger.warn(
                message = "ApiManager.fetchConfig(): ${response.statusCode} $bodySnippet",
                tag = TAG,
            )
            return@withContext null
        }

        return@withContext try {
            json.decodeFromString(ConfigResponseData.serializer(), response.body)
        } catch (t: Throwable) {
            logger.error(
                message = "ApiManager.fetchConfig(): failed to parse config response: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            null
        }
    }

    /**
     * Builds the request URL per AC-1. Returns `null` (after logging a
     * WARN) if:
     *  - `sdkKey` is absent — we can't build the path without it.
     *  - The resolved endpoint is not HTTPS AND not a loopback address —
     *    NFR7 forbids plaintext production traffic. `localhost` /
     *    `127.0.0.1` are exempt so that tests using MockWebServer over
     *    plain HTTP work without TLS setup; production endpoints are
     *    never loopback, so this carve-out cannot weaken real-world
     *    security.
     *
     * Output shape: `{base}/config/{sdkKey}{query}` where `{query}` is
     * built per AC-1 (F-006 option a):
     *  - `?` prefix when either `environment` is non-null or
     *    `cacheLevel == "low"`; otherwise empty.
     *  - `environment={env}` appended when `config.environment` is set.
     *  - `_conv_low_cache=1` appended when `cacheLevel == "low"`, with a
     *    leading `&` if `environment=` was already appended (so the two
     *    params are joined as `?environment=prod&_conv_low_cache=1`).
     *
     * Kept to two return statements (detekt `ReturnCount` threshold) by
     * folding the precondition checks into a single early-return, then
     * returning the assembled URL.
     */
    private fun buildConfigUrl(): String? {
        val sdkKey = config.sdkKey
        val endpoint = config.api?.endpoint?.config ?: ConfigDefaults.DEFAULT_CONFIG_ENDPOINT
        val rejection = when {
            sdkKey == null ->
                "ApiManager.fetchConfig(): sdkKey is null; skipping fetch"
            !isSchemeAllowed(endpoint) ->
                "ApiManager.fetchConfig(): refusing non-https endpoint $endpoint"
            else -> null
        }
        if (rejection != null) {
            logger.warn(message = rejection, tag = TAG)
            return null
        }

        // Normalise trailing slash so "endpoint/config/sdkKey" never produces
        // "endpoint//config/sdkKey" or "endpointconfig/sdkKey".
        val base = endpoint.trimEnd('/')
        val query = buildConfigQuery()
        return "$base/$PATH_CONFIG_SEGMENT/$sdkKey$query"
    }

    /**
     * Assembles the query-string portion of the config URL per AC-1
     * (F-006 option a). Mirrors JS SDK `api-manager.ts:302-304` but
     * intentionally diverges by inserting an `&` separator between
     * `environment=...` and `_conv_low_cache=1` to produce valid HTTP
     * query strings — the JS SDK omits the separator (a known JS quirk
     * the backend tolerates).
     *
     * `config.environment` is always present in production traffic
     * because [com.convert.sdk.core.config.ConvertConfig.environment]
     * defaults to `"staging"` (Story 1.2). The conditional on
     * `environment` is therefore expressed defensively: if a future
     * change makes the field nullable, this builder still produces a
     * correct URL (empty query, or low-cache-only).
     *
     * Returns the empty string when neither parameter applies (only
     * possible when both `environment` is empty AND `cacheLevel` is not
     * `"low"`).
     */
    private fun buildConfigQuery(): String {
        val environment = config.environment
        val isLowCache = config.network?.cacheLevel == "low"
        if (environment.isEmpty() && !isLowCache) return ""

        val builder = StringBuilder("?")
        if (environment.isNotEmpty()) {
            builder.append("environment=").append(environment)
        }
        if (isLowCache) {
            // Insert `&` only when an earlier `key=value` is already in the
            // query — i.e. when `environment=` was just appended. The
            // detection uses `'='` so the literal `?` from the prefix is
            // never mistaken for an existing parameter.
            if (builder.contains('=')) builder.append('&')
            builder.append("_conv_low_cache=1")
        }
        return builder.toString()
    }

    private fun buildHeaders(): Map<String, String> {
        val secret = config.sdkKeySecret ?: return emptyMap()
        return mapOf(HEADER_AUTHORIZATION to secret)
    }

    private fun isSchemeAllowed(endpoint: String): Boolean {
        val isHttps = endpoint.startsWith("https://", ignoreCase = true)
        val isHttp = endpoint.startsWith("http://", ignoreCase = true)
        val isLoopback = if (isHttp) {
            val afterScheme = endpoint.removePrefix("http://").removePrefix("HTTP://")
            val hostEnd = afterScheme.indexOfAny(charArrayOf('/', ':'))
            val host = if (hostEnd == -1) afterScheme else afterScheme.substring(0, hostEnd)
            host in LOOPBACK_HOSTS
        } else {
            false
        }
        return isHttps || isLoopback
    }

    public companion object {
        private const val TAG: String = "ApiManager"
        private const val HEADER_AUTHORIZATION: String = "Authorization"
        private const val HEADER_CONTENT_TYPE: String = "Content-Type"
        private const val CONTENT_TYPE_JSON: String = "application/json"

        /**
         * Mandatory URL path segment between the configured base endpoint
         * and the `sdkKey`. Matches JS SDK `api-manager.ts:300-313` which
         * routes to `/config/${sdkKey}${query}`. Story 2.2 AC-1 requires
         * the literal `/config/` segment in the request URL.
         */
        private const val PATH_CONFIG_SEGMENT: String = "config"

        /**
         * HTTP 2xx success range — any status outside this range is treated
         * as a fetch failure per AC-3 (uniform 400/401/403/404/500+ handling).
         */
        private val HTTP_2XX_RANGE: IntRange = 200..299
        private const val MAX_BODY_LOG_CHARS: Int = 200
        private val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "[::1]")

        /**
         * Story 5.2 AC-2 exponential backoff delays: 10s, 20s, 40s. Explicit
         * list (rather than `10_000 shl retryCount`) for readability per
         * Dev Notes. [Source: architecture.md#Retry-strategy]
         */
        private val RETRY_DELAYS_MS: LongArray = longArrayOf(10_000L, 20_000L, 40_000L)

        /** Maximum number of foreground retries before persisting + giving up. */
        private const val MAX_FOREGROUND_RETRIES: Int = 3

        // Wire shape constants — matches JS SDK. Per-event keys live
        // here; the outer-payload keys (accountId / projectId / source /
        // enrichData / visitors / visitorId / segments / events) moved
        // to [TrackingPayloadBuilder] when Story 5.3 extracted the
        // payload-building logic so the background WorkManager worker
        // can produce byte-identical bodies.
        private const val KEY_EVENT_TYPE: String = "eventType"
        private const val KEY_DATA: String = "data"
        private const val KEY_EXPERIENCE_ID: String = "experienceId"
        private const val KEY_VARIATION_ID: String = "variationId"
        private const val KEY_GOAL_ID: String = "goalId"
        private const val KEY_GOAL_DATA: String = "goalData"
        private const val KEY_KEY: String = "key"
        private const val KEY_VALUE: String = "value"

        private const val EVENT_TYPE_BUCKETING: String = "bucketing"
        private const val EVENT_TYPE_CONVERSION: String = "conversion"

        private const val TEMPLATE_PROJECT_ID: String = "[project_id]"
    }
}
