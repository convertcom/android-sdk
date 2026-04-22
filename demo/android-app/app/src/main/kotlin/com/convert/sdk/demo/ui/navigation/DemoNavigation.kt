/*
 * Convert Android SDK Demo App — Navigation
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.convert.sdk.demo.ui.screen.ConfigScreen
import com.convert.sdk.demo.ui.screen.ConversionsScreen
import com.convert.sdk.demo.ui.screen.ExperiencesScreen
import com.convert.sdk.demo.ui.screen.FeaturesScreen
import com.convert.sdk.demo.ui.screen.OfflineScreen
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.1 AC-5 — the five bottom-navigation destinations. The
 * `route` strings match AC-5 verbatim and are referenced by both the
 * [NavHost] composable in [DemoNavHost] and the NavigationBarItems in
 * `MainActivity.kt`.
 */
enum class DemoDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Experiences(route = "experiences", label = "Experiences", icon = Icons.Filled.Science),
    Features(route = "features", label = "Features", icon = Icons.Filled.Bolt),
    Conversions(route = "conversions", label = "Conversions", icon = Icons.Filled.MonetizationOn),
    Offline(route = "offline", label = "Offline", icon = Icons.Filled.CloudOff),
    Config(route = "config", label = "Config", icon = Icons.Filled.Settings),
}

/**
 * Jetpack Navigation Compose host. `startDestination = "experiences"`
 * implements AC-6's zero-step onboarding.
 */
@Composable
fun DemoNavHost(
    navController: NavHostController,
    sdkViewModel: SdkViewModel,
) {
    NavHost(
        navController = navController,
        startDestination = DemoDestination.Experiences.route,
    ) {
        composable(DemoDestination.Experiences.route) { ExperiencesScreen(sdkViewModel) }
        composable(DemoDestination.Features.route) { FeaturesScreen(sdkViewModel) }
        composable(DemoDestination.Conversions.route) { ConversionsScreen(sdkViewModel) }
        composable(DemoDestination.Offline.route) { OfflineScreen(sdkViewModel) }
        composable(DemoDestination.Config.route) { ConfigScreen(sdkViewModel) }
    }
}
