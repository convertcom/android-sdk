/*
 * Convert Android SDK Demo App — SdkViewModel Conversion-results tests (Story 7.5)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Story 7.5 AC-1 / AC-2 / AC-3 — ViewModel-level tests for the
 * Conversions-screen state. Drives the ViewModel through an injected
 * [ConversionTracker] double so the test never touches the real SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversionResultsTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun newVm(tracker: ConversionTracker): SdkViewModel =
        SdkViewModel(
            eventSubscriber = FakeEventSubscriber(),
            initialNetworkOnline = true,
            conversionTracker = tracker,
        )

    @Test
    fun `conversionResults flow is empty at construction`() {
        val vm = newVm(FakeConversionTracker())
        assertTrue(vm.conversionResults.value.isEmpty())
    }

    @Test
    fun `first trackPurchaseConversion call invokes tracker with correct goal key and data`() {
        val tracker = FakeConversionTracker()
        val vm = newVm(tracker)

        vm.trackPurchaseConversion()

        assertEquals(1, tracker.calls.size, "tracker.trackConversion should be called exactly once")
        val (key, data) = tracker.calls.single()
        assertEquals("purchase-goal", key)
        assertEquals(2, data.size)
        val byKey = data.associateBy { it.key }
        assertEquals(JsonPrimitive(10.3), byKey[GoalDataKey.AMOUNT]?.value)
        assertEquals(JsonPrimitive(2), byKey[GoalDataKey.PRODUCTS_COUNT]?.value)
    }

    @Test
    fun `first trackPurchaseConversion call appends non-dedup result`() {
        val vm = newVm(FakeConversionTracker())

        vm.trackPurchaseConversion()

        val r = vm.conversionResults.value.single()
        assertFalse(r.isDedup, "first call is not a dedup")
        assertFalse(r.isError)
        assertEquals("purchase-goal", r.goalKey)
        assertEquals(10.3, r.amount)
        assertEquals(2, r.productsCount)
    }

    @Test
    fun `second trackPurchaseConversion call appends a dedup result (AC-3)`() {
        val tracker = FakeConversionTracker()
        val vm = newVm(tracker)

        vm.trackPurchaseConversion()
        vm.trackPurchaseConversion()

        val results = vm.conversionResults.value
        assertEquals(2, results.size)
        // Newest-first: the dedup tap is at index 0.
        assertTrue(results[0].isDedup, "second tap must produce a dedup result")
        assertFalse(results[1].isDedup, "first tap stays non-dedup")
        assertEquals("purchase-goal", results[0].goalKey)
    }

    @Test
    fun `dedup tap still calls SDK trackConversion (SDK owns the dedup guard)`() {
        // Demo UI does not short-circuit the SDK call — the SDK itself
        // performs the atomic dedup guard per Story 4.3 AC-6. The demo's
        // `trackedGoalKeys` set is purely for surfacing the UI state.
        // Confirming the ViewModel forwards every tap to the tracker
        // prevents a future "don't call the SDK again" optimization that
        // would break the inspector observability contract.
        val tracker = FakeConversionTracker()
        val vm = newVm(tracker)

        vm.trackPurchaseConversion()
        vm.trackPurchaseConversion()

        assertEquals(
            2,
            tracker.calls.size,
            "tracker must be called on every tap — SDK owns dedup, demo UI does not",
        )
    }

    @Test
    fun `trackPurchaseConversion surfaces an error result for an unknown goal and does not track (F-174)`() {
        // Goal-existence pre-check: when the configured goal key is not in
        // the fetched config, the SDK's trackConversion would WARN-log and
        // drop it silently. The demo must instead show the red error card
        // and NOT call the SDK (a dropped tap with a misleading "tracked"
        // card is exactly the F-174 demo-side symptom).
        val tracker = FakeConversionTracker(goalExists = false)
        val vm = newVm(tracker)

        vm.trackPurchaseConversion()

        val r = vm.conversionResults.value.single()
        assertTrue(r.isError, "unknown goal must produce an error result")
        assertFalse(r.isDedup)
        assertEquals("purchase-goal", r.goalKey)
        assertNotNull(r.errorMessage, "error result must carry a headline message")
        assertTrue(
            tracker.calls.isEmpty(),
            "an unknown goal must NOT be forwarded to the SDK tracker",
        )
    }

    @Test
    fun `second tap emits dedup DEBUG log (AC-3)`() {
        val vm = newVm(FakeConversionTracker())

        vm.trackPurchaseConversion()
        // Clear the logs StateFlow's first-call noise (if any future log
        // additions happen on the happy path) — focus on the dedup path.
        val logsBeforeSecondTap = vm.logs.value.size

        vm.trackPurchaseConversion()

        val newLogs = vm.logs.value.take(vm.logs.value.size - logsBeforeSecondTap)
        // The ViewModel emits a DEBUG log matching the SDK's literal:
        // "Goal 'purchase-goal' already tracked for visitor, skipping"
        val dedupLog = newLogs.firstOrNull { it.level == LogLevel.DEBUG }
        assertNotNull(dedupLog, "dedup tap must emit a DEBUG log entry")
        assertTrue(
            dedupLog!!.message.contains("purchase-goal", ignoreCase = false),
            "log must mention the goalKey — got '${dedupLog.message}'",
        )
        assertTrue(
            dedupLog.message.contains("already tracked", ignoreCase = true),
            "log must mention 'already tracked' — got '${dedupLog.message}'",
        )
        assertTrue(
            dedupLog.message.contains("skipping", ignoreCase = true),
            "log must mention 'skipping' — got '${dedupLog.message}'",
        )
    }

    @Test
    fun `conversionResults list caps at 20 and drops the oldest entries`() {
        val vm = newVm(FakeConversionTracker())

        // A single trackPurchaseConversion for the same key produces one
        // non-dedup result then 20 dedup results — total 21 cards; the
        // cap should drop the oldest to leave exactly 20.
        repeat(21) { vm.trackPurchaseConversion() }

        val results = vm.conversionResults.value
        assertEquals(20, results.size, "list must be capped at 20 entries")
    }

    @Test
    fun `clearConversionResults empties the list but preserves dedup memory`() {
        // Clearing the UI list must NOT reset the trackedGoalKeys set —
        // otherwise a 3rd tap after a clear would show a spurious
        // non-dedup card (and a repeat CONVERSION event in the
        // inspector), which contradicts the "dedup is per-visitor, not
        // per-UI-session" contract from Story 4.3.
        val vm = newVm(FakeConversionTracker())
        vm.trackPurchaseConversion()
        assertEquals(1, vm.conversionResults.value.size)

        vm.clearConversionResults()

        assertTrue(vm.conversionResults.value.isEmpty())

        // Third tap — should be a dedup, not a fresh non-dedup.
        vm.trackPurchaseConversion()
        val r = vm.conversionResults.value.single()
        assertTrue(
            r.isDedup,
            "tap after clear should still dedup — the tracked-keys memory survives UI clears",
        )
    }

    @Test
    fun `conversionResults ids are unique`() {
        val vm = newVm(FakeConversionTracker())
        repeat(5) { vm.trackPurchaseConversion() }

        val ids = vm.conversionResults.value.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun `conversionResults and featureResults are independent`() {
        // Three StateFlows in the same ViewModel (results, featureResults,
        // conversionResults) must not accidentally share state. Tapping
        // conversions should leave the feature list untouched.
        val vm = newVm(FakeConversionTracker())

        vm.trackPurchaseConversion()
        assertEquals(1, vm.conversionResults.value.size)
        assertTrue(vm.featureResults.value.isEmpty())
        assertTrue(vm.results.value.isEmpty())

        vm.clearConversionResults()
        assertTrue(vm.conversionResults.value.isEmpty())
    }

    // ------------------------------------------------------------------

    private class FakeConversionTracker(
        private val goalExists: Boolean = true,
    ) : ConversionTracker {
        val calls: MutableList<Pair<String, List<GoalData>>> = mutableListOf()

        override fun trackConversion(goalKey: String, goalData: List<GoalData>) {
            calls += goalKey to goalData
        }

        override fun hasGoal(goalKey: String): Boolean = goalExists
    }

    private class FakeEventSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
