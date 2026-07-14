package com.example.h1econversion.viewmodel

import android.app.Application
import android.net.Uri
import android.os.storage.StorageVolume
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.audio.UsbFileRepository
import com.example.h1econversion.model.DeviceFilesUiState
import com.example.h1econversion.model.FileSource
import com.example.h1econversion.model.RecordingFile
import kotlinx.coroutines.CancellationException
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

    companion object {
        private const val TAG = "DeviceFilesViewModel"
    }

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

    /**
     * SAF ツリーピッカー（ACTION_OPEN_DOCUMENT_TREE）の起動トリガー。
     * 画面側が observe してピッカーを起動する。
     * 値はピッカーの初期 URI（null の場合は汎用ピッカー）。
     */
    private val _openDocumentTreeTrigger = Channel<android.net.Uri?>(Channel.CONFLATED)
    val openDocumentTreeTrigger: Flow<android.net.Uri?> = _openDocumentTreeTrigger.receiveAsFlow()

    private var foundFiles: List<RecordingFile> = emptyList()

    /** 現在実行中のスキャン Job。重複起動防止とキャンセルに使用する。 */
    private var scanJob: Job? = null

    /** 最後に検出した H1e StorageVolume（SAF 権限取得後に再利用） */
    private var h1eVolume: StorageVolume? = null

    /**
     * USB デバイスの検出 → 権限要求 → ファイル走査 を順次実行する。
     * 既存のスキャン Job がある場合はキャンセルしてから新規に開始する。
     *
     * Android 13+ では SAF 権限が必須のため、権限不足の場合は自動的に
     * ACTION_OPEN_DOCUMENT_TREE の起動をトリガーします。
     */
    fun checkDeviceAndLoadFiles() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
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

                // Step 3: Find H1e storage volume → try to access → load files
                _uiState.value = DeviceFilesUiState.ScanningFiles
                scanFilesFromVolume()
            } catch (e: CancellationException) {
                // スキャンキャンセルは正常動作。再スローしてエラー状態に遷移させない。
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "checkDeviceAndLoadFiles: unexpected error", e)
                _uiState.value = DeviceFilesUiState.Error(
                    "予期しないエラーが発生しました: ${e.message}"
                )
            }
        }
    }

    /**
     * H1e ストレージボリュームを検出し、ファイル一覧の取得を試みます。
     * SAF 権限がない場合は自動的にツリーピッカーを起動します。
     */
    private suspend fun scanFilesFromVolume() {
        // H1e ボリュームを検出
        val volume = usbRepo.findH1eStorageVolume()
        if (volume == null) {
            Log.w(TAG, "scanFilesFromVolume: H1e storage volume not found")
            _uiState.value = DeviceFilesUiState.Error(
                "H1e のストレージが見つかりません。\n\n" +
                "考えられる原因:\n" +
                "・H1e の電源が入っていない\n" +
                "・USB OTG ケーブルが正しく接続されていない\n\n" +
                "下部の「手動で選択」ボタンから直接ファイルを選択してください。"
            )
            return
        }
        h1eVolume = volume

        // SAF 経由でアクセスを試みる
        val docFile = usbRepo.getDocumentFileForVolume(volume)
        if (docFile != null) {
            // SAF 権限あり：直接ファイル一覧を取得
            Log.d(TAG, "scanFilesFromVolume: SAF access granted, listing files")
            val files = usbRepo.listRecordingFiles(docFile)
            foundFiles = files
            _uiState.value = DeviceFilesUiState.FilesLoaded(files)
            return
        }

        // SAF 権限なし：ツリーピッカーを起動
        Log.d(TAG, "scanFilesFromVolume: SAF permission needed, launching document tree picker")
        val initialUri = usbRepo.createInitialTreeUri(volume)
        _uiState.value = DeviceFilesUiState.NeedSafPermission(
            "H1e のストレージにアクセスするには権限の付与が必要です。\n表示される画面で H1e を選択してください。"
        )
        _openDocumentTreeTrigger.send(initialUri)
    }

    /**
     * SAF ツリーピッカーから返却された tree URI を処理します。
     * 権限を保存し、ファイル一覧を取得します。
     */
    fun onTreeUriGranted(treeUri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = DeviceFilesUiState.ScanningFiles
                Log.d(TAG, "onTreeUriGranted: processing tree URI $treeUri")

                val docFile = usbRepo.processTreeUriResult(treeUri)
                if (docFile == null) {
                    _uiState.value = DeviceFilesUiState.Error(
                        "ストレージへのアクセスに失敗しました。\n手動で選択してください。"
                    )
                    return@launch
                }

                val files = usbRepo.listRecordingFiles(docFile)
                foundFiles = files
                _uiState.value = DeviceFilesUiState.FilesLoaded(files)
            } catch (e: CancellationException) {
                // スキャンキャンセルは正常動作。再スローしてエラー状態に遷移させない。
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "onTreeUriGranted: failed", e)
                _uiState.value = DeviceFilesUiState.Error(
                    "ファイル一覧の取得に失敗しました: ${e.message}"
                )
            }
        }
    }

    /**
     * SAF 権限付与画面を再表示します（前回キャンセルした場合など）。
     */
    fun requestSafPermission() {
        val volume = h1eVolume
        if (volume == null) {
            checkDeviceAndLoadFiles()
            return
        }
        viewModelScope.launch {
            val initialUri = usbRepo.createInitialTreeUri(volume)
            _openDocumentTreeTrigger.send(initialUri)
        }
    }

    fun selectFile(file: RecordingFile) {
        Log.d(TAG, "selectFile: file=${file.name}, uri=${file.uri}")
        viewModelScope.launch {
            _copyingFileName.value = file.name
            Log.d(TAG, "selectFile: calling copyUriToLocal...")
            val result = localRepo.copyUriToLocal(file.uri, file.name, FileSource.USB_DEVICE)
            _copyingFileName.value = null
            Log.d(TAG, "selectFile: copyUriToLocal returned, isSuccess=${result.isSuccess}")

            result.fold(
                onSuccess = { selectedFile ->
                    Log.d(TAG, "selectFile: SUCCESS, navigating to ${selectedFile.localPath}")
                    _navigationEvent.send(
                        DeviceFilesNavigationEvent.NavigateToFileInfo(selectedFile.localPath)
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "selectFile: FAILURE", error)
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
