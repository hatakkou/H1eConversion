package com.example.h1econversion.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.h1econversion.R
import com.example.h1econversion.model.FileGainItem
import com.example.h1econversion.model.MultiFileUiState
import com.example.h1econversion.model.WaveformUiData
import com.example.h1econversion.viewmodel.MultiFileViewModel
import kotlin.math.roundToInt

/**
 * 複数ファイルのゲイン一括調整・試聴画面。
 *
 * 各行: ☑チェックボックス | ファイル名 | ゲインスライダー | ▶再生ボタン + 再生バー
 * 上部に一括ゲイン設定スライダー、下部に変換実行ボタンを配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiFileGainScreen(
    filePaths: List<String>,
    onNavigateBack: () -> Unit,
    onNavigateToConversion: (pathGainList: List<Pair<String, Float>>) -> Unit,
    viewModel: MultiFileViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 初回表示時にファイル一覧を読み込み
    // 空パス時は読み込まない（バックスタックからの復帰時、remember の再評価で
    // consume() が空を返しても ViewModel の既存状態を上書きしないため）
    LaunchedEffect(filePaths) {
        if (filePaths.isNotEmpty()) {
            viewModel.loadFiles(filePaths)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multi_gain_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopPlayback()
                        onNavigateBack()
                    }) {
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
                is MultiFileUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.multi_gain_loading))
                    }
                }

                is MultiFileUiState.Ready -> {
                    MultiFileContent(
                        state = state,
                        onToggleSelectAll = viewModel::toggleSelectAll,
                        onToggleFileSelection = viewModel::toggleFileSelection,
                        onSetFileGain = viewModel::setFileGain,
                        onSetBatchGain = viewModel::setBatchGain,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onNavigateToConversion = {
                            val selected = viewModel.getSelectedFilesWithGain()
                            if (selected.isNotEmpty()) {
                                viewModel.stopPlayback()
                                onNavigateToConversion(selected)
                            }
                        },
                    )
                }

                is MultiFileUiState.Error -> {
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
private fun MultiFileContent(
    state: MultiFileUiState.Ready,
    onToggleSelectAll: () -> Unit,
    onToggleFileSelection: (Int) -> Unit,
    onSetFileGain: (Int, Float) -> Unit,
    onSetBatchGain: (Float) -> Unit,
    onTogglePlayPause: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onNavigateToConversion: () -> Unit,
) {
    val selectedCount = state.files.count { it.isSelected }
    val allSelected = state.files.all { it.isSelected }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 一括ゲイン設定カード ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.multi_gain_batch_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${(state.batchGain * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = state.batchGain,
                    onValueChange = onSetBatchGain,
                    valueRange = 0f..5f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // ラベル行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.volume_zero),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.volume_normal),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.weight(4f))
                    Text(
                        text = stringResource(R.string.volume_max),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ---- 全選択/解除 + 件数表示 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggleSelectAll,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (allSelected) "すべて解除" else "すべて選択",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$selectedCount / ${state.files.size} ファイル選択中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- ファイル一覧 ----
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 4.dp,
            ),
        ) {
            itemsIndexed(
                items = state.files,
                key = { _, item -> item.localPath },
            ) { index, item ->
                FileGainRow(
                    item = item,
                    onToggleSelection = { onToggleFileSelection(index) },
                    onSetGain = { gain -> onSetFileGain(index, gain) },
                    onTogglePlayPause = { onTogglePlayPause(index) },
                    onSeek = { positionMs ->
                        // 非アクティブなファイルのシークは、そのファイルをアクティブにしてから行う
                        if (state.activePlaybackIndex != index) {
                            onTogglePlayPause(index)
                        }
                        onSeek(positionMs)
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // ---- 変換実行ボタン ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Button(
                onClick = onNavigateToConversion,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCount > 0,
            ) {
                Text(stringResource(R.string.multi_gain_convert_button, selectedCount))
            }
        }
    }
}

/**
 * 1ファイル分の行コンポーネント。
 *
 * レイアウト:
 *   [☑] [ファイル名] [再生時間]
 *   [Vol] [ゲインスライダー] [%]
 *   [▶] [現在位置 / 全体]  ← 再生ボタン＋時間表示
 *   [～～～～～ 波形ミニキャンバス（タップでシーク）～～～～～]
 */
@Composable
private fun FileGainRow(
    item: FileGainItem,
    onToggleSelection: () -> Unit,
    onSetGain: (Float) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // ---- 上段: チェックボックス + ファイル名 + 再生時間 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleSelection,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (item.isSelected) Icons.Default.CheckBox
                        else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = if (item.isSelected) "選択解除" else "選択",
                        modifier = Modifier.size(22.dp),
                        tint = if (item.isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (item.durationMs > 0) {
                    Text(
                        text = formatDuration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 中段: ゲインスライダー ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.volume_label_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
                Slider(
                    value = item.gain,
                    onValueChange = onSetGain,
                    valueRange = 0f..5f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    text = "${(item.gain * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.End,
                )
            }

            // ---- 下段1: 再生ボタン + 現在位置 / 全体時間 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(32.dp),
                    enabled = item.wavInfo != null,
                ) {
                    Icon(
                        imageVector = if (item.isPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (item.isPlaying) "停止" else "再生",
                        modifier = Modifier.size(20.dp),
                        tint = if (item.wavInfo != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 現在位置 / 全体時間
                Text(
                    text = "${formatDuration(item.playbackPositionMs)} / ${formatDuration(item.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 下段2: 波形ミニキャンバス（タップでシーク） ----
            MiniWaveformBar(
                waveformData = item.waveformData,
                durationMs = item.durationMs,
                positionMs = item.playbackPositionMs,
                isPlaying = item.isPlaying,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

/**
 * ミニ波形＋再生位置バー。
 *
 * 波形データがあれば波形を描画し、現在位置にカーソルを表示します。
 * タップで再生位置をシークできます。波形データがない場合は空のバーを表示します。
 */
@Composable
private fun MiniWaveformBar(
    waveformData: WaveformUiData?,
    durationMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barHeight = 36.dp
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val cursorColor = MaterialTheme.colorScheme.error
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Canvas(
        modifier = modifier
            .height(barHeight)
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                    val seekMs = (ratio * durationMs).toLong()
                    onSeek(seekMs)
                }
            },
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 背景
        drawRect(
            color = backgroundColor,
            topLeft = Offset.Zero,
            size = size,
        )

        // 波形の描画
        val wf = waveformData
        if (wf != null && wf.minValues.isNotEmpty() && wf.maxValues.isNotEmpty()) {
            val columnCount = wf.minValues.size
            val columnWidth = canvasWidth / columnCount

            // 未再生部分の波形（全体）
            val unplayedPath = Path()
            var isFirst = true
            for (col in 0 until columnCount) {
                val minVal = wf.minValues[col]
                val maxVal = wf.maxValues[col]
                val x = col * columnWidth + columnWidth / 2f

                // スケール: [-1, 1] → [10%, 90%] of canvasHeight
                val yMin = (canvasHeight * 0.9f - (minVal + 1f) / 2f * canvasHeight * 0.8f)
                    .coerceIn(0f, canvasHeight)
                val yMax = (canvasHeight * 0.9f - (maxVal + 1f) / 2f * canvasHeight * 0.8f)
                    .coerceIn(0f, canvasHeight)

                if (isFirst) {
                    unplayedPath.moveTo(x, yMin)
                    isFirst = false
                } else {
                    unplayedPath.lineTo(x, yMin)
                }
                unplayedPath.lineTo(x, yMax)
            }
            drawPath(unplayedPath, unplayedColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
        } else {
            // 波形データなし：中央に細い線を描画
            drawLine(
                color = unplayedColor,
                start = Offset(0f, canvasHeight / 2f),
                end = Offset(canvasWidth, canvasHeight / 2f),
                strokeWidth = 1.5f,
            )
        }

        // 再生済み部分のカーソルライン
        val cursorX = progress * canvasWidth
        drawLine(
            color = cursorColor,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, canvasHeight),
            strokeWidth = 2.5f,
        )

        // 再生済み部分のオーバーレイ（薄く着色）
        if (progress > 0f) {
            drawRect(
                color = playedColor.copy(alpha = 0.08f),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(cursorX, canvasHeight),
            )
        }
    }
}

/**
 * ミリ秒を "mm:ss" 形式にフォーマットします。
 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
