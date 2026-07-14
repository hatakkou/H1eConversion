package com.example.h1econversion.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.h1econversion.model.RecordingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles USB device detection, permission, and file access for Zoom H1 essential.
 *
 * Zoom Corporation vendor ID: 0x1686 (5766 decimal).
 */
class UsbFileRepository(private val context: Context) {

    companion object {
        private const val TAG = "UsbFileRepository"
        const val ZOOM_VENDOR_ID = 0x1686
        const val ACTION_USB_PERMISSION = "com.example.h1econversion.USB_PERMISSION"
        const val PERMISSION_TIMEOUT_MS = 15_000L
    }

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Find a connected USB device from Zoom Corporation.
     */
    fun findZoomDevice(): UsbDevice? {
        return usbManager.deviceList.values.find { device ->
            device.vendorId == ZOOM_VENDOR_ID
        }
    }

    /**
     * Request USB permission for the given device. Returns true if granted.
     */
    fun requestPermission(device: UsbDevice): Flow<Boolean> = callbackFlow {
        // If we already have permission, return immediately
        if (usbManager.hasPermission(device)) {
            trySend(true)
            close()
            return@callbackFlow
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    trySend(granted)
                    close()
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        usbManager.requestPermission(device, permissionIntent)

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------------------
    // H1e ストレージボリューム検出
    // ---------------------------------------------------------------------------

    /**
     * H1e の [StorageVolume] を検出します。
     * StorageManager から "ZOOM" または "H1" を含む説明のリムーバブルボリュームを探します。
     * 見つからない場合は null を返します。
     */
    fun findH1eStorageVolume(): StorageVolume? {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return null
        return storageManager.storageVolumes.find { volume ->
            try {
                val desc = volume.getDescription(context)
                (desc.contains("H1", ignoreCase = true) || desc.contains("ZOOM", ignoreCase = true))
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 指定された StorageVolume に対応する SAF ツリー URI を構築して
     * [DocumentFile] を取得します。
     *
     * 最初に [getPersistedTreeUriForVolume] でユーザーが以前許可した
     * ツリーURI（RECORD 等のサブツリーを含む）を探し、見つかればそれを再利用します。
     * 永続的権限がない場合のみ [buildTreeUriForVolume] でルートURIを構築します。
     *
     * Android 13+ では SAF 権限が必須のため、権限がない場合は SecurityException が
     * 発生します。その場合は null を返します（呼び出し側で ACTION_OPEN_DOCUMENT_TREE を
     * 起動して権限を取得してください）。
     *
     * @param volume H1e の StorageVolume
     * @return アクセス可能な場合は DocumentFile、権限不足の場合は null
     */
    suspend fun getDocumentFileForVolume(volume: StorageVolume): DocumentFile? =
        withContext(Dispatchers.IO) {
            try {
                // 1) 既存の永続的URI権限を確認（サブツリー選択を再利用）
                val persistedUri = getPersistedTreeUriForVolume(volume)
                val treeUri = if (persistedUri != null) {
                    Log.d(TAG, "getDocumentFileForVolume: reusing persisted URI $persistedUri")
                    persistedUri
                } else {
                    buildTreeUriForVolume(volume) ?: return@withContext null
                }
                Log.d(TAG, "getDocumentFileForVolume: treeUri=$treeUri")

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile == null) {
                    Log.w(TAG, "getDocumentFileForVolume: DocumentFile.fromTreeUri returned null")
                    return@withContext null
                }

                // exists() が SecurityException を投げる = SAF 権限不足
                val exists = try {
                    docFile.exists()
                } catch (e: SecurityException) {
                    Log.w(TAG, "getDocumentFileForVolume: no SAF permission for $treeUri", e)
                    return@withContext null
                }

                if (exists) {
                    Log.d(TAG, "getDocumentFileForVolume: SUCCESS, name=${docFile.name} uri=${docFile.uri}")
                    return@withContext docFile
                } else {
                    Log.w(TAG, "getDocumentFileForVolume: DocumentFile does not exist")
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.w(TAG, "getDocumentFileForVolume: failed", e)
                return@withContext null
            }
        }

    /**
     * 指定ボリュームの UUID に一致する永続的ツリー URI 権限を [persistedUriPermissions]
     * から検索します。ユーザーが以前に許可したサブツリー（RECORD 等）があれば再利用します。
     *
     * @param volume 検索対象の StorageVolume
     * @return 一致する永続的ツリー URI。見つからない場合は null
     */
    private fun getPersistedTreeUriForVolume(volume: StorageVolume): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uuid = volume.uuid ?: return null
        val permissions = context.contentResolver.persistedUriPermissions
        for (perm in permissions) {
            if (!perm.isReadPermission) continue
            val uri = perm.uri
            if (uri.scheme == "content" &&
                uri.authority == "com.android.externalstorage.documents"
            ) {
                val docId = DocumentsContract.getTreeDocumentId(uri) ?: continue
                if (docId.startsWith(uuid)) {
                    return uri
                }
            }
        }
        return null
    }

    /**
     * H1e ストレージへのアクセス権限を取得するための SAF ツリーピッカーの
     * 初期 URI を取得します。
     *
     * [buildTreeUriForVolume] で tree URI を構築します。
     * ContentProvider クエリを含むため [Dispatchers.IO] 上で実行します。
     *
     * @param volume H1e の StorageVolume
     * @return SAF ツリーピッカーの初期 URI。作成できない場合は null（null でもピッカー起動可）
     */
    suspend fun createInitialTreeUri(volume: StorageVolume): android.net.Uri? =
        withContext(Dispatchers.IO) {
            try {
                val uri = buildTreeUriForVolume(volume)
                Log.d(TAG, "createInitialTreeUri: uri=$uri")
                uri
            } catch (e: Exception) {
                Log.w(TAG, "createInitialTreeUri: failed", e)
                null
            }
        }

    /**
     * SAF ツリーピッカーから返却された URI を処理し、永続的権限を保存した上で
     * [DocumentFile] を返します。
     *
     * DocumentFile.exists() 等の I/O を含むため [Dispatchers.IO] 上で実行します。
     *
     * @param treeUri SAF ツリーピッカーから返却された tree URI
     * @return アクセス可能な DocumentFile。失敗時は null
     */
    suspend fun processTreeUriResult(treeUri: android.net.Uri): DocumentFile? =
        withContext(Dispatchers.IO) {
            try {
                // 永続的読み取り権限を取得
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                Log.d(TAG, "processTreeUriResult: persistable permission granted for $treeUri")

                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.exists()) {
                    Log.d(TAG, "processTreeUriResult: SUCCESS, name=${docFile.name} uri=${docFile.uri}")
                    return@withContext docFile
                } else {
                    Log.w(TAG, "processTreeUriResult: DocumentFile is null or does not exist")
                    return@withContext null
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "processTreeUriResult: SecurityException", e)
                return@withContext null
            } catch (e: Exception) {
                Log.w(TAG, "processTreeUriResult: failed", e)
                return@withContext null
            }
        }

    /**
     * StorageVolume の情報から SAF ツリー URI を構築します。
     *
     * API 29+: StorageVolume.uuid を使用
     * API 26-28: DocumentsContract のルート照会を試みる（古いAndroidでは制限がない）
     */
    private fun buildTreeUriForVolume(volume: StorageVolume): android.net.Uri? {
        // API 29+: UUID から直接 tree URI を構築
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uuid = volume.uuid
            if (uuid != null) {
                val docId = if (uuid.endsWith(":")) uuid else "$uuid:"
                Log.d(TAG, "buildTreeUriForVolume: using uuid=$uuid")
                return DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    docId,
                )
            }
        }

        // フォールバック: MediaStore のボリューム名を使用 (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mediaStoreVolumeName = volume.mediaStoreVolumeName
            if (mediaStoreVolumeName != null) {
                Log.d(TAG, "buildTreeUriForVolume: using mediaStoreVolumeName=$mediaStoreVolumeName")
                // MediaStore volume name から DocumentsContract のルートを照会
                return buildTreeUriFromDocumentsRoot(mediaStoreVolumeName)
            }
        }

        // 最終フォールバック: DocumentsContract ルート照会
        Log.d(TAG, "buildTreeUriForVolume: falling back to DocumentsContract roots query")
        return buildTreeUriFromDocumentsRoot(null)
    }

    /**
     * DocumentsContract のルートを照会して tree URI を構築します。
     * フィルタ文字列が指定された場合は、タイトル/サマリーに部分一致するルートを探します。
     *
     * 注意: Android 13+ ではこの照会自体が SecurityException になるため、
     * このメソッドは API 29 未満の端末でのフォールバックとしてのみ使用されます。
     */
    private fun buildTreeUriFromDocumentsRoot(filter: String?): android.net.Uri? {
        try {
            val rootsUri = DocumentsContract.buildRootsUri("com.android.externalstorage.documents")
            context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
                // COLUMN_DOCUMENT_ID を優先使用（ツリーURI構築に適切なドキュメントID形式）
                var rootIdIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                if (rootIdIdx < 0) {
                    // フォールバック: COLUMN_ROOT_ID（古いプロバイダー用）
                    rootIdIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                }
                val flagsIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
                val titleIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                val summaryIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_SUMMARY)

                if (rootIdIdx < 0) return null

                while (cursor.moveToNext()) {
                    val rootId = cursor.getString(rootIdIdx) ?: continue
                    val flags = if (flagsIdx >= 0) cursor.getInt(flagsIdx) else 0
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else ""
                    val summary = if (summaryIdx >= 0) cursor.getString(summaryIdx) ?: "" else ""

                    val supportsChildren = (flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD) != 0
                    if (!supportsChildren) continue

                    // フィルタが指定されている場合はマッチするか確認
                    if (filter != null) {
                        val matches = title.contains(filter, ignoreCase = true) ||
                            summary.contains(filter, ignoreCase = true) ||
                            title.contains("H1", ignoreCase = true) ||
                            title.contains("ZOOM", ignoreCase = true) ||
                            summary.contains("H1", ignoreCase = true) ||
                            summary.contains("ZOOM", ignoreCase = true)
                        if (!matches) continue
                    }

                    val docId = if (rootId.endsWith(":")) rootId else "$rootId:"
                    return DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        docId,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "buildTreeUriFromDocumentsRoot: SecurityException (expected on Android 13+)", e)
        } catch (e: Exception) {
            Log.w(TAG, "buildTreeUriFromDocumentsRoot: failed", e)
        }
        return null
    }

    /**
     * List all WAV recording files from the device's storage.
     * Looks in RECORD/ folder and root directory.
     * IO（DocumentFile 操作）は Dispatchers.IO 上で実行する。
     *
     * 注意: SAF 権限が不十分な場合、DocumentFile.listFiles() が
     * SecurityException をスローすることがあります。すべて例外を捕捉します。
     */
    suspend fun listRecordingFiles(rootDir: DocumentFile): List<RecordingFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<RecordingFile>()

        // H1 essential typically stores recordings in a "RECORD" folder
        val recordDirs = listOf(
            findSubDirectory(rootDir, "RECORD"),
            rootDir,
        ).filterNotNull()

        var anyDirListed = false
        val seenNames = mutableSetOf<String>()
        for (dir in recordDirs) {
            try {
                val dirFiles = dir.listFiles()
                anyDirListed = true  // listFiles() が成功 = このディレクトリは読み取り可能
                for (file in dirFiles) {
                    try {
                        if (file.isFile && file.name != null && file.name!!.lowercase().endsWith(".wav")) {
                            if (seenNames.add(file.name!!)) {
                                files.add(
                                    RecordingFile(
                                        name = file.name!!,
                                        uri = file.uri,
                                        sizeBytes = file.length(),
                                        lastModified = file.lastModified(),
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process file: ${file.name}", e)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot list files in directory (no SAF permission): ${dir.name}", e)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list files in directory: ${dir.name}", e)
            }
        }

        // すべてのディレクトリの一覧取得に失敗した場合はエラーを伝播
        if (!anyDirListed && recordDirs.isNotEmpty()) {
            throw java.io.IOException("Failed to list any recording directories on device")
        }

        return@withContext files.sortedByDescending { it.lastModified }
    }

    /**
     * 指定された親ディレクトリ内のサブディレクトリを検索します。
     * listFiles() の例外を捕捉し、失敗時は null を返します。
     */
    private fun findSubDirectory(parent: DocumentFile, name: String): DocumentFile? {
        return try {
            parent.listFiles().find { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        } catch (e: SecurityException) {
            Log.w(TAG,
                "Cannot list directory to find '$name' (no SAF permission)", e)
            null
        } catch (e: Exception) {
            Log.w(TAG,
                "Failed to find subdirectory '$name'", e)
            null
        }
    }

    /**
     * Format file size for display.
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Format a timestamp for display.
     */
    fun formatTimestamp(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }
}
