package com.example.h1econversion.ui.screen

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.h1econversion.R
import com.example.h1econversion.model.PlaybackState
import com.example.h1econversion.model.WaveformUiState
import com.example.h1econversion.viewmodel.WaveformViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveformScreen(
    localPath: String,
    onNavigateBack: () -> Unit,
    onNavigateToConversion: ((path: String, gain: Float, format: String) -> Unit)? = null,
    viewModel: WaveformViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(localPath) {
        viewModel.loadFile(Uri.decode(localPath))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.waveform_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (val state = uiState) {
                is WaveformUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.loading_waveform))
                    }
                }

                is WaveformUiState.Ready -> {
                    WaveformContent(
                        localPath = localPath,
                        state = state,
                        onPlayPause = viewModel::togglePlayPause,
                        onStop = viewModel::stop,
                        onVolumeChange = viewModel::setVolume,
                        onSeek = viewModel::seekTo,
                        onNavigateToConversion = onNavigateToConversion,
                    )
                }

                is WaveformUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformContent(
    localPath: String,
    state: WaveformUiState.Ready,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onNavigateToConversion: ((path: String, gain: Float, format: String) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- Info card ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.wav_format_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.wav_format_value,
                        state.wavInfo.sampleRate,
                        state.wavInfo.numChannels,
                        state.wavInfo.bitsPerSample,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wav_duration_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDuration(state.wavInfo.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Waveform ----
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                WaveformCanvas(
                    waveformData = state.waveformData,
                    currentPositionMs = state.currentPositionMs,
                    durationMs = state.wavInfo.durationMs,
                    onSeek = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Time display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(state.currentPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatDuration(state.wavInfo.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Playback controls ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = when (state.playerState) {
                        PlaybackState.Playing -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when (state.playerState) {
                        PlaybackState.Playing -> stringResource(R.string.pause)
                        else -> stringResource(R.string.play)
                    },
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.stop),
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Volume slider ----
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.volume_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${(state.volume * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..5f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // ラベルをスライダーの実際の値位置に合わせて配置
                // 0.0(0%) → 1.0(100%) → 5.0(500%) の比率 = 1:4
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.volume_zero), style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.volume_normal), style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.weight(4f))
                    Text(stringResource(R.string.volume_max), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- 変換へ進むボタン ----
        if (onNavigateToConversion != null) {
            val formatStr = "${state.wavInfo.sampleRate} Hz / ${state.wavInfo.numChannels} ch / ${state.wavInfo.bitsPerSample} bit float"
            Button(
                onClick = {
                    onStop()  // 再生を停止してから遷移
                    onNavigateToConversion(Uri.decode(localPath), state.volume, formatStr)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.next_button_waveform))
            }
        }
    }
}

/**
 * Canvas-based waveform renderer with playback position cursor.
 */
@Composable
private fun WaveformCanvas(
    waveformData: com.example.h1econversion.model.WaveformUiData,
    currentPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val waveformColor = MaterialTheme.colorScheme.primary
    val cursorColor = MaterialTheme.colorScheme.error
    val playedOverlayColor = waveformColor.copy(alpha = 0.6f)
    val unplayedColor = waveformColor.copy(alpha = 0.25f)

    // Deduplicate: only recompose when data actually changes
    val minValues = remember(waveformData) { waveformData.minValues }
    val maxValues = remember(waveformData) { waveformData.maxValues }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .semantics {
                contentDescription = "波形表示"
            }
            .semantics(mergeDescendants = true) {
                // 再生位置を進捗情報として公開
                if (durationMs > 0) {
                    setProgress(
                        action = { targetProgress ->
                            onSeek((targetProgress * durationMs).toLong())
                            true
                        },
                    )
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        range = 0f..durationMs.toFloat(),
                    )
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((fraction * durationMs).toLong())
                    }
                }
            },
    ) {
        if (minValues.isEmpty()) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height
        val midY = canvasHeight / 2f
        val ampScale = midY * 0.9f // leave small margin at top/bottom
        val columnWidth = canvasWidth / minValues.size

        // Determine cursor x position
        val cursorFraction = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
        val cursorX = cursorFraction * canvasWidth

        // Use a Path for efficiency — draw all waveform columns in one pass,
        // splitting into "played" (left of cursor) and "unplayed" (right of cursor)
        val playedPath = Path()
        val unplayedPath = Path()

        for (i in minValues.indices) {
            val x = i * columnWidth + columnWidth / 2f
            val minY = midY - (minValues[i] * ampScale).coerceIn(-ampScale, ampScale)
            val maxY = midY - (maxValues[i] * ampScale).coerceIn(-ampScale, ampScale)

            val path = if (x <= cursorX) playedPath else unplayedPath
            path.moveTo(x, minY)
            path.lineTo(x, maxY)
        }

        // Draw unplayed portion first (behind)
        drawPath(
            path = unplayedPath,
            color = unplayedColor,
            style = Stroke(width = columnWidth.coerceAtMost(2.5f)),
        )

        // Draw played portion on top
        drawPath(
            path = playedPath,
            color = playedOverlayColor,
            style = Stroke(width = columnWidth.coerceAtMost(2.5f)),
        )

        // Draw cursor line
        drawLine(
            color = cursorColor,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, canvasHeight),
            strokeWidth = 2f,
        )

        // Center line (zero crossing)
        drawLine(
            color = waveformColor.copy(alpha = 0.12f),
            start = Offset(0f, midY),
            end = Offset(canvasWidth, midY),
            strokeWidth = 1f,
        )
    }
}

/**
 * Format milliseconds as "MM:SS" or "HH:MM:SS".
 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
