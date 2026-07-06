package com.example.cliprecorder.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.example.cliprecorder.BuildConfig
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.video.ClipItem
import com.example.cliprecorder.video.TitleConfig
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
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
    val isGeneratingTitle by viewModel.isGeneratingTitle.collectAsState()
    val titleProgress by viewModel.titleProgress.collectAsState()

    val scope = rememberCoroutineScope()

    var showTitleCreator    by remember { mutableStateOf(false) }
    // ダイアログを閉じても設定が失われないよう Screen レベルで保持する
    var tcTitle       by remember { mutableStateOf("") }
    var tcSubtitle    by remember { mutableStateOf("") }
    var tcDuration    by remember { mutableIntStateOf(1) }
    var tcPortrait    by remember { mutableStateOf(true) }
    var tcBgColor     by remember { mutableIntStateOf(AndroidColor.BLACK) }
    var tcTxtColor    by remember { mutableIntStateOf(AndroidColor.WHITE) }
    var tcTxtVertical by remember { mutableStateOf(false) }
    var tcResolution  by remember { mutableStateOf(com.example.cliprecorder.video.TitleResolution.FHD) }

    if (showTitleCreator) {
        TitleCreatorDialog(
            title = tcTitle,       onTitleChange = { tcTitle = it },
            subtitle = tcSubtitle, onSubtitleChange = { tcSubtitle = it },
            duration = tcDuration, onDurationChange = { tcDuration = it },
            portrait = tcPortrait, onPortraitChange = { tcPortrait = it },
            bgColor = tcBgColor,   onBgColorChange = { tcBgColor = it },
            txtColor = tcTxtColor, onTxtColorChange = { tcTxtColor = it },
            txtVertical = tcTxtVertical, onTxtVerticalChange = { tcTxtVertical = it },
            resolution = tcResolution, onResolutionChange = { tcResolution = it },
            onDismiss = { showTitleCreator = false },
            onGenerate = { config ->
                showTitleCreator = false
                viewModel.generateTitleVideo(config)
            },
        )
    }

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
        floatingActionButton = {
            if (selectedCount == 0 && !isMerging && !isGeneratingTitle) {
                FloatingActionButton(onClick = { showTitleCreator = true }) {
                    Icon(Icons.Default.Title, contentDescription = "タイトル動画を作成")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End,
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
                    // ---- グリッド表示（縦横混在対応：StaggeredGrid）----
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalItemSpacing = 2.dp,
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

            // タイトル動画生成中オーバーレイ
            if (isGeneratingTitle) {
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
                            "タイトル動画を生成中... ${(titleProgress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// 回転を適用したサムネイルと表示アスペクト比（width/height）
private data class VideoThumbnail(val bitmap: Bitmap, val aspectRatio: Float)

private suspend fun loadVideoThumbnail(context: android.content.Context, uri: android.net.Uri): VideoThumbnail? =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@runCatching null
                val rotated = if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
                        .also { if (it !== frame) frame.recycle() }
                } else frame
                VideoThumbnail(rotated, rotated.width.toFloat() / rotated.height.toFloat())
            }
        }.getOrNull()
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
    var thumbnail by remember(clip.uri) { mutableStateOf<VideoThumbnail?>(null) }
    LaunchedEffect(clip.uri) {
        thumbnail = loadVideoThumbnail(context, clip.uri)
    }

    // ロード前はアスペクト比プレースホルダーとして 9:16 を使用
    val aspectRatio = thumbnail?.aspectRatio ?: (9f / 16f)

    Box(
        modifier = Modifier
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = { onPlay() },
            ),
    ) {
        val bmp = thumbnail?.bitmap
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

        // 再生ボタン
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

        // 秒数（右下）
        val durationStr = formatDuration(clip.durationMs)
        if (durationStr.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 20.dp, end = 3.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Text(durationStr, color = Color.White, fontSize = 9.sp,
                    style = MaterialTheme.typography.labelSmall)
            }
        }

        // クリップ名（下部帯）
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

    var thumbnail by remember(clip.uri) { mutableStateOf<VideoThumbnail?>(null) }
    LaunchedEffect(clip.uri) {
        thumbnail = loadVideoThumbnail(context, clip.uri)
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

        // サムネイル（高さ固定・幅はアスペクト比に合わせて伸縮、最大 72dp）
        val thumbAspect = thumbnail?.aspectRatio ?: (16f / 9f)
        Box(
            modifier = Modifier
                .height(48.dp)
                .width((48f * thumbAspect).coerceAtMost(72f).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val bmp = thumbnail?.bitmap
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
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                )
            }
            // 秒数（右下）
            val dur = formatDuration(clip.durationMs)
            if (dur.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                ) {
                    Text(dur, color = Color.White, fontSize = 8.sp,
                        style = MaterialTheme.typography.labelSmall)
                }
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
                    val dur = formatDuration(clip.durationMs)
                    if (dur.isNotEmpty()) append("  |  $dur")
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

// ---- タイトル動画作成ダイアログ ----

private val BG_PRESETS = listOf(
    AndroidColor.BLACK,
    AndroidColor.WHITE,
    AndroidColor.rgb(15, 23, 42),   // ネイビー
    AndroidColor.rgb(15, 52, 56),   // ダークティール
    AndroidColor.rgb(42, 9, 69),    // ダークパープル
    AndroidColor.rgb(69, 10, 10),   // ダークレッド
    AndroidColor.rgb(30, 30, 30),   // チャコール
    AndroidColor.rgb(20, 40, 20),   // ダークグリーン
)

private val TEXT_PRESETS = listOf(
    AndroidColor.WHITE,
    AndroidColor.BLACK,
    AndroidColor.YELLOW,
    AndroidColor.rgb(255, 215, 0),  // ゴールド
    AndroidColor.rgb(135, 206, 250),// ライトブルー
    AndroidColor.rgb(255, 180, 180),// ライトピンク
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleCreatorDialog(
    title: String,           onTitleChange: (String) -> Unit,
    subtitle: String,        onSubtitleChange: (String) -> Unit,
    duration: Int,           onDurationChange: (Int) -> Unit,
    portrait: Boolean,       onPortraitChange: (Boolean) -> Unit,
    bgColor: Int,            onBgColorChange: (Int) -> Unit,
    txtColor: Int,           onTxtColorChange: (Int) -> Unit,
    txtVertical: Boolean,    onTxtVerticalChange: (Boolean) -> Unit,
    resolution: com.example.cliprecorder.video.TitleResolution,
    onResolutionChange: (com.example.cliprecorder.video.TitleResolution) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: (TitleConfig) -> Unit,
) {

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .widthIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("タイトル動画を作成",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(16.dp))

                // プレビュー
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(bgColor)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (title.isEmpty() && subtitle.isEmpty()) {
                        Text("テキストを入力してください",
                            color = Color(txtColor).copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodySmall)
                    } else if (txtVertical) {
                        // 縦書きプレビュー：文字を縦に並べた Row（右→左）
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            // 列は右→左なので、reversedの列を表示
                            val cols = title.chunked(8).reversed()
                            cols.forEachIndexed { _, col ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                ) {
                                    col.forEach { ch ->
                                        Text(
                                            ch.toString(),
                                            color = Color(txtColor),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // 横書きプレビュー
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 16.dp)) {
                            if (title.isNotEmpty()) {
                                Text(
                                    title,
                                    color = Color(txtColor),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (subtitle.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    subtitle,
                                    color = Color(txtColor).copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // テキスト入力
                OutlinedTextField(
                    value = title,
                    onValueChange = { onTitleChange(it) },
                    label = { Text("タイトル") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { onSubtitleChange(it) },
                    label = { Text("サブタイトル（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                // 秒数スライダー
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("秒数: ${duration}秒",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(80.dp))
                    Slider(
                        value = duration.toFloat(),
                        onValueChange = { onDurationChange(it.toInt()) },
                        valueRange = 1f..15f,
                        steps = 13,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 縦横切り替え
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("向き:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        selected = portrait,
                        onClick = { onPortraitChange(true) },
                        label = { Text("縦 (9:16)") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = !portrait,
                        onClick = { onPortraitChange(false) },
                        label = { Text("横 (16:9)") },
                    )
                }

                Spacer(Modifier.height(8.dp))

                Spacer(Modifier.height(8.dp))

                // 解像度
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("解像度:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    com.example.cliprecorder.video.TitleResolution.entries.forEach { res ->
                        FilterChip(
                            selected = resolution == res,
                            onClick = { onResolutionChange(res) },
                            label = { Text(res.label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 文字方向
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文字方向:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        selected = !txtVertical,
                        onClick = { onTxtVerticalChange(false) },
                        label = { Text("横書き") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = txtVertical,
                        onClick = { onTxtVerticalChange(true) },
                        label = { Text("縦書き") },
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 背景色
                Text("背景色:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BG_PRESETS) { color ->
                        ColorSwatch(color = color, selected = bgColor == color) { onBgColorChange(color) }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 文字色
                Text("文字色:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TEXT_PRESETS) { color ->
                        ColorSwatch(color = color, selected = txtColor == color) { onTxtColorChange(color) }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ボタン
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("キャンセル") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onGenerate(
                                TitleConfig(
                                    title = title.trim(),
                                    subtitle = subtitle.trim(),
                                    durationSec = duration,
                                    portrait = portrait,
                                    resolution = resolution,
                                    bgColor = bgColor,
                                    textColor = txtColor,
                                    textVertical = txtVertical,
                                )
                            )
                        },
                        enabled = title.trim().isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("生成")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected)
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    else
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .then(border)
            .clickable(onClick = onClick),
    )
}
