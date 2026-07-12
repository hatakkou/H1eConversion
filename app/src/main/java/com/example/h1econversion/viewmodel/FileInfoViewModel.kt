package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.model.FileInfoUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileInfoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FileInfoViewModel"
    }

    private val localRepo = LocalFileRepository(application)

    private val _uiState = MutableStateFlow<FileInfoUiState>(FileInfoUiState.Loading)
    val uiState: StateFlow<FileInfoUiState> = _uiState.asStateFlow()

    fun loadFileInfo(localPath: String) {
        Log.d(TAG, "loadFileInfo: start, localPath=$localPath")
        viewModelScope.launch {
            if (localPath.isBlank()) {
                Log.w(TAG, "loadFileInfo: localPath is blank")
                _uiState.value = FileInfoUiState.Error("ファイルパスが指定されていません")
                return@launch
            }

            Log.d(TAG, "loadFileInfo: calling localRepo.getFileInfo($localPath)")
            val selectedFile = localRepo.getFileInfo(localPath)
            Log.d(TAG, "loadFileInfo: getFileInfo returned: $selectedFile")
            if (selectedFile != null) {
                _uiState.value = FileInfoUiState.Success(selectedFile)
                Log.d(TAG, "loadFileInfo: state set to Success")
            } else {
                Log.w(TAG, "loadFileInfo: file not found at $localPath")
                _uiState.value = FileInfoUiState.Error("ファイルが見つかりません")
            }
        }
    }
}
