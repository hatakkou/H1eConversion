package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.AudioPlayer
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.audio.PlayerState
import com.example.h1econversion.audio.WavDecoder
import com.example.h1econversion.audio.WaveformGenerator
import com.example.h1econversion.model.FileGainItem
import com.example.h1econversion.model.MultiFileUiState
import com.example.h1econversion.model.WavInfoData
import com.example.h1econversion.model.WaveformUiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 複数ファイルのゲイン調整・試聴を管理する ViewModel。
 */
class MultiFileViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MultiFileViewModel"
    }

    private val player = AudioPlayer()

    private val _uiState = MutableStateFlow<MultiFileUiState>(MultiFileUiState.Loading)
    val uiState: StateFlow<MultiFileUiState> = _uiState.asStateFlow()

    /** ファイルパス一覧（loadFiles で設定） */
    private var filePaths: List<String> = emptyList()

    /** 各ファイルのゲイン値 */
    private var fileGains: MutableList<Float> = mutableListOf()

    /** 各ファイルの選択状態 */
    private var fileSelections: MutableList<Boolean> = mutableListOf()

    /** 各ファイルの WavInfo（再生用） */
    private var fileWavInfos: MutableList<com.example.h1econversion.audio.WavInfo?> = mutableListOf()

    /** 各ファイルの波形データ（ミニ表示用） */
    private var fileWaveformData: MutableList<WaveformUiData?> = mutableListOf()

    /** 現在再生中のファイルインデックス（-1 = なし） */
    private var activePlaybackIndex: Int = -1

    /** プレイヤー状態監視 Job */
    private var playerObserverJob: Job? = null

    /**
     * 複数ファイルを読み込み、UI 状態を初期化します。
     * 各ファイルの WAV ヘッダを解析し、再生時間を取得します。
     *
     * @param paths ローカルファイルパスのリスト
     */
    fun loadFiles(paths: List<String>) {
        Log.d(TAG, "loadFiles: start, count=${paths.size}")
        if (paths.isEmpty()) {
            _uiState.value = MultiFileUiState.Error("ファイルが選択されていません")
            return
        }

        filePaths = paths
        fileGains = MutableList(paths.size) { 1.0f }
        fileSelections = MutableList(paths.size) { true }
        fileWavInfos = MutableList(paths.size) { null }
        fileWaveformData = MutableList(paths.size) { null }

        _uiState.value = MultiFileUiState.Loading

        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    paths.mapIndexed { index, path ->
                        val fileName = LocalFileRepository.getDisplayName(path)
                        val wavInfo = try {
                            WavDecoder.parseHeader(path)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse WAV header for $path", e)
                            null
                        }
                        fileWavInfos[index] = wavInfo

                        // 波形データ生成（ミニ表示用、幅 400px）
                        val waveformData = if (wavInfo != null) {
                            try {
                                val wf = WaveformGenerator.generateFromFile(path, wavInfo, outputWidth = 400)
                                WaveformUiData(
                                    minValues = wf.minValues.toList(),
                                    maxValues = wf.maxValues.toList(),
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to generate waveform for $path", e)
                                null
                            }
                        } else null
                        fileWaveformData[index] = waveformData

                        FileGainItem(
                            localPath = path,
                            fileName = fileName,
                            gain = 1.0f,
                            isSelected = true,
                            durationMs = wavInfo?.durationMs ?: 0L,
                            wavInfo = if (wavInfo != null) WavInfoData(
                                sampleRate = wavInfo.sampleRate,
                                numChannels = wavInfo.numChannels.toInt(),
                                bitsPerSample = wavInfo.bitsPerSample.toInt(),
                                durationMs = wavInfo.durationMs,
                            ) else null,
                            waveformData = waveformData,
                        )
                    }
                }

                _uiState.value = MultiFileUiState.Ready(
                    files = items,
                    batchGain = 1.0f,
                )

                // プレイヤー状態の監視を開始
                startPlayerObserver()
            } catch (e: Exception) {
                Log.e(TAG, "loadFiles: failed", e)
                _uiState.value = MultiFileUiState.Error(
                    e.message ?: "ファイルの読み込みに失敗しました"
                )
            }
        }
    }

    /**
     * プレイヤーの状態変化を監視し、UI に反映します。
     */
    private fun startPlayerObserver() {
        playerObserverJob?.cancel()
        playerObserverJob = viewModelScope.launch {
            combine(
                player.state,
                player.currentFrame,
                player.gain,
            ) { state, frame, _ ->
                Triple(state, frame, 0f)
            }.collect { (state, frame, _) ->
                val current = _uiState.value
                if (current !is MultiFileUiState.Ready) return@collect

                val playingIndex = activePlaybackIndex

                // 再生終了時
                if (state is PlayerState.Finished) {
                    if (playingIndex >= 0 && playingIndex < current.files.size) {
                        val updated = current.files.toMutableList()
                        updated[playingIndex] = updated[playingIndex].copy(
                            isPlaying = false,
                            playbackPositionMs = updated[playingIndex].durationMs,
                        )
                        _uiState.value = current.copy(
                            files = updated,
                            activePlaybackIndex = -1,
                        )
                        activePlaybackIndex = -1
                    }
                    return@collect
                }

                // 再生位置の更新
                if (playingIndex >= 0 && playingIndex < current.files.size) {
                    val wavInfo = fileWavInfos.getOrNull(playingIndex)
                    val positionMs = if (wavInfo != null && wavInfo.sampleRate > 0) {
                        frame * 1000L / wavInfo.sampleRate
                    } else 0L

                    val isPlaying = state is PlayerState.Playing
                    val updated = current.files.toMutableList()
                    updated[playingIndex] = updated[playingIndex].copy(
                        isPlaying = isPlaying,
                        playbackPositionMs = positionMs,
                    )
                    _uiState.value = current.copy(files = updated)
                }
            }
        }
    }

    /**
     * 指定ファイルの再生/停止をトグルします。
     * 別のファイルが再生中の場合は停止してから切り替えます。
     */
    fun togglePlayPause(fileIndex: Int) {
        val current = _uiState.value
        if (current !is MultiFileUiState.Ready) return
        if (fileIndex >= filePaths.size) return

        val path = filePaths[fileIndex]
        val wavInfo = fileWavInfos[fileIndex] ?: return

        // 現在のゲインをプレイヤーに設定
        player.setGain(fileGains[fileIndex])

        if (activePlaybackIndex == fileIndex) {
            // 同じファイル：再生中なら停止、停止中なら再生
            when (player.state.value) {
                is PlayerState.Playing -> {
                    viewModelScope.launch { player.stop() }
                }
                else -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        player.play(path, wavInfo)
                    }
                }
            }
        } else {
            // 別のファイルに切り替え
            viewModelScope.launch {
                player.stop()
                // stop() は suspending 関数のため、その間に UI 状態が変化している可能性がある。
                // 最新の状態を再取得してから前のファイルの再生状態をリセットする。
                val latestState = _uiState.value
                val prevIndex = activePlaybackIndex
                if (prevIndex >= 0 && latestState is MultiFileUiState.Ready && prevIndex < latestState.files.size) {
                    val updated = latestState.files.toMutableList()
                    updated[prevIndex] = updated[prevIndex].copy(
                        isPlaying = false,
                        playbackPositionMs = 0,
                    )
                    _uiState.value = latestState.copy(files = updated)
                }

                activePlaybackIndex = fileIndex
                // 新しいファイルのゲインを設定
                player.setGain(fileGains[fileIndex])
                // 新しい activePlaybackIndex を UI に反映してから再生開始
                val stateAfterSwitch = _uiState.value
                if (stateAfterSwitch is MultiFileUiState.Ready) {
                    _uiState.value = stateAfterSwitch.copy(activePlaybackIndex = fileIndex)
                }
                withContext(Dispatchers.IO) {
                    player.play(path, wavInfo)
                }
            }
        }
    }

    /**
     * 再生を停止します。
     */
    fun stopPlayback() {
        val stoppedIndex = activePlaybackIndex
        viewModelScope.launch {
            player.stop()
            // 停止完了後に UI 状態を更新（停止した行の再生状態をリセット）
            val latestState = _uiState.value
            if (stoppedIndex >= 0 && latestState is MultiFileUiState.Ready && stoppedIndex < latestState.files.size) {
                val updated = latestState.files.toMutableList()
                updated[stoppedIndex] = updated[stoppedIndex].copy(
                    isPlaying = false,
                    playbackPositionMs = 0,
                )
                _uiState.value = latestState.copy(
                    files = updated,
                    activePlaybackIndex = -1,
                )
            } else if (latestState is MultiFileUiState.Ready) {
                _uiState.value = latestState.copy(activePlaybackIndex = -1)
            }
            activePlaybackIndex = -1
        }
    }

    /**
     * 現在再生中のファイルの再生位置をシークします。
     *
     * @param positionMs シーク先の位置（ミリ秒）
     */
    fun seekTo(positionMs: Long) {
        if (activePlaybackIndex < 0) return
        viewModelScope.launch {
            player.seekTo(positionMs)
        }
    }

    /**
     * 指定ファイルをアクティブにしてからシークします。
     * 既にアクティブなファイルの場合は直接シークし、
     * 別ファイルの場合は再生開始後にシークします。
     *
     * @param fileIndex 対象ファイルのインデックス
     * @param positionMs シーク先の位置（ミリ秒）
     */
    fun seekFile(fileIndex: Int, positionMs: Long) {
        val current = _uiState.value
        if (current !is MultiFileUiState.Ready) return
        if (fileIndex >= filePaths.size) return

        if (activePlaybackIndex == fileIndex) {
            // 既にアクティブなファイル → 直接シーク
            seekTo(positionMs)
            return
        }

        // 別のファイル → アクティブにしてからシーク
        val path = filePaths[fileIndex]
        val wavInfo = fileWavInfos[fileIndex] ?: return

        viewModelScope.launch {
            player.stop()
            val latestState = _uiState.value
            val prevIndex = activePlaybackIndex
            if (prevIndex >= 0 && latestState is MultiFileUiState.Ready && prevIndex < latestState.files.size) {
                val updated = latestState.files.toMutableList()
                updated[prevIndex] = updated[prevIndex].copy(
                    isPlaying = false,
                    playbackPositionMs = 0,
                )
                _uiState.value = latestState.copy(files = updated)
            }

            activePlaybackIndex = fileIndex
            player.setGain(fileGains[fileIndex])
            withContext(Dispatchers.IO) {
                player.play(path, wavInfo)
            }
            // 再生開始後にシーク
            player.seekTo(positionMs)
        }
    }

    /**
     * 指定ファイルのゲインを設定します。
     */
    fun setFileGain(fileIndex: Int, gain: Float) {
        if (fileIndex in fileGains.indices) {
            fileGains[fileIndex] = gain.coerceIn(0f, 5.0f)
            // 再生中なら即時反映
            if (activePlaybackIndex == fileIndex) {
                player.setGain(fileGains[fileIndex])
            }
            // UI 更新
            updateFileItem(fileIndex) { it.copy(gain = fileGains[fileIndex]) }
        }
    }

    /**
     * 一括ゲインを設定します（全ファイルに適用）。
     */
    fun setBatchGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 5.0f)
        for (i in fileGains.indices) {
            fileGains[i] = clamped
        }
        // 再生中なら即時反映
        if (activePlaybackIndex >= 0 && activePlaybackIndex < fileGains.size) {
            player.setGain(fileGains[activePlaybackIndex])
        }
        // UI 一括更新
        val current = _uiState.value
        if (current is MultiFileUiState.Ready) {
            _uiState.value = current.copy(
                batchGain = clamped,
                files = current.files.mapIndexed { index, item ->
                    item.copy(gain = fileGains[index])
                },
            )
        }
    }

    /**
     * 指定ファイルの選択状態をトグルします。
     */
    fun toggleFileSelection(fileIndex: Int) {
        if (fileIndex in fileSelections.indices) {
            fileSelections[fileIndex] = !fileSelections[fileIndex]
            updateFileItem(fileIndex) { it.copy(isSelected = fileSelections[fileIndex]) }
        }
    }

    /**
     * 全ファイルの選択/解除をトグルします。
     */
    fun toggleSelectAll() {
        val current = _uiState.value
        if (current !is MultiFileUiState.Ready) return

        val allSelected = fileSelections.all { it }
        val newValue = !allSelected
        for (i in fileSelections.indices) {
            fileSelections[i] = newValue
        }

        _uiState.value = current.copy(
            files = current.files.mapIndexed { index, item ->
                item.copy(isSelected = fileSelections[index])
            },
        )
    }

    /**
     * 選択中のファイルパスとゲインのペア一覧を取得します。
     */
    fun getSelectedFilesWithGain(): List<Pair<String, Float>> {
        return filePaths.mapIndexedNotNull { index, path ->
            if (fileSelections.getOrElse(index) { false }) {
                path to fileGains.getOrElse(index) { 1.0f }
            } else null
        }
    }

    /**
     * 選択中のファイル数を取得します。
     */
    fun getSelectedCount(): Int = fileSelections.count { it }

    /**
     * 指定インデックスのファイル情報を更新します。
     */
    private fun updateFileItem(index: Int, transform: (FileGainItem) -> FileGainItem) {
        val current = _uiState.value
        if (current is MultiFileUiState.Ready && index in current.files.indices) {
            val updated = current.files.toMutableList()
            updated[index] = transform(updated[index])
            _uiState.value = current.copy(files = updated)
        }
    }

    private val cleanupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    override fun onCleared() {
        super.onCleared()
        playerObserverJob?.cancel()
        // viewModelScope は既にキャンセルされているため、独立したスコープで解放する
        cleanupScope.launch {
            player.stop()
            player.release()
        }
    }
}
