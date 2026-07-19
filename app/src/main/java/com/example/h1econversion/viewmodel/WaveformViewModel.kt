package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.AudioPlayer
import com.example.h1econversion.audio.PlayerState
import com.example.h1econversion.audio.WavDecoder
import com.example.h1econversion.audio.WaveformGenerator
import com.example.h1econversion.model.PlaybackState
import com.example.h1econversion.model.WavInfoData
import com.example.h1econversion.model.WaveformUiData
import com.example.h1econversion.model.WaveformUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class WaveformViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WaveformViewModel"
    }

    private val player = AudioPlayer()

    private val _uiState = MutableStateFlow<WaveformUiState>(WaveformUiState.Loading)
    val uiState: StateFlow<WaveformUiState> = _uiState.asStateFlow()

    private var currentFilePath: String = ""
    private var currentWavInfo: com.example.h1econversion.audio.WavInfo? = null
    private var playerObserverJob: Job? = null
    /** 読み込みジョブ（新規読込時にキャンセル） */
    private var loadJob: Job? = null

    /**
     * WAV ファイルを読み込み、波形データを生成します。
     * 新しい読み込みが開始されると前回の読み込みジョブはキャンセルされます。
     */
    fun loadFile(localPath: String) {
        Log.d(TAG, "loadFile: start, localPath=$localPath")
        if (localPath.isBlank()) {
            Log.w(TAG, "loadFile: localPath is blank")
            _uiState.value = WaveformUiState.Error("ファイルパスが指定されていません")
            return
        }

        // 前回の読み込みジョブとプレイヤー監視をキャンセル
        loadJob?.cancel()
        loadJob = null
        playerObserverJob?.cancel()
        playerObserverJob = null

        val requestedPath = localPath

        loadJob = viewModelScope.launch {
            Log.d(TAG, "loadFile: coroutine launched, setting Loading state")
            _uiState.value = WaveformUiState.Loading

            val result = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "loadFile: parsing WAV header for $requestedPath")
                    val wavInfo = WavDecoder.parseHeader(requestedPath)
                    if (wavInfo == null) {
                        Log.e(TAG, "loadFile: WavDecoder.parseHeader returned null")
                        return@withContext Result.failure<Pair<com.example.h1econversion.audio.WavInfo, com.example.h1econversion.audio.WaveformData>>(
                            IllegalArgumentException("WAVファイルを解析できません")
                        )
                    }

                    Log.d(TAG, "loadFile: WAV header parsed: $wavInfo")
                    Log.d(TAG, "loadFile: generating waveform data...")
                    val waveformData = WaveformGenerator.generateFromFile(requestedPath, wavInfo)
                    Log.d(TAG, "loadFile: waveform data generated, outputWidth=${waveformData.outputWidth}")
                    Result.success(wavInfo to waveformData)
                } catch (e: CancellationException) {
                    Log.d(TAG, "loadFile: cancelled")
                    // キャンセルは再スロー（コルーチンを適切に終了させる）
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load WAV file", e)
                    Result.failure<Pair<com.example.h1econversion.audio.WavInfo, com.example.h1econversion.audio.WaveformData>>(e)
                }
            }

            // 結果が現在のリクエストパスと一致する場合のみ反映
            result.fold(
                onSuccess = { (wavInfo, waveformData) ->
                    currentFilePath = requestedPath
                    currentWavInfo = wavInfo
                    player.load(wavInfo)
                    _uiState.value = WaveformUiState.Ready(
                        wavInfo = WavInfoData(
                            sampleRate = wavInfo.sampleRate,
                            numChannels = wavInfo.numChannels.toInt(),
                            bitsPerSample = wavInfo.bitsPerSample.toInt(),
                            durationMs = wavInfo.durationMs,
                        ),
                        waveformData = WaveformUiData(
                            minValues = waveformData.minValues.toList(),
                            maxValues = waveformData.maxValues.toList(),
                        ),
                        playerState = PlaybackState.Stopped,
                        currentPositionMs = 0L,
                        volume = player.gain.value,
                    )
                },
                onFailure = { error ->
                    // キャンセル時はエラー表示しない
                    if (error is CancellationException) throw error
                    _uiState.value = WaveformUiState.Error(
                        error.message ?: "不明なエラーが発生しました"
                    )
                },
            )
        }

        // Observe player state and position (replace previous observer)
        playerObserverJob = viewModelScope.launch {
            combine(
                player.state,
                player.currentFrame,
                player.gain,
            ) { state, frame, gain ->
                val info = currentWavInfo
                val positionMs = if (info != null && info.sampleRate > 0) {
                    frame * 1000L / info.sampleRate
                } else 0L

                val playbackState = when (state) {
                    is PlayerState.Idle -> PlaybackState.Stopped
                    is PlayerState.Playing -> PlaybackState.Playing
                    is PlayerState.Paused -> PlaybackState.Paused
                    is PlayerState.Finished -> PlaybackState.Finished
                }

                Triple(playbackState, positionMs, gain)
            }.collect { (playbackState, positionMs, gain) ->
                val current = _uiState.value
                if (current is WaveformUiState.Ready) {
                    _uiState.value = current.copy(
                        playerState = playbackState,
                        currentPositionMs = positionMs,
                        volume = gain,
                    )
                }
            }
        }
    }

    fun play() {
        val ui = _uiState.value
        if (ui !is WaveformUiState.Ready) return
        val filePath = currentFilePath
        val wavInfo = currentWavInfo
        if (filePath.isBlank() || wavInfo == null) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                player.play(filePath, wavInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            }
        }
    }

    fun pause() {
        viewModelScope.launch { player.pause() }
    }

    fun stop() {
        viewModelScope.launch { player.stop() }
    }

    fun setVolume(value: Float) {
        player.setGain(value)
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { player.seekTo(positionMs) }
    }

    fun togglePlayPause() {
        when (_uiState.value) {
            is WaveformUiState.Ready -> {
                val state = (_uiState.value as WaveformUiState.Ready).playerState
                when (state) {
                    PlaybackState.Playing -> pause()
                    PlaybackState.Paused, PlaybackState.Finished -> play()
                    PlaybackState.Stopped -> play()
                }
            }
            else -> {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        playerObserverJob?.cancel()
        runBlocking { player.release() }
    }
}
