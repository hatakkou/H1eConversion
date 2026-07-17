package com.example.h1econversion.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.model.FileSource
import com.example.h1econversion.model.ImportUiState
import com.example.h1econversion.model.SelectedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StartNavigationEvent {
    data class NavigateToFileInfo(val localPath: String) : StartNavigationEvent
    data class NavigateToMultiFileGain(
        val localPaths: List<String>,
        val failedCount: Int = 0,
        val failedDetails: List<String> = emptyList(),
    ) : StartNavigationEvent
}

class StartViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StartViewModel"
    }

    private val localRepo = LocalFileRepository(application)

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _navigationEvent = Channel<StartNavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<StartNavigationEvent> = _navigationEvent.receiveAsFlow()

    /**
     * Called when the user picks a file from the system file picker.
     */
    fun onFilePicked(uri: Uri, fileName: String) {
        Log.d(TAG, "onFilePicked: start, uri=$uri, fileName=$fileName")
        viewModelScope.launch {
            Log.d(TAG, "onFilePicked: coroutine launched")
            _importState.value = ImportUiState.Copying(fileName)
            Log.d(TAG, "onFilePicked: state set to Copying, calling copyUriToLocal...")
            val result = localRepo.copyUriToLocal(uri, fileName, FileSource.LOCAL_IMPORT)
            Log.d(TAG, "onFilePicked: copyUriToLocal returned, isSuccess=${result.isSuccess}, isFailure=${result.isFailure}")
            result.fold(
                onSuccess = { selectedFile ->
                    Log.d(TAG, "onFilePicked: SUCCESS, selectedFile=$selectedFile")
                    _importState.value = ImportUiState.Idle
                    val event = StartNavigationEvent.NavigateToFileInfo(selectedFile.localPath)
                    Log.d(TAG, "onFilePicked: sending navigation event: $event")
                    _navigationEvent.send(event)
                    Log.d(TAG, "onFilePicked: navigation event sent")
                },
                onFailure = { error ->
                    Log.e(TAG, "onFilePicked: FAILURE, error=${error.message}", error)
                    _importState.value = ImportUiState.Error(
                        error.message ?: "ファイルの読み込みに失敗しました"
                    )
                },
            )
        }
        Log.d(TAG, "onFilePicked: viewModelScope.launch returned (async)")
    }

    /**
     * 複数ファイルがピッカーから選択された場合の一括処理。
     * 全ファイルを並列コピーし、完了後に MultiFileGain 画面へ遷移します。
     *
     * @param files URIとファイル名のペアのリスト
     */
    fun onMultipleFilesPicked(files: List<Pair<Uri, String>>) {
        Log.d(TAG, "onMultipleFilesPicked: start, count=${files.size}")
        if (files.isEmpty()) return

        viewModelScope.launch {
            _importState.value = ImportUiState.Copying("${files.size}ファイルをコピー中…")

            // 並列コピー（IO スレッド）
            val results = withContext(Dispatchers.IO) {
                files.map { (uri, fileName) ->
                    Log.d(TAG, "onMultipleFilesPicked: copying $fileName")
                    localRepo.copyUriToLocal(uri, fileName, FileSource.LOCAL_IMPORT)
                }
            }

            // 結果の集計
            val successPaths = mutableListOf<String>()
            val errors = mutableListOf<String>()

            results.forEach { result ->
                result.fold(
                    onSuccess = { selectedFile ->
                        Log.d(TAG, "onMultipleFilesPicked: success - ${selectedFile.localPath}")
                        successPaths.add(selectedFile.localPath)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "onMultipleFilesPicked: failure", error)
                        errors.add(error.message ?: "不明なエラー")
                    },
                )
            }

            _importState.value = ImportUiState.Idle

            if (successPaths.isNotEmpty() && errors.isEmpty()) {
                // 全ファイル成功 → そのまま遷移
                Log.d(TAG, "onMultipleFilesPicked: navigating with ${successPaths.size} files")
                _navigationEvent.send(
                    StartNavigationEvent.NavigateToMultiFileGain(successPaths)
                )
            } else if (successPaths.isNotEmpty() && errors.isNotEmpty()) {
                // 部分失敗 → 成功ファイルで遷移し、失敗情報を通知
                Log.w(TAG, "onMultipleFilesPicked: partial failure, ${successPaths.size} success, ${errors.size} failed")
                _importState.value = ImportUiState.Warning(
                    "${successPaths.size}ファイル成功 / ${errors.size}ファイル失敗"
                )
                _navigationEvent.send(
                    StartNavigationEvent.NavigateToMultiFileGain(
                        localPaths = successPaths,
                        failedCount = errors.size,
                        failedDetails = errors,
                    )
                )
            } else if (errors.isNotEmpty()) {
                // 全ファイル失敗
                _importState.value = ImportUiState.Error(
                    "すべてのファイルの読み込みに失敗しました: ${errors.first()}"
                )
            }
        }
    }

    fun clearError() {
        _importState.value = ImportUiState.Idle
    }
}
