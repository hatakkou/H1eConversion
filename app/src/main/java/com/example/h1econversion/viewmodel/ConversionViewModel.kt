package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.MediaCodecConverter
import com.example.h1econversion.audio.MediaCodecConverter.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 変換画面の UI 状態。
 */
sealed interface ConversionUiState {
    /** 変換待ち（パラメータ確認中） */
    data class Idle(
        val inputFileName: String,
        val inputFormat: String,
        val gainPercent: Int,
        val outputFormat: String,
    ) : ConversionUiState

    /** 変換実行中 */
    data class Converting(
        val inputFileName: String,
        val gainPercent: Int,
        val progressLog: String,
    ) : ConversionUiState

    /** 変換成功 */
    data class Success(
        val inputFileName: String,
        val gainPercent: Int,
        val outputFile: File,
    ) : ConversionUiState

    /** 変換失敗 */
    data class Error(
        val message: String,
    ) : ConversionUiState
}

/**
 * WAV → MP3 変換画面の ViewModel。
 */
class ConversionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ConversionViewModel"
    }

    private val _uiState = MutableStateFlow<ConversionUiState>(
        ConversionUiState.Idle("", "", 100, "")
    )
    val uiState: StateFlow<ConversionUiState> = _uiState.asStateFlow()

    /** 現在の入力パスとゲインを保持（リトライ用） */
    private var currentInputPath: String = ""
    private var currentInputFormat: String = ""
    private var currentGain: Float = 1.0f

    /**
     * 変換パラメータを設定し、Idle 状態を表示します。
     *
     * @param inputPath 入力 WAV ファイルのパス
     * @param inputFormat 入力フォーマットの表示文字列（例: "96000 Hz / 2 ch / 32 bit float"）
     * @param gain リニアゲイン倍率（1.0 = 100%）
     */
    fun prepare(inputPath: String, inputFormat: String, gain: Float) {
        currentInputPath = inputPath
        currentInputFormat = inputFormat
        currentGain = gain

        val gainPercent = (gain * 100f).toInt()
        _uiState.value = ConversionUiState.Idle(
            inputFileName = File(inputPath).name,
            inputFormat = inputFormat,
            gainPercent = gainPercent,
            outputFormat = "AAC / ${MediaCodecConverter.AAC_BITRATE / 1000}kbps / M4A",
        )
    }

    /**
     * 変換を開始します。
     */
    fun startConversion() {
        val inputPath = currentInputPath
        val gain = currentGain
        if (inputPath.isBlank()) return

        val inputFile = File(inputPath)
        val cacheDir = getApplication<Application>().cacheDir
            ?: run {
                _uiState.value = ConversionUiState.Error("キャッシュディレクトリにアクセスできません")
                return
            }
        val outputDir = cacheDir.resolve("converted")
        val baseName = inputFile.nameWithoutExtension
        val gainPercent = (gain * 100f).toInt()

        // 出力ファイル名: "元ファイル名_gainXXX.mp3"
        val outputName = "${baseName}_gain${gainPercent}.m4a"
        val outputPath = File(outputDir, outputName).absolutePath

        Log.d(TAG, "Starting conversion: $inputPath -> $outputPath (gain=$gain)")

        _uiState.value = ConversionUiState.Converting(
            inputFileName = inputFile.name,
            gainPercent = gainPercent,
            progressLog = "変換を開始しています…",
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    MediaCodecConverter.convert(inputPath, outputPath, gain)
                }
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Conversion succeeded: ${result.outputFile.absolutePath}")
                        _uiState.value = ConversionUiState.Success(
                            inputFileName = inputFile.name,
                            gainPercent = gainPercent,
                            outputFile = result.outputFile,
                        )
                    }
                    is Result.Error -> {
                        Log.e(TAG, "Conversion failed: ${result.message}")
                        _uiState.value = ConversionUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed with exception", e)
                _uiState.value = ConversionUiState.Error("変換中に例外が発生しました: ${e.message}")
            }
        }
    }

    /**
     * リトライ（Idle 状態に戻す）
     */
    fun retry() {
        prepare(currentInputPath, currentInputFormat, currentGain)
    }
}
