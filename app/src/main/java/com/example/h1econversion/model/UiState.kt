package com.example.h1econversion.model

/**
 * UI state sealed interfaces for all screens.
 */

sealed interface DeviceFilesUiState {
    data object Idle : DeviceFilesUiState
    data object CheckingDevice : DeviceFilesUiState
    data object DeviceNotFound : DeviceFilesUiState
    data object PermissionDenied : DeviceFilesUiState
    data object ScanningFiles : DeviceFilesUiState
    data class FilesLoaded(val files: List<RecordingFile>) : DeviceFilesUiState
    data class Error(val message: String) : DeviceFilesUiState
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Copying(val fileName: String) : ImportUiState
    data class Success(val selectedFile: SelectedFile) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

sealed interface FileInfoUiState {
    data object Loading : FileInfoUiState
    data class Success(val selectedFile: SelectedFile) : FileInfoUiState
    data class Error(val message: String) : FileInfoUiState
}

sealed interface WaveformUiState {
    data object Loading : WaveformUiState
    data class Ready(
        val wavInfo: WavInfoData,
        val waveformData: WaveformUiData,
        val playerState: PlaybackState,
        val currentPositionMs: Long,
        val volume: Float,
    ) : WaveformUiState
    data class Error(val message: String) : WaveformUiState
}

enum class PlaybackState {
    Stopped, Playing, Paused, Finished
}

/**
 * Subset of WavInfo exposed to the UI layer.
 */
data class WavInfoData(
    val sampleRate: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val durationMs: Long,
)

/**
 * Subset of WaveformData exposed to the UI layer.
 * Uses List<Float> for Compose state compatibility.
 */
data class WaveformUiData(
    val minValues: List<Float>,
    val maxValues: List<Float>,
)
