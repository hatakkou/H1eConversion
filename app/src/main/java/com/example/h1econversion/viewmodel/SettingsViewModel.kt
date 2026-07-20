package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.data.SettingsStore
import com.example.h1econversion.model.BitratePreset
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
 * 設定画面の UI 状態。
 */
sealed interface SettingsUiState {
    /** 設定表示中 */
    data class Ready(
        val settings: ConversionSettings,
        /** インポート済みファイルが存在するか */
        val hasImportedFiles: Boolean,
        /** 変換キャッシュが存在するか */
        val hasConversionCache: Boolean,
        /** インポート済みファイルの総サイズ（バイト） */
        val importedFilesSizeBytes: Long,
        /** 変換キャッシュの総サイズ（バイト） */
        val cacheSizeBytes: Long,
        /** データ削除の確認ダイアログ表示中か */
        val showDeleteConfirmation: Boolean = false,
        /** 操作結果メッセージ（null の場合は非表示） */
        val resultMessage: String? = null,
    ) : SettingsUiState
}

/**
 * 設定画面の ViewModel。
 *
 * DataStore 経由で設定の読み書きを行い、ユーザーデータ削除機能も提供します。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val settingsStore = SettingsStore.getInstance(getApplication())
    private val localFileRepo = LocalFileRepository(getApplication())

    private val _uiState = MutableStateFlow<SettingsUiState>(
        // 初期値はデフォルト設定 + ストレージ未計算
        SettingsUiState.Ready(
            settings = ConversionSettings(),
            hasImportedFiles = false,
            hasConversionCache = false,
            importedFilesSizeBytes = 0L,
            cacheSizeBytes = 0L,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // 設定の変更を監視して UI に反映
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                refreshStorageInfo(settings)
            }
        }
    }

    // ---- 設定更新 ----

    /** コーデックを更新。コンテナが非互換の場合は自動調整します。 */
    fun selectCodec(codec: CodecType) {
        viewModelScope.launch {
            // 現在のコンテナが新しいコーデックと互換かチェック
            val currentSettings = settingsStore.settingsFlow.first()
            val compatibleContainer = getCompatibleContainer(codec, currentSettings.container)
            settingsStore.updateCodec(codec)
            if (compatibleContainer != currentSettings.container) {
                settingsStore.updateContainer(compatibleContainer)
            }
        }
    }

    /** 指定されたコーデックと互換性のあるコンテナを返します。 */
    private fun getCompatibleContainer(codec: CodecType, currentContainer: ContainerType): ContainerType {
        return when (codec) {
            CodecType.PCM_WAV -> ContainerType.WAV
            CodecType.AAC -> {
                // AAC 互換のコンテナのみ許可
                if (currentContainer == ContainerType.WAV) ContainerType.M4A
                else currentContainer
            }
        }
    }

    /** ビットレートを更新 */
    fun selectBitrate(bitrate: BitratePreset) {
        viewModelScope.launch {
            settingsStore.updateBitrate(bitrate)
        }
    }

    /** コンテナ（拡張子）を更新 */
    fun selectContainer(container: ContainerType) {
        viewModelScope.launch {
            settingsStore.updateContainer(container)
        }
    }

    // ---- データ削除 ----

    /** 削除確認ダイアログを表示 */
    fun showDeleteConfirmation() {
        val s = _uiState.value
        if (s is SettingsUiState.Ready) {
            _uiState.value = s.copy(showDeleteConfirmation = true)
        }
    }

    /** 削除確認ダイアログを非表示 */
    fun dismissDeleteConfirmation() {
        val s = _uiState.value
        if (s is SettingsUiState.Ready) {
            _uiState.value = s.copy(showDeleteConfirmation = false)
        }
    }

    /**
     * インポート済みファイルと変換キャッシュを全削除します。
     *
     * - recordings/ ディレクトリ内の全 WAV + .meta ファイル
     * - cache/converted/ ディレクトリ内の全変換済みファイル
     */
    fun deleteAllUserData() {
        viewModelScope.launch {
            try {
                var deletedRecordings = 0
                var deletedCache = 0

                withContext(Dispatchers.IO) {
                    // インポート済み録音ファイル削除
                    deletedRecordings = localFileRepo.deleteAllRecordings()
                    Log.i(TAG, "Deleted $deletedRecordings recording file(s)")

                    // 変換キャッシュ削除
                    val app = getApplication<Application>()
                    val cacheDir = app.cacheDir
                    if (cacheDir != null) {
                        val convertedDir = File(cacheDir, "converted")
                        if (convertedDir.exists()) {
                            val cacheFiles = convertedDir.listFiles()
                            if (cacheFiles != null) {
                                for (file in cacheFiles) {
                                    if (file.delete()) {
                                        deletedCache++
                                    }
                                }
                            }
                        }
                    }
                    Log.i(TAG, "Deleted $deletedCache cache file(s)")
                }

                // UI 更新
                refreshStorageInfo(
                    // 現在の設定を維持したまま再読込
                    settingsStore.settingsFlow.first()
                )

                val s = _uiState.value
                if (s is SettingsUiState.Ready) {
                    _uiState.value = s.copy(
                        showDeleteConfirmation = false,
                        resultMessage = buildDeleteResultMessage(deletedRecordings, deletedCache),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete user data", e)
                val s = _uiState.value
                if (s is SettingsUiState.Ready) {
                    _uiState.value = s.copy(
                        showDeleteConfirmation = false,
                        resultMessage = "データ削除に失敗しました: ${e.message}",
                    )
                }
            }
        }
    }

    /** 結果メッセージをクリア */
    fun clearResultMessage() {
        val s = _uiState.value
        if (s is SettingsUiState.Ready) {
            _uiState.value = s.copy(resultMessage = null)
        }
    }

    // ---- 内部ヘルパー ----

    /**
     * ストレージ使用状況を再計算して UI 状態を更新します。
     */
    private suspend fun refreshStorageInfo(settings: ConversionSettings) {
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()

            // インポート済みファイルの情報
            val recordingsDir = File(app.filesDir, "recordings")
            val recordingsFiles: Array<java.io.File> = recordingsDir.listFiles()
                ?: emptyArray()
            val filteredRecordings = recordingsFiles.filter { !it.name.endsWith(".tmp") }
            val importedSize = filteredRecordings.sumOf { it.length() }

            // 変換キャッシュの情報
            val cacheDir = app.cacheDir
            val convertedDir = if (cacheDir != null) File(cacheDir, "converted") else null
            val cacheFiles: Array<java.io.File> = convertedDir?.listFiles()
                ?: emptyArray()
            val cacheSize = cacheFiles.sumOf { it.length() }

            // 現在の UI 状態から showDeleteConfirmation / resultMessage を引き継ぐ
            val current = _uiState.value
            _uiState.value = (current as? SettingsUiState.Ready)?.copy(
                settings = settings,
                hasImportedFiles = filteredRecordings.isNotEmpty(),
                hasConversionCache = cacheFiles.isNotEmpty(),
                importedFilesSizeBytes = importedSize,
                cacheSizeBytes = cacheSize,
            ) ?: SettingsUiState.Ready(
                settings = settings,
                hasImportedFiles = filteredRecordings.isNotEmpty(),
                hasConversionCache = cacheFiles.isNotEmpty(),
                importedFilesSizeBytes = importedSize,
                cacheSizeBytes = cacheSize,
            )
        }
    }

    /**
     * 削除結果メッセージを構築します。
     */
    private fun buildDeleteResultMessage(recordings: Int, cache: Int): String {
        if (recordings == 0 && cache == 0) {
            return "削除対象のデータがありませんでした"
        }
        val parts = mutableListOf<String>()
        if (recordings > 0) parts.add("インポート済みファイル ${recordings}件")
        if (cache > 0) parts.add("変換キャッシュ ${cache}件")
        return parts.joinToString("、") + " を削除しました"
    }
}
