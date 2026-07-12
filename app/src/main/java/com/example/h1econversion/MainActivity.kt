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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: callback invoked, uri=$uri, isManualDeviceSelect=$isManualDeviceSelect")
                    try {
                        if (uri != null) {
                            // 永続的読み取り権限を取得（再起動後もアクセス可能にする）
                            try {
                                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                contentResolver.takePersistableUriPermission(uri, takeFlags)
                                if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: persistable uri permission granted for $uri")
                            } catch (e: SecurityException) {
                                Log.w(TAG, "filePickerLauncher: could not take persistable permission (non-fatal)", e)
                            }

                            lifecycleScope.launch {
                                try {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: resolveFileName start, uri=$uri")
                                    val fileName = resolveFileName(uri)
                                    if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: resolveFileName done, fileName=$fileName")

                                    if (isManualDeviceSelect) {
                                        if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: routing to DeviceFilesViewModel.onManualFilePicked")
                                        isManualDeviceSelect = false
                                        deviceFilesViewModel.onManualFilePicked(uri, fileName)
                                    } else {
                                        if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: routing to StartViewModel.onFilePicked")
                                        startViewModel.onFilePicked(uri, fileName)
                                    }
                                    if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: onFilePicked/onManualFilePicked returned")
                                } catch (e: Exception) {
                                    Log.e(TAG, "filePickerLauncher: ERROR in picker callback coroutine", e)
                                }
                            }
                        } else {
                            if (BuildConfig.DEBUG) Log.d(TAG, "filePickerLauncher: uri is null (user cancelled)")
                            isManualDeviceSelect = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "filePickerLauncher: FATAL ERROR in picker callback", e)
                    }
                }

                // Observe navigation events from StartViewModel
                val importState by startViewModel.importState.collectAsState()
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
