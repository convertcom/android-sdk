/*
 * Convert Android SDK Demo App — MainActivity deep-link tests (qs-08)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import android.content.Intent
import android.net.Uri
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.PreviewController
import com.convert.sdk.demo.viewmodel.PreviewUiState
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * qs-08 (experiment-preview) demo testbed — Robolectric coverage for
 * [MainActivity]'s deep-link wiring.
 *
 * [MainActivity.handlePreviewIntent] is exercised two ways:
 *  1. Directly, against a plain [SdkViewModel] wired to a fake
 *     [PreviewController] — no Activity/Application involved, mirrors
 *     [com.convert.sdk.demo.viewmodel.SdkViewModelTest]'s style.
 *  2. Through a real [MainActivity] launched with an `ACTION_VIEW`
 *     intent via [Robolectric.buildActivity], asserting on the
 *     Activity's hoisted [MainActivity.sdkViewModel] — proving
 *     [MainActivity.onCreate] actually drives the wiring end-to-end,
 *     not just that the extraction helper works in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    // --- (1) handlePreviewIntent in isolation --------------------------

    private fun newVm(controller: FakePreviewController): SdkViewModel =
        SdkViewModel(
            eventSubscriber = SilentSubscriber,
            initialNetworkOnline = true,
            previewController = controller,
        )

    @Test
    fun `handlePreviewIntent with a valid ACTION_VIEW preview uri applies the preview`() {
        val controller = FakePreviewController()
        val vm = newVm(controller)
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("convertdemo://preview?convert_preview=12.34"),
        )

        MainActivity.handlePreviewIntent(intent, vm)

        assertEquals(listOf("12" to "34"), controller.setPreviewCalls)
        assertEquals(PreviewUiState.Active("12", "34"), vm.previewState.value)
    }

    @Test
    fun `handlePreviewIntent with a malformed preview value does not crash and surfaces an error`() {
        val controller = FakePreviewController()
        val vm = newVm(controller)
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("convertdemo://preview?convert_preview=not-numeric"),
        )

        MainActivity.handlePreviewIntent(intent, vm)

        assertTrue(controller.setPreviewCalls.isEmpty())
        assertTrue(vm.previewState.value is PreviewUiState.Error)
    }

    @Test
    fun `handlePreviewIntent with the MAIN LAUNCHER intent is a no-op`() {
        val controller = FakePreviewController()
        val vm = newVm(controller)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        MainActivity.handlePreviewIntent(intent, vm)

        assertTrue(controller.setPreviewCalls.isEmpty())
        assertEquals(PreviewUiState.Inactive, vm.previewState.value)
    }

    @Test
    fun `handlePreviewIntent with a null intent does not crash`() {
        val controller = FakePreviewController()
        val vm = newVm(controller)

        MainActivity.handlePreviewIntent(null, vm)

        assertTrue(controller.setPreviewCalls.isEmpty())
        assertEquals(PreviewUiState.Inactive, vm.previewState.value)
    }

    // --- (2) end-to-end through a real MainActivity ---------------------

    @Test
    fun `launching MainActivity with a valid preview deep link drives applyPreviewParam`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("convertdemo://preview?convert_preview=12.34"),
        )

        val activity = Robolectric.buildActivity(MainActivity::class.java, intent)
            .setup()
            .get()

        assertEquals(PreviewUiState.Active("12", "34"), activity.sdkViewModel.previewState.value)
    }

    @Test
    fun `launching MainActivity with a malformed preview deep link does not crash`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("convertdemo://preview?convert_preview=oops"),
        )

        val activity = Robolectric.buildActivity(MainActivity::class.java, intent)
            .setup()
            .get()

        assertTrue(activity.sdkViewModel.previewState.value is PreviewUiState.Error)
    }

    // ---------------------------------------------------------------------

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }

    /**
     * Minimal in-memory [PreviewController] double — mirrors
     * [com.convert.sdk.demo.viewmodel.SdkViewModelTest]'s fake of the
     * same shape.
     */
    private class FakePreviewController : PreviewController {
        val setPreviewCalls: MutableList<Pair<String, String>> = mutableListOf()
        private var active: Boolean = false

        override fun setPreview(experienceId: String, variationId: String) {
            setPreviewCalls += experienceId to variationId
            active = true
        }

        override fun clearPreview() {
            active = false
        }

        override fun isPreviewActive(): Boolean = active
    }
}
