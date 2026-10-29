package com.example.iaqieqmornitoring.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.iaqieqmornitoring.bridge.BridgeNav
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.iaqieqmornitoring.components.RoutinePromptHost
import com.example.iaqieqmornitoring.screens.*

@Composable
fun AppNavigation() {

    val nav = rememberNavController()

    // Screen changes requested by the AI/voice bridge
    LaunchedEffect(Unit) {
        BridgeNav.requests.collect { route -> nav.navigate(route) { popUpTo("home") } }
    }

    NavHost(
        navController = nav,
        startDestination = "startup"
    ) {
        composable("iaqi") {
            IAQIScaleScreen(nav)
        }

        composable("startup") {
            StartupScreen(nav)
        }

        composable("home") {
            DashboardScreen(nav)
        }

        composable("analytics") {
            AnalyticsScreen(nav)
        }

        composable("mission") {
            MissionScreen(nav)
        }

        composable("routine") {
            RoutineScreen(nav)
        }

        composable("camera") {
            CameraDepthScreen(nav)
        }

        composable("signlanguage") {
            SignLanguageScreen(nav)
        }

        composable("settings") {
            SettingsScreen(nav)
        }

        composable(
            route = "detail/{sensor}",
            arguments = listOf(navArgument("sensor") { type = NavType.StringType })
        ) { backStackEntry ->

            val sensorName = backStackEntry.arguments?.getString("sensor") ?: ""

            DetailScreen(sensorName = sensorName, navController = nav)
        }
    }

    // Routine popup: shown on top of whichever screen is open.
    RoutinePromptHost()
}