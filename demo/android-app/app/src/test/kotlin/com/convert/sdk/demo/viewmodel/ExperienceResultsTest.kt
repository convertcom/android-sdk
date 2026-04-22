/*
 * Convert Android SDK Demo App — SdkViewModel Experience-results tests (Story 7.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.Variation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Story 7.3 AC-2 / AC-3 / AC-4 — ViewModel-level tests for the
 * Experiences-screen state. Drives the ViewModel through an injected
 * [ExperienceRunner] double so the test never touches the real SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceResultsTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun newVm(runner: ExperienceRunner): SdkViewModel =
        SdkViewModel(
            eventSubscriber = FakeEventSubscriber(),
            initialNetworkOnline = true,
            experienceRunner = runner,
        )

    @Test
    fun `results flow is empty at construction`() {
        val vm = newVm(FakeRunner())
        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `runSingleExperience with bucketed variation adds a non-error result`() {
        val runner = FakeRunner()
        runner.singleResult = Variation(
            key = "treatment",
            experienceKey = "test-experience",
        )
        val vm = newVm(runner)

        vm.runSingleExperience("test-experience")

        val results = vm.results.value
        assertEquals(1, results.size, "one tap → one result")
        val r = results.first()
        assertFalse(r.isError, "bucketed variation must NOT be an error result")
        assertEquals("test-experience", r.experienceKey)
        assertEquals("treatment", r.variationKey)
        assertNull(r.errorMessage)
        assertNull(r.errorHint)
    }

    @Test
    fun `runSingleExperience with null variation adds an error result referencing the key`() {
        val runner = FakeRunner()
        runner.singleResult = null
        val vm = newVm(runner)

        vm.runSingleExperience("missing-exp")

        val r = vm.results.value.single()
        assertTrue(r.isError, "null variation → error result")
        assertEquals("missing-exp", r.experienceKey)
        assertNull(r.variationKey)
        assertNotNull(r.errorMessage)
        assertTrue(
            r.errorMessage!!.contains("missing-exp"),
            "error message should mention the experience key — got '${r.errorMessage}'",
        )
        assertNotNull(r.errorHint, "error results should include a developer hint")
    }

    @Test
    fun `runAllExperiences with two variations adds two newest-first results`() {
        val runner = FakeRunner()
        runner.allResults = listOf(
            Variation(key = "control", experienceKey = "exp-a"),
            Variation(key = "v2", experienceKey = "exp-b"),
        )
        val vm = newVm(runner)

        vm.runAllExperiences()

        val results = vm.results.value
        assertEquals(2, results.size)
        // Newest-first: last variation emitted sits at index 0.
        assertEquals("exp-b", results[0].experienceKey)
        assertEquals("v2", results[0].variationKey)
        assertEquals("exp-a", results[1].experienceKey)
        assertEquals("control", results[1].variationKey)
        assertTrue(results.none { it.isError })
    }

    @Test
    fun `runAllExperiences with empty list adds a single hint error result`() {
        val runner = FakeRunner()
        runner.allResults = emptyList()
        val vm = newVm(runner)

        vm.runAllExperiences()

        val r = vm.results.value.single()
        assertTrue(r.isError)
        assertNotNull(r.errorMessage)
        assertNotNull(r.errorHint)
    }

    @Test
    fun `results list caps at 20 and drops the oldest entries`() {
        val runner = FakeRunner()
        runner.singleResult = Variation(key = "v", experienceKey = "exp")
        val vm = newVm(runner)

        // Run 21 times — cap is 20, oldest must drop.
        repeat(21) { vm.runSingleExperience("exp-$it") }

        val results = vm.results.value
        assertEquals(20, results.size, "list must be capped at 20 entries")
        // Newest-first: the last call (index 20) sits at results[0].
        assertEquals("exp-20", results[0].experienceKey)
        // The oldest still present is index 1 (index 0 was dropped).
        assertEquals("exp-1", results.last().experienceKey)
    }

    @Test
    fun `clearResults empties the list`() {
        val runner = FakeRunner()
        runner.singleResult = Variation(key = "v", experienceKey = "exp")
        val vm = newVm(runner)
        vm.runSingleExperience("exp")
        assertEquals(1, vm.results.value.size)

        vm.clearResults()

        assertTrue(vm.results.value.isEmpty())
    }

    @Test
    fun `result ids are unique`() {
        val runner = FakeRunner()
        runner.singleResult = Variation(key = "v", experienceKey = "exp")
        val vm = newVm(runner)
        repeat(5) { vm.runSingleExperience("exp-$it") }

        val ids = vm.results.value.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "every result must have a unique id")
    }

    @Test
    fun `variation with null key renders with a null variationKey not a crash`() {
        val runner = FakeRunner()
        // The Variation data class allows a null `key` — the ViewModel must not
        // treat that as an error result; the screen is responsible for rendering
        // a human fallback like "(no key)".
        runner.singleResult = Variation(key = null, experienceKey = "exp-nokey")
        val vm = newVm(runner)

        vm.runSingleExperience("exp-nokey")

        val r = vm.results.value.single()
        assertFalse(r.isError, "a bucketed Variation is never an error, even with null key")
        assertNull(r.variationKey)
        assertEquals("exp-nokey", r.experienceKey)
    }

    // ------------------------------------------------------------------

    /** Minimal [ExperienceRunner] double for tests. */
    private class FakeRunner : ExperienceRunner {
        var singleResult: Variation? = null
        var allResults: List<Variation> = emptyList()

        override fun runExperience(experienceKey: String): Variation? = singleResult

        override fun runExperiences(): List<Variation> = allResults
    }

    /** Mirror of the in-viewmodel-test fake. */
    private class FakeEventSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
