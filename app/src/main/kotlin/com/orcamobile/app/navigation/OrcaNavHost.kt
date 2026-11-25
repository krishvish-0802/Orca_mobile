package com.orcamobile.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orcamobile.feature.devices.DEVICES_PRINTER_ARG
import com.orcamobile.feature.devices.EditPrinterRoute
import com.orcamobile.feature.devices.PrinterRoute
import com.orcamobile.feature.devices.PrintersRoute
import com.orcamobile.feature.home.HomeRoute
import com.orcamobile.feature.prepare.PREPARE_MODEL_PATH_ARG
import com.orcamobile.feature.prepare.PrepareRoute
import com.orcamobile.feature.settings.SETTINGS_FOCUS_ARG
import com.orcamobile.feature.settings.SETTINGS_OBJECT_ARG
import com.orcamobile.feature.settings.SETTINGS_PROJECT_ARG
import com.orcamobile.feature.settings.SETTINGS_RANGE_ARG
import com.orcamobile.feature.settings.SETTINGS_SAVE_ARG
import com.orcamobile.feature.settings.SETTINGS_VOLUME_ARG
import com.orcamobile.feature.settings.SettingsRoute
import com.orcamobile.feature.setup.SetupRoute

/** The app's screens. Features know nothing of each other; routes are wired here. */
private object Routes {
    const val HOME = "home"
    const val PREPARE = "prepare/{$PREPARE_MODEL_PATH_ARG}"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val PRINTERS = "printers"
    const val PRINTER = "printers/{$DEVICES_PRINTER_ARG}"
    const val ADD_PRINTER = "printers/add"
    const val EDIT_PRINTER = "printers/edit/{$DEVICES_PRINTER_ARG}"

    fun printer(id: String) = "printers/${Uri.encode(id)}"

    fun editPrinter(id: String) = "printers/edit/${Uri.encode(id)}"

    /** The settings screen; with the optional arguments, for one object, part or height range. */
    const val SETTINGS_PATTERN = "settings?$SETTINGS_PROJECT_ARG={$SETTINGS_PROJECT_ARG}&$SETTINGS_OBJECT_ARG={$SETTINGS_OBJECT_ARG}" +
        "&$SETTINGS_VOLUME_ARG={$SETTINGS_VOLUME_ARG}&$SETTINGS_RANGE_ARG={$SETTINGS_RANGE_ARG}&$SETTINGS_FOCUS_ARG={$SETTINGS_FOCUS_ARG}&$SETTINGS_SAVE_ARG={$SETTINGS_SAVE_ARG}"

    fun prepare(modelPath: String) = "prepare/${Uri.encode(modelPath)}"

    /** The presets' settings, opened on one of them. */
    fun setting(key: String) = "settings?$SETTINGS_FOCUS_ARG=${Uri.encode(key)}"

    /** The presets' settings with the Presets sheet up, to save the changes. */
    const val SAVE_PRESETS = "settings?$SETTINGS_SAVE_ARG=true"

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
                onOpenSetup = { navController.navigate(Routes.SETUP) { launchSingleTop = true } },
                onOpenPrinters = { navController.navigate(Routes.PRINTERS) },
            )
        }
        composable(Routes.PRINTERS) {
            PrintersRoute(
                onBack = { navController.popBackStack() },
                onOpenPrinter = { id -> navController.navigate(Routes.printer(id)) },
                onAddPrinter = { navController.navigate(Routes.ADD_PRINTER) },
            )
        }
        composable(Routes.ADD_PRINTER) {
            EditPrinterRoute(onDone = { navController.popBackStack() })
        }
        composable(Routes.EDIT_PRINTER, arguments = listOf(navArgument(DEVICES_PRINTER_ARG) { type = NavType.StringType })) {
            EditPrinterRoute(
                onDone = { navController.popBackStack() },
                // The printer's own page goes with it.
                onRemoved = { navController.popBackStack(Routes.PRINTERS, inclusive = false) },
            )
        }
        composable(Routes.PRINTER, arguments = listOf(navArgument(DEVICES_PRINTER_ARG) { type = NavType.StringType })) {
            PrinterRoute(onBack = { navController.popBackStack() }, onEdit = { id -> navController.navigate(Routes.editPrinter(id)) })
        }
        composable(Routes.SETUP) {
            SetupRoute(onFinished = { navController.popBackStack() })
        }
        composable(
            route = Routes.PREPARE,
            arguments = listOf(navArgument(PREPARE_MODEL_PATH_ARG) { type = NavType.StringType }),
        ) {
            PrepareRoute(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSetting = { key -> navController.navigate(Routes.setting(key)) },
                onSavePresets = { navController.navigate(Routes.SAVE_PRESETS) },
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
                navArgument(SETTINGS_SAVE_ARG) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
