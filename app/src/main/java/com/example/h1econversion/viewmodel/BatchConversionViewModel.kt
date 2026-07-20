package com.example.h1econversion.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.h1econversion.audio.LocalFileRepository
import com.example.h1econversion.audio.MediaCodecConverter
import com.example.h1econversion.data.SettingsStore
import com.example.h1econversion.model.CodecType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 一括変換のファイル情報。
 */
data class BatchFileInfo(
    val fileName: String,
    val gainPercent: Int,
)

/**
 * 個別ファイルの変換状態。
 */
sealed interface FileStatus {
    /** 変換待ち（セマフォ解放待ち） */
    data object Pending : FileStatus
    /** 変換実行中 */
    data class Converting(val message: String) : FileStatus
    /** 変換完了 */
    data object Completed : FileStatus
    /** 変換失敗 */
    data class Failed(val reason: String) : FileStatus
}

/**
 * ファイルごとの進捗情報。
 */
data class FileProgress(
    val fileName: String,
    val gainPercent: Int,
    val status: FileStatus,
)

/**
 * 一括変換の UI 状態。
 */
sealed interface BatchConversionUiState {
    /** 変換待ち */
    data class Idle(
        val fileInfos: List<BatchFileInfo>,
        val totalFiles: Int,
    ) : BatchConversionUiState

    /** 変換実行中 */
    data class Converting(
        val totalFiles: Int,
        val completedFiles: Int,
        /** 全ファイルの進捗一覧（ファイル名・ゲイン・状態） */
        val fileProgresses: List<FileProgress>,
    ) : BatchConversionUiState

    /** 変換完了 */
    data class Completed(
        val totalFiles: Int,
        val successCount: Int,
        val failureCount: Int,
        val successFiles: List<File>,
        val logs: List<String>,
        val saveState: SaveState = SaveState.Idle,
        /** 累積保存成功数（複数回の saveAllToDownloads 呼び出しにまたがる合計） */
        val cumulativeSavedCount: Int = 0,
    ) : BatchConversionUiState

    /** エラー */
    data class Error(
        val message: String,
    ) : BatchConversionUiState
}

/**
 * 保存操作の状態。
 */
sealed interface SaveState {
    /** 未操作 */
    data object Idle : SaveState
    /** 保存中 */
    data class Saving(val current: Int, val total: Int) : SaveState
    /** 保存完了 */
    data class Done(val savedCount: Int, val message: String) : SaveState
    /** 保存失敗 */
    data class SaveError(val message: String) : SaveState
}

/**
 * 複数ファイルの一括変換を管理する ViewModel。
 *
 * 各ファイルを並列（コルーチン）で変換し、進捗と結果を管理します。
 */
class BatchConversionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BatchConversionVM"
        /** MediaCodecConverter.convert の最大同時実行数（CPUコア数の半分、最小2・最大4） */
        private val MAX_CONCURRENT_CONVERSIONS: Int = run {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            (cpuCores / 2).coerceIn(2, 4)
        }
    }

    private val _uiState = MutableStateFlow<BatchConversionUiState>(
        BatchConversionUiState.Idle(emptyList(), 0)
    )
    val uiState: StateFlow<BatchConversionUiState> = _uiState.asStateFlow()

    /** 変換対象のパスとゲインのリスト */
    private var pathGainPairs: List<Pair<String, Float>> = emptyList()

    /** ファイルごとの進捗（スレッドセーフ） */
    private val fileProgresses = mutableListOf<FileProgress>()
    private val progressLock = Any()

    /** 設定ストア */
    private val settingsStore = SettingsStore.getInstance(getApplication())

    /**
     * 一括変換の準備をします。
     */
    fun prepareBatch(pairs: List<Pair<String, Float>>) {
        pathGainPairs = pairs

        val fileInfos = pairs.map { (path, gain) ->
            BatchFileInfo(
                fileName = LocalFileRepository.getDisplayName(path),
                gainPercent = (gain * 100f).toInt(),
            )
        }

        _uiState.value = BatchConversionUiState.Idle(
            fileInfos = fileInfos,
            totalFiles = pairs.size,
        )
    }

    /**
     * 一括変換を開始します（並列処理）。
     *
     * 各ファイルの進捗を [FileProgress] で個別に追跡し、
     * UI にファイル名＋ステータス行として表示します。
     */
    fun startBatchConversion() {
        val pairs = pathGainPairs
        if (pairs.isEmpty()) {
            _uiState.value = BatchConversionUiState.Error("変換対象がありません")
            return
        }

        // 全ファイルを Pending で初期化
        synchronized(progressLock) {
            fileProgresses.clear()
            pairs.forEach { (path, gain) ->
                fileProgresses.add(
                    FileProgress(
                        fileName = LocalFileRepository.getDisplayName(path),
                        gainPercent = (gain * 100f).toInt(),
                        status = FileStatus.Pending,
                    )
                )
            }
        }

        _uiState.value = BatchConversionUiState.Converting(
            totalFiles = pairs.size,
            completedFiles = 0,
            fileProgresses = synchronized(progressLock) { fileProgresses.toList() },
        )

        viewModelScope.launch {
            try {
                // 設定を読み取り
                val settings = settingsStore.settingsFlow.first()
                val extension = settings.container.extension
                val bitrate = settings.bitrate.bps
                val codecType = settings.codec

                val app = getApplication<Application>()
                val cacheDir = app.cacheDir ?: run {
                    _uiState.value = BatchConversionUiState.Error("キャッシュディレクトリにアクセスできません")
                    return@launch
                }
                val outputDir = cacheDir.resolve("converted")

                // 同時実行数制限用セマフォ
                val semaphore = Semaphore(MAX_CONCURRENT_CONVERSIONS)

                // 並列変換（IO スレッド）
                val results = withContext(Dispatchers.IO) {
                    pairs.mapIndexed { fileIndex, (inputPath, gain) ->
                        async {
                            try {
                                val displayName = LocalFileRepository.getDisplayName(inputPath)
                                val baseName = displayName.substringBeforeLast(".")
                                val gainPercent = (gain * 100f).toInt()

                                // ユニークな出力ファイル名
                                val uniqueSuffix = java.util.UUID.randomUUID().toString().take(8)
                                val outputName = "${baseName}_gain${gainPercent}_${uniqueSuffix}.$extension"
                                val outputPath = File(outputDir, outputName).absolutePath

                                Log.d(TAG, "Converting: $inputPath -> $outputPath (gain=$gain)")

                                // セマフォで同時実行数を制限
                                val result = semaphore.withPermit {
                                    // 変換開始 → ステータスを Converting に更新
                                    updateFileStatus(fileIndex, FileStatus.Converting("開始..."))
                                    emitConvertingState()

                                    MediaCodecConverter.convert(
                                        inputPath, outputPath, gain,
                                        bitrate = bitrate,
                                        codecType = codecType,
                                        containerExtension = extension,
                                    ) { progressMsg ->
                                        // 変換中の進捗メッセージをファイル個別に反映
                                        updateFileStatus(fileIndex, FileStatus.Converting(progressMsg))
                                        emitConvertingState()
                                    }
                                }

                                when (result) {
                                    is MediaCodecConverter.Result.Success -> {
                                        updateFileStatus(fileIndex, FileStatus.Completed)
                                        emitConvertingState()
                                        Result.success(result.outputFile)
                                    }
                                    is MediaCodecConverter.Result.Error -> {
                                        updateFileStatus(fileIndex, FileStatus.Failed(result.message))
                                        emitConvertingState()
                                        Result.failure<File>(RuntimeException(result.message))
                                    }
                                }
                            } catch (e: Exception) {
                                updateFileStatus(fileIndex, FileStatus.Failed(e.message ?: "不明なエラー"))
                                emitConvertingState()
                                Result.failure<File>(e)
                            }
                        }
                    }.awaitAll()
                }

                // 結果の集計
                val successFiles = mutableListOf<File>()
                var successCount = 0
                var failureCount = 0

                results.forEach { result ->
                    result.fold(
                        onSuccess = { file ->
                            successFiles.add(file)
                            successCount++
                        },
                        onFailure = {
                            failureCount++
                        },
                    )
                }

                _uiState.value = BatchConversionUiState.Completed(
                    totalFiles = pairs.size,
                    successCount = successCount,
                    failureCount = failureCount,
                    successFiles = successFiles,
                    logs = buildResultLogs(pairs, successCount, failureCount),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Batch conversion failed", e)
                _uiState.value = BatchConversionUiState.Error(
                    "変換中に例外が発生しました: ${e.message}"
                )
            }
        }
    }

    /**
     * 変換済みファイルを端末の Downloads フォルダに保存します。
     * Android 10 (Q) 以降は MediaStore、それ以前は直接ファイルコピーを使用します。
     */
    fun saveAllToDownloads() {
        val currentState = _uiState.value
        if (currentState !is BatchConversionUiState.Completed) return
        // 未保存のファイルのみを対象とする（既に保存済みで削除されたファイルは successFiles から除去済み）
        val files = currentState.successFiles
        if (files.isEmpty()) {
            _uiState.value = currentState.copy(
                saveState = SaveState.Done(
                    savedCount = 0,
                    message = "すべてのファイルが既に保存されています"
                )
            )
            return
        }

        _uiState.value = currentState.copy(
            saveState = SaveState.Saving(current = 0, total = files.size)
        )

        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                var savedCount = 0
                val errors = mutableListOf<String>()
                val savedFiles = mutableListOf<File>()

                files.forEachIndexed { index, file ->
                    try {
                        val success = withContext(Dispatchers.IO) {
                            copyToDownloads(app, file)
                        }
                        if (success) {
                            savedCount++
                            savedFiles.add(file)
                        } else {
                            errors.add(file.name)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save ${file.name}", e)
                        errors.add(file.name)
                    }

                    val s = _uiState.value
                    if (s is BatchConversionUiState.Completed) {
                        _uiState.value = s.copy(
                            saveState = SaveState.Saving(current = index + 1, total = files.size)
                        )
                    }
                }

                // 累積保存数を更新
                val s = _uiState.value
                val newCumulativeCount = if (s is BatchConversionUiState.Completed) {
                    s.cumulativeSavedCount + savedCount
                } else {
                    savedCount
                }

                val message = if (errors.isEmpty()) {
                    "${savedCount}個のファイルをDownloadsに保存しました（累積: ${newCumulativeCount}個）"
                } else {
                    "${savedCount}個保存 / ${errors.size}個失敗: ${errors.take(2).joinToString(", ")}"
                }

                // Downloads 保存完了後、コピー成功が確認できたキャッシュファイルのみ削除
                // 失敗ファイルは再試行用に保持。成功ファイルは successFiles から除去し、
                // 共有ボタンが削除済みファイルを参照しないようにする
                if (savedFiles.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        savedFiles.forEach { file ->
                            if (file.exists()) {
                                val deleted = file.delete()
                                Log.d(TAG, "saveAllToDownloads: cache cleanup ${file.name} deleted=$deleted")
                            }
                        }
                    }
                }

                val currentAfterCleanup = _uiState.value
                if (currentAfterCleanup is BatchConversionUiState.Completed) {
                    // 保存成功したファイルを successFiles から除去（再試行・共有の対象外にする）
                    val remainingFiles = currentAfterCleanup.successFiles.filter { it !in savedFiles }
                    _uiState.value = currentAfterCleanup.copy(
                        successFiles = remainingFiles,
                        cumulativeSavedCount = newCumulativeCount,
                        saveState = SaveState.Done(savedCount = savedCount, message = message)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveAllToDownloads failed", e)
                val s = _uiState.value
                if (s is BatchConversionUiState.Completed) {
                    _uiState.value = s.copy(
                        saveState = SaveState.SaveError("保存に失敗しました: ${e.message}")
                    )
                }
            }
        }
    }

    /**
     * 単一ファイルを Downloads フォルダにコピーします。
     * @return 成功時 true
     */
    private fun copyToDownloads(app: Application, sourceFile: File): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            copyToDownloadsViaMediaStore(app, sourceFile)
        } else {
            copyToDownloadsLegacy(sourceFile)
        }
    }

    @android.annotation.SuppressLint("InlinedApi")
    private fun copyToDownloadsViaMediaStore(app: Application, sourceFile: File): Boolean {
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/mp4")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        return try {
            val outputStream = resolver.openOutputStream(uri)
                ?: run {
                    // null ストリームは失敗として扱う
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    Log.w(TAG, "copyToDownloadsViaMediaStore: openOutputStream returned null for ${sourceFile.name}")
                    return false
                }
            outputStream.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            contentValues.clear()
            contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            val updated = resolver.update(uri, contentValues, null, null)
            if (updated <= 0) {
                // update が成功しなかった場合も失敗扱い
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                Log.w(TAG, "copyToDownloadsViaMediaStore: resolver.update returned $updated for ${sourceFile.name}")
                return false
            }
            true
        } catch (e: Exception) {
            // 失敗時はペンディング中のエントリを削除
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            Log.w(TAG, "copyToDownloadsViaMediaStore failed for ${sourceFile.name}", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun copyToDownloadsLegacy(sourceFile: File): Boolean {
        return try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            // 同名ファイルが存在する場合はユニークな名前を生成
            val destFile = resolveNonConflictingFile(downloadsDir, sourceFile.name)
            sourceFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "copyToDownloadsLegacy failed for ${sourceFile.name}", e)
            false
        }
    }

    /**
     * ディレクトリ内で競合しないファイル名を生成します。
     * 同名ファイルが存在する場合は末尾に連番を付加します。
     */
    private fun resolveNonConflictingFile(dir: File, desiredName: String): File {
        val candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val base = desiredName.substringBeforeLast(".")
        val ext = desiredName.substringAfterLast(".", "")
        var suffix = 1
        while (true) {
            val name = if (ext.isNotEmpty()) "${base}_$suffix.$ext" else "${base}_$suffix"
            val file = File(dir, name)
            if (!file.exists()) return file
            suffix++
        }
    }

    /**
     * 指定インデックスのファイル進捗状態を更新します（スレッドセーフ）。
     */
    private fun updateFileStatus(index: Int, status: FileStatus) {
        synchronized(progressLock) {
            if (index in fileProgresses.indices) {
                val old = fileProgresses[index]
                fileProgresses[index] = old.copy(status = status)
            }
        }
    }

    /**
     * 現在の進捗を UI 状態として発行します。
     */
    private fun emitConvertingState() {
        val progresses = synchronized(progressLock) { fileProgresses.toList() }
        val completedCount = progresses.count {
            it.status is FileStatus.Completed || it.status is FileStatus.Failed
        }
        _uiState.value = BatchConversionUiState.Converting(
            totalFiles = progresses.size,
            completedFiles = completedCount,
            fileProgresses = progresses,
        )
    }

    /**
     * 完了ログ文字列を構築します。
     */
    private fun buildResultLogs(
        pairs: List<Pair<String, Float>>,
        successCount: Int,
        failureCount: Int,
    ): List<String> {
        val logs = mutableListOf<String>()
        val progresses = synchronized(progressLock) { fileProgresses.toList() }
        progresses.forEach { fp ->
            when (val s = fp.status) {
                is FileStatus.Completed -> logs.add("✓ ${fp.fileName} (${fp.gainPercent}%)")
                is FileStatus.Failed -> logs.add("✗ ${fp.fileName} — ${s.reason}")
                else -> logs.add("? ${fp.fileName} — 不明な状態")
            }
        }
        logs.add("")
        logs.add("一括変換完了: 成功 $successCount / 失敗 $failureCount")
        return logs
    }
}
