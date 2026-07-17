package com.example.h1econversion.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.h1econversion.R
import com.example.h1econversion.viewmodel.BatchConversionUiState
import com.example.h1econversion.viewmodel.BatchConversionViewModel
import com.example.h1econversion.viewmodel.SaveState
import kotlinx.coroutines.launch

/**
 * 複数ファイルの一括変換画面。
 *
 * 各ファイルを並列変換し、進捗と結果を表示します。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConversionScreen(
    pathGainPairs: List<Pair<String, Float>>,
    onNavigateBack: () -> Unit,
    viewModel: BatchConversionViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pathGainPairs) {
        if (pathGainPairs.isNotEmpty()) {
            viewModel.prepareBatch(pathGainPairs)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_conversion_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            when (val state = uiState) {
                is BatchConversionUiState.Idle -> {
                    BatchIdleContent(
                        state = state,
                        onStartConversion = { viewModel.startBatchConversion() },
                    )
                }

                is BatchConversionUiState.Converting -> {
                    BatchConvertingContent(state = state)
                }

                is BatchConversionUiState.Completed -> {
                    BatchCompletedContent(
                        state = state,
                        context = context,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        onNavigateBack = onNavigateBack,
                        onSaveAll = { viewModel.saveAllToDownloads() },
                    )
                }

                is BatchConversionUiState.Error -> {
                    BatchErrorContent(
                        message = state.message,
                        onRetry = { viewModel.prepareBatch(pathGainPairs) },
                    )
                }
            }
        }
    }
}

/**
 * 変換前の確認画面。
 */
@Composable
private fun BatchIdleContent(
    state: BatchConversionUiState.Idle,
    onStartConversion: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.batch_idle_ready),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.batch_idle_file_count, state.totalFiles),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.batch_idle_output_format),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ファイル一覧（画面半分までスクロール可能）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.batch_idle_target_files),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(state.fileInfos) { _, info ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = info.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${info.gainPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onStartConversion,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.batch_idle_start_button, state.totalFiles))
        }
    }
}

/**
 * 変換実行中の画面。
 */
@Composable
private fun BatchConvertingContent(state: BatchConversionUiState.Converting) {
    val listState = rememberLazyListState()

    // 新しいログが追加されたら自動スクロール
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val isAtBottom = lastVisibleItem != null &&
                lastVisibleItem.index == layoutInfo.totalItemsCount - 1
            if (isAtBottom) {
                listState.animateScrollToItem(state.logs.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 進捗ヘッダー
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
                    text = stringResource(R.string.batch_conversion_progress_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.batch_conversion_progress_desc,
                        state.totalFiles,
                        state.completedFiles.coerceAtMost(state.totalFiles),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 全体進捗バー
        if (state.totalFiles > 0) {
            LinearProgressIndicator(
                progress = { state.completedFiles.toFloat() / state.totalFiles },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }

        // ログ表示
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
                itemsIndexed(state.logs) { _, log ->
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
 * 変換完了画面。
 */
@Composable
private fun BatchCompletedContent(
    state: BatchConversionUiState.Completed,
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onNavigateBack: () -> Unit,
    onSaveAll: () -> Unit,
) {
    var showLogDialog by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.conversion_share_chooser_title)
    val shareErrorMsg = stringResource(R.string.conversion_share_error)
    val saveState = state.saveState

    // Android 6-9 向け WRITE_EXTERNAL_STORAGE 実行時許可
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted: Boolean ->
        if (granted) {
            onSaveAll()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.storage_permission_required))
            }
        }
    }

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
                    LazyColumn(
                        state = dialogListState,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        itemsIndexed(state.logs) { _, log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // 成功/一部失敗/全失敗で表示を切り替え
        val isTotalFailure = state.successCount == 0
        val isPartialFailure = state.successCount > 0 && state.failureCount > 0

        Icon(
            imageVector = if (isTotalFailure) Icons.Default.Error else Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = when {
                isTotalFailure -> MaterialTheme.colorScheme.error
                isPartialFailure -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = when {
                isTotalFailure -> stringResource(R.string.batch_conversion_failure_title)
                isPartialFailure -> stringResource(R.string.batch_conversion_partial_success_title)
                else -> stringResource(R.string.batch_conversion_success_title)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                isTotalFailure -> stringResource(R.string.batch_conversion_failure_desc, state.totalFiles)
                isPartialFailure -> stringResource(
                    R.string.batch_conversion_partial_success_desc,
                    state.totalFiles,
                    state.successCount,
                    state.failureCount,
                )
                else -> stringResource(
                    R.string.batch_conversion_success_desc,
                    state.totalFiles,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.batch_conversion_success_detail,
                state.successCount,
                state.failureCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.failureCount > 0) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 成功ファイルの一覧
        if (state.successFiles.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.batch_completed_files_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn {
                        itemsIndexed(state.successFiles) { index, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        val success = shareFile(context, file, chooserTitle)
                                        if (!success) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(shareErrorMsg)
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.batch_share_button), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 保存状態表示 ----
        when (saveState) {
            is SaveState.Saving -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.batch_save_progress, saveState.current, saveState.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (saveState.total > 0) {
                    LinearProgressIndicator(
                        progress = { saveState.current.toFloat() / saveState.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
            }
            is SaveState.Done -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = saveState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            is SaveState.SaveError -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = saveState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is SaveState.Idle -> { /* 未操作時は表示なし */ }
        }

        // ---- 保存ボタン ----
        // Saving 中および Done 後は保存ボタンを無効化
        val isSaveDisabled = saveState is SaveState.Saving || saveState is SaveState.Done
        Button(
            onClick = {
                // Android 6-9 では WRITE_EXTERNAL_STORAGE の実行時許可が必要
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q) {
                    val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, permission
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        onSaveAll()
                    } else {
                        storagePermissionLauncher.launch(permission)
                    }
                } else {
                    onSaveAll()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaveDisabled && state.successCount > 0,
        ) {
            if (saveState is SaveState.Saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = if (saveState is SaveState.Saving) stringResource(R.string.batch_save_button_saving)
                else stringResource(R.string.batch_save_button, state.successCount)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { showLogDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.conversion_view_log_button))
            }
            Button(
                onClick = onNavigateBack,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.batch_conversion_back_button))
            }
        }
    }
}

/**
 * エラー表示。
 */
@Composable
private fun BatchErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("再試行")
        }
    }
}

/**
 * FileProvider 経由でファイルを共有します。
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
        false
    }
}
