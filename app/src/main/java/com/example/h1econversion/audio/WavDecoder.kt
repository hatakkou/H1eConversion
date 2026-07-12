package com.example.h1econversion.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed WAV file header information.
 *
 * @property audioFormat 1 = PCM integer, 3 = IEEE float
 * @property numChannels 1 = mono, 2 = stereo
 * @property sampleRate e.g. 44100, 48000, 96000
 * @property bitsPerSample typically 32 for H1e float recordings
 * @property dataOffset byte offset in the file where PCM sample data begins
 * @property dataSize size of PCM data in bytes
 * @property numFrames total number of sample frames (dataSize / blockAlign)
 * @property durationMs duration in milliseconds
 */
data class WavInfo(
    val audioFormat: Short,
    val numChannels: Short,
    val sampleRate: Int,
    val bitsPerSample: Short,
    val dataOffset: Long,
    val dataSize: Long,
    val numFrames: Long,
    val durationMs: Long,
)

/**
 * Decodes WAV file headers and reads 32-bit float PCM samples.
 *
 * Handles both standard and extended fmt chunks. Scans for the "data" chunk
 * in case there are extra chunks (fact, PEAK, etc.) between fmt and data.
 */
object WavDecoder {

    /**
     * Maximum number of samples that [readSamples] will load into memory.
     *
     * 50 million samples ≈ 200 MB FloatArray on the heap.
     * For larger files, use [readSamplesChunked] which streams in constant memory.
     */
    private const val MAX_MEMORY_SAMPLE_COUNT = 50_000_000

    /**
     * Parse the WAV header from a file path.
     * Returns null if the file is not a valid WAV file.
     */
    fun parseHeader(filePath: String): WavInfo? {
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                parseHeader(raf)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse the WAV header from a [RandomAccessFile] positioned at the start.
     */
    fun parseHeader(raf: RandomAccessFile): WavInfo {
        val header = ByteArray(12)
        raf.readFully(header)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        // "WAVE" 形式の確認: ファイル全体サイズは header[4..7]（LE）だが参照しない
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF") { "RIFF ヘッダーが見つかりません" }
        require(wave == "WAVE") { "WAVE 形式ではありません" }

        var audioFormat: Short = 0
        var numChannels: Short = 0
        var sampleRate: Int = 0
        var bitsPerSample: Short = 0

        // fmt チャンクを探して読み取り
        val chunkBuf = ByteArray(8)
        while (true) {
            raf.readFully(chunkBuf)
            val chunkId = String(chunkBuf, 0, 4, Charsets.US_ASCII)
            val chunkSize = (chunkBuf[4].toInt() and 0xFF) or
                ((chunkBuf[5].toInt() and 0xFF) shl 8) or
                ((chunkBuf[6].toInt() and 0xFF) shl 16) or
                ((chunkBuf[7].toInt() and 0xFF) shl 24)

            when (chunkId) {
                "fmt " -> {
                    val fmtData = ByteArray(chunkSize.coerceAtMost(40))
                    raf.readFully(fmtData)
                    val buf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = buf.short
                    numChannels = buf.short
                    sampleRate = buf.int
                    /* byteRate = */ buf.int
                    /* blockAlign = */ buf.short
                    bitsPerSample = buf.short
                    // 拡張 fmt 部分の残りバイトを消費
                    val fmtRead = chunkSize.coerceAtMost(40)
                    val remaining = chunkSize - fmtRead
                    if (remaining > 0) {
                        raf.seek(raf.filePointer + remaining)
                    }
                    // RIFF パディング: チャンクサイズが奇数の場合 1 バイト追加スキップ
                    if (chunkSize % 2 != 0) {
                        raf.seek(raf.filePointer + 1)
                    }
                }

                "data" -> {
                    val dataOffset = raf.filePointer
                    val blockAlign = (numChannels * (bitsPerSample / 8)).toShort()
                    val numFrames = chunkSize.toLong() / blockAlign
                    val durationMs = if (sampleRate > 0) {
                        numFrames * 1000L / sampleRate
                    } else 0L

                    return WavInfo(
                        audioFormat = audioFormat,
                        numChannels = numChannels,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        dataOffset = dataOffset,
                        dataSize = chunkSize.toLong(),
                        numFrames = numFrames,
                        durationMs = durationMs,
                    )
                }

                else -> {
                    // fmt でも data でもないチャンク（fact, PEAK, LIST など）はスキップ
                    raf.seek(raf.filePointer + chunkSize.toLong())
                    // RIFF パディング: チャンクサイズが奇数の場合 1 バイト追加スキップ
                    if (chunkSize % 2 != 0) {
                        raf.seek(raf.filePointer + 1)
                    }
                }
            }
        }
    }

    /**
     * Read all PCM samples from the data chunk into a [FloatArray].
     *
     * **WARNING**: Allocates memory proportional to the entire PCM data.
     * For files larger than ~50 million samples (~200 MB FloatArray), use
     * [readSamplesChunked] instead to avoid [OutOfMemoryError].
     *
     * Supports 32-bit float (H1e native), 16-bit PCM, 24-bit PCM, and 8-bit PCM.
     * For integer PCM formats, samples are normalized to [-1.0, 1.0].
     * For IEEE float format, samples are already in float range.
     *
     * Returns interleaved channel data: [L0, R0, L1, R1, ...] for stereo.
     *
     * @throws IllegalArgumentException if the audio format is unsupported
     * @throws IllegalStateException if the file exceeds [MAX_MEMORY_SAMPLE_COUNT] samples
     */
    fun readSamples(filePath: String, wavInfo: WavInfo): FloatArray {
        // 対応形式の検証
        val audioFmt = wavInfo.audioFormat.toInt()
        val bits = wavInfo.bitsPerSample.toInt()
        val bytesPerSample = bits / 8
        val isFloat = audioFmt == 3 && bytesPerSample == 4
        val isPcmInt = audioFmt == 1
        val supported = isFloat || (isPcmInt && (bytesPerSample == 1 || bytesPerSample == 2 || bytesPerSample == 3))
        require(supported) {
            "未対応のオーディオ形式です: audioFormat=$audioFmt, bitsPerSample=$bits"
        }

        val numFloats = (wavInfo.dataSize / bytesPerSample).toInt()

        // 安全上限チェック: 超過時は chunked API を促す
        require(numFloats <= MAX_MEMORY_SAMPLE_COUNT) {
            "ファイルが大きすぎます（${numFloats.toLong() * 4 / (1024 * 1024)} MB のメモリが必要です）。" +
                "代わりに readSamplesChunked() を使用してください。"
        }

        val result = FloatArray(numFloats)

        RandomAccessFile(filePath, "r").use { raf ->
            raf.seek(wavInfo.dataOffset)
            val chunkSize = 4096
            val rawBuf = ByteArray(chunkSize)
            val floatBuf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
            var floatIdx = 0

            while (floatIdx < numFloats) {
                val rawMax = minOf(rawBuf.size, (numFloats - floatIdx) * bytesPerSample)
                // サンプル境界に切り下げ（中途半端なサンプルを読まない）
                val bytesToRead = (rawMax / bytesPerSample) * bytesPerSample
                if (bytesToRead <= 0) break
                val bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                floatBuf.clear()
                floatBuf.put(rawBuf, 0, bytesRead)
                floatBuf.flip()

                val count = bytesRead / bytesPerSample
                when {
                    isFloat -> {
                        // 32-bit IEEE float (H1e native, audioFormat=3)
                        for (i in 0 until count) {
                            result[floatIdx + i] = floatBuf.float
                        }
                    }

                    isPcmInt && bytesPerSample == 2 -> {
                        // 16-bit signed PCM
                        for (i in 0 until count) {
                            result[floatIdx + i] = floatBuf.short.toFloat() / 32768f
                        }
                    }

                    isPcmInt && bytesPerSample == 3 -> {
                        // 24-bit signed PCM (3 bytes per sample)
                        for (i in 0 until count) {
                            val offset = i * 3
                            var sample = (rawBuf[offset].toInt() and 0xFF) or
                                ((rawBuf[offset + 1].toInt() and 0xFF) shl 8) or
                                ((rawBuf[offset + 2].toInt() and 0xFF) shl 16)
                            if ((sample and 0x800000) != 0) {
                                sample = sample or 0xFF000000.toInt()
                            }
                            result[floatIdx + i] = sample.toFloat() / 8388608f
                        }
                        // ByteBuffer の位置を進める
                        floatBuf.position(floatBuf.position() + bytesRead)
                    }

                    isPcmInt && bytesPerSample == 1 -> {
                        // 8-bit unsigned PCM
                        for (i in 0 until count) {
                            val s = rawBuf[i].toInt() and 0xFF
                            result[floatIdx + i] = (s - 128) / 128f
                        }
                        floatBuf.position(floatBuf.position() + bytesRead)
                    }

                    else -> {
                        // ここには到達しない（事前検証済み）
                        throw IllegalStateException("Unexpected format")
                    }
                }
                floatIdx += count
            }
        }

        return result
    }

    /**
     * Convenience: parse header and read all float samples at once.
     *
     * **WARNING**: See [readSamples] for memory considerations with large files.
     */
    fun decode(filePath: String): Pair<WavInfo, FloatArray>? {
        val info = parseHeader(filePath) ?: return null
        val samples = readSamples(filePath, info)
        return info to samples
    }

    /**
     * Stream PCM samples from a WAV file in chunks, invoking [onChunk] for each.
     *
     * Memory usage is O(chunkFrames * numChannels), independent of total file size.
     * This is the recommended API for processing large WAV files.
     *
     * Supports the same formats as [readSamples].
     *
     * @param filePath path to the WAV file
     * @param wavInfo parsed WAV header information
     * @param chunkFrames number of sample frames per chunk (default 65536; ~1.5s at 44.1kHz)
     * @param onChunk callback receiving (samples, startFrame, numFrames) for each chunk.
     *        `samples` is interleaved: [L0, R0, L1, R1, ...] for stereo.
     *        `startFrame` is the absolute frame index of the first frame in this chunk.
     *        `numFrames` is the number of frames in this chunk (may be less than chunkFrames for the final chunk).
     *
     * @throws IllegalArgumentException if the audio format is unsupported
     */
    fun readSamplesChunked(
        filePath: String,
        wavInfo: WavInfo,
        chunkFrames: Int = 65536,
        onChunk: (samples: FloatArray, startFrame: Long, numFrames: Int) -> Unit,
    ) {
        val audioFmt = wavInfo.audioFormat.toInt()
        val bits = wavInfo.bitsPerSample.toInt()
        val bytesPerSample = bits / 8
        val channels = wavInfo.numChannels.toInt()
        val bytesPerFrame = channels * bytesPerSample
        val isFloat = audioFmt == 3 && bytesPerSample == 4
        val isPcmInt = audioFmt == 1
        val supported = isFloat || (isPcmInt && (bytesPerSample == 1 || bytesPerSample == 2 || bytesPerSample == 3))
        require(supported) {
            "未対応のオーディオ形式です: audioFormat=$audioFmt, bitsPerSample=$bits"
        }

        val chunkBytes = chunkFrames * bytesPerFrame
        val rawBuf = ByteArray(chunkBytes)
        val floatBuf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

        RandomAccessFile(filePath, "r").use { raf ->
            raf.seek(wavInfo.dataOffset)

            var startFrame = 0L
            var remainingBytes = wavInfo.dataSize

            while (remainingBytes > 0) {
                val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                val bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                val framesInChunk = bytesRead / bytesPerFrame
                if (framesInChunk == 0) break

                floatBuf.clear()
                floatBuf.put(rawBuf, 0, bytesRead)
                floatBuf.flip()

                val numFloats = framesInChunk * channels
                val samples = FloatArray(numFloats)
                val count = framesInChunk * channels

                when {
                    isFloat -> {
                        for (i in 0 until count) {
                            samples[i] = floatBuf.float
                        }
                    }
                    isPcmInt && bytesPerSample == 2 -> {
                        for (i in 0 until count) {
                            samples[i] = floatBuf.short.toFloat() / 32768f
                        }
                    }
                    isPcmInt && bytesPerSample == 3 -> {
                        for (i in 0 until framesInChunk) {
                            val offset = i * 3
                            var sample = (rawBuf[offset].toInt() and 0xFF) or
                                ((rawBuf[offset + 1].toInt() and 0xFF) shl 8) or
                                ((rawBuf[offset + 2].toInt() and 0xFF) shl 16)
                            if ((sample and 0x800000) != 0) {
                                sample = sample or 0xFF000000.toInt()
                            }
                            val valf = sample.toFloat() / 8388608f
                            for (ch in 0 until channels) {
                                samples[i * channels + ch] = valf
                            }
                        }
                    }
                    isPcmInt && bytesPerSample == 1 -> {
                        for (i in 0 until framesInChunk) {
                            val s = rawBuf[i].toInt() and 0xFF
                            val valf = (s - 128) / 128f
                            for (ch in 0 until channels) {
                                samples[i * channels + ch] = valf
                            }
                        }
                    }
                }

                onChunk(samples, startFrame, framesInChunk)

                startFrame += framesInChunk
                remainingBytes -= bytesRead
            }
        }
    }
}
