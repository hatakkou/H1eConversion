package com.example.h1econversion.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.h1econversion.model.CodecType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 音声ファイル変換ユーティリティ。
 *
 * Android 標準の [MediaCodec] + [MediaMuxer] を使用し、
 * WAV ファイルにゲインを適用した後、指定された形式に変換します。
 *
 * 対応出力形式:
 *   - AAC (.m4a コンテナ / .aac raw ADTS)
 *   - PCM WAV パススルー（ゲインのみ適用、無圧縮）
 *
 * 変換パイプライン (AAC):
 *   1. WAV を float で読み込み、ゲインを乗算
 *   2. soft-clip で [-1.0, 1.0] に収める
 *   3. 16-bit PCM に変換
 *   4. MediaCodec (AAC-LC) でエンコード
 *   5. MediaMuxer でコンテナに格納（.m4a）または ADTS ヘッダー付き raw 出力（.aac）
 *
 * 変換パイプライン (PCM WAV):
 *   1. WAV を float で読み込み、ゲインを乗算
 *   2. soft-clip で [-1.0, 1.0] に収める
 *   3. 32-bit float WAV として書き出し
 */
object MediaCodecConverter {

    private const val TAG = "MediaCodecConverter"

    /** デフォルトの AAC 出力ビットレート (bps) */
    const val AAC_BITRATE = 192_000

    /** 1チャンクあたりのフレーム数 */
    private const val CHUNK_FRAMES = 65536

    /** AAC エンコーダーの MIME タイプ */
    private const val AAC_MIME = "audio/mp4a-latm"

/**
 * tanh(x) の高速有理多項式近似（7次 Lambert 近似）。
 * 
 * 音声処理のソフトクリップ用途に最適化されており、大入力時の発散防止、
 * 特殊値（Inf）のクランプ、および不測の NaN 入力を無音化（0.0f）する
 * ハードサニタイズ処理を含んでいます。
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun fastTanh(x: Float): Float {
    // 1. NaN のハードサニタイズ
    // 下流のオーディオパイプラインや AAC エンコーダーの破壊を防ぐため、NaN は無音（0.0f）に置き換えます。
    if (x.isNaN()) return 0.0f

    // 2. 高速な絶対値取得（JVM の JIT によってビット演算命令 andps 等に直接置換されるため、分岐が発生しません）
    val absX = abs(x)

    // 3. クランプ処理と極限トランジション（単一分岐）
    // 閾値を 4.97f とすることで、境界における不連続性（段差）を約 5 ULP に抑え、
    // 特殊値（Infinity）も自動的に ±1.0f に安全にフォールバックさせます。
    if (absX >= 4.97f) {
        return if (x < 0.0f) -1.0f else 1.0f
    }

    // 4. 有理多項式近似（Horner 法による記述、すべて 32bit Float 計算を維持）
    val x2 = x * x
    val num = x * (135135.0f + x2 * (17325.0f + x2 * (378.0f + x2)))
    val den = 135135.0f + x2 * (62370.0f + x2 * (3150.0f + x2 * 28.0f))
    
    return num / den
}

    /** 変換結果 */
    sealed interface Result {
        data class Success(val outputFile: java.io.File) : Result
        data class Error(val message: String) : Result
    }

    /**
     * WAV ファイルを指定された形式に変換します。
     *
     * @param inputPath 入力 WAV ファイルのパス
     * @param outputPath 出力ファイルのパス
     * @param gain リニアゲイン倍率（1.0 = 0dB, 5.0 = +14dB）
     * @param bitrate AAC の出力ビットレート (bps)、PCM 時は無視
     * @param codecType 出力コーデック種別
     * @param containerExtension 出力コンテナの拡張子（"m4a", "aac", "wav"）
     * @param onProgress 進捗ログを受け取るコールバック（オプション）
     */
    suspend fun convert(
        inputPath: String,
        outputPath: String,
        gain: Float,
        bitrate: Int = AAC_BITRATE,
        codecType: CodecType = CodecType.AAC,
        containerExtension: String = "m4a",
        onProgress: ((String) -> Unit)? = null,
    ): Result =
        withContext(Dispatchers.IO) {
            try {
                val inputFile = java.io.File(inputPath)
                if (!inputFile.exists()) {
                    return@withContext Result.Error("入力ファイルが見つかりません: $inputPath")
                }

                val wavInfo = WavDecoder.parseHeader(inputPath)
                    ?: return@withContext Result.Error("WAV ファイルを解析できません")

                val outputFile = java.io.File(outputPath)
                outputFile.parentFile?.mkdirs()

                val clampedGain = gain.coerceIn(0f, 100f)
                onProgress?.invoke("変換を開始します")
                Log.d(TAG, "変換開始: $inputPath -> $outputPath (gain=$clampedGain, codec=$codecType, bitrate=$bitrate, container=$containerExtension)")

                when (codecType) {
                    CodecType.PCM_WAV -> {
                        writeWavWithGain(inputPath, outputPath, wavInfo, clampedGain, onProgress)
                    }
                    CodecType.AAC -> {
                        if (containerExtension == "aac") {
                            encodeAacRaw(inputPath, outputPath, wavInfo, clampedGain, bitrate, onProgress)
                        } else {
                            encodeAac(inputPath, outputPath, wavInfo, clampedGain, bitrate, onProgress)
                        }
                    }
                }

                onProgress?.invoke("変換が完了しました")
                Log.d(TAG, "変換完了: $outputPath")
                Result.Success(outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "変換失敗", e)
                Result.Error("変換中にエラーが発生しました: ${e.message}")
            }
        }

    /**
     * AAC エンコード + MPEG-4 Muxer (.m4a) 出力。
     */
    private fun encodeAac(
        inputPath: String,
        outputPath: String,
        wavInfo: WavInfo,
        gain: Float,
        bitrate: Int,
        onProgress: ((String) -> Unit)?,
    ) {
        val sampleRate = wavInfo.sampleRate
        val channels = wavInfo.numChannels.toInt()
        val bitsPerSample = wavInfo.bitsPerSample.toInt()
        val bytesPerSample = bitsPerSample / 8
        val bytesPerFrame = channels * bytesPerSample
        if (bytesPerFrame <= 0) {
            throw IllegalStateException("不正な WAV フォーマットです: channels=$channels, bits=$bitsPerSample")
        }
        val isFloat = wavInfo.audioFormat.toInt() == 3 && bytesPerSample == 4
        val totalFrames = wavInfo.dataSize / bytesPerFrame

        onProgress?.invoke("入力: ${wavInfo.sampleRate}Hz / ${channels}ch / ${bitsPerSample}bit" +
                if (isFloat) " float" else " PCM")
        onProgress?.invoke("総フレーム数: $totalFrames")
        onProgress?.invoke("出力: AAC-LC / ${bitrate / 1000}kbps / M4A")

        // AAC エンコーダー設定
        val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        onProgress?.invoke("AACエンコーダーを起動しました")

        val muxer = MediaMuxer(
            outputPath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        val raf = RandomAccessFile(inputPath, "r")
        raf.seek(wavInfo.dataOffset)

        var muxerStarted = false
        var trackIndex = -1
        var inputDone = false
        var outputDone = false

        val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
        var remainingBytes: Long = wavInfo.dataSize
        var frameIndex: Long = 0

        // 入力バッファ分割用: pcmBuf を複数のコーデック入力バッファに分割して投入する
        var pendingPcm: ByteArray? = null
        var pendingPcmOffset = 0
        var framesSubmitted: Long = 0

        val bufferInfo = MediaCodec.BufferInfo()

        // 進捗報告用: 前回報告時の処理済みフレーム数（10万フレームごとに報告）
        var lastReportedFrames: Long = 0
        val progressReportInterval = 100_000L

        try {
            while (!outputDone) {
                // ---- 入力: WAV → gain → 16-bit PCM ----
                if (!inputDone) {
                    val inputBufIdx = codec.dequeueInputBuffer(10_000)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)
                            ?: continue

                        if (pendingPcm == null && remainingBytes > 0) {
                            // 新しいチャンクをファイルから読み込み PCM 変換
                            val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                            val bytesRead = raf.read(rawBuf, 0, bytesToRead)

                            if (bytesRead > 0) {
                                pendingPcm = convertTo16BitPcm(
                                    rawBuf, bytesRead, bytesPerSample, isFloat, gain,
                                )
                                pendingPcmOffset = 0
                                remainingBytes -= bytesRead
                                frameIndex += bytesRead / bytesPerFrame

                                // 進捗報告: Nフレームごと
                                val processedFrames = frameIndex
                                if (processedFrames - lastReportedFrames >= progressReportInterval) {
                                    val pct = if (totalFrames > 0) (processedFrames * 100 / totalFrames).toInt() else 0
                                    onProgress?.invoke(
                                        "読み込み中: $processedFrames / $totalFrames フレーム ($pct%)"
                                    )
                                    lastReportedFrames = processedFrames
                                }
                            }
                        }

                        if (pendingPcm != null) {
                            // 入力バッファ容量を超えないよう分割して書き込む
                            val remaining = pendingPcm!!.size - pendingPcmOffset
                            val capacity = inputBuf.remaining()
                            val chunkSize = minOf(remaining, capacity)
                            inputBuf.clear()
                            inputBuf.put(pendingPcm!!, pendingPcmOffset, chunkSize)
                            pendingPcmOffset += chunkSize

                            val chunkFrames = (chunkSize / (channels * 2)).toLong()
                            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                            framesSubmitted += chunkFrames

                            if (pendingPcmOffset >= pendingPcm!!.size) {
                                pendingPcm = null // 全消費
                            }

                            codec.queueInputBuffer(
                                inputBufIdx, 0, chunkSize,
                                presentationTimeUs, 0,
                            )
                        } else {
                            // 全 PCM データ送信完了 → EOS シグナル
                            onProgress?.invoke("全PCMデータを送信しました (EOS)")
                            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                            codec.queueInputBuffer(
                                inputBufIdx, 0, 0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                // ---- 出力: エンコードデータを Muxer へ ----
                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufIdx >= 0 -> {
                        val outputBuf = codec.getOutputBuffer(outputBufIdx)
                            ?: run { codec.releaseOutputBuffer(outputBufIdx, false); continue }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // CSD はスキップ（Muxer が自動で処理）
                            codec.releaseOutputBuffer(outputBufIdx, false)
                        } else if (bufferInfo.size > 0) {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            outputBuf.position(bufferInfo.offset)
                            outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuf, bufferInfo)
                            codec.releaseOutputBuffer(outputBufIdx, false)
                        } else {
                            codec.releaseOutputBuffer(outputBufIdx, false)
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            onProgress?.invoke("Muxerを開始、AAC出力を書き込み中...")
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * WAV の生バイト列 → ゲイン適用 → 16-bit PCM に変換。
     *
     * FloatArray / ShortArray による一括処理で、サンプル単位の ByteBuffer アクセスと
     * [kotlin.math.tanh] 呼び出しを回避し、大幅な高速化を実現。
     *
     * @param rawBuf 生バイト列
     * @param bytesRead 有効バイト数
     * @param bytesPerSample 1サンプルあたりのバイト数
     * @param isFloat 入力が 32-bit IEEE float か
     * @param gain リニアゲイン倍率
     * @return 16-bit PCM バイト列（リトルエンディアン）
     */
    private fun convertTo16BitPcm(
        rawBuf: ByteArray,
        bytesRead: Int,
        bytesPerSample: Int,
        isFloat: Boolean,
        gain: Float,
    ): ByteArray {
        val numSamples = bytesRead / bytesPerSample
        val outBuf = ByteArray(numSamples * 2)
        val outShorts = ShortArray(numSamples)

        when {
            isFloat -> {
                // 32-bit float → FloatArray に一括読み込み
                val inFloats = FloatArray(numSamples)
                ByteBuffer.wrap(rawBuf, 0, bytesRead)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(inFloats)

                for (i in 0 until numSamples) {
                    val amplified = fastTanh(inFloats[i] * gain)
                    outShorts[i] = (amplified * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
            }
            bytesPerSample == 2 -> {
                // 16-bit PCM → ShortArray に一括読み込み
                val inShorts = ShortArray(numSamples)
                ByteBuffer.wrap(rawBuf, 0, bytesRead)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(inShorts)

                for (i in 0 until numSamples) {
                    val sampleFloat = inShorts[i].toFloat() / 32768f
                    val amplified = fastTanh(sampleFloat * gain)
                    outShorts[i] = (amplified * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
            }
            else -> {
                // 24-bit / 8-bit は分岐が少ないので ByteBuffer 直接アクセスを維持
                val bb = ByteBuffer.wrap(rawBuf, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until numSamples) {
                    val sampleFloat: Float = when (bytesPerSample) {
                        3 -> {
                            val b0 = bb.get().toInt() and 0xFF
                            val b1 = bb.get().toInt() and 0xFF
                            val b2 = bb.get().toInt() and 0xFF
                            var sample = b0 or (b1 shl 8) or (b2 shl 16)
                            if (sample and 0x800000 != 0) sample = sample or 0xFF000000.toInt()
                            sample.toFloat() / 8388608f
                        }
                        1 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                        else -> 0f
                    }
                    val amplified = fastTanh(sampleFloat * gain)
                    outShorts[i] = (amplified * 32767f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                }
            }
        }

        // ShortArray → ByteArray に一括書き込み
        ByteBuffer.wrap(outBuf)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(outShorts)

        return outBuf
    }

    // ========================================================================
    // PCM WAV パススルー出力（ゲインのみ適用、無圧縮）
    // ========================================================================

    /**
     * WAV ファイルを読み込み、ゲインを適用して新しい 32-bit float WAV として出力します。
     * エンコードは行わず、PCM データをそのままコピー（ゲイン適用後）します。
     */
    private fun writeWavWithGain(
        inputPath: String,
        outputPath: String,
        wavInfo: WavInfo,
        gain: Float,
        onProgress: ((String) -> Unit)?,
    ) {
        val sampleRate = wavInfo.sampleRate
        val channels = wavInfo.numChannels.toInt()
        val bitsPerSample = wavInfo.bitsPerSample.toInt()
        val bytesPerSample = bitsPerSample / 8
        val bytesPerFrame = channels * bytesPerSample
        if (bytesPerFrame <= 0) {
            throw IllegalStateException("不正な WAV フォーマットです: channels=$channels, bits=$bitsPerSample")
        }
        val isFloat = wavInfo.audioFormat.toInt() == 3 && bytesPerSample == 4
        val totalFrames = wavInfo.dataSize / bytesPerFrame

        onProgress?.invoke("入力: ${sampleRate}Hz / ${channels}ch / ${bitsPerSample}bit" +
                if (isFloat) " float" else " PCM")
        onProgress?.invoke("総フレーム数: $totalFrames")
        onProgress?.invoke("出力: PCM WAV（ゲインのみ適用）")

        val raf = RandomAccessFile(inputPath, "r")
        val fos = FileOutputStream(outputPath)

        try {
            // WAV ヘッダー書き込み（出力は常に 32-bit float）
            val outputBitsPerSample = 32
            val outputBytesPerSample = outputBitsPerSample / 8 // = 4
            val outputBytesPerFrame = channels * outputBytesPerSample
            val dataSize = wavInfo.dataSize // フレーム数は変わらないが、1サンプルあたりのバイト数が異なる可能性がある
            // 入力と出力で bytesPerSample が異なる場合のデータサイズ補正
            val inputTotalSamples = dataSize / bytesPerSample
            val outputDataSize = inputTotalSamples * outputBytesPerSample

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + outputDataSize).toInt()) // file size - 8
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // fmt chunk size
            header.putShort(3) // audio format: IEEE float
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * outputBytesPerFrame) // byte rate
            header.putShort(outputBytesPerFrame.toShort()) // block align
            header.putShort(outputBitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(outputDataSize.toInt())
            fos.write(header.array())

            // データ部: チャンクごとに読み込み・ゲイン適用・書き出し
            raf.seek(wavInfo.dataOffset)
            val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
            var remainingBytes: Long = wavInfo.dataSize
            var totalFramesRead: Long = 0
            val progressReportInterval = 100_000L
            var lastReportedFrames: Long = 0

            while (remainingBytes > 0) {
                val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                val bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                val numSamples = bytesRead / bytesPerSample

                // FloatArray に一括読み込み
                val inFloats = FloatArray(numSamples)
                val bb = ByteBuffer.wrap(rawBuf, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                when {
                    isFloat -> bb.asFloatBuffer().get(inFloats)
                    bytesPerSample == 2 -> {
                        val inShorts = ShortArray(numSamples)
                        bb.asShortBuffer().get(inShorts)
                        for (i in 0 until numSamples) {
                            inFloats[i] = inShorts[i].toFloat() / 32768f
                        }
                    }
                    bytesPerSample == 3 -> {
                        for (i in 0 until numSamples) {
                            val b0 = bb.get().toInt() and 0xFF
                            val b1 = bb.get().toInt() and 0xFF
                            val b2 = bb.get().toInt() and 0xFF
                            var sample = b0 or (b1 shl 8) or (b2 shl 16)
                            if (sample and 0x800000 != 0) sample = sample or 0xFF000000.toInt()
                            inFloats[i] = sample.toFloat() / 8388608f
                        }
                    }
                    bytesPerSample == 1 -> {
                        for (i in 0 until numSamples) {
                            inFloats[i] = ((bb.get().toInt() and 0xFF) - 128) / 128f
                        }
                    }
                }

                // ゲイン適用 + fastTanh → FloatArray に書き戻し
                val outFloats = FloatArray(numSamples)
                for (i in 0 until numSamples) {
                    outFloats[i] = fastTanh(inFloats[i] * gain)
                }

                // FloatArray → ByteArray → ファイル書き出し
                val outBuf = ByteArray(numSamples * 4)
                ByteBuffer.wrap(outBuf)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .put(outFloats)

                fos.write(outBuf)

                totalFramesRead += bytesRead / bytesPerFrame
                remainingBytes -= bytesRead

                if (totalFramesRead - lastReportedFrames >= progressReportInterval) {
                    val pct = if (totalFrames > 0) (totalFramesRead * 100 / totalFrames).toInt() else 0
                    onProgress?.invoke(
                        "処理中: $totalFramesRead / $totalFrames フレーム ($pct%)"
                    )
                    lastReportedFrames = totalFramesRead
                }
            }

            onProgress?.invoke("PCM WAV 出力が完了しました")
        } finally {
            try { raf.close() } catch (_: Exception) {}
            try { fos.close() } catch (_: Exception) {}
        }
    }

    // ========================================================================
    // Raw AAC (.aac) 出力（ADTS ヘッダー付き、MediaMuxer 不使用）
    // ========================================================================

    /**
     * AAC エンコード + ADTS ヘッダー付き raw 出力 (.aac)。
     * MediaMuxer を使用せず、AAC フレームごとに ADTS ヘッダーを付与して直接ファイルに書き出します。
     */
    private fun encodeAacRaw(
        inputPath: String,
        outputPath: String,
        wavInfo: WavInfo,
        gain: Float,
        bitrate: Int,
        onProgress: ((String) -> Unit)?,
    ) {
        val sampleRate = wavInfo.sampleRate
        val channels = wavInfo.numChannels.toInt()
        val bitsPerSample = wavInfo.bitsPerSample.toInt()
        val bytesPerSample = bitsPerSample / 8
        val bytesPerFrame = channels * bytesPerSample
        if (bytesPerFrame <= 0) {
            throw IllegalStateException("不正な WAV フォーマットです: channels=$channels, bits=$bitsPerSample")
        }
        val isFloat = wavInfo.audioFormat.toInt() == 3 && bytesPerSample == 4
        val totalFrames = wavInfo.dataSize / bytesPerFrame

        onProgress?.invoke("入力: ${sampleRate}Hz / ${channels}ch / ${bitsPerSample}bit" +
                if (isFloat) " float" else " PCM")
        onProgress?.invoke("総フレーム数: $totalFrames")
        onProgress?.invoke("出力: AAC-LC / ${bitrate / 1000}kbps / Raw AAC (ADTS)")

        // ADTS パラメータ事前計算
        val freqIndex = getAdtsFreqIndex(sampleRate)
        val profile = 2 // AAC-LC audio object type（ADTS では profile-1 → 1）
        val chanConfig = channels

        // AAC エンコーダー設定
        val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        onProgress?.invoke("AACエンコーダーを起動しました（raw ADTS 出力）")

        // 出力ファイルストリーム（ADTS ヘッダー付き raw AAC）
        val fos = FileOutputStream(outputPath)

        val raf = RandomAccessFile(inputPath, "r")
        raf.seek(wavInfo.dataOffset)

        var inputDone = false
        var outputDone = false

        val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
        var remainingBytes: Long = wavInfo.dataSize
        var frameIndex: Long = 0

        var pendingPcm: ByteArray? = null
        var pendingPcmOffset = 0
        var framesSubmitted: Long = 0

        val bufferInfo = MediaCodec.BufferInfo()

        var lastReportedFrames: Long = 0
        val progressReportInterval = 100_000L

        try {
            while (!outputDone) {
                // ---- 入力: WAV → gain → 16-bit PCM ----
                if (!inputDone) {
                    val inputBufIdx = codec.dequeueInputBuffer(10_000)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx) ?: continue

                        if (pendingPcm == null && remainingBytes > 0) {
                            val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                            val bytesRead = raf.read(rawBuf, 0, bytesToRead)
                            if (bytesRead > 0) {
                                pendingPcm = convertTo16BitPcm(rawBuf, bytesRead, bytesPerSample, isFloat, gain)
                                pendingPcmOffset = 0
                                remainingBytes -= bytesRead
                                frameIndex += bytesRead / bytesPerFrame

                                val processedFrames = frameIndex
                                if (processedFrames - lastReportedFrames >= progressReportInterval) {
                                    val pct = if (totalFrames > 0) (processedFrames * 100 / totalFrames).toInt() else 0
                                    onProgress?.invoke(
                                        "読み込み中: $processedFrames / $totalFrames フレーム ($pct%)"
                                    )
                                    lastReportedFrames = processedFrames
                                }
                            }
                        }

                        if (pendingPcm != null) {
                            val remaining = pendingPcm!!.size - pendingPcmOffset
                            val capacity = inputBuf.remaining()
                            val chunkSize = minOf(remaining, capacity)
                            inputBuf.clear()
                            inputBuf.put(pendingPcm!!, pendingPcmOffset, chunkSize)
                            pendingPcmOffset += chunkSize

                            val chunkFrames = (chunkSize / (channels * 2)).toLong()
                            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                            framesSubmitted += chunkFrames

                            if (pendingPcmOffset >= pendingPcm!!.size) {
                                pendingPcm = null
                            }

                            codec.queueInputBuffer(
                                inputBufIdx, 0, chunkSize,
                                presentationTimeUs, 0,
                            )
                        } else {
                            onProgress?.invoke("全PCMデータを送信しました (EOS)")
                            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                            codec.queueInputBuffer(
                                inputBufIdx, 0, 0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                // ---- 出力: AAC フレーム + ADTS ヘッダー → ファイル ----
                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufIdx >= 0 -> {
                        val outputBuf = codec.getOutputBuffer(outputBufIdx)
                            ?: run { codec.releaseOutputBuffer(outputBufIdx, false); continue }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // CSD はスキップ（ADTS では不要）
                            codec.releaseOutputBuffer(outputBufIdx, false)
                        } else if (bufferInfo.size > 0) {
                            // ADTS ヘッダーを出力し、続けて AAC 生データを書き込む
                            val aacFrame = ByteArray(bufferInfo.size)
                            outputBuf.position(bufferInfo.offset)
                            outputBuf.get(aacFrame, 0, bufferInfo.size)

                            val adtsHeader = buildAdtsHeader(
                                aacFrameSize = bufferInfo.size + 7,
                                profile = profile,
                                freqIndex = freqIndex,
                                chanConfig = chanConfig,
                            )
                            fos.write(adtsHeader)
                            fos.write(aacFrame)

                            codec.releaseOutputBuffer(outputBufIdx, false)
                        } else {
                            codec.releaseOutputBuffer(outputBufIdx, false)
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Raw AAC 出力では出力フォーマット変更は特に対応不要
                        onProgress?.invoke("AACエンコーダー出力準備完了、raw 出力を書き込み中...")
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
            try { fos.close() } catch (_: Exception) {}
        }
    }

    /**
     * ADTS ヘッダー（7バイト）を構築します。
     *
     * ADTS ヘッダー構造:
     *   AAAA AAAA AAAA BCCD EEFF FGHH HIJK LMNO
     *   A: sync word (0xFFF)
     *   B: MPEG version (0=MPEG-4)
     *   C: Layer (0)
     *   D: Protection absent (1=no CRC)
     *   E: Profile（呼び出し元が audio object type を渡す。ADTS では objectType-1 で格納。AAC-LC=2 → ADTS profile=1）
     *   F: Sampling frequency index
     *   G: Private bit (0)
     *   H: Channel configuration
     *   I: Original/copy (0)
     *   J: Home (0)
     *   K: Copyrighted ID (0)
     *   L: Copyrighted ID start (0)
     *   M: Frame length (13 bits: header + raw AAC)
     *   N: Buffer fullness (0x7FF = VBR)
     */
    private fun buildAdtsHeader(
        aacFrameSize: Int,
        profile: Int,
        freqIndex: Int,
        chanConfig: Int,
    ): ByteArray {
        // profile in ADTS is (audio object type - 1), so AAC-LC(2) → 1
        val adtsProfile = (profile - 1).coerceIn(0, 3)

        val header = ByteArray(7)
        // sync word 0xFFF (12 bits)
        header[0] = 0xFF.toByte()
        // MPEG version=0, Layer=0, protection absent=1 → 0b11110001 = 0xF1
        header[1] = 0xF1.toByte()
        // profile(2bit) + freqIndex(4bit) + chanConfig high 1bit
        header[2] = ((adtsProfile shl 6) or (freqIndex shl 2) or ((chanConfig shr 2) and 0x01)).toByte()
        // chanConfig low 2bit + frameLength high 2bit
        header[3] = (((chanConfig and 0x03) shl 6) or ((aacFrameSize shr 11) and 0x03)).toByte()
        // frameLength middle 8bit
        header[4] = ((aacFrameSize shr 3) and 0xFF).toByte()
        // frameLength low 3bit + buffer fullness high 5bit (0x7FF = VBR)
        header[5] = (((aacFrameSize and 0x07) shl 5) or 0x1F).toByte()
        // buffer fullness low 6bit (0x3F) + number_of_raw_data_blocks(0) → 0xFC
        header[6] = 0xFC.toByte()

        return header
    }

    /**
     * サンプルレートから ADTS の sampling frequency index を取得します。
     */
    private fun getAdtsFreqIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 3 // デフォルト: 48000 Hz
        }
    }
}
