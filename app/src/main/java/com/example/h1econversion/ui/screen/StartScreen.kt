package com.example.h1econversion.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.h1econversion.R
import com.example.h1econversion.model.ImportUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * スタート画面。
 * importState に応じて表示を切り替えます。
 * - Idle: 2ボタン（デバイスに接続 / ファイルをインポート）
 * - Copying: ファイルコピー中の進捗表示
 * - Error/Warning: エラー・警告表示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    onConnectDevice: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    importState: StateFlow<ImportUiState>? = null,
    onClearError: () -> Unit = {},
) {
    val state by (importState ?: MutableStateFlow<ImportUiState>(
        ImportUiState.Idle
    )).collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "設定",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        // Crossfade + when で安全に状態に応じた表示を切り替え
        // AnimatedVisibility + as キャストは状態遷移時に ClassCastException のリスクがあるため不使用
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 300),
            modifier = Modifier.padding(paddingValues),
        ) { currentState ->
        when (currentState) {
            is ImportUiState.Idle -> {
                IdleContent(
                    onConnectDevice = onConnectDevice,
                    onImportFile = onImportFile,
                )
            }
            is ImportUiState.Copying -> {
                ImportingContent(
                    currentIndex = currentState.currentIndex,
                    totalCount = currentState.totalCount,
                    currentFileName = currentState.currentFileName,
                )
            }
            is ImportUiState.Error -> {
                CenteredMessageContent(
                    message = currentState.message,
                    isError = true,
                    onDismiss = onClearError,
                )
            }
            is ImportUiState.Warning -> {
                CenteredMessageContent(
                    message = currentState.message,
                    isError = false,
                    onDismiss = onClearError,
                )
            }
            is ImportUiState.Success -> {
                // 成功時は Idle と同じ（通常すぐにナビゲーションされる）
                IdleContent(
                    onConnectDevice = onConnectDevice,
                    onImportFile = onImportFile,
                )
            }
        }
    }
    } // Scaffold 終了
}

/**
 * Idle 時の通常スタート画面（2ボタン）。
 */
@Composable
private fun IdleContent(
    onConnectDevice: () -> Unit,
    onImportFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(96.dp))

        // デバイスに接続
        ActionCard(
            title = "デバイスに接続",
            description = "Zoom H1 essential をUSB接続して\n録音データを読み込む",
            icon = {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = "デバイスに接続",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = onConnectDevice,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ファイルをインポート
        ActionCard(
            title = "ファイルをインポート",
            description = "端末内の音声ファイルを選択して読み込む",
            icon = {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "ファイルをインポート",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = onImportFile,
        )

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * ファイルコピー中の進捗表示（画面全体）。
 */
@Composable
private fun ImportingContent(
    currentIndex: Int,
    totalCount: Int,
    currentFileName: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // アイコン
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // プログレスインジケーター
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 進捗テキスト
        if (totalCount > 1) {
            Text(
                text = stringResource(R.string.importing_files, currentIndex, totalCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.importing_file_single),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 現在のファイル名
        Text(
            text = currentFileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // プログレスバー
        if (totalCount > 1) {
            LinearProgressIndicator(
                progress = { currentIndex.toFloat() / totalCount.toFloat() },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp),
            )
        } else {
            // 単一ファイルの場合は不確定プログレスバー
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp),
            )
        }
    }
}

/**
 * エラー/警告メッセージ表示（画面中央）。
 */
@Composable
private fun CenteredMessageContent(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onDismiss) {
            Text("閉じる")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()

            Spacer(modifier = Modifier.width(20.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
