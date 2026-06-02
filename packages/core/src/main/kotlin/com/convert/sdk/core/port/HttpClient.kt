/*
 * Convert Android SDK — core/port
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Transport abstraction used by the core module to issue HTTP requests.
 *
 * The core module does not depend on any specific HTTP stack; a concrete
 * adapter (see `OkHttpClientAdapter` in the SDK module, Story 2.1) wires
 * the platform HTTP library into this port.
 *
 * Implementations must be safe to call from any coroutine context. Calls
 * perform real I/O — they should be dispatched on [kotlinx.coroutines.Dispatchers.IO]
 * by the caller's coroutine scope.
 *
 * Declared `public` so that `:packages:sdk` adapters — which live in a
 * separate Gradle module — can implement this interface. Application
 * code should not implement this port directly (Story 2.1).
 */
public interface HttpClient {

    /**
     * Issues an HTTP GET request against the given URL.
     *
     * @param url fully qualified target URL.
     * @param headers optional request headers; callers should pass an empty map
     *   if no additional headers are needed.
     * @return the response wrapped in [HttpResponse].
     */
    public suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Issues an HTTP POST request against the given URL with a string body.
     *
     * @param url fully qualified target URL.
     * @param body serialized request body (typically JSON).
     * @param headers optional request headers; callers should pass an empty map
     *   if no additional headers are needed.
     * @return the response wrapped in [HttpResponse].
     */
    public suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    /**
     * Minimal HTTP response envelope used by the core module.
     *
     * This type is intentionally not `@Serializable`: it is a transport
     * primitive, not a payload. Callers deserialize [body] themselves using
     * the appropriate kotlinx.serialization parser.
     *
     * @property statusCode HTTP status code returned by the server.
     * @property body response body as a UTF-8 string; empty string if no body.
     * @property headers response headers, lower-cased by convention.
     */
    public data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String>,
    )
}
