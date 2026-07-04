package com.example.cliprecorder.ui

import android.graphics.Matrix
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
import com.example.cliprecorder.viewmodel.CameraViewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergePreviewScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }

    val allClips      by viewModel.clips.collectAsState()
    val isMerging     by viewModel.isMerging.collectAsState()
    val mergeProgress by viewModel.mergeProgress.collectAsState()
    val mergeMetaSelectInfo by viewModel.mergeMetaSelectInfo.collectAsState()
    val mergeScaleInfo      by viewModel.mergeScaleInfo.collectAsState()

    val selectedClips = remember(allClips) { allClips.filter { it.selected } }
    val orderedClips  = remember(selectedClips) { selectedClips.toMutableStateList() }

    // プレイヤー状態
    var surface        by remember { mutableStateOf<Surface?>(null) }
    var tvRef          by remember { mutableStateOf<TextureView?>(null) }
    var videoWidth     by remember { mutableIntStateOf(0) }
    var videoHeight    by remember { mutableIntStateOf(0) }
    var currentIndex   by remember { mutableIntStateOf(0) }

    // TextureView の描画 Matrix をアスペクト比に合わせて補正する
    fun adjustAspect(tv: TextureView, vw: Int, vh: Int) {
        val tvW = tv.width.toFloat()
        val tvH = tv.height.toFloat()
        if (tvW <= 0 || tvH <= 0 || vw <= 0 || vh <= 0) return
        val videoRatio = vw.toFloat() / vh
        val viewRatio  = tvW / tvH
        val matrix = Matrix()
        if (videoRatio < viewRatio) {
            // 縦動画: 横を縮めて中央配置
            val scale = videoRatio / viewRatio
            matrix.setScale(scale, 1f, tvW / 2f, tvH / 2f)
        } else {
            // 横動画: 縦を縮めて中央配置（通常はほぼ全面）
            val scale = viewRatio / videoRatio
            matrix.setScale(1f, scale, tvW / 2f, tvH / 2f)
        }
        tv.setTransform(matrix)
    }
    var isPreparing  by remember { mutableStateOf(false) }
    var isPlaying    by remember { mutableStateOf(false) }
    // ロードトリガー: 値が変わるたびに LaunchedEffect が再起動
    var loadRevision by remember { mutableIntStateOf(0) }
    var loadAutoPlay by remember { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { runCatching { mediaPlayer.release() } }
    }

    // クリップを読み込む（状態を更新してトリガーを叩く）
    fun triggerLoad(index: Int, play: Boolean) {
        currentIndex = index.coerceIn(0, (orderedClips.size - 1).coerceAtLeast(0))
        loadAutoPlay = play
        loadRevision++
    }

    // MediaPlayer を準備して再生する
    LaunchedEffect(loadRevision, surface) {
        val s    = surface ?: return@LaunchedEffect
        val clip = orderedClips.getOrNull(currentIndex) ?: return@LaunchedEffect
        val play = loadAutoPlay

        isPreparing = true
        isPlaying   = false

        suspendCancellableCoroutine { cont ->
            runCatching {
                mediaPlayer.reset()
                mediaPlayer.setSurface(s)
                mediaPlayer.setDataSource(context, clip.uri)
                mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        videoWidth = w; videoHeight = h
                        tvRef?.let { adjustAspect(it, w, h) }
                    }
                }
                mediaPlayer.setOnPreparedListener { mp ->
                    isPreparing = false
                    // prepare 完了時点で既にサイズが得られている場合も補正
                    tvRef?.let { adjustAspect(it, mp.videoWidth, mp.videoHeight) }
                    if (play) { mp.start(); isPlaying = true }
                    if (cont.isActive) cont.resume(Unit)
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    isPreparing = false
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                mediaPlayer.setOnCompletionListener {
                    val next = currentIndex + 1
                    if (next < orderedClips.size) {
                        triggerLoad(next, play = true)
                    } else {
                        isPlaying = false
                        triggerLoad(0, play = false)
                    }
                }
                mediaPlayer.prepareAsync()
            }.onFailure {
                isPreparing = false
                if (cont.isActive) cont.resume(Unit)
            }
            // LaunchedEffect がキャンセルされたとき（別のクリップに切り替えなど）
            cont.invokeOnCancellation { runCatching { mediaPlayer.reset() } }
        }
    }

    // マージ完了で自動的に戻る
    var wasMerging by remember { mutableStateOf(false) }
    LaunchedEffect(isMerging) {
        if (wasMerging && !isMerging) onBack()
        wasMerging = isMerging
    }

    // ---- メタデータ選択ダイアログ ----
    mergeMetaSelectInfo?.let { metaInfo ->
        var selectedIndex by remember(metaInfo) { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissMergeMetaSelect() },
            title = { Text("メタデータの取得元") },
            text = {
                Column {
                    Text("出力ファイルのフレームレート・解像度などをどのクリップから取得しますか？",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    metaInfo.clips.forEachIndexed { i, clip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedIndex == i, onClick = { selectedIndex = i })
                            Text(clip.name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    // ---- スケール確認ダイアログ ----
    mergeScaleInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissScaleConfirm() },
            title = { Text("解像度が異なります") },
            text = {
                Text("選択クリップの解像度: ${info.description}\n\n" +
                    "最小解像度 (${info.targetWidth}×${info.targetHeight}) に合わせて" +
                    "スケールしてから結合します。\n処理に数十秒かかる場合があります。")
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
                        runCatching { if (isPlaying) mediaPlayer.pause() }
                        onBack()
                    }, enabled = !isMerging) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // 時系列（撮影日時）昇順に並び替え（古い順 = 先頭）
                            val sorted = orderedClips.sortedBy { it.createdAt }
                            orderedClips.clear()
                            orderedClips.addAll(sorted)
                            currentIndex = 0
                            triggerLoad(0, play = false)
                        },
                        enabled = !isMerging,
                    ) {
                        Icon(Icons.Default.Sort, "時系列順に戻す")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ---- 動画プレイヤー ----
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                tvRef = this
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        surface = Surface(st)
                                        // Surface 準備完了後に既知の動画サイズで補正
                                        if (videoWidth > 0 && videoHeight > 0)
                                            adjustAspect(this@apply, videoWidth, videoHeight)
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                        if (videoWidth > 0 && videoHeight > 0)
                                            adjustAspect(this@apply, videoWidth, videoHeight)
                                    }
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        surface?.release(); surface = null; return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isPreparing) CircularProgressIndicator(color = Color.White)
                }

                // ---- 再生コントロール ----
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { if (currentIndex > 0) triggerLoad(currentIndex - 1, play = false) },
                        enabled = !isPreparing && currentIndex > 0,
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
                        onClick = { if (currentIndex < orderedClips.size - 1) triggerLoad(currentIndex + 1, play = false) },
                        enabled = !isPreparing && currentIndex < orderedClips.size - 1,
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
                    "再生順序（上が先頭・↑↓ で並び替え）",
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
                                        // currentIndex を追従
                                        currentIndex = when (currentIndex) {
                                            index     -> index - 1
                                            index - 1 -> index
                                            else      -> currentIndex
                                        }
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
                                        currentIndex = when (currentIndex) {
                                            index     -> index + 1
                                            index + 1 -> index
                                            else      -> currentIndex
                                        }
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
                        runCatching { if (isPlaying) mediaPlayer.pause() }
                        isPlaying = false
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
                Surface(color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize()) {}
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("結合中... ${(mergeProgress * 100).toInt()}%",
                        color = Color.White, style = MaterialTheme.typography.bodyLarge)
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
