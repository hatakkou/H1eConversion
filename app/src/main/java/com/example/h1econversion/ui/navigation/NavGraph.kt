package com.example.h1econversion.ui.navigation

import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.h1econversion.ui.screen.ConversionScreen
import com.example.h1econversion.ui.screen.DeviceFilesScreen
import com.example.h1econversion.ui.screen.FileInfoScreen
import com.example.h1econversion.ui.screen.StartScreen
import com.example.h1econversion.ui.screen.WaveformScreen

object Routes {
    const val START = "start"
    const val DEVICE_FILES = "device_files"
    const val FILE_INFO = "file_info/{localPath}"
    const val WAVEFORM = "waveform/{localPath}"
    const val CONVERSION = "conversion/{localPath}/{gain}/{inputFormat}"

    fun fileInfo(localPath: String): String {
        val encoded = Base64.encodeToString(localPath.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "file_info/$encoded"
    }

    fun waveform(localPath: String): String {
        val encoded = Base64.encodeToString(localPath.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "waveform/$encoded"
    }

    fun conversion(localPath: String, gain: Float, inputFormat: String): String {
        val encodedPath = Base64.encodeToString(localPath.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val encodedFormat = Base64.encodeToString(inputFormat.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "conversion/$encodedPath/$gain/$encodedFormat"
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
            val encodedPath = backStackEntry.arguments?.getString("localPath") ?: ""
            val localPath = String(Base64.decode(encodedPath, Base64.URL_SAFE))
            FileInfoScreen(
                localPath = localPath,
                onNavigateBack = {
                    navController.popBackStack(Routes.START, inclusive = false)
                },
                onNavigateToWaveform = { path ->
                    navController.navigate(Routes.waveform(path))
                },
            )
        }

        composable(
            route = Routes.WAVEFORM,
            arguments = listOf(
                navArgument("localPath") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("localPath") ?: ""
            val localPath = String(Base64.decode(encodedPath, Base64.URL_SAFE))
            WaveformScreen(
                localPath = localPath,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToConversion = { path, gain, format ->
                    navController.navigate(Routes.conversion(path, gain, format))
                },
            )
        }

        composable(
            route = Routes.CONVERSION,
            arguments = listOf(
                navArgument("localPath") { type = NavType.StringType },
                navArgument("gain") { type = NavType.FloatType },
                navArgument("inputFormat") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("localPath") ?: ""
            val gain = backStackEntry.arguments?.getFloat("gain") ?: 1.0f
            val encodedFormat = backStackEntry.arguments?.getString("inputFormat") ?: ""
            val localPath = String(Base64.decode(encodedPath, Base64.URL_SAFE))
            val inputFormat = String(Base64.decode(encodedFormat, Base64.URL_SAFE))
            ConversionScreen(
                inputPath = localPath,
                inputFormat = inputFormat,
                gain = gain,
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}
