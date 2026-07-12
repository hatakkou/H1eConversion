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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface StartNavigationEvent {
    data class NavigateToFileInfo(val localPath: String) : StartNavigationEvent
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

    fun clearError() {
        _importState.value = ImportUiState.Idle
    }
}
