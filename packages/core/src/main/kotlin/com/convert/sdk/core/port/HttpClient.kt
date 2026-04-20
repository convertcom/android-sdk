/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Port abstraction for HTTP transport used by the SDK.
 *
 * Implementations live in [`packages/sdk`] as adapters (e.g., an OkHttp-backed
 * adapter in Story 2.2). The core module depends only on this interface so that
 * it remains free of Android or third-party networking dependencies.
 */
internal interface HttpClient {

    /**
     * Performs an HTTP GET request.
     *
     * @param url absolute URL to request.
     * @param headers optional request headers; defaults to an empty map.
     * @return the [HttpResponse] produced by the adapter.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Performs an HTTP POST request with a string body.
     *
     * @param url absolute URL to request.
     * @param body the serialized request body (usually JSON).
     * @param headers optional request headers; defaults to an empty map.
     * @return the [HttpResponse] produced by the adapter.
     */
    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    /**
     * Transport-level HTTP response. This is intentionally a pure-data carrier
     * and is NOT `@Serializable` — adapters construct it from their native
     * response types.
     *
     * @property statusCode the HTTP status code returned by the server.
     * @property body the raw response body as a string.
     * @property headers the response headers keyed by name.
     */
    data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String>,
    )
}
