package com.example.h1econversion.model

import android.net.Uri

/**
 * Represents a recording file discovered on an external device (USB).
 */
data class RecordingFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val lastModified: Long,
)
