package com.example.h1econversion.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.h1econversion.model.BitratePreset
import com.example.h1econversion.model.CodecType
import com.example.h1econversion.model.ContainerType
import com.example.h1econversion.viewmodel.SettingsUiState
import com.example.h1econversion.viewmodel.SettingsViewModel

/**
 * 設定画面。
 *
 * - コーデック選択（AAC / PCM WAV）
 * - ビットレート選択（AAC 時のみ表示）
 * - 拡張子選択（.m4a / .aac / .wav）
 * - ユーザーデータ削除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作結果メッセージがある場合は Snackbar で表示
    LaunchedEffect(uiState) {
        if (uiState is SettingsUiState.Ready) {
            val msg = (uiState as SettingsUiState.Ready).resultMessage
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearResultMessage()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        when (val state = uiState) {
            is SettingsUiState.Ready -> {
                SettingsContent(
                    state = state,
                    onSelectCodec = { viewModel.selectCodec(it) },
                    onSelectBitrate = { viewModel.selectBitrate(it) },
                    onSelectContainer = { viewModel.selectContainer(it) },
                    onDeleteData = { viewModel.showDeleteConfirmation() },
                    modifier = Modifier.padding(paddingValues),
                )

                // 削除確認ダイアログ
                if (state.showDeleteConfirmation) {
                    DeleteConfirmationDialog(
                        onConfirm = { viewModel.deleteAllUserData() },
                        onDismiss = { viewModel.dismissDeleteConfirmation() },
                    )
                }
            }
        }
    }
}

/**
 * 設定画面のメインコンテンツ。
 */
@Composable
private fun SettingsContent(
    state: SettingsUiState.Ready,
    onSelectCodec: (CodecType) -> Unit,
    onSelectBitrate: (BitratePreset) -> Unit,
    onSelectContainer: (ContainerType) -> Unit,
    onDeleteData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 変換設定セクション ----
        SectionHeader(title = "変換設定")

        // コーデック選択
        SettingLabel(text = "出力コーデック")
        Spacer(modifier = Modifier.height(4.dp))
        CodecSelector(
            selected = state.settings.codec,
            onSelect = onSelectCodec,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ビットレート選択（AAC 時のみ表示）
        if (state.settings.codec == CodecType.AAC) {
            SettingLabel(text = "ビットレート")
            Spacer(modifier = Modifier.height(4.dp))
            BitrateSelector(
                selected = state.settings.bitrate,
                onSelect = onSelectBitrate,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // コンテナ（拡張子）選択
        SettingLabel(text = "出力拡張子")
        Spacer(modifier = Modifier.height(4.dp))
        ContainerSelector(
            selected = state.settings.container,
            codec = state.settings.codec,
            onSelect = onSelectContainer,
        )

        Spacer(modifier = Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(28.dp))

        // ---- ストレージ情報セクション ----
        SectionHeader(title = "ストレージ")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // インポート済みファイル
                StorageInfoRow(
                    label = "インポート済みファイル",
                    fileCount = if (state.hasImportedFiles) "あり" else "なし",
                    sizeBytes = state.importedFilesSizeBytes,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 変換キャッシュ
                StorageInfoRow(
                    label = "変換キャッシュ",
                    fileCount = if (state.hasConversionCache) "あり" else "なし",
                    sizeBytes = state.cacheSizeBytes,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // データ削除ボタン
        val canDelete = state.hasImportedFiles || state.hasConversionCache
        Button(
            onClick = onDeleteData,
            enabled = canDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("すべてのユーザーデータを削除")
        }

        if (!canDelete) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "削除可能なデータはありません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── セクションヘッダー ──

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

// ── 設定ラベル ──

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── コーデック選択 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodecSelector(
    selected: CodecType,
    onSelect: (CodecType) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CodecType.entries.forEach { codec ->
            FilterChip(
                selected = selected == codec,
                onClick = { onSelect(codec) },
                label = { Text(codec.displayName) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

// ── ビットレート選択（4段階スライダー） ──

@Composable
private fun BitrateSelector(
    selected: BitratePreset,
    onSelect: (BitratePreset) -> Unit,
) {
    val presets = BitratePreset.entries
    val selectedIndex = presets.indexOf(selected).coerceIn(0, presets.size - 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 現在の選択値を表示
        Text(
            text = selected.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // 4段階のスライダー
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { newValue ->
                val index = newValue.toInt().coerceIn(0, presets.size - 1)
                onSelect(presets[index])
            },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2, // 中間ステップ数（両端を除く）
            modifier = Modifier.fillMaxWidth(),
        )

        // スライダー下端のラベル（最小・最大）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = presets.first().displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = presets.last().displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── コンテナ（拡張子）選択 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerSelector(
    selected: ContainerType,
    codec: CodecType,
    onSelect: (ContainerType) -> Unit,
) {
    // PCM_WAV の場合は .wav のみ選択可能
    val availableContainers = if (codec == CodecType.PCM_WAV) {
        listOf(ContainerType.WAV)
    } else {
        listOf(ContainerType.M4A, ContainerType.AAC_RAW)
    }

    // 選択中のコンテナが利用可能リストにない場合は最初の有効な値にフォールバック
    val effectiveSelected = if (selected in availableContainers) selected else availableContainers.first()

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        availableContainers.forEach { container ->
            FilterChip(
                selected = effectiveSelected == container,
                onClick = { onSelect(container) },
                label = { Text(container.displayName) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

// ── ストレージ情報行 ──

@Composable
private fun StorageInfoRow(
    label: String,
    fileCount: String,
    sizeBytes: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "状態: $fileCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = formatFileSize(sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 削除確認ダイアログ ──

@Composable
private fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("ユーザーデータの削除") },
        text = {
            Text(
                "インポート済みの録音ファイルと変換キャッシュをすべて削除します。\n\n" +
                        "この操作は取り消せません。\n" +
                        "変換が完了し保存済みのファイルは削除されません。\n\n" +
                        "続行しますか？",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("削除する")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

// ── ファイルサイズフォーマット ──

/**
 * バイト数を人間が読みやすい形式にフォーマットします。
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${size.toLong()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(size, units[unitIndex])
    }
}
