/*
 * Convert Android SDK Demo App — MainActivity
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.convert.sdk.demo.ui.component.EventInspectorSheet
import com.convert.sdk.demo.ui.navigation.DemoDestination
import com.convert.sdk.demo.ui.navigation.DemoNavHost
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.1 AC-4 / AC-5 / AC-6 — host Activity. Wires:
 *  - The shared [SdkViewModel] (constructed with the production
 *    [com.convert.sdk.demo.viewmodel.EventSubscriber] from
 *    [DemoApplication]).
 *  - A [BottomSheetScaffold] with a persistent
 *    [EventInspectorSheet] peek of ~200dp, a [TopAppBar], and a
 *    5-item [NavigationBar].
 *  - A [DemoNavHost] whose `startDestination` is
 *    [DemoDestination.Experiences] — zero-step onboarding (AC-6).
 *
 * `@OptIn(ExperimentalMaterial3Api::class)` covers BottomSheetScaffold
 * + TopAppBar + rememberBottomSheetScaffoldState (all still marked
 * experimental in Material 3 2026.03.01 per Gotcha 3).
 */
class MainActivity : ComponentActivity() {

    /**
     * qs-08 (experiment-preview) demo testbed — hoisted out of the
     * Compose tree so [onCreate] / [onNewIntent] can drive
     * [SdkViewModel.applyPreviewParam] directly from a deep-link
     * [Intent]. `by viewModels { ... }` resolves against this
     * Activity's [androidx.lifecycle.ViewModelStore] — the same
     * instance [DemoAppScaffold] renders against — so there is exactly
     * one [SdkViewModel] per Activity instance either way.
     *
     * `internal` visibility lets a Robolectric test assert on
     * [SdkViewModel.previewState] after driving a deep-link [Intent]
     * through [Companion.handlePreviewIntent].
     */
    internal val sdkViewModel: SdkViewModel by viewModels {
        SdkViewModelFactory(application as DemoApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handlePreviewIntent(intent, sdkViewModel)

        setContent {
            MaterialTheme {
                DemoAppScaffold(sdkViewModel = sdkViewModel)
            }
        }
    }

    /**
     * qs-08 — a second deep link while the demo is already running (the
     * default launch mode keeps a single task/Activity instance active
     * rather than always starting a fresh one) delivers here instead of
     * a new [onCreate]. [setIntent] keeps [getIntent] in sync for any
     * later caller.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreviewIntent(intent, sdkViewModel)
    }

    companion object {
        /**
         * qs-08 — the deep-link query-param key
         * [com.convert.sdk.core.preview.PreviewParam.parse] expects the
         * VALUE of (see the `AndroidManifest.xml` `convertdemo://preview`
         * intent-filter and `README.md`'s "Try it: Experiment preview"
         * section).
         */
        private const val PREVIEW_QUERY_PARAM = "convert_preview"

        /**
         * qs-08 — extracts `convert_preview` from an `ACTION_VIEW`
         * intent's data URI (e.g.
         * `convertdemo://preview?convert_preview=123.456`) and hands the
         * raw value to [SdkViewModel.applyPreviewParam], which owns
         * parsing/validation. Any other intent (the MAIN/LAUNCHER
         * intent, or an `ACTION_VIEW` with no `convert_preview` param) is
         * a no-op — this never throws.
         *
         * `internal` so a Robolectric test can drive it directly.
         */
        internal fun handlePreviewIntent(intent: Intent?, viewModel: SdkViewModel) {
            if (intent?.action != Intent.ACTION_VIEW) return
            val raw = intent.data?.getQueryParameter(PREVIEW_QUERY_PARAM) ?: return
            viewModel.applyPreviewParam(raw)
        }
    }
}

/**
 * Root composable. Split out of [MainActivity.onCreate] so it can be
 * previewed in Android Studio without spinning up an Activity.
 *
 * qs-08 — takes the already-hoisted [sdkViewModel] directly (rather
 * than a [ViewModelProvider.Factory] resolved here via
 * `viewModel(factory = ...)`) so [MainActivity.onCreate] /
 * [MainActivity.onNewIntent] and this composable observe the exact same
 * instance without a second factory lookup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoAppScaffold(sdkViewModel: SdkViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val sheetState = rememberBottomSheetScaffoldState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convert SDK Demo") },
            )
        },
        bottomBar = {
            NavigationBar {
                DemoDestination.entries.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        BottomSheetScaffold(
            scaffoldState = sheetState,
            sheetPeekHeight = 200.dp,
            sheetContent = {
                EventInspectorSheet(viewModel = sdkViewModel)
            },
            modifier = Modifier.padding(innerPadding),
        ) { sheetInnerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sheetInnerPadding),
            ) {
                Column {
                    DemoNavHost(navController = navController, sdkViewModel = sdkViewModel)
                }
            }
        }
    }
}

/**
 * ViewModelProvider.Factory that builds [SdkViewModel] with the
 * production [com.convert.sdk.demo.viewmodel.EventSubscriber] from
 * [DemoApplication]. Keeps the ViewModel free of Android-Context
 * plumbing and preserves unit-testability through its simple fake.
 */
private class SdkViewModelFactory(private val demoApp: DemoApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SdkViewModel::class.java)) {
            "SdkViewModelFactory only creates SdkViewModel, got ${modelClass.name}"
        }
        return SdkViewModel(
            eventSubscriber = demoApp.eventSubscriber(),
            initialNetworkOnline = true,
            experienceRunner = demoApp.experienceRunner(),
            featureRunner = demoApp.featureRunner(),
            conversionTracker = demoApp.conversionTracker(),
            configSnapshotProvider = demoApp.configSnapshotProvider(),
            previewController = demoApp.previewController(),
        ) as T
    }
}

