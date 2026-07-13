package com.example.h1econversion.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.h1econversion.R
import com.example.h1econversion.viewmodel.ConversionUiState
import com.example.h1econversion.viewmodel.ConversionViewModel

/**
 * WAV → MP3 変換画面。
 *
 * 変換パラメータの確認 → 変換実行 → 結果表示のフロー。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
    inputPath: String,
    inputFormat: String,
    gain: Float,
    onNavigateBack: () -> Unit,
    viewModel: ConversionViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 画面表示時にパラメータを設定
    LaunchedEffect(inputPath, gain) {
        viewModel.prepare(inputPath, inputFormat, gain)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversion_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        // 注: 外側のColumnにverticalScrollを付けない。
        // Converting状態ではLazyColumnを使うため、verticalScrollとの競合を避ける。
        // IdleContent側で必要に応じてスクロールを設定する。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            when (val state = uiState) {
                is ConversionUiState.Idle -> {
                    IdleContent(
                        state = state,
                        onStartConversion = { viewModel.startConversion() },
                    )
                }

                is ConversionUiState.Converting -> {
                    ConvertingContent(state = state)
                }

                is ConversionUiState.Success -> {
                    SuccessContent(
                        state = state,
                        context = context,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        onNavigateBack = onNavigateBack,
                    )
                }

                is ConversionUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.retry() },
                    )
                }
            }
        }
    }
}

/**
 * 変換前のパラメータ確認画面。
 */
@Composable
private fun IdleContent(
    state: ConversionUiState.Idle,
    onStartConversion: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // パラメータカード
        Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.conversion_params_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))

            ParamRow(
                label = stringResource(R.string.conversion_input_file),
                value = state.inputFileName,
            )
            Spacer(modifier = Modifier.height(8.dp))

            ParamRow(
                label = stringResource(R.string.conversion_input_format),
                value = state.inputFormat,
            )
            Spacer(modifier = Modifier.height(8.dp))

            ParamRow(
                label = stringResource(R.string.conversion_gain),
                value = "${state.gainPercent}%",
            )
            Spacer(modifier = Modifier.height(8.dp))

            ParamRow(
                label = stringResource(R.string.conversion_output_format),
                value = state.outputFormat,
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // 変換開始ボタン
    Button(
        onClick = onStartConversion,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.conversion_start_button))
    }
    } // end outer Column
}

/**
 * 変換実行中の画面。
 * 進捗インジケーターとリアルタイムログ表示を備える。
 */
@Composable
private fun ConvertingContent(state: ConversionUiState.Converting) {
    val listState = rememberLazyListState()

    // 新しいログが追加されたら自動スクロール（ユーザーが最下部にいる場合のみ）
    LaunchedEffect(state.progressLogs.size) {
        if (state.progressLogs.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            // 最後のアイテムが画面内に見えている場合のみ自動スクロール
            val isAtBottom = lastVisibleItem != null &&
                lastVisibleItem.index == layoutInfo.totalItemsCount - 1
            if (isAtBottom) {
                listState.animateScrollToItem(state.progressLogs.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 上部: 進捗インジケーター + ファイル情報
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.conversion_progress_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.conversion_progress_desc,
                        state.inputFileName,
                        state.gainPercent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 下部: ログ表示エリア
        Text(
            text = stringResource(R.string.conversion_log_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                items(state.progressLogs) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * 変換成功時の画面。
 */
@Composable
private fun SuccessContent(
    state: ConversionUiState.Success,
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateBack: () -> Unit,
) {
    var showLogDialog by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.conversion_share_chooser_title)
    val shareErrorMsg = stringResource(R.string.conversion_share_error)

    // ログ確認ダイアログ
    if (showLogDialog) {
        val dialogListState = rememberLazyListState()
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(stringResource(R.string.conversion_log_title)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                ) {
                    if (state.progressLogs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.conversion_log_empty),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            state = dialogListState,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            items(state.progressLogs) { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text(stringResource(R.string.conversion_log_close))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.conversion_success_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.conversion_success_desc,
                state.inputFileName,
                state.gainPercent,
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 共有ボタン
        Button(
            onClick = {
                val success = shareFile(context, state.outputFile, chooserTitle)
                if (!success) {
                    scope.launch {
                        snackbarHostState.showSnackbar(shareErrorMsg)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(stringResource(R.string.conversion_share_button))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ログ確認ボタン
        OutlinedButton(
            onClick = { showLogDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.conversion_view_log_button))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 戻るボタン
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.conversion_back_button))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * エラー時の画面。
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.conversion_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

/**
 * パラメータ表示用の行コンポーネント。
 */
@Composable
private fun ParamRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 出力ファイルを共有インテントで送信します。
 * @return 共有インテントの起動に成功した場合 true、失敗時は false
 */
private fun shareFile(context: android.content.Context, file: java.io.File, chooserTitle: String): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
        true
    } catch (e: Exception) {
        android.util.Log.e("ConversionScreen", "Failed to share file", e)
        false
    }
}
