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
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.generated.ConfigResponseData
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
 * via [buildJsonObject] at flush time. The generated
 * [com.convert.sdk.core.model.generated.VisitorTrackingEvents] type is
 * still used by [com.convert.sdk.core.model.Visitor] for round-trip
 * reads (Story 5.1 SDK-1).
 *
 * #### enrichData semantics
 *
 * The JS SDK sets `enrichData = !config.dataStore` — true when the SDK
 * does not keep an in-memory store of loaded config (so the backend
 * enriches at write time). For the Android SDK, `config.data != null`
 * means we have the loaded CDN config in memory and can send
 * fully-formed events, so `enrichData = false`. When `config.data` is
 * null we shouldn't be able to enqueue anyway (projectId null → flush
 * is skipped), but the field is emitted for wire parity.
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
 * queue under the lock so enqueue order is preserved (Story 5.2 picks
 * up from here with retry + disk persistence).
 *
 * #### Timer-loop lifecycle
 *
 * If a [scope] is supplied at construction, [init] launches a timer
 * coroutine that calls [flush] every `releaseInterval`. If no scope is
 * supplied (pure-JVM core tests) the timer is disabled; tests drive
 * [flush] via [flushForTest]. The timer job is captured in
 * [timerJob]; a second [init] call is a no-op (idempotent — AC-9 /
 * Gotcha 3 in the story).
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
) {

    /**
     * Internal envelope around one queued event: the visitor it belongs
     * to, the visitor's segment snapshot at enqueue time, and the
     * already-built event JSON (eventType + data) ready to drop into the
     * outbound payload. Keeping the event pre-serialised lets [flush]
     * avoid any per-event serialisation work under the lock.
     *
     * @property visitorId the visitor id for grouping at flush time.
     * @property segments segment snapshot to attach to this visitor's
     *   entry in the outbound payload. Merged segments produced by
     *   [com.convert.sdk.core.model.VisitorContext.getMergedSegments];
     *   may be empty.
     * @property event pre-built JSON for this event (one of
     *   `{eventType: "bucketing", data: {...}}` or
     *   `{eventType: "conversion", data: {...}}`).
     */
    internal data class VisitorEvent(
        val visitorId: String,
        val segments: Map<String, JsonElement>,
        val event: JsonObject,
    )

    private val trackingEnabled: AtomicBoolean = AtomicBoolean(
        config.network?.tracking ?: ConfigDefaults.DEFAULT_TRACKING_ENABLED,
    )

    /**
     * Guards [eventQueue]. Every mutation (enqueue, snapshot, clear,
     * requeue) goes through `synchronized(queueLock)`. JVM monitor lock
     * is chosen over `kotlinx.coroutines.Mutex` because the non-suspend
     * public API ([enqueueBucketingEvent], [enqueueConversionEvent])
     * cannot `withLock { ... }`. Work done inside the lock is always
     * O(snapshot size) and NEVER includes HTTP I/O (see [flush]'s
     * snapshot-and-release pattern — lock is released BEFORE the POST).
     */
    private val queueLock: Any = Any()
    private val eventQueue: MutableList<VisitorEvent> = mutableListOf()

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

    public fun isTrackingEnabled(): Boolean = trackingEnabled.get()

    public fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled.set(enabled)
    }

    /**
     * Enqueues a bucketing event for a visitor. Called by
     * [com.convert.sdk.android.ConvertContext.runExperience] after a
     * non-sticky bucketing decision. When the queue reaches [batchSize]
     * the flush is triggered on [scope]; if no scope was supplied the
     * caller (or the periodic timer via [flushForTest] in tests) drives
     * the flush explicitly.
     *
     * The bucketing event's wire shape:
     *
     * ```
     * { "eventType": "bucketing",
     *   "data": { "experienceId": "...", "variationId": "..." } }
     * ```
     *
     * Declared `open` so tests in `:packages:sdk` can override with a
     * recording spy (unchanged from Story 3.2 SDK-4).
     *
     * @param visitorId the visitor whose bucketing is being reported.
     * @param experienceId the stable experience id.
     * @param variationId the selected variation id.
     * @param segments merged default + custom segments (Story 4.4); may
     *   be empty.
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
        val data = buildJsonObject {
            put(KEY_EXPERIENCE_ID, experienceId)
            put(KEY_VARIATION_ID, variationId)
        }
        val event = buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_BUCKETING)
            put(KEY_DATA, data)
        }
        enqueueInternal(VisitorEvent(visitorId, segments, event))
    }

    /**
     * Enqueues a conversion event for a visitor. Called by
     * [com.convert.sdk.android.ConvertContext.trackConversion] twice:
     * once with `goalData = null` for the bare hit, and (when present)
     * once with the full goalData list for the transaction payload.
     *
     * Wire shape:
     *
     * ```
     * { "eventType": "conversion",
     *   "data": { "goalId": "...", "goalData": [...]? } }
     * ```
     *
     * [goalData] is serialized entry-by-entry — each [GoalData.value]
     * is a [JsonElement] so numbers stay numeric, strings stay
     * stringly-typed, and the JS SDK's union of `number | string |
     * Array<string>` survives the wire round-trip. When `goalData` is
     * null or empty the field is omitted (bare conversion hit).
     *
     * @param visitorId the visitor whose conversion is being reported.
     * @param goalId the stable goal id.
     * @param goalData optional transaction-payload entries; null or
     *   empty → bare conversion.
     * @param segments merged default + custom segments (Story 4.4).
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
        val data = buildConversionData(goalId, goalData)
        val event = buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_CONVERSION)
            put(KEY_DATA, data)
        }
        enqueueInternal(VisitorEvent(visitorId, segments, event))
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
            eventQueue += event
            shouldFlush = eventQueue.size >= batchSize
        }
        if (shouldFlush) {
            scope?.launch { flush() }
        }
    }

    /**
     * Posts the current queue contents to the tracking endpoint.
     *
     * Snapshot-and-release sequence (AC-9):
     *  1. Under [queueLock], copy + drain [eventQueue] into a local
     *     snapshot. Release the lock.
     *  2. Build the JSON payload from the snapshot (one Visitor entry
     *     per unique visitorId).
     *  3. POST to `{trackEndpoint}/track/{sdkKey}` on [ioDispatcher].
     *  4. On HTTP 2xx: fire [SystemEvents.API_QUEUE_RELEASED] with
     *     `{reason, result, visitors}` matching JS SDK `api-manager.ts:232-237`
     *     and drop the snapshot.
     *  5. On non-2xx or thrown exception: re-prepend the snapshot to
     *     the queue under the lock so the next flush retries. No event
     *     fires on failure (Story 5.2 will add retry semantics).
     *
     * The HTTP call runs OUTSIDE the lock — holding a monitor across a
     * network call would starve concurrent enqueues.
     *
     * Skipped with WARN when `sdkKey` or projectId is unresolvable —
     * we must not POST to a partial URL.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    internal suspend fun flush() {
        val sdkKey = config.sdkKey
        val projectId = config.data?.project?.id
        if (sdkKey.isNullOrEmpty()) {
            logger.warn("ApiManager.flush(): sdkKey is null, skipping flush", tag = TAG)
            return
        }
        if (projectId.isNullOrEmpty()) {
            logger.warn("ApiManager.flush(): projectId is null, skipping flush", tag = TAG)
            return
        }

        val snapshot: List<VisitorEvent> = synchronized(queueLock) {
            if (eventQueue.isEmpty()) return
            val copy = eventQueue.toList()
            eventQueue.clear()
            copy
        }

        val url = buildTrackUrl(sdkKey, projectId)
        val payload = buildPayload(snapshot)

        val response = try {
            withContext(ioDispatcher) {
                httpClient.post(url, payload, mapOf(HEADER_CONTENT_TYPE to CONTENT_TYPE_JSON))
            }
        } catch (t: Throwable) {
            logger.warn(
                message = "ApiManager.flush(): network error: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            requeueFront(snapshot)
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
            requeueFront(snapshot)
        }
    }

    /**
     * Test seam — drives [flush] synchronously without requiring a
     * scope. Pure-JVM core tests call this to exercise the flush path
     * deterministically (no timer loop, no suspense on
     * `scope.launch`).
     */
    internal suspend fun flushForTest() {
        flush()
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
            eventQueue.addAll(0, snapshot)
        }
    }

    private fun buildTrackUrl(sdkKey: String, projectId: String): String {
        val template = config.api?.endpoint?.track ?: ConfigDefaults.DEFAULT_TRACK_ENDPOINT
        val withProject = template.replace(TEMPLATE_PROJECT_ID, projectId)
        val normalised = withProject.trimEnd('/')
        return "$normalised/track/$sdkKey"
    }

    private fun buildPayload(snapshot: List<VisitorEvent>): String {
        val grouped: Map<String, List<VisitorEvent>> = snapshot.groupBy { it.visitorId }
        val visitors = buildJsonArray {
            grouped.forEach { (visitorId, events) ->
                add(buildVisitorEntry(visitorId, events))
            }
        }
        val payload = buildJsonObject {
            put(KEY_ACCOUNT_ID, config.data?.accountId ?: "")
            put(KEY_PROJECT_ID, config.data?.project?.id ?: "")
            put(KEY_ENRICH_DATA, config.data == null)
            config.network?.source?.let { put(KEY_SOURCE, it) }
            put(KEY_VISITORS, visitors)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
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

    private fun buildVisitorEntry(visitorId: String, events: List<VisitorEvent>): JsonObject {
        // Segments: the JS SDK's VisitorsQueue.push replaces the
        // existing visitor's segments on subsequent pushes — the most
        // recent snapshot wins. We mirror that by taking the last
        // event's segments.
        val lastSegments = events.last().segments
        val eventsArray = buildJsonArray {
            events.forEach { add(it.event) }
        }
        val segmentsObj = if (lastSegments.isNotEmpty()) {
            buildJsonObject { lastSegments.forEach { (k, v) -> put(k, v) } }
        } else {
            null
        }
        return buildJsonObject {
            put(KEY_VISITOR_ID, visitorId)
            segmentsObj?.let { put(KEY_SEGMENTS, it) }
            put(KEY_EVENTS, eventsArray)
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

        // Wire shape constants — matches JS SDK
        // javascript-sdk/packages/types/src/config/types.gen.ts:2738.
        private const val KEY_EVENT_TYPE: String = "eventType"
        private const val KEY_DATA: String = "data"
        private const val KEY_EXPERIENCE_ID: String = "experienceId"
        private const val KEY_VARIATION_ID: String = "variationId"
        private const val KEY_GOAL_ID: String = "goalId"
        private const val KEY_GOAL_DATA: String = "goalData"
        private const val KEY_KEY: String = "key"
        private const val KEY_VALUE: String = "value"
        private const val KEY_ACCOUNT_ID: String = "accountId"
        private const val KEY_PROJECT_ID: String = "projectId"
        private const val KEY_ENRICH_DATA: String = "enrichData"
        private const val KEY_SOURCE: String = "source"
        private const val KEY_VISITORS: String = "visitors"
        private const val KEY_VISITOR_ID: String = "visitorId"
        private const val KEY_SEGMENTS: String = "segments"
        private const val KEY_EVENTS: String = "events"

        private const val EVENT_TYPE_BUCKETING: String = "bucketing"
        private const val EVENT_TYPE_CONVERSION: String = "conversion"

        private const val TEMPLATE_PROJECT_ID: String = "[project_id]"
    }
}
