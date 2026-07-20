package com.example.h1econversion

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.h1econversion.BuildConfig
import com.example.h1econversion.ui.navigation.AppNavGraph
import com.example.h1econversion.ui.theme.H1eConversionTheme
import com.example.h1econversion.viewmodel.DeviceFilesViewModel
import com.example.h1econversion.viewmodel.StartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) Log.d(TAG, "onCreate: start")

        // エッジtoエッジ表示。システムバー背景は Compose テーマの白背景が透過して表示される
        // enableEdgeToEdge() は API 35+ の3ボタンナビゲーションスクリムも自動処理する
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            H1eConversionTheme {
                val navController = rememberNavController()
                val startViewModel: StartViewModel = viewModel()
                val deviceFilesViewModel: DeviceFilesViewModel = viewModel()

                // 手動選択モード：true の場合、ピッカー結果を DeviceFilesViewModel に転送
                var isManualDeviceSelect by remember { mutableStateOf(false) }

                // File picker for "ファイルをインポート" (複数選択) / 手動選択 (単一選択)
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris: List<Uri> ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: callback invoked, uris=$uris, count=${uris.size}, isManualDeviceSelect=$isManualDeviceSelect")
                    try {
                        if (uris.isNotEmpty()) {
                            // URI 読み取り権限はピッカーからの一時付与で十分（インポート後はアプリ内ストレージにコピーするため）

                            lifecycleScope.launch {
                                try {
                                    when {
                                        isManualDeviceSelect && uris.size == 1 -> {
                                            // 手動選択モード（単一ファイル）
                                            if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: routing single to DeviceFilesViewModel")
                                            isManualDeviceSelect = false
                                            val fileName = resolveFileName(uris.first())
                                            deviceFilesViewModel.onManualFilePicked(uris.first(), fileName)
                                        }
                                        isManualDeviceSelect -> {
                                            // 手動選択で複数は想定外だが、最初の1件だけ処理
                                            if (BuildConfig.DEBUG) Log.w(TAG, "filePickerLauncher: manual select with multiple files, using first only")
                                            isManualDeviceSelect = false
                                            val fileName = resolveFileName(uris.first())
                                            deviceFilesViewModel.onManualFilePicked(uris.first(), fileName)
                                        }
                                        else -> {
                                            // 通常のインポート（複数ファイル）
                                            if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: routing ${uris.size} files to StartViewModel.onMultipleFilesPicked")
                                            val files = uris.map { uri ->
                                                val fileName = resolveFileName(uri)
                                                uri to fileName
                                            }
                                            startViewModel.onMultipleFilesPicked(files)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "filePickerLauncher: ERROR in picker callback coroutine", e)
                                }
                            }
                        } else {
                            if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: uris is empty (user cancelled)")
                            isManualDeviceSelect = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "filePickerLauncher: FATAL ERROR in picker callback", e)
                    }
                }

                // ナビゲーションイベントの監視
                LaunchedEffect(Unit) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: observing startViewModel.navigationEvent")
                    startViewModel.navigationEvent.collect { event ->
                        if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: received navigation event: $event")
                        when (event) {
                            is com.example.h1econversion.viewmodel.StartNavigationEvent.NavigateToFileInfo -> {
                                val route = com.example.h1econversion.ui.navigation.Routes.fileInfo(event.localPath)
                                if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: navigating to $route (localPath=${event.localPath})")
                                try {
                                    navController.navigate(route)
                                    if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: navigation succeeded")
                                } catch (e: Exception) {
                                    Log.e(TAG, "MainActivity: navigation failed", e)
                                }
                            }
                            is com.example.h1econversion.viewmodel.StartNavigationEvent.NavigateToMultiFileGain -> {
                                val route = com.example.h1econversion.ui.navigation.Routes.multiGain(event.localPaths)
                                if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: navigating to multi_gain with ${event.localPaths.size} files")
                                // 部分失敗があればトースト表示
                                if (event.failedCount > 0) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "${event.localPaths.size}ファイル成功 / ${event.failedCount}ファイル失敗",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                                try {
                                    navController.navigate(route)
                                    if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity: multi_gain navigation succeeded")
                                } catch (e: Exception) {
                                    Log.e(TAG, "MainActivity: multi_gain navigation failed", e)
                                }
                            }
                        }
                    }
                }

                AppNavGraph(
                    navController = navController,
                    onLaunchFilePicker = {
                        // DeviceFilesScreen からの手動選択かどうかでフラグを切り替え
                        // import 画面からの呼び出しでは isManualDeviceSelect は false のまま
                        filePickerLauncher.launch(arrayOf("audio/*"))
                    },
                    onLaunchManualPicker = {
                        isManualDeviceSelect = true
                        filePickerLauncher.launch(arrayOf("audio/*"))
                    },
                    importState = startViewModel.importState,
                    onClearError = { startViewModel.clearError() },
                )
            }
        }
    }

    /**
     * URI からファイル名を解決する（IO スレッドで実行）。
     * クエリ失敗時はフォールバック名を返す。
     */
    private suspend fun resolveFileName(uri: Uri): String = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) Log.d(TAG, "resolveFileName: querying content resolver for $uri")
        var name = "recording.wav"
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "resolveFileName: cursor obtained, columnCount=${cursor.columnCount}")
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    Log.d(TAG, "resolveFileName: DISPLAY_NAME index=$nameIndex")
                }
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: name
                    if (BuildConfig.DEBUG) Log.d(TAG, "resolveFileName: resolved name=$name")
                } else {
                    Log.w(TAG, "resolveFileName: cursor empty or DISPLAY_NAME not found")
                }
            } ?: Log.w(TAG, "resolveFileName: contentResolver.query returned null")
        } catch (e: SecurityException) {
            Log.w(TAG, "resolveFileName: SecurityException", e)
        } catch (e: Exception) {
            Log.w(TAG, "resolveFileName: Exception", e)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "resolveFileName: returning name=$name")
        name
    }
}
