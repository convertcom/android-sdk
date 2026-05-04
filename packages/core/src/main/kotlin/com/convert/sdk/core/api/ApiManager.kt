/*
 * Convert Android SDK — core/api
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConfigDefaults
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches project configuration from the Convert CDN.
 *
 * ### Story 2.2 scope
 *
 * Single responsibility: build a HTTPS GET request against the configured
 * CDN endpoint, parse a successful response into a [ConfigResponseData],
 * and return `null` on any failure. The ApiManager never throws; every
 * exceptional path logs a WARN or ERROR and returns `null` so that the
 * caller ([com.convert.sdk.android.ConvertSDK]) can fall back to a cached
 * config (Story 2.2 AC-6 — implemented in the SDK module's init flow).
 *
 * ### URL construction (AC-1)
 *
 * The request URL is built from:
 *   `{endpoint}/config/{sdkKey}[?environment={env}[&_conv_low_cache=1]]`
 *
 * where `endpoint` is `config.api.endpoint.config` falling back to
 * [ConfigDefaults.DEFAULT_CONFIG_ENDPOINT]. The literal `/config/` path
 * segment is mandatory (matches JS SDK `api-manager.ts:300-313` which
 * routes to `/config/${sdkKey}${query}`). Query-string assembly
 * (corrected re-implementation of JS SDK `api-manager.ts:302-304`):
 *  - Start with `?` if either `config.environment` is non-null or
 *    `config.network.cacheLevel == "low"`; otherwise empty string.
 *  - If `config.environment` is non-null, append `environment=${env}`.
 *  - If `config.network.cacheLevel == "low"`, append
 *    `${if (query.contains('=')) "&" else ""}_conv_low_cache=1`. The
 *    `_conv_low_cache=1` parameter bypasses the CDN cache (development
 *    only).
 *
 * Note: JS SDK `api-manager.ts:302-304` omits the `&` separator between
 * params when both are present; the Android SDK corrects this to produce
 * valid query strings. This deviation is intentional and documented in
 * Story 2.2 AC-1 (F-006 option a).
 *
 * `config.environment` defaults to `"staging"` (see
 * [com.convert.sdk.core.config.ConvertConfig]) so the `environment=...`
 * parameter is always present in production traffic; omitting it would
 * silently route to the CDN's default environment.
 *
 * ### HTTPS enforcement (AC-1, NFR7)
 *
 * Any URL whose scheme is not `https` is rejected with a WARN. This
 * prevents accidental plaintext credential leakage when an endpoint
 * override is misconfigured.
 *
 * ### Authorization (AC-1, AC-9)
 *
 * When `config.sdkKeySecret` is non-null, the ApiManager sets the
 * `Authorization` request header to the secret value verbatim — no
 * `Bearer` prefix, no base64 encoding. The backend performs a
 * timing-safe SHA256 comparison against the stored secret. The secret
 * NEVER appears in any log message, stack trace, or rendered
 * representation of the request; only the URL is logged on failure.
 *
 * ### Error handling (AC-3)
 *
 * Every failure mode is uniform from the caller's perspective:
 *  - Non-HTTPS URL → WARN, return null. No network call.
 *  - Missing `sdkKey` → WARN, return null. No network call.
 *  - Transport failure (statusCode 0) → WARN, return null.
 *  - Non-2xx status → WARN with `{status} {first 200 chars of body}`,
 *    return null.
 *  - 2xx with un-parseable body → ERROR with the literal prefix
 *    `ApiManager.fetchConfig(): failed to parse config response`,
 *    return null.
 *  - 2xx with valid JSON → return the parsed [ConfigResponseData].
 *
 * ### Forward compatibility
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
 * ### Visibility (Story 2.2)
 *
 * Declared `public` so that `:packages:sdk` — which lives in a separate
 * Gradle module and therefore a separate Kotlin `internal` visibility
 * scope — can instantiate this class from its Builder. Consumers of
 * the published `sdk-core` artifact should treat this as SDK-internal:
 * the instance is held privately by [com.convert.sdk.android.ConvertSDK]
 * and never re-exposed through the public API. (Same rationale as
 * [com.convert.sdk.core.data.DataManager] and
 * [com.convert.sdk.core.event.EventManager] — Story 2.1.)
 *
 * @property httpClient the transport port used to issue the GET request.
 * @property logger used for failure logging; all messages are tagged
 *   [TAG] so Logcat filtering works.
 * @property config the SDK's fully-assembled configuration. Read for
 *   `sdkKey`, `sdkKeySecret`, `api.endpoint.config`, and
 *   `network.cacheLevel`.
 * @property json the kotlinx.serialization [Json] instance used to parse
 *   responses. Shared with the SDK's [com.convert.sdk.android.adapter.FileConfigCache]
 *   so cache and fetch paths have identical parse behaviour.
 */
public open class ApiManager(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val config: ConvertConfig,
    private val json: Json,
) {

    /**
     * Mutable tracking-enabled flag pre-wired for Story 5.4's event-enqueue
     * bypass (AC-5). Initialised from `config.network?.tracking` with a
     * [ConfigDefaults.DEFAULT_TRACKING_ENABLED] fallback so callers always
     * see a non-null value.
     *
     * `AtomicBoolean` (over `Volatile var`) is chosen for two reasons:
     *  1. Story 5.4 will read this flag on every enqueue attempt from
     *     multiple coroutine dispatchers — atomic reads are cheaper than
     *     a `synchronized` guard and don't require a [Mutex].
     *  2. The JS SDK sets tracking directly on its ApiManager; mirroring
     *     that single-field semantic here keeps the cross-SDK parity story
     *     simple. A lock-guarded backing field would be heavier for zero
     *     additional safety on a scalar boolean.
     *
     * Note: this flag does NOT gate [fetchConfig] — config fetch is always
     * enabled because bucketing depends on loaded config (AC-5). Only the
     * outbound tracking-event path (Story 5.4) inspects this flag.
     */
    private val trackingEnabled: AtomicBoolean = AtomicBoolean(
        config.network?.tracking ?: ConfigDefaults.DEFAULT_TRACKING_ENABLED,
    )

    /**
     * Returns the current state of the tracking toggle.
     *
     * Declared `public` so :packages:sdk (separate Kotlin `internal` scope)
     * can query it from the ConvertSDK wiring path; consumers of the
     * published `sdk-core` artifact should treat this as SDK-internal.
     *
     * @return `true` when outbound tracking events should be enqueued,
     *   `false` when they should be suppressed (bucketing still runs).
     */
    public fun isTrackingEnabled(): Boolean = trackingEnabled.get()

    /**
     * Toggles outbound tracking on or off at runtime.
     *
     * Invoked by Story 5.4's `ConvertContext.setTracking(...)` (and,
     * eventually, a top-level SDK consent API). Atomic — a concurrent
     * [isTrackingEnabled] call will see either the old or the new value,
     * never a torn read.
     *
     * Declared `public` so :packages:sdk can call it; see
     * [isTrackingEnabled] for the cross-module visibility rationale.
     *
     * @param enabled `true` to resume enqueuing tracking events, `false`
     *   to suppress them while preserving bucketing determinism.
     */
    public fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled.set(enabled)
    }

    /**
     * Fetches the project's configuration from the CDN and returns it on
     * success.
     *
     * Dispatched onto [Dispatchers.IO] so the actual socket I/O happens on
     * a thread reserved for blocking work; callers invoke this from
     * `scope.launch { ... }` on the SDK scope (which itself runs on
     * `Dispatchers.Default`). The `withContext` switch is the
     * architecture-mandated pattern for HTTP calls (§Coroutine-Usage).
     *
     * @return the parsed [ConfigResponseData] on 2xx, or `null` on any
     *   failure (see class KDoc).
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    public suspend fun fetchConfig(): ConfigResponseData? = withContext(Dispatchers.IO) {
        val url = buildConfigUrl() ?: return@withContext null
        val headers = buildHeaders()

        val response = try {
            httpClient.get(url, headers)
        } catch (t: Throwable) {
            // Adapter is expected to return statusCode=0 rather than throw,
            // but we defend against any unexpected throw — NEVER include the
            // Authorization header in the log message (paranoid check).
            logger.warn(
                message = "ApiManager.fetchConfig(): network error fetching $url: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            return@withContext null
        }

        // Transport layer failure — OkHttpClientAdapter maps IOException to
        // statusCode = 0 with an empty body.
        if (response.statusCode == 0) {
            logger.warn(
                message = "ApiManager.fetchConfig(): network error (statusCode 0) fetching $url",
                tag = TAG,
            )
            return@withContext null
        }

        // Non-2xx response — log with truncated body, return null.
        if (response.statusCode !in HTTP_2XX_RANGE) {
            val bodySnippet = response.body.take(MAX_BODY_LOG_CHARS)
            logger.warn(
                message = "ApiManager.fetchConfig(): ${response.statusCode} $bodySnippet",
                tag = TAG,
            )
            return@withContext null
        }

        // 2xx — parse the JSON body. ignoreUnknownKeys lets new backend
        // fields pass through unharmed; explicitNulls=false keeps the body
        // tight when re-serialized for the cache.
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

    /**
     * Builds the request headers per AC-1 / AC-9. The Authorization header
     * is conditionally included when `sdkKeySecret` is non-null, and its
     * value is the secret VERBATIM (no base64, no Bearer prefix) — the
     * backend does a timing-safe SHA256 comparison.
     */
    private fun buildHeaders(): Map<String, String> {
        val secret = config.sdkKeySecret ?: return emptyMap()
        return mapOf(HEADER_AUTHORIZATION to secret)
    }

    /**
     * Accepts the endpoint URL scheme when:
     *  - it starts with `https://`, OR
     *  - it starts with `http://` AND points at a loopback address
     *    (`localhost`, `127.0.0.1`, or `[::1]`). The loopback carve-out
     *    exists so that test harnesses using MockWebServer over plain
     *    HTTP work without TLS setup; production CDN endpoints never
     *    resolve to loopback, so real-world security is unaffected.
     *
     * Collapsed to a single return expression to satisfy detekt's
     * `ReturnCount` ceiling.
     */
    private fun isSchemeAllowed(endpoint: String): Boolean {
        val isHttps = endpoint.startsWith("https://", ignoreCase = true)
        val isHttp = endpoint.startsWith("http://", ignoreCase = true)
        val isLoopback = if (isHttp) {
            // Extract host portion: everything between "http://" and the
            // next '/', ':', or end-of-string.
            val afterScheme = endpoint.removePrefix("http://").removePrefix("HTTP://")
            val hostEnd = afterScheme.indexOfAny(charArrayOf('/', ':'))
            val host = if (hostEnd == -1) afterScheme else afterScheme.substring(0, hostEnd)
            host in LOOPBACK_HOSTS
        } else {
            false
        }
        return isHttps || isLoopback
    }

    /**
     * Stub — Story 3.2 SDK-4 placeholder for the bucketing-event enqueue
     * that Story 5.1 will implement. Callers (primarily
     * [com.convert.sdk.android.ConvertContext.runExperience]) invoke this
     * on every non-sticky bucketing decision; the real body will build a
     * `viewExp` tracking payload and hand it to the outbound event queue.
     *
     * Declared `open` so tests in the `:packages:sdk` module can override
     * it with a recording spy (see `ConvertContextRunExperienceTest`
     * `RecordingApiManager`). The stub body is intentionally empty — the
     * "tracking disabled" and "SDK not ready" branches are gated upstream
     * by the caller, so a no-op here is the correct default until
     * Story 5.1 lands.
     *
     * @param visitorId the visitor whose bucketing is being reported.
     * @param experienceId the experience id (not key — tracking payload
     *   references the stable id).
     * @param variationId the id of the selected variation.
     */
    public open fun enqueueBucketingEvent(
        visitorId: String,
        experienceId: String,
        variationId: String,
    ) {
        // Intentional no-op — Story 5.1 implements.
        logger.debug(
            message = "ApiManager.enqueueBucketingEvent() stub — " +
                "visitorId=$visitorId experienceId=$experienceId variationId=$variationId",
            tag = TAG,
        )
    }

    /**
     * Stub — Story 4.2 SDK-1 placeholder for the conversion-event enqueue
     * that Story 5.1 will implement. Called EXACTLY ONCE per
     * [com.convert.sdk.android.ConvertContext.trackConversion] invocation
     * (F-008 / F-017 remediation): a single `ConversionEvent` carries
     * `goalId` plus an optional `goalData` list when revenue /
     * custom-dimension metadata is present. Matches the OpenAPI-generated
     * [com.convert.sdk.core.model.generated.ConversionEvent] wire format
     * and the JS SDK type schema at
     * `javascript-sdk/packages/types/src/config/types.gen.ts:2749-2757`
     * which declares only two event types — `'bucketing'` and
     * `'conversion'`. There is no `'tr'` event type; revenue rides
     * inside the conversion event's `goalData`.
     *
     * Declared `open` so tests in the `:packages:sdk` module can override
     * it with a recording spy — same pattern as [enqueueBucketingEvent].
     * The stub body is intentionally empty; Story 5.1 will land the real
     * payload construction + outbound queue write.
     *
     * @param visitorId the visitor whose conversion is being reported.
     * @param goalId the stable id of the conversion goal (not the goal
     *   key — tracking payload references the id per
     *   [com.convert.sdk.core.model.generated.ConversionEvent.goalId]).
     * @param goalData optional list of payload entries
     *   ([com.convert.sdk.core.model.GoalData]) — `null` for a bare
     *   conversion hit, non-null when the caller supplied revenue or
     *   custom-dimension fields.
     */
    public open fun enqueueConversionEvent(
        visitorId: String,
        goalId: String,
        goalData: List<com.convert.sdk.core.model.GoalData>?,
    ) {
        // Intentional no-op — Story 5.1 implements.
        logger.debug(
            message = "ApiManager.enqueueConversionEvent() stub — " +
                "visitorId=$visitorId goalId=$goalId goalDataSize=${goalData?.size ?: 0}",
            tag = TAG,
        )
    }

    public companion object {
        private const val TAG: String = "ApiManager"
        private const val HEADER_AUTHORIZATION: String = "Authorization"

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

        /**
         * Maximum number of characters of the HTTP response body to include
         * in a non-2xx WARN log message. Keeps logs short and avoids
         * dumping potentially sensitive payloads.
         */
        private const val MAX_BODY_LOG_CHARS: Int = 200

        /**
         * Loopback hostnames exempt from the HTTPS-only restriction so that
         * local test harnesses (MockWebServer) work over plain HTTP without
         * TLS setup.
         */
        private val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "[::1]")
    }
}
