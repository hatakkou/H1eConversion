package com.example.h1econversion.ui.navigation

import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.h1econversion.model.ImportUiState
import com.example.h1econversion.ui.screen.BatchConversionScreen
import com.example.h1econversion.ui.screen.ConversionScreen
import com.example.h1econversion.ui.screen.DeviceFilesScreen
import com.example.h1econversion.ui.screen.FileInfoScreen
import com.example.h1econversion.ui.screen.MultiFileGainScreen
import com.example.h1econversion.ui.screen.StartScreen
import com.example.h1econversion.ui.screen.WaveformScreen
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val START = "start"
    const val DEVICE_FILES = "device_files"
    const val FILE_INFO = "file_info/{localPath}"
    const val WAVEFORM = "waveform/{localPath}"
    const val CONVERSION = "conversion/{localPath}/{gain}/{inputFormat}"
    const val MULTI_GAIN = "multi_gain"
    const val BATCH_CONVERSION = "batch_conversion"

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

    /**
     * 複数ファイルのパスリストを MultiFileStateHolder に格納し、
     * MultiFileGain 画面へのルートを返します。
     */
    fun multiGain(paths: List<String>): String {
        com.example.h1econversion.model.MultiFileStateHolder.set(paths)
        return MULTI_GAIN
    }

    /**
     * ファイルパスとゲインのリストを MultiFileStateHolder に格納し、
     * バッチ変換画面へのルートを返します。
     */
    fun batchConversion(pathGainList: List<Pair<String, Float>>): String {
        // 一時的にエンコードして保持（BatchConversionScreen で読み取り）
        com.example.h1econversion.model.MultiFileStateHolder.setPathGainPairs(pathGainList)
        return BATCH_CONVERSION
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    onLaunchFilePicker: () -> Unit,
    onLaunchManualPicker: () -> Unit = {},
    importState: StateFlow<ImportUiState>? = null,
    onClearError: () -> Unit = {},
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
                importState = importState,
                onClearError = onClearError,
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

        // ---- 複数ファイルゲイン調整画面 ----
        composable(Routes.MULTI_GAIN) { backStackEntry ->
            // backStackEntry.id をキーにすることで、コンポーザブルが削除→再追加
            // されても同一の Navigation entry では consume() が再実行されない
            val paths = remember(backStackEntry.id) {
                com.example.h1econversion.model.MultiFileStateHolder.consume()
            }
            MultiFileGainScreen(
                filePaths = paths,
                onNavigateBack = {
                    navController.popBackStack(Routes.START, inclusive = false)
                },
                onNavigateToConversion = { pathGainList ->
                    navController.navigate(Routes.batchConversion(pathGainList))
                },
            )
        }

        // ---- 一括変換画面 ----
        composable(Routes.BATCH_CONVERSION) { backStackEntry ->
            // backStackEntry.id をキーにすることで、コンポーザブルが削除→再追加
            // されても同一の Navigation entry では consumePathGainPairs() が再実行されない
            val pathGainPairs = remember(backStackEntry.id) {
                com.example.h1econversion.model.MultiFileStateHolder.consumePathGainPairs()
            }
            BatchConversionScreen(
                pathGainPairs = pathGainPairs,
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}
