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
import com.orcamobile.feature.settings.SETTINGS_FOCUS_ARG
import com.orcamobile.feature.settings.SETTINGS_OBJECT_ARG
import com.orcamobile.feature.settings.SETTINGS_PROJECT_ARG
import com.orcamobile.feature.settings.SETTINGS_RANGE_ARG
import com.orcamobile.feature.settings.SETTINGS_VOLUME_ARG
import com.orcamobile.feature.settings.SettingsRoute

/** The app's screens. Features know nothing of each other; routes are wired here. */
private object Routes {
    const val HOME = "home"
    const val PREPARE = "prepare/{$PREPARE_MODEL_PATH_ARG}"
    const val SETTINGS = "settings"

    /** The settings screen; with the optional arguments, for one object, part or height range. */
    const val SETTINGS_PATTERN = "settings?$SETTINGS_PROJECT_ARG={$SETTINGS_PROJECT_ARG}&$SETTINGS_OBJECT_ARG={$SETTINGS_OBJECT_ARG}" +
        "&$SETTINGS_VOLUME_ARG={$SETTINGS_VOLUME_ARG}&$SETTINGS_RANGE_ARG={$SETTINGS_RANGE_ARG}&$SETTINGS_FOCUS_ARG={$SETTINGS_FOCUS_ARG}"

    fun prepare(modelPath: String) = "prepare/${Uri.encode(modelPath)}"

    /** The presets' settings, opened on one of them. */
    fun setting(key: String) = "settings?$SETTINGS_FOCUS_ARG=${Uri.encode(key)}"

    fun objectSettings(project: String, objectIndex: Int, volume: Int?, range: Int?) =
        "settings?$SETTINGS_PROJECT_ARG=${Uri.encode(project)}&$SETTINGS_OBJECT_ARG=$objectIndex" +
            "&$SETTINGS_VOLUME_ARG=${volume ?: -1}&$SETTINGS_RANGE_ARG=${range ?: -1}"
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
                onOpenSetting = { key -> navController.navigate(Routes.setting(key)) },
                onOpenObjectSettings = { project, objectIndex, volume, range ->
                    navController.navigate(Routes.objectSettings(project, objectIndex, volume, range))
                },
            )
        }
        composable(
            route = Routes.SETTINGS_PATTERN,
            arguments = listOf(
                navArgument(SETTINGS_PROJECT_ARG) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(SETTINGS_OBJECT_ARG) {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument(SETTINGS_VOLUME_ARG) {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument(SETTINGS_RANGE_ARG) {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument(SETTINGS_FOCUS_ARG) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
