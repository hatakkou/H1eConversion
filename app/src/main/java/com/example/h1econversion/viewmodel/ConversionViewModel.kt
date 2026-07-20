package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.audio.MediaCodecConverter
import com.example.h1econversion.audio.MediaCodecConverter.Result
import com.example.h1econversion.data.SettingsStore
import com.example.h1econversion.model.CodecType
import com.example.h1econversion.model.ContainerType
import com.example.h1econversion.model.ConversionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
        val progressLogs: List<String>,
    ) : ConversionUiState

    /** 変換成功 */
    data class Success(
        val inputFileName: String,
        val gainPercent: Int,
        val outputFile: File,
        val progressLogs: List<String>,
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

    /** 変換ログを保持（変換後に確認できるようフィールド化） */
    private val logBuffer = mutableListOf<String>()

    /** 設定ストア */
    private val settingsStore = SettingsStore.getInstance(getApplication())

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
        // UUIDプレフィックスを除去した表示用ファイル名を取得
        val displayName = LocalFileRepository.getDisplayName(inputPath)

        // 設定を読み取って出力フォーマット表示文字列を構築
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val outputFormat = buildOutputFormatString(settings)
            _uiState.value = ConversionUiState.Idle(
                inputFileName = displayName,
                inputFormat = inputFormat,
                gainPercent = gainPercent,
                outputFormat = outputFormat,
            )
        }
    }

    /**
     * 設定から出力フォーマットの表示文字列を構築します。
     */
    private fun buildOutputFormatString(settings: ConversionSettings): String {
        return when (settings.codec) {
            CodecType.AAC -> {
                val containerLabel = when (settings.container) {
                    ContainerType.M4A -> "M4A"
                    ContainerType.AAC_RAW -> "AAC (Raw)"
                    else -> settings.container.extension.uppercase()
                }
                "AAC / ${settings.bitrate.bps / 1000}kbps / $containerLabel"
            }
            CodecType.PCM_WAV -> "PCM / WAV（無圧縮）"
        }
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
        // UUIDプレフィックスを除去した表示用ファイル名を取得
        val displayName = LocalFileRepository.getDisplayName(inputPath)
        val baseName = displayName.substringBeforeLast(".")
        val gainPercent = (gain * 100f).toInt()

        Log.d(TAG, "Starting conversion: $inputPath (gain=$gain)")

        // 初期ログメッセージ
        logBuffer.clear()
        logBuffer.add("変換を開始します...")

        viewModelScope.launch {
            try {
                // 設定を読み取り
                val settings = settingsStore.settingsFlow.first()
                val extension = settings.container.extension
                val bitrate = settings.bitrate.bps
                val codecType = settings.codec

                // 出力ファイル名: "元ファイル名_gainXXX.{extension}"
                val outputName = "${baseName}_gain${gainPercent}.$extension"
                val outputPath = File(outputDir, outputName).absolutePath

                Log.d(TAG, "Output: $outputPath (codec=$codecType, bitrate=$bitrate, ext=$extension)")

                _uiState.value = ConversionUiState.Converting(
                    inputFileName = displayName,
                    gainPercent = gainPercent,
                    progressLogs = logBuffer.toList(),
                )

                val result = withContext(Dispatchers.IO) {
                    MediaCodecConverter.convert(
                        inputPath = inputPath,
                        outputPath = outputPath,
                        gain = gain,
                        bitrate = bitrate,
                        codecType = codecType,
                        containerExtension = extension,
                    ) { logMessage ->
                        // MutableStateFlowはスレッドセーフ。IOスレッドから直接更新
                        logBuffer.add(logMessage)
                        val s = _uiState.value
                        if (s is ConversionUiState.Converting) {
                            _uiState.value = s.copy(progressLogs = logBuffer.toList())
                        }
                    }
                }
                when (result) {
                    is Result.Success -> {
                        Log.d(TAG, "Conversion succeeded: ${result.outputFile.absolutePath}")
                        _uiState.value = ConversionUiState.Success(
                            inputFileName = displayName,
                            gainPercent = gainPercent,
                            outputFile = result.outputFile,
                            progressLogs = logBuffer.toList(),
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
