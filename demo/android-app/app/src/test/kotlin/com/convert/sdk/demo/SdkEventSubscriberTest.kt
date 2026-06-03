/*
 * Convert Android SDK Demo App — buildEventSubscriber + applicationScope tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import com.convert.sdk.demo.viewmodel.EventSubscriber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.ContinuationInterceptor

/**
 * Story 7.1 AC-3 (F-166) — verifies the demo's background-dispatcher
 * SDK-init contract:
 *
 *  - The application scope uses [kotlinx.coroutines.Dispatchers.Default]
 *    (never `Main`) so SDK build/await work cannot block the UI thread.
 *  - [buildEventSubscriber] defers `sdk.on(...)` registration until the
 *    Deferred completes; subscribing before the SDK is ready does NOT
 *    block.
 *  - Cancel-before-register is race-free: closing the outer token
 *    before the Deferred completes results in zero net registrations
 *    on the underlying [SdkEventSource].
 *
 * Tests run on [UnconfinedTestDispatcher] so each `launch { … }` runs
 * eagerly; the tests advance time explicitly with [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkEventSubscriberTest {

    @Test
    fun `applicationScope uses Default dispatcher`() {
        val scope = newApplicationScope()
        val interceptor = scope.coroutineContext[ContinuationInterceptor]
        val description = interceptor.toString()
        assertTrue(
            description.contains("Default"),
            "applicationScope must run on Dispatchers.Default — got: $description",
        )
    }

    @Test
    fun `subscribe before deferred registers exactly once when deferred completes`() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSdkEventSource()
        val deferred = CompletableDeferred<SdkEventSource>()
        val scope = CoroutineScope(coroutineContext)

        val subscriber: EventSubscriber = buildEventSubscriber(scope, deferred)

        // Subscribe BEFORE the deferred is completed — the registration
        // coroutine is launched but suspends on deferred.await().
        val token = subscriber.subscribe("ready") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(
            emptyList<String>(),
            source.onCalls,
            "no on() should fire before the deferred completes",
        )

        // Complete the deferred — coroutine resumes, registers exactly
        // one subscription on the source.
        deferred.complete(source)
        advanceUntilIdle()
        assertEquals(
            listOf("ready"),
            source.onCalls,
            "exactly one on() registration after deferred completes",
        )
        assertEquals(
            emptyList<String>(),
            source.offCalls,
            "no off() — token still alive",
        )

        // Cleanup so the test does not leak the subscription.
        token.close()
    }

    @Test
    fun `cancel before register results in zero net registrations`() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSdkEventSource()
        val deferred = CompletableDeferred<SdkEventSource>()
        val scope = CoroutineScope(coroutineContext)

        val subscriber: EventSubscriber = buildEventSubscriber(scope, deferred)

        // Subscribe, then immediately close the token — BEFORE the
        // deferred completes. The registration coroutine is still
        // suspended on deferred.await().
        val token = subscriber.subscribe("ready") { /* no-op */ }
        token.close()

        // Now complete the deferred. The post-await coroutine should
        // register, lose the CAS race against the DISPOSED sentinel,
        // and dispose its own subscription immediately.
        deferred.complete(source)
        advanceUntilIdle()

        assertEquals(
            source.onCalls.size,
            source.offCalls.size,
            "net registrations must be zero — on count must equal off count",
        )
    }

    /**
     * In-memory [SdkEventSource] for assertions. Each `on()` records
     * the event and returns an [AutoCloseable] that records `off()`.
     */
    private class FakeSdkEventSource : SdkEventSource {
        val onCalls: MutableList<String> = mutableListOf()
        val offCalls: MutableList<String> = mutableListOf()

        override fun on(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable {
            onCalls.add(event)
            return AutoCloseable { offCalls.add(event) }
        }
    }
}
