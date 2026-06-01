/*
 * Convert Android SDK Demo App — MainActivity
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo

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
import androidx.lifecycle.viewmodel.compose.viewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val demoApp = application as DemoApplication
        val viewModelFactory = SdkViewModelFactory(demoApp)

        setContent {
            MaterialTheme {
                DemoAppScaffold(viewModelFactory = viewModelFactory)
            }
        }
    }
}

/**
 * Root composable. Split out of [MainActivity.onCreate] so it can be
 * previewed in Android Studio without spinning up an Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoAppScaffold(viewModelFactory: ViewModelProvider.Factory) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val sdkViewModel: SdkViewModel = viewModel(factory = viewModelFactory)

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
        ) as T
    }
}

