package com.example.cliprecorder.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.cliprecorder.BuildConfig
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.video.ClipItem
import com.example.cliprecorder.viewmodel.CameraViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import java.text.SimpleDateFormat
import java.util.*

private data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onNavigateEdit: () -> Unit = {},
    onNavigateMergePreview: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settings = remember { SettingsManager(context) }
    val clips by viewModel.clips.collectAsState()
    val isMerging by viewModel.isMerging.collectAsState()
    val mergeProgress by viewModel.mergeProgress.collectAsState()
    val mergeScaleInfo by viewModel.mergeScaleInfo.collectAsState()
    val mergeMetaSelectInfo by viewModel.mergeMetaSelectInfo.collectAsState()
    val isExportingGif by viewModel.isExportingGif.collectAsState()
    val isMixingBgm by viewModel.isMixingBgm.collectAsState()

    val scope = rememberCoroutineScope()

    val bgmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { viewModel.addBgmToSelected(it) }
        }
    }

    // 再生アプリ選択ダイアログ
    var playerPickerUri by remember { mutableStateOf<Uri?>(null) }
    var playerApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    if (playerPickerUri != null && playerApps.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { playerPickerUri = null },
            title = { Text("再生アプリを選択") },
            text = {
                Column {
                    playerApps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settings.setVideoPlayerPackage(app.packageName) }
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(playerPickerUri, "video/mp4")
                                        setPackage(app.packageName)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching { context.startActivity(intent) }
                                    playerPickerUri = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            app.icon?.toBitmap(48, 48)?.asImageBitmap()?.let { bmp ->
                                Image(bmp, contentDescription = null,
                                    modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (playerApps.size > 1) {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        TextButton(
                            onClick = {
                                scope.launch { settings.setVideoPlayerPackage("") }
                                playerPickerUri = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("選択を解除（毎回聞く）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    val selectedCount = clips.count { it.selected }
    val totalBytes = clips.sumOf { it.sizeBytes }
    val storageSummary = remember(totalBytes) {
        when {
            totalBytes >= 1_073_741_824L -> "%.1f GB".format(totalBytes / 1_073_741_824.0)
            totalBytes >= 1_048_576L     -> "%.1f MB".format(totalBytes / 1_048_576.0)
            else                         -> "${totalBytes / 1024} KB"
        }
    }

    // ドラッグ&ドロップ用 LazyList state
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.reorderClips(from.index, to.index)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // メタデータ取得元選択ダイアログ
    mergeMetaSelectInfo?.let { metaInfo ->
        var selectedIndex by remember(metaInfo) { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissMergeMetaSelect() },
            title = { Text("メタデータ取得元を選択") },
            text = {
                Column {
                    Text(
                        "回転・コーデック情報を取得するクリップを1つ選んでください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    metaInfo.clips.forEachIndexed { index, clip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedIndex == index,
                                onClick = { selectedIndex = index },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                clip.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.confirmMergeMetaSelect(settings, metaInfo.clips[selectedIndex].uri)
                }) {
                    Text("決定")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMergeMetaSelect() }) { Text("キャンセル") }
            },
        )
    }

    // 解像度違いスケール結合確認ダイアログ
    mergeScaleInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissScaleConfirm() },
            title = { Text("解像度が異なります") },
            text = {
                Text(
                    "選択クリップの解像度: ${info.description}\n\n" +
                    "最小解像度 (${info.targetWidth}×${info.targetHeight}) に合わせて" +
                    "スケールしてから結合します。\n処理に数十秒かかる場合があります。"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.mergeSelectedWithScale(settings) }) {
                    Text("スケールして結合")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissScaleConfirm() }) { Text("キャンセル") }
            }
        )
    }

    // 一括削除確認ダイアログ
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("削除の確認") },
            text = { Text("選択した ${selectedCount} 件を削除しますか？\nこの操作は元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(); showDeleteConfirm = false }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            }
        )
    }

    // 個別削除確認ダイアログ
    var deleteTargetClip by remember { mutableStateOf<com.example.cliprecorder.video.ClipItem?>(null) }
    deleteTargetClip?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTargetClip = null },
            title = { Text("削除の確認") },
            text = { Text("「${target.name}」を削除しますか？\nこの操作は元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteClip(target); deleteTargetClip = null }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetClip = null }) { Text("キャンセル") }
            }
        )
    }

    val selectedClip = if (selectedCount == 1) clips.firstOrNull { it.selected } else null
    var isGridView by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectedCount > 0)
                        Text("${selectedCount}件選択中")
                    else
                        Column {
                            Text("クリップ一覧", style = MaterialTheme.typography.titleMedium)
                            Text("${clips.size}件  $storageSummary",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) "リスト表示" else "グリッド表示",
                        )
                    }
                    if (selectedCount > 0) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "選択解除")
                        }
                    } else {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全選択")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectedCount >= 1) {
                BottomAppBar(tonalElevation = 0.dp) {
                    // 削除
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "削除",
                            tint = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    // 1件選択時のアクション
                    if (selectedClip != null) {
                        // 編集
                        IconButton(onClick = onNavigateEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "編集")
                        }
                        // GIF
                        IconButton(
                            onClick = { viewModel.exportSelectedAsGif() },
                            enabled = !isExportingGif,
                        ) {
                            if (isExportingGif)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else
                                Text("GIF", style = MaterialTheme.typography.labelLarge)
                        }
                        // BGM（有料版）
                        if (!BuildConfig.IS_FREE_TIER) {
                            IconButton(
                                onClick = {
                                    bgmPickerLauncher.launch(
                                        Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
                                    )
                                },
                                enabled = !isMixingBgm,
                            ) {
                                if (isMixingBgm)
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else
                                    Icon(Icons.Default.MusicNote, contentDescription = "BGM")
                            }
                        }
                        // シェア
                        IconButton(onClick = {
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, selectedClip.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "シェア"
                            ))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "シェア")
                        }
                    }
                    // 結合（2件以上のとき表示。無料版はグレーアウトのみ）
                    if (selectedCount >= 2) {
                        TextButton(
                            onClick = { if (!BuildConfig.IS_FREE_TIER) onNavigateMergePreview() },
                            enabled = !BuildConfig.IS_FREE_TIER && !isMerging,
                        ) {
                            Text("結合")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("クリップがありません", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                // アプリ一覧取得・ダイアログ表示（強制=true で保存済みを無視して選択し直す）
                fun showPlayerPicker(uri: Uri, force: Boolean) {
                    scope.launch {
                        if (!force) {
                            val savedPkg = settings.videoPlayerPackage.first()
                            if (savedPkg.isNotEmpty()) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    setPackage(savedPkg)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val ok = runCatching { context.startActivity(intent) }.isSuccess
                                if (!ok) settings.setVideoPlayerPackage("")
                                else return@launch
                            }
                        }
                        val probe = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val pm = context.packageManager
                        val apps = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                            .map { ri ->
                                AppInfo(
                                    packageName = ri.activityInfo.packageName,
                                    label = ri.loadLabel(pm).toString(),
                                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                                )
                            }
                            .distinctBy { it.packageName }
                            .sortedBy { it.label }
                        if (apps.isEmpty()) {
                            val chooser = Intent.createChooser(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "動画を開くアプリを選択",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(chooser) }
                        } else if (!force && apps.size == 1) {
                            settings.setVideoPlayerPackage(apps[0].packageName)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                setPackage(apps[0].packageName)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                        } else {
                            playerApps = apps
                            playerPickerUri = uri
                        }
                    }
                }

                if (isGridView) {
                    // ---- グリッド表示 ----
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                    ) {
                        items(clips, key = { it.uri.toString() }) { clip ->
                            ClipGridItem(
                                clip = clip,
                                onToggle = { viewModel.toggleSelect(clip) },
                                onPlay = { showPlayerPicker(clip.uri, force = false) },
                                onPlayLong = { showPlayerPicker(clip.uri, force = true) },
                            )
                        }
                    }
                } else {
                    // ---- リスト表示 ----
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(clips, key = { it.uri.toString() }) { clip ->
                            ReorderableItem(reorderState, key = clip.uri.toString()) { isDragging ->
                                val elevation by animateDpAsState(
                                    targetValue = if (isDragging) 8.dp else 0.dp,
                                    label = "elevation",
                                )
                                Surface(shadowElevation = elevation) {
                                    ClipRow(
                                        clip = clip,
                                        onToggle = { viewModel.toggleSelect(clip) },
                                        onPlay = { showPlayerPicker(clip.uri, force = false) },
                                        onPlayLong = { showPlayerPicker(clip.uri, force = true) },
                                        onDelete = { deleteTargetClip = clip },
                                        dragHandle = {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "並び替え",
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            haptic.performHapticFeedback(
                                                                HapticFeedbackType.LongPress
                                                            )
                                                        },
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                                tint = MaterialTheme.colorScheme.outline,
                                            )
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }

            // 結合中オーバーレイ
            if (isMerging) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "結合中... ${(mergeProgress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipGridItem(
    clip: ClipItem,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onPlayLong: () -> Unit,
) {
    val context = LocalContext.current
    var thumbnail by remember(clip.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = { onPlay() },
            ),
    ) {
        // サムネイル
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.VideoFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }

        // 選択時オーバーレイ
        if (clip.selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "選択中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp),
            )
        }

        // 再生ボタン（タップ）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .combinedClickable(onClick = onPlay, onLongClick = onPlayLong),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "再生",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        // クリップ名（下部グラデーション帯）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Text(
                clip.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    clip: ClipItem,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onPlayLong: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dateStr = remember(clip.createdAt) {
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(clip.createdAt))
    }
    val sizeKb = clip.sizeBytes / 1024
    val resolution = if (clip.width > 0 && clip.height > 0) "${clip.width}×${clip.height}" else ""

    var thumbnail by remember(clip.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .then(
                if (clip.selected)
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ドラッグハンドル
        dragHandle()

        Checkbox(checked = clip.selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))

        // サムネイル
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                clip.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("$dateStr  |  ${sizeKb}KB")
                    if (resolution.isNotEmpty()) append("  |  $resolution")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(onClick = onPlay, onLongClick = onPlayLong),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayCircle, contentDescription = "再生",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "削除",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}
