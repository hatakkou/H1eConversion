package com.example.h1econversion.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 32-bit float WAV → AAC (.m4a) 変換ユーティリティ。
 *
 * Android 標準の [MediaCodec] + [MediaMuxer] を使用し、
 * 32-bit float WAV ファイルにゲインを適用した後、AAC にエンコードします。
 * 外部依存は一切不要です。
 *
 * 変換パイプライン:
 *   1. WAV を float で読み込み、ゲインを乗算
 *   2. soft-clip で [-1.0, 1.0] に収める
 *   3. 16-bit PCM に変換
 *   4. MediaCodec (AAC-LC) でエンコード
 *   5. MediaMuxer で .m4a コンテナに格納
 */
object MediaCodecConverter {

    private const val TAG = "MediaCodecConverter"

    /** AAC 出力ビットレート (bps) */
    const val AAC_BITRATE = 192_000

    /** 1チャンクあたりのフレーム数 */
    private const val CHUNK_FRAMES = 4096

    /** AAC エンコーダーの MIME タイプ */
    private const val AAC_MIME = "audio/mp4a-latm"

    /** 変換結果 */
    sealed interface Result {
        data class Success(val outputFile: java.io.File) : Result
        data class Error(val message: String) : Result
    }

    /**
     * WAV ファイルを AAC (.m4a) に変換します。
     *
     * @param inputPath 入力 WAV ファイルのパス
     * @param outputPath 出力 .m4a ファイルのパス
     * @param gain リニアゲイン倍率（1.0 = 0dB, 5.0 = +14dB）
     */
    suspend fun convert(inputPath: String, outputPath: String, gain: Float): Result =
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
                Log.d(TAG, "AAC変換開始: $inputPath -> $outputPath (gain=$clampedGain)")

                encodeAac(inputPath, outputPath, wavInfo, clampedGain)

                Log.d(TAG, "AAC変換完了: $outputPath")
                Result.Success(outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "AAC変換失敗", e)
                Result.Error("変換中にエラーが発生しました: ${e.message}")
            }
        }

    /**
     * 実際のエンコード処理。
     */
    private fun encodeAac(
        inputPath: String,
        outputPath: String,
        wavInfo: WavInfo,
        gain: Float,
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

        // AAC エンコーダー設定
        val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

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
        val bb = ByteBuffer.wrap(rawBuf, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteArray(numSamples * 2)
        val outBb = ByteBuffer.wrap(outBuf).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val sampleFloat: Float = when {
                isFloat -> bb.float
                bytesPerSample == 2 -> bb.short.toFloat() / 32768f
                bytesPerSample == 3 -> {
                    val b0 = bb.get().toInt() and 0xFF
                    val b1 = bb.get().toInt() and 0xFF
                    val b2 = bb.get().toInt() and 0xFF
                    var sample = b0 or (b1 shl 8) or (b2 shl 16)
                    if (sample and 0x800000 != 0) sample = sample or 0xFF000000.toInt()
                    sample.toFloat() / 8388608f
                }
                bytesPerSample == 1 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                else -> 0f
            }

            // ゲイン適用 + tanh soft-clip（16-bit 変換前の最終段）
            val amplified = kotlin.math.tanh(sampleFloat * gain)

            val pcm16 = (amplified * 32767f).toInt().coerceIn(-32768, 32767)
            outBb.putShort(pcm16.toShort())
        }
        return outBuf
    }
}
