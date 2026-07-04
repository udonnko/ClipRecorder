package com.example.cliprecorder.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cliprecorder.BuildConfig
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.video.ClipItem
import com.example.cliprecorder.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergePreviewScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }

    val allClips by viewModel.clips.collectAsState()
    val isMerging by viewModel.isMerging.collectAsState()
    val mergeProgress by viewModel.mergeProgress.collectAsState()
    val mergeMetaSelectInfo by viewModel.mergeMetaSelectInfo.collectAsState()
    val mergeScaleInfo by viewModel.mergeScaleInfo.collectAsState()

    val selectedClips = remember(allClips) { allClips.filter { it.selected } }
    val orderedClips = remember(selectedClips) { selectedClips.toMutableStateList() }

    // プレイヤー状態
    var surface by remember { mutableStateOf<Surface?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPreparing by remember { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { runCatching { mediaPlayer.release() } }
    }

    // マージ完了で自動的に戻る
    var wasMerging by remember { mutableStateOf(false) }
    LaunchedEffect(isMerging) {
        if (wasMerging && !isMerging) onBack()
        wasMerging = isMerging
    }

    fun loadClip(index: Int, play: Boolean = false) {
        val s = surface ?: return
        val clip = orderedClips.getOrNull(index) ?: return
        isPreparing = true
        isPlaying = false
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.setSurface(s)
            mediaPlayer.setDataSource(context, clip.uri)
            mediaPlayer.setOnPreparedListener { mp ->
                isPreparing = false
                if (play) { mp.start(); isPlaying = true }
            }
            mediaPlayer.setOnCompletionListener {
                if (currentIndex < orderedClips.size - 1) {
                    currentIndex++
                    loadClip(currentIndex, play = true)
                } else {
                    isPlaying = false
                    currentIndex = 0
                    loadClip(0, play = false)
                }
            }
            mediaPlayer.prepareAsync()
        }.onFailure { isPreparing = false }
    }

    LaunchedEffect(surface) {
        if (surface != null) loadClip(0)
    }

    // ---- メタデータ選択ダイアログ ----
    mergeMetaSelectInfo?.let { metaInfo ->
        var selectedIndex by remember(metaInfo) { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissMergeMetaSelect() },
            title = { Text("メタデータの取得元") },
            text = {
                Column {
                    Text(
                        "出力ファイルのフレームレート・解像度などの情報をどのクリップから取得しますか？",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    metaInfo.clips.forEachIndexed { i, clip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedIndex == i, onClick = { selectedIndex = i })
                            Text(clip.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.confirmMergeMetaSelect(settings, metaInfo.clips[selectedIndex].uri)
                }) { Text("この動画を基準にする") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMergeMetaSelect() }) { Text("キャンセル") }
            },
        )
    }

    // ---- スケール結合確認ダイアログ ----
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
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("結合プレビュー") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isPlaying) { runCatching { mediaPlayer.pause() }; isPlaying = false }
                        onBack()
                    }, enabled = !isMerging) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ---- 動画プレイヤー ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        surface = Surface(st)
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        surface?.release(); surface = null; return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isPreparing) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                // ---- 再生コントロール ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (currentIndex > 0) { currentIndex--; loadClip(currentIndex) }
                        },
                        enabled = !isPreparing && orderedClips.size > 1,
                    ) { Icon(Icons.Default.SkipPrevious, "前のクリップ") }

                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                runCatching { mediaPlayer.pause() }; isPlaying = false
                            } else {
                                runCatching { mediaPlayer.start() }; isPlaying = true
                            }
                        },
                        enabled = !isPreparing,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "一時停止" else "再生",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            if (currentIndex < orderedClips.size - 1) {
                                currentIndex++; loadClip(currentIndex)
                            }
                        },
                        enabled = !isPreparing && orderedClips.size > 1,
                    ) { Icon(Icons.Default.SkipNext, "次のクリップ") }
                }

                // 現在クリップ表示
                orderedClips.getOrNull(currentIndex)?.let { clip ->
                    Text(
                        "${currentIndex + 1} / ${orderedClips.size}：${clip.name}",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ---- 再生順序リスト ----
                Text(
                    "再生順序（↑↓ で並び替え）",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(orderedClips) { index, clip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == currentIndex)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                clip.name,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val item = orderedClips.removeAt(index)
                                        orderedClips.add(index - 1, item)
                                        when (currentIndex) {
                                            index     -> currentIndex = index - 1
                                            index - 1 -> currentIndex = index
                                        }
                                        loadClip(currentIndex)
                                    }
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(36.dp),
                            ) { Icon(Icons.Default.KeyboardArrowUp, "上へ", modifier = Modifier.size(20.dp)) }

                            IconButton(
                                onClick = {
                                    if (index < orderedClips.size - 1) {
                                        val item = orderedClips.removeAt(index)
                                        orderedClips.add(index + 1, item)
                                        when (currentIndex) {
                                            index     -> currentIndex = index + 1
                                            index + 1 -> currentIndex = index
                                        }
                                        loadClip(currentIndex)
                                    }
                                },
                                enabled = index < orderedClips.size - 1,
                                modifier = Modifier.size(36.dp),
                            ) { Icon(Icons.Default.KeyboardArrowDown, "下へ", modifier = Modifier.size(20.dp)) }
                        }
                        HorizontalDivider()
                    }
                }

                // ---- 結合ボタン ----
                Button(
                    onClick = {
                        if (isPlaying) { runCatching { mediaPlayer.pause() }; isPlaying = false }
                        viewModel.mergeOrdered(settings, orderedClips.toList())
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    enabled = !isMerging && !BuildConfig.IS_FREE_TIER && orderedClips.size >= 2,
                ) {
                    Text("この順序で結合")
                }
            }

            // ---- 結合中オーバーレイ ----
            if (isMerging) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize()) {}
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "結合中... ${(mergeProgress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { mergeProgress },
                            modifier = Modifier.width(200.dp),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
