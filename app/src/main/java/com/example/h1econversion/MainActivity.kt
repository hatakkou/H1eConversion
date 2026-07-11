package com.example.h1econversion

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.h1econversion.ui.navigation.AppNavGraph
import com.example.h1econversion.ui.theme.H1eConversionTheme
import com.example.h1econversion.viewmodel.DeviceFilesViewModel
import com.example.h1econversion.viewmodel.StartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            H1eConversionTheme {
                val navController = rememberNavController()
                val startViewModel: StartViewModel = viewModel()
                val deviceFilesViewModel: DeviceFilesViewModel = viewModel()

                // 手動選択モード：true の場合、ピッカー結果を DeviceFilesViewModel に転送
                var isManualDeviceSelect by remember { mutableStateOf(false) }

                // File picker for "ファイルをインポート" / 手動選択
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri != null) {
                        lifecycleScope.launch {
                            val fileName = resolveFileName(uri)
                            if (isManualDeviceSelect) {
                                isManualDeviceSelect = false
                                deviceFilesViewModel.onManualFilePicked(uri, fileName)
                            } else {
                                startViewModel.onFilePicked(uri, fileName)
                            }
                        }
                    }
                }

                // Observe navigation events from StartViewModel
                val importState by startViewModel.importState.collectAsState()
                LaunchedEffect(Unit) {
                    startViewModel.navigationEvent.collect { event ->
                        when (event) {
                            is com.example.h1econversion.viewmodel.StartNavigationEvent.NavigateToFileInfo -> {
                                val encoded = Uri.encode(event.localPath)
                                navController.navigate("file_info/$encoded")
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
                )
            }
        }
    }

    /**
     * URI からファイル名を解決する（IO スレッドで実行）。
     * クエリ失敗時はフォールバック名を返す。
     */
    private suspend fun resolveFileName(uri: Uri): String = withContext(Dispatchers.IO) {
        var name = "recording.wav"
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        } catch (_: SecurityException) {
            // フォールバック名をそのまま返す
        } catch (_: Exception) {
            // その他のクエリ失敗時もフォールバック名を返す
        }
        name
    }
}
