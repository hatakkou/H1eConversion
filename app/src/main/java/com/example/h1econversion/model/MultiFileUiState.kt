package com.example.h1econversion.model

/**
 * 複数ファイルゲイン調整画面の UI 状態。
 */

/**
 * 1ファイル分のゲイン調整アイテム。
 */
data class FileGainItem(
    val localPath: String,
    val fileName: String,
    val gain: Float,                // リニアゲイン（1.0 = 100%）
    val isSelected: Boolean = true,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0,
    val durationMs: Long = 0,
    val wavInfo: WavInfoData? = null,
    /** 波形表示用データ（mini表示用、幅は ~400px） */
    val waveformData: WaveformUiData? = null,
)

/**
 * 複数ファイルゲイン調整画面の UI 状態。
 */
sealed interface MultiFileUiState {
    /** ファイル情報読み込み中 */
    data object Loading : MultiFileUiState

    /** 準備完了 */
    data class Ready(
        val files: List<FileGainItem>,
        val batchGain: Float,       // 一括ゲイン値（スライダー表示用）
        val activePlaybackIndex: Int = -1,  // 現在再生中のファイルインデックス（-1 = なし）
    ) : MultiFileUiState

    /** エラー */
    data class Error(val message: String) : MultiFileUiState
}
