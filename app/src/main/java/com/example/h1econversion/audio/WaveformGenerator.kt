package com.example.h1econversion.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Downsampled waveform envelope data for rendering.
 *
 * Each index in [minValues] / [maxValues] corresponds to one pixel column
 * in the waveform display. Values are in [-1.0, 1.0] range.
 */
data class WaveformData(
    val minValues: FloatArray,
    val maxValues: FloatArray,
    val sampleRate: Int,
    val totalFrames: Long,
    val durationMs: Long,
) {
    val outputWidth: Int get() = minValues.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return minValues.contentEquals(other.minValues) &&
            maxValues.contentEquals(other.maxValues) &&
            sampleRate == other.sampleRate &&
            totalFrames == other.totalFrames &&
            durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = minValues.contentHashCode()
        result = 31 * result + maxValues.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + totalFrames.hashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * Generates min/max envelope data from PCM float samples for waveform rendering.
 *
 * Uses a streaming approach: reads the WAV file in chunks and aggregates
 * directly into output columns. Memory usage is O(outputWidth), not O(numFrames).
 */
object WaveformGenerator {

    /** Default output resolution. */
    private const val DEFAULT_OUTPUT_WIDTH = 2048
    /** Number of sample frames to read per chunk. */
    private const val CHUNK_FRAMES = 4096

    /**
     * Generate waveform data by streaming from the WAV file.
     *
     * This is the primary entry point. It reads the file in chunks,
     * mixes channels to mono, and computes min/max per output column.
     * Constant memory regardless of file length.
     *
     * This is a [suspend] function that periodically checks for
     * coroutine cancellation — safe to cancel during very large file processing.
     */
    suspend fun generateFromFile(
        filePath: String,
        wavInfo: WavInfo,
        outputWidth: Int = DEFAULT_OUTPUT_WIDTH,
    ): WaveformData {
        val actualWidth = minOf(outputWidth, wavInfo.numFrames.toInt().coerceAtLeast(1))
        val minValues = FloatArray(actualWidth) { Float.POSITIVE_INFINITY }
        val maxValues = FloatArray(actualWidth) { Float.NEGATIVE_INFINITY }
        val framesPerColumn = maxOf(1.0, wavInfo.numFrames.toDouble() / actualWidth)
        val channels = wavInfo.numChannels.toInt()
        val bytesPerSample = wavInfo.bitsPerSample.toInt() / 8
        val bytesPerFrame = channels * bytesPerSample

        RandomAccessFile(filePath, "r").use { raf ->
            raf.seek(wavInfo.dataOffset)
            val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
            val byteBuf = ByteBuffer.allocate(rawBuf.size).order(ByteOrder.LITTLE_ENDIAN)

            var frameIdx = 0L

            while (frameIdx < wavInfo.numFrames) {
                // コルーチンキャンセルに応答（Dispatchers.IO 上の長時間処理）
                coroutineContext.ensureActive()

                val framesRemaining = (wavInfo.numFrames - frameIdx).toInt()
                val framesToRead = minOf(CHUNK_FRAMES, framesRemaining)
                val bytesToRead = framesToRead * bytesPerFrame
                val bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                byteBuf.clear()
                byteBuf.put(rawBuf, 0, bytesRead)
                byteBuf.flip()

                val framesRead = bytesRead / bytesPerFrame

                for (f in 0 until framesRead) {
                    // チャンネルをミックス → モノラル
                    var mono = 0f
                    for (ch in 0 until channels) {
                        mono += readSample(byteBuf, bytesPerSample)
                    }
                    mono /= channels

                    frameIdx++
                    // フレームインデックスから直接カラムを計算
                    val colIdx = ((frameIdx - 1).toDouble() / framesPerColumn)
                        .toInt().coerceIn(0, actualWidth - 1)
                    if (mono < minValues[colIdx]) minValues[colIdx] = mono
                    if (mono > maxValues[colIdx]) maxValues[colIdx] = mono
                }
            }
        }

        // Replace any columns that got no samples (shouldn't happen normally)
        for (i in minValues.indices) {
            if (minValues[i] == Float.POSITIVE_INFINITY) minValues[i] = 0f
            if (maxValues[i] == Float.NEGATIVE_INFINITY) maxValues[i] = 0f
        }

        return WaveformData(
            minValues = minValues,
            maxValues = maxValues,
            sampleRate = wavInfo.sampleRate,
            totalFrames = wavInfo.numFrames,
            durationMs = wavInfo.durationMs,
        )
    }

    /**
     * Read one sample from the ByteBuffer according to bit depth.
     *
     * - 32-bit float: IEEE 754 float (H1e native format)
     * - 16-bit PCM: signed integer, normalized to [-1, 1]
     * - 24-bit PCM: signed integer from 3 bytes, normalized to [-1, 1]
     * - 8-bit PCM: unsigned integer, normalized to [-1, 1]
     */
    private fun readSample(buf: ByteBuffer, bytesPerSample: Int): Float {
        return when (bytesPerSample) {
            4 -> buf.float // 32-bit IEEE float
            2 -> buf.short.toFloat() / 32768f // 16-bit signed PCM
            3 -> {
                // 24-bit signed PCM (little-endian, 3 bytes)
                val b0 = buf.get().toInt() and 0xFF
                val b1 = buf.get().toInt() and 0xFF
                val b2 = buf.get().toInt() and 0xFF
                var sample = b0 or (b1 shl 8) or (b2 shl 16)
                // sign-extend from 24 bits
                if ((sample and 0x800000) != 0) {
                    sample = sample or 0xFF000000.toInt()
                }
                sample.toFloat() / 8388608f // 2^23
            }
            else -> {
                // 8-bit unsigned PCM
                (buf.get().toInt() and 0xFF).toFloat() / 128f - 1f
            }
        }
    }

    /**
     * Generate waveform data from in-memory float samples.
     * Used for testing or when samples are already loaded.
     */
    fun generate(
        samples: FloatArray,
        numChannels: Int,
        sampleRate: Int,
        totalFrames: Long,
        durationMs: Long,
        outputWidth: Int = DEFAULT_OUTPUT_WIDTH,
    ): WaveformData {
        val actualWidth = minOf(outputWidth, totalFrames.toInt().coerceAtLeast(1))
        val minValues = FloatArray(actualWidth) { Float.POSITIVE_INFINITY }
        val maxValues = FloatArray(actualWidth) { Float.NEGATIVE_INFINITY }
        val framesPerColumn = maxOf(1.0, totalFrames.toDouble() / actualWidth)

        var frameIdx = 0L
        var i = 0
        while (i < samples.size) {
            var mono = 0f
            for (ch in 0 until numChannels) {
                if (i + ch < samples.size) {
                    mono += samples[i + ch]
                }
            }
            mono /= numChannels

            frameIdx++
            i += numChannels
            // フレームインデックスから直接カラムを計算
            val colIdx = ((frameIdx - 1).toDouble() / framesPerColumn)
                .toInt().coerceIn(0, actualWidth - 1)
            if (mono < minValues[colIdx]) minValues[colIdx] = mono
            if (mono > maxValues[colIdx]) maxValues[colIdx] = mono
        }

        return WaveformData(
            minValues = minValues,
            maxValues = maxValues,
            sampleRate = sampleRate,
            totalFrames = totalFrames,
            durationMs = durationMs,
        )
    }
}
