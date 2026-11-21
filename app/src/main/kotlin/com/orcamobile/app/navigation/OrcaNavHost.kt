package com.orcamobile.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orcamobile.feature.home.HomeRoute
import com.orcamobile.feature.prepare.PREPARE_MODEL_PATH_ARG
import com.orcamobile.feature.prepare.PrepareRoute
import com.orcamobile.feature.settings.SettingsRoute

/** The app's screens. Features know nothing of each other; routes are wired here. */
private object Routes {
    const val HOME = "home"
    const val PREPARE = "prepare/{$PREPARE_MODEL_PATH_ARG}"
    const val SETTINGS = "settings"

    fun prepare(modelPath: String) = "prepare/${Uri.encode(modelPath)}"
}

@Composable
fun OrcaNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                onOpenModel = { path -> navController.navigate(Routes.prepare(path)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.PREPARE,
            arguments = listOf(navArgument(PREPARE_MODEL_PATH_ARG) { type = NavType.StringType }),
        ) {
            PrepareRoute(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
