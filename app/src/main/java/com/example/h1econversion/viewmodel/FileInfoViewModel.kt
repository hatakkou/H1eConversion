package com.example.h1econversion.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.model.FileInfoUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepo = LocalFileRepository(application)

    private val _uiState = MutableStateFlow<FileInfoUiState>(FileInfoUiState.Loading)
    val uiState: StateFlow<FileInfoUiState> = _uiState.asStateFlow()

    fun loadFileInfo(localPath: String) {
        viewModelScope.launch {
            if (localPath.isBlank()) {
                _uiState.value = FileInfoUiState.Error("ファイルパスが指定されていません")
                return@launch
            }

            val selectedFile = localRepo.getFileInfo(localPath)
            if (selectedFile != null) {
                _uiState.value = FileInfoUiState.Success(selectedFile)
            } else {
                _uiState.value = FileInfoUiState.Error("ファイルが見つかりません")
            }
        }
    }
}
