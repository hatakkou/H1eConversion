package com.example.h1econversion.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.storage.StorageManager
import android.provider.DocumentsContract
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

    /**
     * Find the storage volume associated with the H1 essential device.
     * Returns a DocumentFile representing the root of the device's storage.
     * IO（StorageManager / ContentResolver / DocumentFile）は Dispatchers.IO 上で実行する。
     */
    suspend fun findH1eStorageVolume(): DocumentFile? = withContext(Dispatchers.IO) {
        // まず StorageManager で H1 / ZOOM と一致するボリュームの説明を収集する（URI 構築には使わない）
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val h1Descriptions = mutableSetOf<String>()
        storageManager?.storageVolumes?.forEach { volume ->
            val desc = volume.getDescription(context)
            if (desc.contains("H1", ignoreCase = true) || desc.contains("ZOOM", ignoreCase = true)) {
                h1Descriptions.add(desc)
            }
        }

        // DocumentsContract のルートを列挙し、リムーバブルストレージまたは H1 説明に一致するボリュームを探す
        val rootsUri = DocumentsContract.buildRootsUri("com.android.externalstorage.documents")
        context.contentResolver.query(rootsUri, null, null, null, null)?.use { cursor ->
            val rootIdIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
            val flagsIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
            val titleIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
            val summaryIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_SUMMARY)

            while (cursor.moveToNext()) {
                val rootId = cursor.getString(rootIdIdx)
                val flags = cursor.getInt(flagsIdx)
                val title = cursor.getString(titleIdx) ?: ""
                val summary = cursor.getString(summaryIdx) ?: ""

                val supportsChildren = (flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD) != 0
                if (!supportsChildren) continue

                // H1 / ZOOM の説明と一致するか判定
                val matchesH1 = h1Descriptions.any { desc ->
                    title.contains(desc, ignoreCase = true) ||
                        summary.contains(desc, ignoreCase = true)
                } || title.contains("H1", ignoreCase = true) ||
                    title.contains("ZOOM", ignoreCase = true) ||
                    summary.contains("H1", ignoreCase = true) ||
                    summary.contains("ZOOM", ignoreCase = true)

                if (matchesH1) {
                    // 正しい tree URI を構築：documentId には rootId + ":" が必要
                    val docId = if (rootId.endsWith(":")) rootId else "$rootId:"
                    val treeUri = DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        docId,
                    )
                    DocumentFile.fromTreeUri(context, treeUri)?.let { docFile ->
                        if (docFile.exists()) {
                            return@withContext docFile
                        }
                    }
                }
            }
        }

        null
    }

    /**
     * List all WAV recording files from the device's storage.
     * Looks in RECORD/ folder and root directory.
     * IO（DocumentFile 操作）は Dispatchers.IO 上で実行する。
     */
    suspend fun listRecordingFiles(rootDir: DocumentFile): List<RecordingFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<RecordingFile>()

        // H1 essential typically stores recordings in a "RECORD" folder
        val recordDirs = listOf(
            findSubDirectory(rootDir, "RECORD"),
            rootDir,
        ).filterNotNull()

        val seenNames = mutableSetOf<String>()
        for (dir in recordDirs) {
            dir.listFiles().forEach { file ->
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
            }
        }

        return@withContext files.sortedByDescending { it.lastModified }
    }

    private fun findSubDirectory(parent: DocumentFile, name: String): DocumentFile? {
        return parent.listFiles().find { it.isDirectory && it.name.equals(name, ignoreCase = true) }
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
