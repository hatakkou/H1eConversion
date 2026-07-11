package com.example.h1econversion.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.audio.UsbFileRepository
import com.example.h1econversion.model.DeviceFilesUiState
import com.example.h1econversion.model.FileSource
import com.example.h1econversion.model.RecordingFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface DeviceFilesNavigationEvent {
    data class NavigateToFileInfo(val localPath: String) : DeviceFilesNavigationEvent
}

class DeviceFilesViewModel(application: Application) : AndroidViewModel(application) {

    private val usbRepo = UsbFileRepository(application)
    private val localRepo = LocalFileRepository(application)

    private val _uiState = MutableStateFlow<DeviceFilesUiState>(DeviceFilesUiState.Idle)
    val uiState: StateFlow<DeviceFilesUiState> = _uiState.asStateFlow()

    private val _copyingFileName = MutableStateFlow<String?>(null)
    val copyingFileName: StateFlow<String?> = _copyingFileName.asStateFlow()

    private val _navigationEvent = Channel<DeviceFilesNavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<DeviceFilesNavigationEvent> = _navigationEvent.receiveAsFlow()

    /**
     * 手動ファイル選択のトリガーイベント。
     * 画面側が observe してドキュメントピッカーを起動する。
     */
    private val _manualSelectTrigger = Channel<Unit>(Channel.CONFLATED)
    val manualSelectTrigger: Flow<Unit> = _manualSelectTrigger.receiveAsFlow()

    private var foundFiles: List<RecordingFile> = emptyList()

    /** 現在実行中のスキャン Job。重複起動防止とキャンセルに使用する。 */
    private var scanJob: Job? = null

    /**
     * USB デバイスの検出 → 権限要求 → ファイル走査 を順次実行する。
     * 既存のスキャン Job がある場合はキャンセルしてから新規に開始する。
     */
    fun checkDeviceAndLoadFiles() {
        // 既存のスキャンをキャンセル
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = DeviceFilesUiState.CheckingDevice

            // Step 1: Find Zoom device
            val device = usbRepo.findZoomDevice()
            if (device == null) {
                _uiState.value = DeviceFilesUiState.DeviceNotFound
                return@launch
            }

            // Step 2: Request permission
            val permissionFlow = usbRepo.requestPermission(device)
            val granted = withTimeoutOrNull(UsbFileRepository.PERMISSION_TIMEOUT_MS) {
                permissionFlow.first()
            }
            if (granted != true) {
                _uiState.value = DeviceFilesUiState.PermissionDenied
                return@launch
            }

            // Step 3: Scan files
            _uiState.value = DeviceFilesUiState.ScanningFiles

            val volume = usbRepo.findH1eStorageVolume()
            if (volume == null) {
                _uiState.value = DeviceFilesUiState.Error(
                    "ストレージボリュームが見つかりません。\n手動で選択してください。"
                )
                return@launch
            }

            val files = usbRepo.listRecordingFiles(volume)
            foundFiles = files
            _uiState.value = DeviceFilesUiState.FilesLoaded(files)
        }
    }

    fun selectFile(file: RecordingFile) {
        viewModelScope.launch {
            _copyingFileName.value = file.name
            val result = localRepo.copyUriToLocal(file.uri, file.name, FileSource.USB_DEVICE)
            _copyingFileName.value = null

            result.fold(
                onSuccess = { selectedFile ->
                    _navigationEvent.send(
                        DeviceFilesNavigationEvent.NavigateToFileInfo(selectedFile.localPath)
                    )
                },
                onFailure = { error ->
                    _uiState.value = DeviceFilesUiState.Error(
                        error.message ?: "ファイルのコピーに失敗しました"
                    )
                },
            )
        }
    }

    /**
     * 手動ファイル選択をリクエストする。
     * 画面側が observe してドキュメントピッカーを起動する。
     */
    fun requestManualSelect() {
        _manualSelectTrigger.trySend(Unit)
    }

    /**
     * 手動選択でピッカーから返却されたファイルをローカルにコピーし、
     * FileInfo 画面へ遷移する。
     */
    fun onManualFilePicked(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _copyingFileName.value = fileName
            val result = localRepo.copyUriToLocal(uri, fileName, FileSource.LOCAL_IMPORT)
            _copyingFileName.value = null

            result.fold(
                onSuccess = { selectedFile ->
                    _navigationEvent.send(
                        DeviceFilesNavigationEvent.NavigateToFileInfo(selectedFile.localPath)
                    )
                },
                onFailure = { error ->
                    _uiState.value = DeviceFilesUiState.Error(
                        error.message ?: "ファイルのコピーに失敗しました"
                    )
                },
            )
        }
    }

    fun retry() {
        checkDeviceAndLoadFiles()
    }

    fun getUsbRepo(): UsbFileRepository = usbRepo
}
