/*
 * Convert Android SDK — sdk/adapter tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import com.convert.sdk.core.port.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Story 2.1 SDK-3 / AC-7: OkHttpClientAdapter runs real HTTP requests via
 * MockWebServer.
 *
 * These are JUnit 4 tests because MockWebServer's shutdown hooks play more
 * nicely with the JUnit 4 runner (and this module already mixes Jupiter
 * and Vintage engines for Robolectric support).
 */
internal class OkHttpClientAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var adapter: OkHttpClientAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        adapter = OkHttpClientAdapter(okHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get returns 200 and body from remote`() = runTest {
        server.enqueue(MockResponse().setBody("{\"ok\":true}").setResponseCode(200))

        val response = adapter.get(server.url("/config").toString())

        assertEquals(200, response.statusCode)
        assertEquals("{\"ok\":true}", response.body)
    }

    @Test
    fun `get forwards custom headers`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        adapter.get(
            url = server.url("/x").toString(),
            headers = mapOf("Authorization" to "Bearer abc", "X-Trace" to "t-1"),
        )

        val recorded = server.takeRequest()
        assertEquals("Bearer abc", recorded.getHeader("Authorization"))
        assertEquals("t-1", recorded.getHeader("X-Trace"))
    }

    @Test
    fun `post sends body and content-type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ack"))

        val response = adapter.post(
            url = server.url("/events").toString(),
            body = "{\"event\":\"view\"}",
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("{\"event\":\"view\"}", recorded.body.readUtf8())
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals(200, response.statusCode)
        assertEquals("ack", response.body)
    }

    @Test
    fun `post explicit content-type header is respected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        adapter.post(
            url = server.url("/e").toString(),
            body = "raw-text",
            headers = mapOf("Content-Type" to "text/plain"),
        )

        val recorded = server.takeRequest()
        // Explicit header overrides the default.
        val ct = recorded.getHeader("Content-Type")
        assertNotNull(ct)
        assertTrue("Content-Type should contain text/plain, got: $ct", ct!!.contains("text/plain"))
    }

    @Test
    fun `get on unreachable url returns 0 status without throwing`() = runTest {
        // Shut the server down first — the next request will fail with
        // ConnectException; the adapter must log and swallow.
        server.shutdown()

        val response = adapter.get("http://127.0.0.1:1/nowhere")

        assertEquals(0, response.statusCode)
        assertEquals("", response.body)
        assertTrue(response.headers.isEmpty())
    }

    @Test
    fun `response headers are populated in the HttpResponse`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("hi")
                .setHeader("X-Response-Id", "abc-123"),
        )

        val response: HttpClient.HttpResponse = adapter.get(server.url("/h").toString())

        // Headers are preserved — case-insensitive lookup (OkHttp lower-cases
        // when converting, but the port contract says "lower-cased by convention").
        val found = response.headers.entries.firstOrNull { it.key.equals("X-Response-Id", ignoreCase = true) }
        assertNotNull(found)
        assertEquals("abc-123", found?.value)
    }

    @Test
    fun `5xx responses are returned as-is not swallowed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("try later"))

        val response = adapter.get(server.url("/down").toString())

        // Even 5xx is a "response" — the adapter must not coalesce to 0.
        assertEquals(503, response.statusCode)
        assertEquals("try later", response.body)
    }

    @Test
    fun `post body defaults to json content-type when no headers supplied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        adapter.post(
            url = server.url("/p").toString(),
            body = "{}",
            headers = emptyMap(),
        )

        val recorded = server.takeRequest()
        val ct = recorded.getHeader("Content-Type") ?: ""
        assertTrue("expected json content-type, got: $ct", ct.contains("application/json"))
    }

    @Test
    fun `cancellation cancels in-flight call`() {
        // Story 2.2 AC-10 completeness: verify OkHttpClientAdapter's
        // suspendCancellableCoroutine invokeOnCancellation handler actually
        // cancels the underlying OkHttp Call.
        //
        // Strategy: enqueue a MockResponse with a socket-level pause so the
        // request hangs; launch the GET inside a coroutine; cancel the
        // coroutine; assert that the resulting call's isCanceled is true.
        // We can't easily assert from inside the test because
        // suspendCancellableCoroutine's cleanup happens on the cancelled
        // coroutine's thread. Instead, we rely on OkHttp's dispatcher's
        // runningCallsCount dropping back to 0 after cancellation.
        server.enqueue(
            MockResponse()
                .setBody("late")
                .setBodyDelay(30, TimeUnit.SECONDS)
                .setResponseCode(200),
        )

        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            // This will suspend because MockWebServer delays the body by 30s;
            // the coroutine cancellation below should cut it short.
            adapter.get(server.url("/slow").toString())
        }
        // Wait for the call to actually be in-flight.
        val deadline = System.currentTimeMillis() + 2_000
        while (okHttpClient.dispatcher.runningCallsCount() == 0 &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(1, okHttpClient.dispatcher.runningCallsCount())

        // Cancel the coroutine; invokeOnCancellation should cancel the Call.
        job.cancel()

        // Wait for the dispatcher to drain.
        val drainDeadline = System.currentTimeMillis() + 2_000
        while (okHttpClient.dispatcher.runningCallsCount() > 0 &&
            System.currentTimeMillis() < drainDeadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(
            "cancelled call must be removed from dispatcher",
            0,
            okHttpClient.dispatcher.runningCallsCount(),
        )
    }
}
