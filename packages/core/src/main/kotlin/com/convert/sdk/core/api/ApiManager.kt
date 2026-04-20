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
 *   `{endpoint}/{sdkKey}[?_conv_low_cache=1]`
 *
 * where `endpoint` is `config.api.endpoint.config` falling back to
 * [ConfigDefaults.DEFAULT_CONFIG_ENDPOINT]. The `_conv_low_cache=1` query
 * parameter is appended only when `config.network.cacheLevel == "low"` —
 * this bypasses the CDN cache (development only), matching JS SDK
 * `api-manager.ts:302-304`.
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
 * `ignoreUnknownKeys = true` and `explicitNulls = false` so that new
 * backend fields don't break old SDK versions (NFR12).
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
internal class ApiManager(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val config: ConvertConfig,
    private val json: Json,
) {

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
    suspend fun fetchConfig(): ConfigResponseData? = withContext(Dispatchers.IO) {
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
     *  - The resolved endpoint is not HTTPS — NFR7 forbids plaintext.
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
            !endpoint.startsWith("https://", ignoreCase = true) ->
                "ApiManager.fetchConfig(): refusing non-https endpoint $endpoint"
            else -> null
        }
        if (rejection != null) {
            logger.warn(message = rejection, tag = TAG)
            return null
        }

        // Normalise trailing slash so "endpoint/sdkKey" never produces
        // "endpoint//sdkKey" or "endpointsdkKey". Append the cache-bypass
        // query parameter for `cacheLevel == "low"` (development only).
        val base = endpoint.trimEnd('/')
        val withKey = "$base/$sdkKey"
        return if (config.network?.cacheLevel == "low") {
            "$withKey?_conv_low_cache=1"
        } else {
            withKey
        }
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

    companion object {
        private const val TAG: String = "ApiManager"
        private const val HEADER_AUTHORIZATION: String = "Authorization"

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
    }
}
