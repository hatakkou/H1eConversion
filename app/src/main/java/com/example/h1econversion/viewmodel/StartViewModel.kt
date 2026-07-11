package com.example.h1econversion.viewmodel

import android.app.Application
import android.net.Uri
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

    private val localRepo = LocalFileRepository(application)

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _navigationEvent = Channel<StartNavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<StartNavigationEvent> = _navigationEvent.receiveAsFlow()

    /**
     * Called when the user picks a file from the system file picker.
     */
    fun onFilePicked(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Copying(fileName)
            val result = localRepo.copyUriToLocal(uri, fileName, FileSource.LOCAL_IMPORT)
            result.fold(
                onSuccess = { selectedFile ->
                    _importState.value = ImportUiState.Idle
                    _navigationEvent.send(
                        StartNavigationEvent.NavigateToFileInfo(selectedFile.localPath)
                    )
                },
                onFailure = { error ->
                    _importState.value = ImportUiState.Error(
                        error.message ?: "ファイルの読み込みに失敗しました"
                    )
                },
            )
        }
    }

    fun clearError() {
        _importState.value = ImportUiState.Idle
    }
}
