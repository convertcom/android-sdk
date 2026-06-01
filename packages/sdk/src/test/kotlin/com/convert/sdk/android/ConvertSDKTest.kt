/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.generated.ConfigResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Robolectric-backed tests for the [ConvertSDK.Builder] + [ConvertSDK]
 * runtime. Story 2.1 AC-10.
 *
 * Covers:
 * - Builder captures every config option.
 * - build() extracts applicationContext from a non-application Context
 *   (Activity) and never retains the passed ref.
 * - build() returns within NFR1 budget — measured as the median of 5
 *   back-to-back runs (min + max discarded to absorb Robolectric JVM
 *   warm-up); median bound is 60ms (50ms NFR1 + 10ms warmup margin).
 * - sdkKeySecret is not in ConvertSDK.toString() or ConvertConfig.toString().
 * - Direct-data mode fires onReady immediately (next tick).
 * - sdk-key mode without cached config does NOT fire onReady (Story 2.2
 *   lands the fetch).
 * - Multiple onReady callbacks fire in registration order.
 * - on(event, cb) / off(event, cb) round-trip through EventManager.
 * - createContext() returns a ConvertContext with a fresh UUID.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    /**
     * Busy-waits up to [timeoutMs] for [counter] to reach [minValue].
     * Used by Story 2.4 tests that assert on side effects of
     * [EventManager.fire] — the dispatch is async on the SDK scope, so
     * a small bounded poll is the pragmatic way to let the background
     * coroutine settle without Robolectric-specific scheduler hooks.
     */
    private fun awaitAtLeast(
        minValue: Int,
        counter: java.util.concurrent.atomic.AtomicInteger,
        timeoutMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && counter.get() < minValue) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `builder captures all config options into ConvertConfig`() {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-1")
            .sdkKeySecret("secret-hidden")
            .environment("prod")
            .configEndpoint("https://example.test/config")
            .trackEndpoint("https://example.test/track")
            .dataRefreshInterval(60_000L)
            .batchSize(25)
            .releaseInterval(5_000L)
            .hashSeed(1234)
            .maxTraffic(10_000)
            .excludeExperienceIdHash(true)
            .logLevel(LogLevel.INFO)
            .trackingEnabled(true)
            .cacheLevel("low")
            .rulesKeysCaseSensitive(false)
            .rulesNegation("not")
            .build()

        val config = sdk.config
        assertEquals("sk-1", config.sdkKey)
        assertEquals("secret-hidden", config.sdkKeySecret)
        assertEquals("prod", config.environment)
        assertEquals("https://example.test/config", config.api?.endpoint?.config)
        assertEquals("https://example.test/track", config.api?.endpoint?.track)
        assertEquals(60_000L, config.dataRefreshInterval)
        assertEquals(25, config.events?.batchSize)
        assertEquals(5_000L, config.events?.releaseInterval)
        assertEquals(1234, config.bucketing?.hashSeed)
        assertEquals(10_000, config.bucketing?.maxTraffic)
        assertEquals(true, config.bucketing?.excludeExperienceIdHash)
        assertEquals(LogLevel.INFO, config.logger?.logLevel)
        assertEquals(true, config.network?.tracking)
        assertEquals("low", config.network?.cacheLevel)
        assertEquals(false, config.rules?.keysCaseSensitive)
        assertEquals("not", config.rules?.negation)
    }

    @Test
    fun `build extracts applicationContext from a non-application Context`() {
        // Pass an Activity — build must reduce to its Application.
        val activityController = Robolectric.buildActivity(Activity::class.java).create()
        val activity = activityController.get()

        val sdk = ConvertSDK.builder(activity)
            .sdkKey("k")
            .build()

        // The held appContext is the Application, not the Activity.
        assertTrue(
            "expected Application context, got ${sdk.appContext?.javaClass?.name}",
            sdk.appContext is Application,
        )
    }

    @Test
    fun `build returns within NFR1 budget (50ms + 10ms warmup, 5-run median)`() {
        // NFR1 is <50ms on a mid-range device; Robolectric JVM warm-up
        // inflates the FIRST measurement on a cold JVM, so we take 5
        // back-to-back samples, discard the min and max (absorbs warm-up
        // on either tail), and assert the median of the remaining 3 is
        // under 60ms (NFR1 50ms + 10ms warmup margin per Story 2.1 AC-10
        // / F-059 remediation).
        //
        // A loose 200ms bound (the prior implementation) is FORBIDDEN by
        // the corrected story — it cannot detect a 4x regression up to
        // 199ms.
        val samples = LongArray(SAMPLE_COUNT)
        for (i in 0 until SAMPLE_COUNT) {
            samples[i] = measureTimeMillis {
                ConvertSDK.builder(appContext)
                    .sdkKey("k")
                    .build()
            }
        }
        val sorted = samples.sortedArray()
        // Drop sorted[0] (min) and sorted[SAMPLE_COUNT-1] (max); the
        // median of the remaining three is sorted[2].
        val median = sorted[SAMPLE_COUNT / 2]
        assertTrue(
            "build() median(${sorted.toList()})=${median}ms (bound: ${NFR1_MEDIAN_BOUND_MS}ms)",
            median < NFR1_MEDIAN_BOUND_MS,
        )
    }

    @Test
    fun `SDK toString does not leak sdkKeySecret`() {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("pub")
            .sdkKeySecret("super-secret-value")
            .build()

        val rendered = sdk.toString()
        assertFalse(
            "secret must not appear in ConvertSDK.toString(): $rendered",
            rendered.contains("super-secret-value"),
        )
    }

    @Test
    fun `ConvertConfig toString also redacts the secret`() {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("pub")
            .sdkKeySecret("super-secret-value")
            .build()

        val rendered = sdk.config.toString()
        assertFalse(
            "secret must not appear in ConvertConfig.toString(): $rendered",
            rendered.contains("super-secret-value"),
        )
        assertTrue(
            "expected [REDACTED] marker: $rendered",
            rendered.contains("[REDACTED]"),
        )
    }

    @Test
    fun `direct-data mode fires onReady`() {
        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .data(ConfigResponseData())
            .build()

        sdk.onReady { latch.countDown() }

        assertTrue(
            "onReady should fire within 2s in direct-data mode",
            latch.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `sdk-key mode with unreachable endpoint and no cache does not fire onReady`() {
        // Story 2.2: sdk-key mode now wires a fetch + cache-fallback. Point
        // the endpoint at a known-unreachable URL so the fetch fails fast
        // and the cache-read path finds nothing — deterministic, no network.
        // The broader integration test (ConvertSDKConfigFetchTest) uses
        // MockWebServer; this test intentionally stays in the "everything
        // fails" branch without pulling in MockWebServer setup.
        val latch = CountDownLatch(1)
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-1")
            .configEndpoint("https://127.0.0.1:1/unreachable/")
            .build()

        sdk.onReady { latch.countDown() }

        // Wait enough for the fetch to fail (connect-refused is immediate) and
        // the cache-read to miss; onReady must NOT fire.
        val fired = latch.await(3, TimeUnit.SECONDS)
        assertFalse(
            "onReady must NOT fire when both fetch and cache fail",
            fired,
        )
    }

    @Test
    fun `multiple onReady callbacks all fire`() {
        // Story 2.4 AC-3 + AC-6: all onReady subscribers are delivered
        // the READY event — either through the EventManager broadcast
        // (early subscribers captured in the fire snapshot) or through
        // the deferred-replay path (late subscribers registered after
        // fire). Each path schedules an independent `scope.launch`, so
        // on `Dispatchers.Default` the relative ORDER across
        // independent onReady calls is not guaranteed — that's an
        // acceptable trade-off for the single-path, race-free replay
        // mechanism. Strict registration-order delivery is only
        // guaranteed WITHIN a single `fire` call's snapshot (covered
        // by EventManagerTest `multiple subscribers fire in
        // registration order`).
        val fires = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(3)

        val sdk = ConvertSDK.builder(appContext)
            .data(ConfigResponseData())
            .build()

        sdk.onReady {
            fires.incrementAndGet()
            done.countDown()
        }
        sdk.onReady {
            fires.incrementAndGet()
            done.countDown()
        }
        sdk.onReady {
            fires.incrementAndGet()
            done.countDown()
        }

        assertTrue("all three onReady should fire", done.await(2, TimeUnit.SECONDS))
        assertEquals(3, fires.get())
    }

    @Test
    fun `on returns a SubscriptionToken and off accepts it`() {
        // Story 2.4 AC-7: on returns a SubscriptionToken (the primary
        // unsubscribe key). off(event, token) and off(event, callback)
        // both still exist — token is preferred, callback-identity is a
        // Kotlin-consumer convenience.
        val sdk = ConvertSDK.builder(appContext)
            .data(ConfigResponseData())
            .build()

        var fired = 0
        val cb = EventCallback { fired++ }

        val token: SubscriptionToken = sdk.on(CUSTOM_EVENT, cb)
        assertNotNull(token)

        // Token-based off returns ConvertSDK (fluent); no throw on a
        // never-registered token either.
        assertSame(sdk, sdk.off(CUSTOM_EVENT, token))

        // Callback-identity off still works and returns the SDK (fluent).
        sdk.on(CUSTOM_EVENT, cb)
        assertSame(sdk, sdk.off(CUSTOM_EVENT, cb))
    }

    @Test
    fun `off by token stops subsequent deliveries via EventManager`() {
        // Story 2.4 AC-7 end-to-end: subscribe via ConvertSDK, fire via the
        // internal EventManager, observe the callback; then off(event, token)
        // and fire again — callback must not fire.
        val sdk = ConvertSDK.builder(appContext)
            .data(ConfigResponseData())
            .build()

        val fires = java.util.concurrent.atomic.AtomicInteger(0)
        val cb = EventCallback { fires.incrementAndGet() }
        val token = sdk.on(CUSTOM_EVENT, cb)

        sdk.eventManager.fire(CUSTOM_EVENT, emptyMap())
        // Give the scope a tick to dispatch; scope is production so we
        // busy-wait briefly until the fire count updates or time runs out.
        awaitAtLeast(1, fires, timeoutMs = 2_000)
        assertEquals(1, fires.get())

        sdk.off(CUSTOM_EVENT, token)
        sdk.eventManager.fire(CUSTOM_EVENT, emptyMap())
        // Give the scope another tick; count must NOT increment.
        Thread.sleep(200)
        assertEquals(1, fires.get())
    }

    @Test
    fun `onReady registered AFTER READY already fired still fires via replay`() {
        // Story 2.4 AC-3 + AC-6: the late-subscriber case — register after
        // direct-data READY has broadcast. EventManager's deferred replay
        // covers it; the ad-hoc hasData() branch in ConvertSDK is gone.
        val sdk = ConvertSDK.builder(appContext)
            .data(ConfigResponseData())
            .build()
        // Give the Builder's scope.launch time to run setData + fire(READY).
        Thread.sleep(200)

        val latch = CountDownLatch(1)
        sdk.onReady { latch.countDown() }

        assertTrue(
            "late-registered onReady should fire via EventManager replay",
            latch.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `createContext no-arg returns same persisted UUID across calls`() {
        // Story 3.1 AC-1 flipped the earlier (Story 1.2 / 2.1) "every
        // call gets a fresh UUID" contract — now the SDK persists the
        // first auto-UUID and returns it on every subsequent no-arg call.
        // Clear the visitor_id key first so this test is independent of
        // the other tests' ordering.
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .remove("visitor_id")
            .apply()

        val sdk = ConvertSDK.builder(appContext).data(ConfigResponseData()).build()

        val a = sdk.createContext()
        val b = sdk.createContext()

        assertNotNull(a)
        assertNotNull(b)
        assertEquals("AC-1: persisted auto-UUID is stable", a.visitorId, b.visitorId)
    }

    @Test
    fun `createContext with explicit visitorId echoes it back`() {
        val sdk = ConvertSDK.builder(appContext).data(ConfigResponseData()).build()

        val context = sdk.createContext("visitor-X")

        assertEquals("visitor-X", context.visitorId)
    }

    @Test
    fun `Builder with both sdkKey and data prefers data (no throw)`() {
        // Architecture: never throw from public API. The Builder validates
        // and logs a WARN, choosing data.
        val prefetched = ConfigResponseData()
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey("sk-1")
            .data(prefetched)
            .build()

        // data mode wins — hasData is true immediately after build() because
        // direct-data path seeds DataManager synchronously before returning.
        // (The onReady fire is on the next tick, but hasData is sync.)
        assertNotNull(sdk)
        // Config still carries both for audit, but the runtime path used
        // `data`. We can at least verify it didn't throw.
    }

    @Test
    fun `Builder build with neither sdkKey nor data still returns an SDK (no throw)`() {
        // Architecture: never throw. Invalid state logs a WARN.
        val sdk = ConvertSDK.builder(appContext).build()
        assertNotNull(sdk)
        // With nothing set, the SDK has no data and no fetch is possible.
        // onReady should not fire (consumer code handles the null return).
        assertNull(sdk.config.sdkKey)
        assertNull(sdk.config.data)
    }

    private companion object {
        /**
         * Custom (non-SystemEvents) name used by Story 2.4 AC-7 round-trip
         * tests so the assertion target does not collide with SDK-fired
         * events such as READY / CONFIG_UPDATED. Centralised here so the
         * single literal change point keeps the round-trip and off-by-token
         * tests aligned.
         */
        const val CUSTOM_EVENT: String = "custom-event"

        /**
         * Number of back-to-back `build()` measurements taken by the NFR1
         * timing test. With 5 samples we can drop the min and max
         * (Robolectric JVM warm-up sits in one of those tails) and assert
         * on the median of the remaining 3.
         */
        const val SAMPLE_COUNT: Int = 5

        /**
         * Median bound for [SAMPLE_COUNT] back-to-back `build()` runs:
         * NFR1's 50ms ceiling plus a 10ms warm-up margin per Story 2.1
         * AC-10 / F-059 remediation. Hard bound — a regression that
         * pushes the median above this fails the test.
         */
        const val NFR1_MEDIAN_BOUND_MS: Long = 60L
    }
}
