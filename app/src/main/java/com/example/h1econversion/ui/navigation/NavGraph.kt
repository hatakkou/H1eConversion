package com.example.h1econversion.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.h1econversion.ui.screen.DeviceFilesScreen
import com.example.h1econversion.ui.screen.FileInfoScreen
import com.example.h1econversion.ui.screen.StartScreen

object Routes {
    const val START = "start"
    const val DEVICE_FILES = "device_files"
    const val FILE_INFO = "file_info/{localPath}"

    fun fileInfo(localPath: String): String {
        val encoded = Uri.encode(localPath)
        return "file_info/$encoded"
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    onLaunchFilePicker: () -> Unit,
    onLaunchManualPicker: () -> Unit = {},
) {
    NavHost(
        navController = navController,
        startDestination = Routes.START,
    ) {
        composable(Routes.START) {
            StartScreen(
                onConnectDevice = {
                    navController.navigate(Routes.DEVICE_FILES)
                },
                onImportFile = {
                    onLaunchFilePicker()
                },
            )
        }

        composable(Routes.DEVICE_FILES) {
            DeviceFilesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onFileSelected = { localPath ->
                    navController.navigate(Routes.fileInfo(localPath))
                },
                onLaunchManualPicker = onLaunchManualPicker,
            )
        }

        composable(
            route = Routes.FILE_INFO,
            arguments = listOf(
                navArgument("localPath") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val localPath = backStackEntry.arguments?.getString("localPath") ?: ""
            FileInfoScreen(
                localPath = localPath,
                onNavigateBack = {
                    navController.popBackStack(Routes.START, inclusive = false)
                },
            )
        }
    }
}
