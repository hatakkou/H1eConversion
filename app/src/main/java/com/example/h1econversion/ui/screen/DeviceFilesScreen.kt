package com.example.h1econversion.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.h1econversion.R
import com.example.h1econversion.model.DeviceFilesUiState
import com.example.h1econversion.model.RecordingFile
import com.example.h1econversion.viewmodel.DeviceFilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFilesScreen(
    onNavigateBack: () -> Unit,
    onFileSelected: (localPath: String) -> Unit,
    onLaunchManualPicker: () -> Unit = {},
    viewModel: DeviceFilesViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val copyingFileName by viewModel.copyingFileName.collectAsState()

    // SAF ツリーピッカー（ACTION_OPEN_DOCUMENT_TREE）
    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onTreeUriGranted(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.checkDeviceAndLoadFiles()
    }

    // 手動ファイル選択トリガーの監視
    LaunchedEffect(Unit) {
        viewModel.manualSelectTrigger.collect {
            onLaunchManualPicker()
        }
    }

    // SAF ツリーピッカー起動トリガーの監視
    LaunchedEffect(Unit) {
        viewModel.openDocumentTreeTrigger.collect { initialUri ->
            treePickerLauncher.launch(initialUri)
        }
    }

    // Handle navigation events
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is com.example.h1econversion.viewmodel.DeviceFilesNavigationEvent.NavigateToFileInfo -> {
                    onFileSelected(event.localPath)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_files_title)) },
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
                is DeviceFilesUiState.CheckingDevice -> {
                    CenteredMessage(
                        icon = { Icon(Icons.Default.Usb, null, Modifier.size(64.dp)) },
                        message = stringResource(R.string.checking_device),
                        showProgress = true,
                    )
                }

                is DeviceFilesUiState.DeviceNotFound -> {
                    CenteredMessage(
                        icon = {
                            Icon(
                                Icons.Default.UsbOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        message = stringResource(R.string.device_not_found),
                        description = stringResource(R.string.device_not_found_desc),
                    ) {
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                is DeviceFilesUiState.PermissionDenied -> {
                    CenteredMessage(
                        icon = {
                            Icon(
                                Icons.Default.UsbOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        message = stringResource(R.string.permission_denied),
                    ) {
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                is DeviceFilesUiState.ScanningFiles -> {
                    CenteredMessage(
                        icon = { Icon(Icons.Default.Usb, null, Modifier.size(64.dp)) },
                        message = stringResource(R.string.scanning_files),
                        showProgress = true,
                    )
                }

                is DeviceFilesUiState.FilesLoaded -> {
                    if (state.files.isEmpty()) {
                        CenteredMessage(
                            icon = {
                                Icon(
                                    Icons.Default.AudioFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                )
                            },
                            message = stringResource(R.string.no_recordings),
                        ) {
                            Button(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.reload))
                            }
                        }
                    } else {
                        FileListView(
                            files = state.files,
                            copyingFileName = copyingFileName,
                            onFileClick = { file -> viewModel.selectFile(file) },
                            usbRepo = viewModel.getUsbRepo(),
                        )
                    }
                }

                is DeviceFilesUiState.Error -> {
                    CenteredMessage(
                        icon = {
                            Icon(
                                Icons.Default.UsbOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        message = stringResource(R.string.error_occurred),
                        description = state.message,
                    ) {
                        Row {
                            Button(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.retry))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { viewModel.requestManualSelect() }) {
                                Text(stringResource(R.string.manual_select))
                            }
                        }
                    }
                }

                is DeviceFilesUiState.NeedSafPermission -> {
                    CenteredMessage(
                        icon = {
                            Icon(
                                Icons.Default.Usb,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        message = "ストレージへのアクセス権限が必要です",
                        description = state.message,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = { viewModel.requestSafPermission() }) {
                                Text("権限を付与する")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.requestManualSelect() }
                            ) {
                                Text("手動でファイルを選択する")
                            }
                        }
                    }
                }
                is DeviceFilesUiState.Idle -> {
                    // Initial state; waiting for LaunchedEffect
                }
            }
        }
    }
}

@Composable
private fun FileListView(
    files: List<RecordingFile>,
    copyingFileName: String?,
    onFileClick: (RecordingFile) -> Unit,
    usbRepo: com.example.h1econversion.audio.UsbFileRepository,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Copy progress overlay
        if (copyingFileName != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "コピー中…",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = copyingFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Text(
            text = "${files.size}件の録音ファイル",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            items(files, key = { it.uri.toString() }) { file ->
                FileCard(
                    file = file,
                    onClick = { onFileClick(file) },
                    usbRepo = usbRepo,
                )
            }
        }
    }
}

@Composable
private fun FileCard(
    file: RecordingFile,
    onClick: () -> Unit,
    usbRepo: com.example.h1econversion.audio.UsbFileRepository,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AudioFile,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = usbRepo.formatTimestamp(file.lastModified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = usbRepo.formatFileSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    icon: @Composable () -> Unit,
    message: String,
    description: String? = null,
    showProgress: Boolean = false,
    actions: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        icon()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (showProgress) {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
        }
        if (actions != null) {
            Spacer(modifier = Modifier.height(24.dp))
            actions()
        }
    }
}
