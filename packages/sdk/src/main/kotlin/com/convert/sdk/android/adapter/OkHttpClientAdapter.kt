/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import com.convert.sdk.android.CONVERT_AGENT_USER_AGENT
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpClient] adapter backed by OkHttp.
 *
 * Bridges OkHttp's async `Call.enqueue` callback API to Kotlin coroutines
 * via [suspendCancellableCoroutine]. On coroutine cancellation the
 * outstanding [Call] is cancelled (Gotcha 2), preventing socket leaks.
 *
 * Failure semantics (AC-7 and architecture §Error Recovery):
 *  - A `4xx` / `5xx` response is returned as-is with the real status code
 *    and body — the adapter does not swallow HTTP errors.
 *  - An IO/protocol failure (no response ever received — DNS failure,
 *    connection refused, SSL handshake failure, timeout) is logged at
 *    WARN and mapped to an [HttpClient.HttpResponse] with `statusCode = 0`,
 *    empty `body`, empty `headers`. Consumer code branches on
 *    `statusCode == 0` to distinguish "no response" from any other outcome.
 *
 * ### Default content-type for POST
 *
 * If the caller does not include a `Content-Type` header, the adapter
 * sets `application/json; charset=utf-8` because every endpoint the SDK
 * currently posts to accepts JSON. Callers who need a different
 * content-type pass it through [headers] and the adapter defers to their
 * value.
 *
 * ### User-Agent invariant
 *
 * Every request carries `User-Agent: ConvertAgent/1.0`
 * ([CONVERT_AGENT_USER_AGENT]). The header is set AFTER the caller-headers
 * `apply { }` block, so `Request.Builder.header()`'s case-insensitive
 * replace semantics make it non-overridable by any caller-supplied
 * `User-Agent` — the announcement is an SDK invariant (mirrors the PHP
 * SDK's `ApiManager`). This exempts requests from the metrics endpoint's
 * bot filter; see [CONVERT_AGENT_USER_AGENT] for the rationale.
 *
 * ### Constructing the adapter
 *
 * Consumers of the SDK should not build this directly — the
 * [com.convert.sdk.android.ConvertSDK.Builder] constructs one shared
 * [OkHttpClient] per SDK instance and wires it into the adapter so the
 * connection pool is reused across every outbound request. See
 * [DEFAULT_CALL_TIMEOUT_SECONDS], [DEFAULT_CONNECT_TIMEOUT_SECONDS],
 * [DEFAULT_READ_TIMEOUT_SECONDS].
 *
 * @property okHttpClient shared OkHttp client instance. Expensive to
 *   construct; the SDK holds exactly one per [com.convert.sdk.android.ConvertSDK].
 * @property logger used to log IO failures at WARN. [Logger.NoOp] by
 *   default so the adapter is testable without a logger.
 */
internal class OkHttpClientAdapter(
    private val okHttpClient: OkHttpClient,
    private val logger: Logger = Logger.NoOp,
) : HttpClient {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpClient.HttpResponse {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            // SDK invariant: set after caller headers so it cannot be overridden.
            .header("User-Agent", CONVERT_AGENT_USER_AGENT)
            .build()
        return execute(request)
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpClient.HttpResponse {
        // Determine content-type: explicit header (any casing) wins; otherwise json default.
        val explicitContentType = headers.entries.firstOrNull {
            it.key.equals(HEADER_CONTENT_TYPE, ignoreCase = true)
        }?.value
        val mediaType = (explicitContentType ?: DEFAULT_CONTENT_TYPE).toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply {
                headers.forEach { (name, value) ->
                    // Skip the content-type header we just handled — setting
                    // it here would duplicate it alongside the RequestBody's.
                    if (!name.equals(HEADER_CONTENT_TYPE, ignoreCase = true)) {
                        header(name, value)
                    }
                }
            }
            // SDK invariant: set after caller headers so it cannot be overridden.
            .header("User-Agent", CONVERT_AGENT_USER_AGENT)
            .build()
        return execute(request)
    }

    /**
     * Dispatches [request] via OkHttp and bridges the callback to a
     * cancellable coroutine.
     */
    private suspend fun execute(request: Request): HttpClient.HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = okHttpClient.newCall(request)
            continuation.invokeOnCancellation {
                // Architecture: on cancellation, cancel the in-flight HTTP call
                // so the connection isn't wasted (Gotcha 2).
                call.cancel()
            }
            call.enqueue(object : Callback {
                // TD-3 / AC-3.1: catch Throwable (not just Exception) so that
                // Error subclasses (e.g. OutOfMemoryError mid-body-read) also
                // resume the continuation instead of leaving it hanging forever.
                // OkHttp does NOT call onFailure when the body read throws, so
                // this catch is the only recovery path. @Suppress follows the
                // same pattern as FileEventQueue.acquireFileLockAndRun.
                @Suppress("TooGenericExceptionCaught")
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bodyText = response.body?.string().orEmpty()
                        val headerMap: Map<String, String> = response.headers.toMap()
                        continuation.resume(
                            HttpClient.HttpResponse(
                                statusCode = response.code,
                                body = bodyText,
                                headers = headerMap,
                            ),
                        )
                    } catch (t: Throwable) {
                        // AC-3.1: body?.string() can throw (e.g. connection reset
                        // mid-body). OkHttp does NOT call onFailure in that case,
                        // so the catch here is the only way to resume the
                        // continuation. Guard with isActive so a cancellation
                        // that raced this path does not throw "already resumed".
                        if (continuation.isActive) continuation.resumeWithException(t)
                    } finally {
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    logger.warn(
                        message = "HTTP ${request.method} ${request.url} failed: ${e.message}",
                        throwable = e,
                        tag = "OkHttpClientAdapter",
                    )
                    continuation.resume(
                        HttpClient.HttpResponse(
                            statusCode = 0,
                            body = "",
                            headers = emptyMap(),
                        ),
                    )
                }
            })
        }

    internal companion object {
        /**
         * Total per-call timeout in seconds (DNS + connect + request +
         * response body). 30s matches the implicit overall budget of the
         * JS SDK's `fetch` calls (no explicit timeout there) so a slow
         * config endpoint observed identically across SDKs.
         */
        const val DEFAULT_CALL_TIMEOUT_SECONDS: Long = 30

        /**
         * TCP connect timeout in seconds. 10s follows standard Android
         * networking guidance for mobile connections.
         * [Source: Android network best practices —
         *   https://developer.android.com/training/basics/network-ops/]
         */
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS: Long = 10

        /**
         * Socket read timeout in seconds. 15s gives the config CDN a
         * generous window past OkHttp's 10s default to absorb
         * intermittent radio stalls without exceeding the 30s call
         * budget.
         */
        const val DEFAULT_READ_TIMEOUT_SECONDS: Long = 15

        private const val HEADER_CONTENT_TYPE: String = "Content-Type"
        private const val DEFAULT_CONTENT_TYPE: String = "application/json; charset=utf-8"

        /**
         * Factory for the shared [OkHttpClient] the Builder constructs once
         * and passes into every adapter. Keeps the timeout policy in one place.
         */
        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Converts an `okhttp3.Headers` object to a `Map<String, String>`.
 *
 * When multiple values share a header name, the last value wins — this
 * is a simplification for the minimal Story 2.1 surface. Callers that
 * need multi-value headers (e.g. `Set-Cookie`) must read them from the
 * `Response` directly in a future story.
 */
private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    this@toMap.forEach { (name, value) -> put(name, value) }
}
