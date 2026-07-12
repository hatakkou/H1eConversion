package com.example.h1econversion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import java.io.RandomAccessFile
import kotlin.math.tanh

/**
 * Playback state.
 */
sealed interface PlayerState {
    data object Idle : PlayerState
    data object Playing : PlayerState
    data object Paused : PlayerState
    data object Finished : PlayerState
}

/**
 * AudioTrack-based player for WAV files.
 *
 * Streams 32-bit float PCM data from disk, applies real-time gain,
 * and reports the current playback position.
 *
 * Designed for 32-bit float recordings (H1e). Gain is applied as a
 * simple multiplier on the float samples — no quantization loss
 * because the source is already float.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        /** Number of float frames written per AudioTrack.write() call. */
        private const val CHUNK_FRAMES = 4096

        /**
         * tanh ベースのソフトクリッパー。
         * Android の AudioFlinger が float 値を [-1.0, 1.0] にハードクリップするのを防ぎ、
         * アナログライクな飽和特性で自然な音にします。
         *
         * tanh(2.0) ≒ 0.964 のため、5倍ゲインでも極端な歪みにならない。
         */
        fun softClip(x: Float): Float = tanh(x)
    }

    private var audioTrack: AudioTrack? = null
    private var currentFile: RandomAccessFile? = null

    /** 再生ジョブ用 CoroutineScope。release() 時に再生成されます。 */
    private var playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var _wavInfo: WavInfo? = null

    /** 再生ジョブの直列化用 Mutex */
    private val mutex = Mutex()
    /** 現在の再生ジョブ（キャンセル可能） */
    private var playbackJob: Job? = null

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _currentFrame = MutableStateFlow(0L)
    val currentFrame: StateFlow<Long> = _currentFrame.asStateFlow()

    private val _gain = MutableStateFlow(1.0f)
    val gain: StateFlow<Float> = _gain.asStateFlow()

    /** Duration in milliseconds (0 when nothing loaded). */
    val durationMs: Long get() = _wavInfo?.durationMs ?: 0L

    /** Current playback position in milliseconds. */
    val currentPositionMs: Long
        get() {
            val info = _wavInfo ?: return 0L
            if (info.sampleRate == 0) return 0L
            return _currentFrame.value * 1000L / info.sampleRate
        }

    /**
     * Set the playback volume as a linear gain multiplier.
     *
     * - 1.0f = original volume (0 dB)
     * - 0.5f = half volume (-6 dB)
     * - 5.0f = 5x volume (max, ~+14 dB)
     *
     * For 32-bit float audio, values > 1.0 do not cause internal clipping,
     * though the final DAC output may still saturate.
     */
    fun setGain(value: Float) {
        _gain.value = value.coerceIn(0f, 5.0f)
    }

    /**
     * Load a WAV file for playback. Does not start playing.
     */
    suspend fun load(filePath: String, wavInfo: WavInfo) {
        mutex.withLock {
            playbackJob?.cancel()
            playbackJob?.join()
            playbackJob = null
            releaseInternal()
            _wavInfo = wavInfo
            _currentFrame.value = 0L
            _state.value = PlayerState.Idle
            // ファイルハンドルは play() のたびに開くので、ここでは保持しない
        }
    }

    /**
     * 再生を開始または再開します。Mutex により直列化されます。
     */
    suspend fun play(filePath: String, wavInfo: WavInfo) {
        // 事前チェック（ロック外）
        if (_state.value is PlayerState.Playing) return

        mutex.withLock {
            // 二重チェック
            if (_state.value is PlayerState.Playing) return@withLock

            // 既存の再生ジョブをキャンセルして完了を待つ
            playbackJob?.cancel()
            playbackJob?.join()
            playbackJob = null

            val wasPaused = when (_state.value) {
                is PlayerState.Paused -> true
                else -> {
                    // リソースを解放してから新規開始
                    releaseInternal()
                    _wavInfo = wavInfo
                    false
                }
            }

            // Mutex 内で再生ループを起動（stop/seekTo/release との競合を防止）
            val info = _wavInfo ?: return@withLock
            val path = filePath
            playbackJob = playerScope.launch(Dispatchers.IO) {
                if (wasPaused) {
                    resume()
                } else {
                    startPlayback(path, info)
                }
            }
        }
    }

    /**
     * WavInfo から AudioTrack 用のエンコーディングとチャンネルマスクを取得します。
     * 未対応の形式の場合は例外をスローします。
     *
     * @return Pair(encoding, channelOut)
     */
    private fun validateAndGetEncoding(info: WavInfo): Pair<Int, Int> {
        val bits = info.bitsPerSample.toInt()
        val channels = info.numChannels.toInt()
        val audioFormat = info.audioFormat.toInt()

        // チャンネル数の検証
        val channelOut = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException(
                "未対応のチャンネル数です: $channels（1または2のみ対応）"
            )
        }

        // ビット深度と audioFormat の組み合わせ検証
        val encoding = when {
            audioFormat == 3 && bits == 32 -> AudioFormat.ENCODING_PCM_FLOAT  // IEEE float
            audioFormat == 1 && bits == 16 -> AudioFormat.ENCODING_PCM_16BIT   // 16-bit PCM
            audioFormat == 1 && bits == 8  -> AudioFormat.ENCODING_PCM_8BIT    // 8-bit PCM
            else -> throw IllegalArgumentException(
                "未対応のオーディオ形式です: audioFormat=$audioFormat, bitsPerSample=$bits"
            )
        }

        return encoding to channelOut
    }

    private suspend fun startPlayback(filePath: String, wavInfo: WavInfo) = withContext(Dispatchers.IO) {
        _wavInfo = wavInfo

        val (encoding, channelOut) = validateAndGetEncoding(wavInfo)
        val bits = wavInfo.bitsPerSample.toInt()
        val channels = wavInfo.numChannels.toInt()

        val minBufSize = AudioTrack.getMinBufferSize(
            wavInfo.sampleRate, channelOut, encoding,
        )
        val bufferSize = maxOf(minBufSize, channels * CHUNK_FRAMES * (bits / 8))

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(wavInfo.sampleRate)
                .setChannelMask(channelOut)
                .setEncoding(encoding)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack = track

        val raf = RandomAccessFile(filePath, "r")
        currentFile = raf
        raf.seek(wavInfo.dataOffset)

        val bytesPerFrame = channels * (bits / 8)
        val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
        val floatBuf = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatArray(CHUNK_FRAMES * channels)
        } else {
            null
        }

        track.play()
        _state.value = PlayerState.Playing

        // dataSize ベースの残量管理
        var remainingBytes: Long = wavInfo.dataSize

        try {
            var bytesRead: Int
            while (isActive && _state.value is PlayerState.Playing && remainingBytes > 0) {
                val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                val currentGain = _gain.value

                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        if (floatBuf != null) {
                            val numFloats = bytesRead / 4
                            val bb = java.nio.ByteBuffer.wrap(rawBuf, 0, bytesRead)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until numFloats) {
                                floatBuf[i] = softClip(bb.float * currentGain)
                            }
                            track.write(floatBuf, 0, numFloats, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                    AudioFormat.ENCODING_PCM_16BIT -> {
                        val numShorts = bytesRead / 2
                        val bb = java.nio.ByteBuffer.wrap(rawBuf, 0, bytesRead)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val outBuf = ShortArray(numShorts)
                        for (i in 0 until numShorts) {
                            val sample = (bb.short.toFloat() / 32768f * currentGain)
                                .coerceIn(-1f, 1f)
                            outBuf[i] = (sample * 32767f).toInt().toShort()
                        }
                        track.write(outBuf, 0, numShorts, AudioTrack.WRITE_BLOCKING)
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> {
                        for (i in 0 until bytesRead) {
                            val s = rawBuf[i].toInt() and 0xFF
                            rawBuf[i] = ((s - 128) * currentGain + 128)
                                .coerceIn(0f, 255f).toInt().toByte()
                        }
                        track.write(rawBuf, 0, bytesRead, AudioTrack.WRITE_BLOCKING)
                    }
                    else -> {
                        track.write(rawBuf, 0, bytesRead)
                    }
                }

                val framesWritten = bytesRead / bytesPerFrame
                _currentFrame.updateAndGet { it + framesWritten }
                remainingBytes -= bytesRead
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback interrupted", e)
        } finally {
            if (_state.value is PlayerState.Playing) {
                _state.value = PlayerState.Finished
            }
        }
    }

    /**
     * 再生を一時停止します。位置は保持されます。Mutex により直列化されます。
     */
    suspend fun pause() {
        mutex.withLock {
            if (_state.value !is PlayerState.Playing) return@withLock
            audioTrack?.pause()
            _state.value = PlayerState.Paused
        }
    }

    /**
     * 一時停止位置から再開します。
     */
    private suspend fun resume() = withContext(Dispatchers.IO) {
        if (_state.value !is PlayerState.Paused) return@withContext
        val track = audioTrack ?: return@withContext
        val raf = currentFile ?: return@withContext
        val info = _wavInfo ?: return@withContext

        val (encoding, _) = validateAndGetEncoding(info)
        val bits = info.bitsPerSample.toInt()
        val channels = info.numChannels.toInt()
        val bytesPerFrame = channels * (bits / 8)

        // 現在位置からファイルをシーク
        val seekPos = info.dataOffset + _currentFrame.value * bytesPerFrame
        raf.seek(seekPos)

        val rawBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)
        val floatBuf = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatArray(CHUNK_FRAMES * channels)
        } else {
            null
        }

        track.play()
        _state.value = PlayerState.Playing

        // 残りバイト数を現在位置から計算
        var remainingBytes: Long = info.dataSize - (raf.filePointer - info.dataOffset)

        try {
            var bytesRead: Int
            while (isActive && _state.value is PlayerState.Playing && remainingBytes > 0) {
                val bytesToRead = minOf(rawBuf.size.toLong(), remainingBytes).toInt()
                bytesRead = raf.read(rawBuf, 0, bytesToRead)
                if (bytesRead <= 0) break

                val currentGain = _gain.value

                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        if (floatBuf != null) {
                            val numFloats = bytesRead / 4
                            val bb = java.nio.ByteBuffer.wrap(rawBuf, 0, bytesRead)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until numFloats) {
                                floatBuf[i] = softClip(bb.float * currentGain)
                            }
                            track.write(floatBuf, 0, numFloats, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                    AudioFormat.ENCODING_PCM_16BIT -> {
                        val numShorts = bytesRead / 2
                        val bb = java.nio.ByteBuffer.wrap(rawBuf, 0, bytesRead)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val outBuf = ShortArray(numShorts)
                        for (i in 0 until numShorts) {
                            val sample = (bb.short.toFloat() / 32768f * currentGain)
                                .coerceIn(-1f, 1f)
                            outBuf[i] = (sample * 32767f).toInt().toShort()
                        }
                        track.write(outBuf, 0, numShorts, AudioTrack.WRITE_BLOCKING)
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> {
                        for (i in 0 until bytesRead) {
                            val s = rawBuf[i].toInt() and 0xFF
                            rawBuf[i] = ((s - 128) * currentGain + 128)
                                .coerceIn(0f, 255f).toInt().toByte()
                        }
                        track.write(rawBuf, 0, bytesRead, AudioTrack.WRITE_BLOCKING)
                    }
                    else -> {
                        track.write(rawBuf, 0, bytesRead)
                    }
                }

                _currentFrame.updateAndGet { it + bytesRead / bytesPerFrame }
                remainingBytes -= bytesRead
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resume interrupted", e)
        } finally {
            if (_state.value is PlayerState.Playing) {
                _state.value = PlayerState.Finished
            }
        }
    }

    /**
     * 再生を停止し、先頭位置に戻します。Mutex により直列化されます。
     */
    suspend fun stop() {
        mutex.withLock {
            playbackJob?.cancel()
            playbackJob?.join()
            playbackJob = null
            audioTrack?.apply {
                try { pause() } catch (_: Exception) {}
                try { flush() } catch (_: Exception) {}
            }
            _state.value = PlayerState.Idle
            _currentFrame.value = 0L
            currentFile?.seek(_wavInfo?.dataOffset ?: 0L)
        }
    }

    /**
     * 指定位置（ミリ秒）へシークします。Playing/Paused 状態では AudioTrack を
     * 一時停止・フラッシュしてからシークし、Playing 状態だった場合のみ再開します。
     * Mutex により直列化されます。
     */
    suspend fun seekTo(positionMs: Long) {
        mutex.withLock {
            val info = _wavInfo ?: return@withLock
            val targetFrame = if (info.numFrames <= 0) {
                0L
            } else {
                (positionMs * info.sampleRate / 1000L)
                    .coerceIn(0, info.numFrames - 1)
            }
            val bytesPerFrame = info.numChannels.toInt() * (info.bitsPerSample.toInt() / 8)
            val byteOffset = info.dataOffset + targetFrame * bytesPerFrame

            val wasPlaying = _state.value is PlayerState.Playing
            if (_state.value is PlayerState.Playing || _state.value is PlayerState.Paused) {
                // 再生ループの完了を待ってから共有リソースを操作
                playbackJob?.cancel()
                playbackJob?.join()
                playbackJob = null

                audioTrack?.pause()
                audioTrack?.flush()
                currentFile?.seek(byteOffset)
                _currentFrame.value = targetFrame

                if (wasPlaying) {
                    // 再生を新しい位置から再開
                    _state.value = PlayerState.Paused
                    playbackJob = playerScope.launch(Dispatchers.IO) {
                        resume()
                    }
                } else {
                    _state.value = PlayerState.Paused
                }
            } else {
                currentFile?.seek(byteOffset)
                _currentFrame.value = targetFrame
            }
        }
    }

    /**
     * すべてのリソースを解放します。Mutex により直列化されます。
     */
    suspend fun release() {
        mutex.withLock {
            playbackJob?.cancel()
            playbackJob?.join()
            playbackJob = null
            releaseInternal()
            _wavInfo = null
            _state.value = PlayerState.Idle
            _currentFrame.value = 0L
            // CoroutineScope を再生成し、次回の play() に備える
            playerScope.cancel()
            playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    /**
     * AudioTrack と RandomAccessFile を内部解放します（状態は変更しません）。
     */
    private fun releaseInternal() {
        audioTrack?.apply {
            try { pause() } catch (_: Exception) {}
            try { flush() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioTrack = null
        try { currentFile?.close() } catch (_: Exception) {}
        currentFile = null
    }
}

// Atomic-like update for MutableStateFlow on API < 35
private fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return next
    }
}
