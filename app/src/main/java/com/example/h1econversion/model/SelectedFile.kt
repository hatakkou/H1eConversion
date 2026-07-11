package com.example.h1econversion.model

/**
 * Represents a file that has been copied to app-local storage and is ready for processing.
 */
data class SelectedFile(
    val name: String,
    val localPath: String,
    val sizeBytes: Long,
    val source: FileSource,
)
