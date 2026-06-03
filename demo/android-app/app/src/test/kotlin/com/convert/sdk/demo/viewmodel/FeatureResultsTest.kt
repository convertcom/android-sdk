/*
 * Convert Android SDK Demo App — SdkViewModel Feature-results tests (Story 7.4)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.FeatureStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Story 7.4 AC-2 / AC-3 / AC-5 — ViewModel-level tests for the
 * Features-screen state. Drives the ViewModel through an injected
 * [FeatureRunner] double so the test never touches the real SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureResultsTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun newVm(runner: FeatureRunner): SdkViewModel =
        SdkViewModel(
            eventSubscriber = FakeEventSubscriber(),
            initialNetworkOnline = true,
            featureRunner = runner,
        )

    private fun feature(
        key: String,
        enabled: Boolean,
        variables: Map<String, JsonElement>? = null,
        experienceKey: String? = null,
    ): Feature = Feature(
        id = "$key-id",
        key = key,
        name = key,
        status = if (enabled) FeatureStatus.ENABLED else FeatureStatus.DISABLED,
        variables = variables,
        experienceKey = experienceKey,
    )

    @Test
    fun `featureResults flow is empty at construction`() {
        val vm = newVm(FakeFeatureRunner())
        assertTrue(vm.featureResults.value.isEmpty())
    }

    @Test
    fun `runFeature with bucketed enabled feature adds a non-error result with typed variables`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(
            key = "test-feature",
            enabled = true,
            variables = mapOf(
                "buttonColor" to JsonPrimitive("blue"),
                "maxRetries" to JsonPrimitive(3),
                "showBanner" to JsonPrimitive(true),
                "discountFactor" to JsonPrimitive(0.15),
            ),
            experienceKey = "homepage-test",
        )
        val vm = newVm(runner)

        vm.runFeature("test-feature")

        val r = vm.featureResults.value.single()
        assertFalse(r.isError)
        assertEquals("test-feature", r.featureKey)
        assertTrue(r.enabled, "ENABLED feature must render enabled=true")
        assertEquals("homepage-test", r.experienceKey)
        assertEquals(4, r.variables.size)

        val byName = r.variables.associateBy { it.name }
        assertEquals("\"blue\"", byName["buttonColor"]?.value)
        assertEquals("string", byName["buttonColor"]?.typeLabel)
        assertEquals("3", byName["maxRetries"]?.value)
        assertEquals("integer", byName["maxRetries"]?.typeLabel)
        assertEquals("true", byName["showBanner"]?.value)
        assertEquals("boolean", byName["showBanner"]?.typeLabel)
        assertEquals("0.15", byName["discountFactor"]?.value)
        assertEquals("float", byName["discountFactor"]?.typeLabel)
    }

    @Test
    fun `runFeature with disabled feature renders enabled=false and no variable rows`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(
            key = "off-feature",
            enabled = false,
            variables = null, // DISABLED features carry no variables
        )
        val vm = newVm(runner)

        vm.runFeature("off-feature")

        val r = vm.featureResults.value.single()
        assertFalse(r.isError, "DISABLED is still a valid evaluation — not an error result")
        assertFalse(r.enabled)
        assertTrue(r.variables.isEmpty(), "null variables → empty typed-variable list")
    }

    @Test
    fun `runFeature with null return adds an error result referencing the key (AC-5)`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = null
        val vm = newVm(runner)

        vm.runFeature("missing-feat")

        val r = vm.featureResults.value.single()
        assertTrue(r.isError, "null feature → error result")
        assertEquals("missing-feat", r.featureKey)
        assertFalse(r.enabled)
        assertNotNull(r.errorMessage)
        assertTrue(
            r.errorMessage!!.contains("missing-feat"),
            "error message should mention the feature key — got '${r.errorMessage}'",
        )
        assertNotNull(r.errorHint, "error results should include a developer hint")
    }

    @Test
    fun `runFeatures with two features adds two newest-first results`() {
        val runner = FakeFeatureRunner()
        runner.allResults = listOf(
            feature(key = "feat-a", enabled = true),
            feature(key = "feat-b", enabled = false),
        )
        val vm = newVm(runner)

        vm.runFeatures()

        val results = vm.featureResults.value
        assertEquals(2, results.size)
        // Newest-first: last feature sits at index 0.
        assertEquals("feat-b", results[0].featureKey)
        assertFalse(results[0].enabled)
        assertEquals("feat-a", results[1].featureKey)
        assertTrue(results[1].enabled)
        assertTrue(results.none { it.isError })
    }

    @Test
    fun `runFeatures with empty list adds a single hint error result`() {
        val runner = FakeFeatureRunner()
        runner.allResults = emptyList()
        val vm = newVm(runner)

        vm.runFeatures()

        val r = vm.featureResults.value.single()
        assertTrue(r.isError)
        assertNotNull(r.errorMessage)
        assertNotNull(r.errorHint)
    }

    @Test
    fun `featureResults list caps at 20 and drops the oldest entries`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(key = "f", enabled = true)
        val vm = newVm(runner)

        repeat(21) { vm.runFeature("feat-$it") }

        val results = vm.featureResults.value
        assertEquals(20, results.size, "list must be capped at 20 entries")
        // featureKey is stored verbatim from the caller; index 0 is the newest.
        assertEquals("feat-20", results[0].featureKey)
        assertEquals("feat-1", results.last().featureKey)
    }

    @Test
    fun `clearFeatureResults empties the list`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(key = "f", enabled = true)
        val vm = newVm(runner)
        vm.runFeature("f")
        assertEquals(1, vm.featureResults.value.size)

        vm.clearFeatureResults()

        assertTrue(vm.featureResults.value.isEmpty())
    }

    @Test
    fun `featureResults ids are unique`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(key = "f", enabled = true)
        val vm = newVm(runner)
        repeat(5) { vm.runFeature("feat-$it") }

        val ids = vm.featureResults.value.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun `featureResults and experienceResults are independent`() {
        // A developer rapidly tapping both screens should not see the
        // two lists intermix. The ViewModel exposes one StateFlow per
        // list; this pins the contract that clearing one does not
        // affect the other.
        val runner = FakeFeatureRunner()
        runner.singleResult = feature(key = "f", enabled = true)
        val vm = newVm(runner)

        vm.runFeature("f")
        assertEquals(1, vm.featureResults.value.size)
        assertTrue(vm.results.value.isEmpty())

        vm.clearFeatureResults()
        assertTrue(vm.featureResults.value.isEmpty())
        // (experienceResults path uses ExperienceRunner — tested in its own suite)
    }

    @Test
    fun `feature with null key is still a valid non-error result`() {
        val runner = FakeFeatureRunner()
        runner.singleResult = Feature(
            id = null,
            key = null, // allowed by the data class
            name = null,
            status = FeatureStatus.ENABLED,
            variables = null,
        )
        val vm = newVm(runner)

        vm.runFeature("unknown-shape")

        val r = vm.featureResults.value.single()
        assertFalse(r.isError, "a returned Feature is a valid evaluation even when key is null")
        // Caller-supplied key is preserved on the result so the card still has a title.
        assertEquals("unknown-shape", r.featureKey)
    }

    // ------------------------------------------------------------------

    private class FakeFeatureRunner : FeatureRunner {
        var singleResult: Feature? = null
        var allResults: List<Feature> = emptyList()

        override fun runFeature(featureKey: String): Feature? = singleResult
        override fun runFeatures(): List<Feature> = allResults
    }

    private class FakeEventSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
