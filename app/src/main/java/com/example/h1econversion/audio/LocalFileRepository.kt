package com.example.h1econversion.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.h1econversion.model.FileSource
import com.example.h1econversion.model.SelectedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles local file operations — copy, read, directory management.
 */
class LocalFileRepository(private val context: Context) {

    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    companion object {
        private const val TAG = "LocalFileRepository"
        private const val META_EXTENSION = ".meta"
        private const val META_SEPARATOR = "\n"

        /**
         * ファイルパスからユーザー表示用のファイル名を取得します。
         * メタファイルがあれば元のファイル名を返し、なければUUIDプレフィックスを除去した名前を返します。
         */
        fun getDisplayName(filePath: String): String {
            val file = File(filePath)
            // メタファイルから元のファイル名を復元
            val metaFile = File(filePath + META_EXTENSION)
            if (metaFile.exists()) {
                try {
                    val lines = metaFile.readText().trim().split(META_SEPARATOR)
                    if (lines.size >= 2 && lines[1].isNotBlank()) {
                        return lines[1]
                    }
                } catch (_: Exception) {
                    // メタファイル読み取り失敗時はフォールバック
                }
            }
            // フォールバック: UUID_ プレフィックスを除去
            return stripUuidPrefix(file.name)
        }

        /**
         * "UUID_ファイル名" 形式からUUID部分を除去します。
         * UUIDパターンにマッチしない場合は元の名前をそのまま返します。
         */
        private fun stripUuidPrefix(fileName: String): String {
            // UUIDパターン: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx_
            val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_")
            return uuidRegex.replaceFirst(fileName, "")
        }
    }

    /**
     * Copy a file from a content URI to app-local storage.
     * - パス トラバーサル防止のため fileName を basename に正規化
     * - UUID ベースのユニークなファイル名で保存（拡張子は保持）
     * - 一時ファイルに書き込み後、完了時にリネーム（アトミック化）
     * - コピー元の FileSource をメタデータとして永続化
     * Returns the copied file info on success.
     */
    suspend fun copyUriToLocal(sourceUri: Uri, fileName: String, source: FileSource): Result<SelectedFile> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "copyUriToLocal: START, sourceUri=$sourceUri, fileName=$fileName, source=$source")
            try {
                // パストラバーサル防止：basename のみを取得
                val safeBaseName = File(fileName).name ?: "recording.wav"
                Log.d(TAG, "copyUriToLocal: safeBaseName=$safeBaseName")
                // 拡張子を保持しつつ UUID ベースの一意なファイル名を生成
                val dotIndex = safeBaseName.lastIndexOf('.')
                val baseName = if (dotIndex > 0) safeBaseName.substring(0, dotIndex) else safeBaseName
                val extension = if (dotIndex > 0) safeBaseName.substring(dotIndex) else ".wav"
                val uniqueName = "${UUID.randomUUID()}_$baseName$extension"
                Log.d(TAG, "copyUriToLocal: uniqueName=$uniqueName")

                val targetFile = File(recordingsDir, uniqueName)
                Log.d(TAG, "copyUriToLocal: targetFile=${targetFile.absolutePath}")
                // 正規パスが recordingsDir 配下であることを検証
                val canonicalTarget = targetFile.canonicalFile
                val canonicalDir = recordingsDir.canonicalFile
                Log.d(TAG, "copyUriToLocal: canonicalTarget=${canonicalTarget.absolutePath}, canonicalDir=${canonicalDir.absolutePath}")
                if (!canonicalTarget.path.startsWith(canonicalDir.path + File.separator)) {
                    Log.e(TAG, "copyUriToLocal: PATH TRAVERSAL DETECTED! uniqueName=$uniqueName")
                    return@withContext Result.failure(
                        SecurityException("ファイル名が許可されていないパスを指しています: $uniqueName")
                    )
                }

                // 一時ファイルに書き込み
                val tempFile = File(recordingsDir, "$uniqueName.tmp")
                Log.d(TAG, "copyUriToLocal: tempFile=${tempFile.absolutePath}")
                try {
                    Log.d(TAG, "copyUriToLocal: opening input stream for $sourceUri...")
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        Log.d(TAG, "copyUriToLocal: input stream opened, copying to temp file...")
                        FileOutputStream(tempFile).use { output ->
                            val bytesCopied = input.copyTo(output, bufferSize = 8 * 1024)
                            Log.d(TAG, "copyUriToLocal: copied $bytesCopied bytes")
                        }
                    } ?: run {
                        Log.e(TAG, "copyUriToLocal: openInputStream returned NULL for $sourceUri")
                        return@withContext Result.failure(
                            IllegalStateException("Cannot open input stream for $sourceUri")
                        )
                    }

                    Log.d(TAG, "copyUriToLocal: renaming temp file to final...")
                    // コピー成功後に一時ファイルを最終ファイルへリネーム
                    if (!tempFile.renameTo(canonicalTarget)) {
                        Log.e(TAG, "copyUriToLocal: RENAME FAILED, deleting temp file")
                        tempFile.delete()
                        return@withContext Result.failure(
                            IllegalStateException("Failed to finalize file: $uniqueName")
                        )
                    }
                    Log.d(TAG, "copyUriToLocal: rename succeeded")
                } catch (e: Exception) {
                    // 失敗時は一時ファイルを確実に削除
                    Log.e(TAG, "copyUriToLocal: error during copy, deleting temp file", e)
                    tempFile.delete()
                    throw e
                }

                // メタデータ（FileSource と元のファイル名）を永続化
                Log.d(TAG, "copyUriToLocal: writing meta file...")
                try {
                    writeMetaFile(canonicalTarget, source, safeBaseName)
                } catch (e: Exception) {
                    // メタデータ書き込み失敗時は、作成済みの音声ファイルを削除してから失敗を返す
                    Log.e(TAG, "copyUriToLocal: meta file write failed, deleting canonical target", e)
                    canonicalTarget.delete()
                    return@withContext Result.failure(e)
                }

                val selectedFile = SelectedFile(
                    name = safeBaseName,
                    localPath = canonicalTarget.absolutePath,
                    sizeBytes = canonicalTarget.length(),
                    source = source,
                )
                Log.d(TAG, "copyUriToLocal: SUCCESS, selectedFile=$selectedFile")
                Result.success(selectedFile)
            } catch (e: Exception) {
                Log.e(TAG, "copyUriToLocal: FAILURE", e)
                Result.failure(e)
            }
        }

    /**
     * Look up a previously copied file by its local path.
     * メタデータが存在すれば元の FileSource と表示用ファイル名を復元する。
     * IO スレッドでファイルの存在確認・メタデータ読み取りを実行する。
     */
    suspend fun getFileInfo(localPath: String): SelectedFile? = withContext(Dispatchers.IO) {
        val file = File(localPath)
        if (!file.exists()) return@withContext null
        val meta = readMetaFile(file)
        val source = meta?.first ?: FileSource.LOCAL_IMPORT
        // メタファイルに保存された元のファイル名を優先、なければUUIDプレフィックス除去でフォールバック
        val displayName = meta?.second?.takeIf { it.isNotBlank() }
            ?: stripUuidPrefix(file.name)
        SelectedFile(
            name = displayName,
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            source = source,
        )
    }

    /**
     * コピー元の FileSource と表示用ファイル名をメタデータファイルに保存する。
     * フォーマット: "FileSource名\n表示用ファイル名"
     */
    private fun writeMetaFile(recordingFile: File, source: FileSource, displayName: String) {
        val metaFile = File(recordingFile.absolutePath + META_EXTENSION)
        metaFile.writeText("${source.name}$META_SEPARATOR$displayName")
    }

    /**
     * メタデータファイルから FileSource と表示用ファイル名を読み取る。
     * ファイルが存在しない、または不正な値の場合は null を返す。
     * 戻り値: Pair<FileSource, 表示用ファイル名?>
     *   - 旧形式（1行のみ）の場合は表示用ファイル名が null
     */
    private fun readMetaFile(recordingFile: File): Pair<FileSource, String?>? {
        val metaFile = File(recordingFile.absolutePath + META_EXTENSION)
        if (!metaFile.exists()) return null
        return try {
            val content = metaFile.readText().trim()
            val lines = content.split(META_SEPARATOR)
            val source = FileSource.valueOf(lines[0])
            val displayName = if (lines.size >= 2) lines[1] else null
            Pair(source, displayName)
        } catch (_: Exception) {
            null
        }
    }
}
