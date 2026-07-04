package com.example.cliprecorder.ui

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cliprecorder.settings.AspectRatio
import com.example.cliprecorder.settings.Fps
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.settings.VideoQuality
import com.example.cliprecorder.BuildConfig
import com.example.cliprecorder.settings.FlashMode
import com.example.cliprecorder.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onNavigateClips: () -> Unit,
    onNavigateSettings: () -> Unit,
    autoStartRecord: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = remember { SettingsManager(context) }

    val isRecording by viewModel.isRecording.collectAsState()
    val isRecordingPaused by viewModel.isRecordingPaused.collectAsState()
    val isPostProcessing by viewModel.isPostProcessing.collectAsState()
    val countdownValue by viewModel.countdownValue.collectAsState()
    val burstRemaining by viewModel.burstRemaining.collectAsState()
    val burstTotal by viewModel.burstTotal.collectAsState()
    val isTimelapseMode by viewModel.isTimelapseMode.collectAsState()
    val timelapseProgress by viewModel.timelapseProgress.collectAsState()
    val isSlowMotionMode by viewModel.isSlowMotionMode.collectAsState()
    val clips by viewModel.clips.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 録画完了時の振動
    LaunchedEffect(isRecording) {
        if (!isRecording) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    // ウィジェットから起動した場合に自動で録画開始
    var widgetStarted by remember { mutableStateOf(false) }
    if (autoStartRecord && !widgetStarted) {
        LaunchedEffect(Unit) {
            widgetStarted = true
            val settingsSnap = settings
            viewModel.onRecordButtonPressed(settingsSnap)
        }
    }

    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val minZoom by viewModel.minZoom.collectAsState()
    val maxZoom by viewModel.maxZoom.collectAsState()
    val allZoomPresets by viewModel.zoomPresets.collectAsState()

    LaunchedEffect(Unit) { viewModel.initZoomPresets(context) }
    val hdrSupported by viewModel.hdrSupported.collectAsState()
    val hdrEnabled by viewModel.hdrEnabled.collectAsState()
    val hasFlash by viewModel.hasFlash.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val gridEnabled by settings.gridEnabled.collectAsState(initial = false)
    val levelEnabled by settings.levelEnabled.collectAsState(initial = false)
    val recordingStartTimeMs by viewModel.recordingStartTimeMs.collectAsState()
    // 毎フレーム (≈16ms) 経過時間を計算して更新
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingStartTimeMs) {
        if (recordingStartTimeMs == null) {
            recordingElapsedMs = 0L
        } else {
            while (true) {
                withFrameMillis { }
                recordingElapsedMs = SystemClock.elapsedRealtime() - (recordingStartTimeMs ?: break)
            }
        }
    }
    val lensFacing by viewModel.lensFacing.collectAsState()
    val rearCameraOptions by viewModel.rearCameraOptions.collectAsState()
    val selectedRearCameraId by viewModel.selectedRearCameraId.collectAsState()
    val availableExtensions by viewModel.availableExtensions.collectAsState()
    val extensionMode by viewModel.extensionMode.collectAsState()
    val whiteBalance by viewModel.whiteBalance.collectAsState()
    // display.rotation は Xiaomi 等で常に 0 を返すことがあるため
    // OrientationEventListener でセンサーから直接端末の傾きを取得する
    var deviceRotationDeg by remember { mutableStateOf(0) }
    var isUpsideDown by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(context) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                isUpsideDown = orientation in 135..224
                deviceRotationDeg = when {
                    orientation < 45 || orientation >= 315 -> 0
                    orientation < 135 -> 90
                    orientation < 225 -> 0  // 逆ポートレート帯はポートレートと同扱い
                    else -> 270
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    // アイコン・プレビューを物理的な「上」方向に向ける。
    // Activity はポートレート固定のため端末の回転量をそのまま使って補正する。
    val iconRotationAnimatable = remember { Animatable(0f) }
    // アイコン：物理的「上」を向かせるため端末回転の逆方向に補正
    // 左傾き(90°)→CCW90°(=270f)、右傾き(270°)→CW90°(=90f)
    val iconRotationTarget = if (isUpsideDown) 180f else ((360 - deviceRotationDeg) % 360).toFloat()
    LaunchedEffect(iconRotationTarget) {
        val current = iconRotationAnimatable.value
        val norm = ((current % 360f) + 360f) % 360f
        // -180〜+180 の最短経路デルタを求める
        val delta = ((iconRotationTarget - norm + 540f) % 360f) - 180f
        iconRotationAnimatable.animateTo(current + delta, animationSpec = tween(durationMillis = 300))
    }
    val iconRotation = iconRotationAnimatable.value
    // プレビュー：端末回転と同方向に回転して landscape 映像を portrait 画面に収める
    // アイコン補正とは逆方向になる
    val previewRotationDeg = deviceRotationDeg.toFloat()
    val isDeviceLandscape = deviceRotationDeg == 90 || deviceRotationDeg == 270
    val evIndex by viewModel.evIndex.collectAsState()
    val evRange by viewModel.evRange.collectAsState()
    val evStep by viewModel.evStep.collectAsState()

    val quality by settings.quality.collectAsState(initial = VideoQuality.FHD)
    val fps by settings.fps.collectAsState(initial = Fps.FPS_30)
    val durationSec by settings.recordDurationSec.collectAsState(initial = 1)
    val rearCameraId by settings.rearCameraId.collectAsState(initial = null)
    val stabilization by settings.stabilizationEnabled.collectAsState(initial = true)
    val selectedAspectRatio by settings.aspectRatio.collectAsState(initial = AspectRatio.RATIO_16_9)

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    var wbExpanded by remember { mutableStateOf(false) }
    var arExpanded by remember { mutableStateOf(false) }

    // カメラプレビュー領域の実際の Rect（黒帯の外側タップを除外するために使用）
    var previewBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    // タップフォーカスの位置（null = 非表示）
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            kotlinx.coroutines.delay(2000)
            focusTapOffset = null
        }
    }
    val focusAlpha by animateFloatAsState(
        targetValue = if (focusTapOffset != null) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "focusAlpha",
    )

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(quality, fps, rearCameraId, stabilization, isSlowMotionMode, selectedAspectRatio) {
        viewModel.initCamera(lifecycleOwner, previewView, quality, fps, rearCameraId, stabilization, selectedAspectRatio)
    }

    val scope = rememberCoroutineScope()
    var showZoomIndicator by remember { mutableStateOf(false) }
    val zoomHideJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val zoomIndicatorAlpha by animateFloatAsState(
        targetValue = if (showZoomIndicator) 1f else 0f,
        animationSpec = tween(durationMillis = if (showZoomIndicator) 100 else 400),
        label = "zoomAlpha",
    )
    val triggerZoomIndicator: () -> Unit = {
        showZoomIndicator = true
        zoomHideJob[0]?.cancel()
        zoomHideJob[0] = scope.launch {
            kotlinx.coroutines.delay(1500)
            showZoomIndicator = false
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .pointerInput(Unit) {
                    coroutineScope {
                        // ピンチでズーム
                        launch {
                            detectTransformGestures { _, _, zoom, _ ->
                                viewModel.setZoom(zoomRatio * zoom)
                                triggerZoomIndicator()
                            }
                        }
                        // タップでフォーカス（プレビュー領域外・黒帯は無視）
                        launch {
                            detectTapGestures { offset ->
                                val bounds = previewBoundsInRoot
                                if (bounds != null && !bounds.contains(offset)) return@detectTapGestures
                                // 1:1 モードの上下黒帯エリアは除外
                                if (selectedAspectRatio == AspectRatio.RATIO_1_1 && bounds != null) {
                                    val squareSize = bounds.width.coerceAtMost(bounds.height)
                                    val barH = (bounds.height - squareSize) / 2
                                    if (offset.y < bounds.top + barH || offset.y > bounds.bottom - barH) return@detectTapGestures
                                }
                                focusTapOffset = offset
                                viewModel.tapToFocus(
                                    previewView.meteringPointFactory,
                                    offset.x, offset.y,
                                )
                            }
                        }
                    }
                }
        ) {
            // Activity はポートレート固定。CameraX が ROTATION_0 基準でセンサー補正するため、
            // プレビューを fillMaxSize() のまま回転させないでも、ユーザーが横向きに持つと
            // ポートレート画面がそのまま横向きの景色として正しく見える。
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val previewModifier = when (selectedAspectRatio) {
                    AspectRatio.RATIO_16_9 -> Modifier.fillMaxSize()
                    AspectRatio.RATIO_4_3  -> Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    AspectRatio.RATIO_1_1  -> Modifier.fillMaxSize()
                }
                Box(modifier = previewModifier.onGloballyPositioned { coords ->
                    previewBoundsInRoot = coords.boundsInRoot()
                }) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // グリッド線（三分割法）
                    if (gridEnabled) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val color = Color.White.copy(alpha = 0.4f)
                            val stroke = 1.dp.toPx()
                            drawLine(color, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
                            drawLine(color, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
                            drawLine(color, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
                            drawLine(color, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
                        }
                    }
                }
                // 1:1 のとき上下に黒帯を重ねて疑似正方形プレビュー
                if (selectedAspectRatio == AspectRatio.RATIO_1_1) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val barH = (maxHeight - maxWidth) / 2
                        if (barH > 0.dp) {
                            Box(Modifier.fillMaxWidth().height(barH).background(Color.Black).align(Alignment.TopStart))
                            Box(Modifier.fillMaxWidth().height(barH).background(Color.Black).align(Alignment.BottomStart))
                        }
                    }
                }
            }

            // 水平器は物理傾きを示すため回転しない（ポートレート座標系で表示）
            if (levelEnabled) {
                LevelOverlay(modifier = Modifier.fillMaxSize())
            }

            // ズーム倍率インジケータ（ピンチ中のみ表示）
            if (zoomIndicatorAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(zoomIndicatorAlpha)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "%.1fx".format(zoomRatio),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
            }

            // タイムラプス進捗オーバーレイ
            timelapseProgress?.let { (captured, total) ->
                val tlTransition = rememberInfiniteTransition(label = "tl_dot")
                val dotAlpha by tlTransition.animateFloat(
                    initialValue = 1f, targetValue = 0.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ), label = "dot_alpha",
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 56.dp, start = 12.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .widthIn(min = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .alpha(dotAlpha)
                                .background(Color.Cyan, CircleShape),
                        )
                        Text(
                            "タイムラプス $captured / $total",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { captured.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    }
                }
            }

            // カウントダウンオーバーレイ
            countdownValue?.let { count ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = count.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(
                                120f, androidx.compose.ui.unit.TextUnitType.Sp
                            )
                        ),
                    )
                }
            }

            // フォーカス＆露出ロックリング（タップ位置に表示）
            focusTapOffset?.let { offset ->
                val ringSize = 72.dp
                val ringSizePx = with(LocalDensity.current) { ringSize.toPx() }
                Column(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (offset.x - ringSizePx / 2).roundToInt(),
                                (offset.y - ringSizePx / 2).roundToInt(),
                            )
                        }
                        .alpha(focusAlpha),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(ringSize)
                            .border(2.dp, Color.Yellow, RoundedCornerShape(6.dp))
                    )
                    Text(
                        "AF/AE",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // ===== 上部フローティングコントロール行 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // フラッシュ（背面カメラのみ・左端）
                if (hasFlash && lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                    IconButton(onClick = { viewModel.cycleFlash() }) {
                        Icon(
                            imageVector = when (flashMode) {
                                FlashMode.AUTO -> Icons.Default.FlashAuto
                                FlashMode.ON   -> Icons.Default.FlashOn
                                FlashMode.OFF  -> Icons.Default.FlashOff
                            },
                            contentDescription = "フラッシュ",
                            tint = if (flashMode == FlashMode.ON) Color.Yellow else Color.White,
                            modifier = Modifier.rotate(iconRotation),
                        )
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Spacer(Modifier.weight(1f))
                // アスペクト比（タップで展開）
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable(enabled = !isRecording) {
                            arExpanded = !arExpanded
                            if (arExpanded) wbExpanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        selectedAspectRatio.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
                // ホワイトバランス
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background(
                            if (whiteBalance == com.example.cliprecorder.settings.WhiteBalance.AUTO)
                                Color.Black.copy(alpha = 0.35f)
                            else
                                Color(0xFF4FC3F7),
                            RoundedCornerShape(20.dp),
                        )
                        .clickable(enabled = !isRecording) {
                            wbExpanded = !wbExpanded
                            if (wbExpanded) arExpanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "WB",
                        color = if (whiteBalance == com.example.cliprecorder.settings.WhiteBalance.AUTO) Color.White else Color.Black,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
                // HDR
                if (hdrSupported) {
                    IconButton(onClick = { viewModel.toggleHdr() }, enabled = !isRecording) {
                        ToggleIcon(
                            imageVector = Icons.Default.HdrOn,
                            active = hdrEnabled,
                            contentDescription = "HDR",
                            modifier = Modifier.rotate(iconRotation),
                        )
                    }
                }
                // グリッド
                IconButton(onClick = { scope.launch { settings.setGridEnabled(!gridEnabled) } }) {
                    ToggleIcon(
                        imageVector = Icons.Default.GridOn,
                        active = gridEnabled,
                        contentDescription = "グリッド",
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
                // 水平器
                IconButton(onClick = { scope.launch { settings.setLevelEnabled(!levelEnabled) } }) {
                    ToggleIcon(
                        imageVector = Icons.Default.Architecture,
                        active = levelEnabled,
                        contentDescription = "水平器",
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
                // 設定
                IconButton(onClick = onNavigateSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "設定",
                        tint = Color.White,
                        modifier = Modifier.rotate(iconRotation),
                    )
                }
            }
            // アスペクト比ドロップダウン（上部バー直下）
            AnimatedVisibility(
                visible = arExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AspectRatio.entries.forEach { ratio ->
                            val sel = selectedAspectRatio == ratio
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (sel) Color.White else Color.Black.copy(alpha = 0.6f),
                                        RoundedCornerShape(20.dp),
                                    )
                                    .clickable {
                                        scope.launch { settings.setAspectRatio(ratio) }
                                        arExpanded = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(ratio.label, color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            // WB ドロップダウン（上部バー直下）
            AnimatedVisibility(
                visible = wbExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                    com.example.cliprecorder.settings.WhiteBalance.entries.forEach { wb ->
                        val sel = whiteBalance == wb
                        Box(
                            modifier = Modifier
                                .background(
                                    if (sel) Color(0xFF4FC3F7) else Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable {
                                    viewModel.setWhiteBalance(wb)
                                    wbExpanded = false
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(wb.label, color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    } // Row
                } // Box
            }
            } // Column (上部フローティングコントロール)

            // 左側：縦 EV スライダー（対応デバイスのみ）
            if (evRange.first < evRange.last) {
                val evSliderHeight = 240.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { viewModel.setEvIndex(0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.WbSunny,
                            contentDescription = "露出補正リセット",
                            tint = if (evIndex == 0) Color.White else Color(0xFFFFD600),
                            modifier = Modifier.size(16.dp).rotate(iconRotation),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.size(width = 40.dp, height = evSliderHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = evIndex.toFloat(),
                            onValueChange = { viewModel.setEvIndex(it.roundToInt()) },
                            valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                            modifier = Modifier.rotate(270f).requiredWidth(evSliderHeight),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFFFFD600),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%+.1f".format(evIndex * evStep),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            // ===== 下部コントロール =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // メニュー開閉ボタン
                    var menuExpanded by remember { mutableStateOf(true) }
                    LaunchedEffect(isRecording) {
                        if (isRecording) {
                            menuExpanded = false
                            wbExpanded = false
                            arExpanded = false
                        }
                    }
                    IconButton(
                        onClick = { menuExpanded = !menuExpanded },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (menuExpanded) Icons.Default.KeyboardArrowDown
                                          else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (menuExpanded) "メニューを隠す" else "メニューを表示",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp).rotate(iconRotation),
                        )
                    }
                    // 折りたたみ対象（ズームプリセット〜モードセレクタ）
                    AnimatedVisibility(
                        visible = menuExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // ズームプリセット
                    val zoomPresets = remember(allZoomPresets, minZoom, maxZoom) {
                        allZoomPresets.filter { it >= minZoom - 0.05f && it <= maxZoom + 0.05f }
                    }
                    if (zoomPresets.size >= 2 && rearCameraOptions.size <= 1 &&
                        lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp),
                        ) {
                            zoomPresets.forEach { preset ->
                                val isActive = abs(zoomRatio - preset) < 0.2f
                                val label = when {
                                    preset == 1.0f -> "1x"
                                    preset == preset.toInt().toFloat() -> "${preset.toInt()}x"
                                    else -> "%.1fx".format(preset)
                                }
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isActive) Color.White
                                            else Color.Black.copy(alpha = 0.45f),
                                            RoundedCornerShape(20.dp),
                                        )
                                        .clickable(enabled = !isRecording) {
                                            viewModel.setZoom(preset)
                                            triggerZoomIndicator()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        label,
                                        color = if (isActive) Color.Black else Color.White,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                    // リアカメラ複数選択
                    if (!BuildConfig.IS_FREE_TIER &&
                        rearCameraOptions.size > 1 &&
                        lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            val selectedIdx: Int = when {
                                rearCameraOptions.any { it.targetZoomRatio != null } ->
                                    rearCameraOptions.indices
                                        .filter { rearCameraOptions[it].targetZoomRatio != null }
                                        .minByOrNull { abs(zoomRatio - rearCameraOptions[it].targetZoomRatio!!) }
                                        ?: -1
                                else ->
                                    rearCameraOptions.indexOfFirst { it.cameraId == selectedRearCameraId }
                            }
                            rearCameraOptions.forEachIndexed { index, option ->
                                val selected = index == selectedIdx
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) Color.White else Color.Black.copy(alpha = 0.45f),
                                            RoundedCornerShape(20.dp),
                                        )
                                        .clickable(enabled = !isRecording) {
                                            if (option.targetZoomRatio != null) viewModel.setZoom(option.targetZoomRatio)
                                            else viewModel.switchToRearCamera(option.cameraId)
                                            triggerZoomIndicator()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(option.label,
                                        color = if (selected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    // Extension モード
                    if (availableExtensions.isNotEmpty() &&
                        lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                        val extLabels = mapOf(
                            androidx.camera.extensions.ExtensionMode.NIGHT        to "夜景",
                            androidx.camera.extensions.ExtensionMode.BOKEH        to "ポートレート",
                            androidx.camera.extensions.ExtensionMode.HDR          to "HDR",
                            androidx.camera.extensions.ExtensionMode.FACE_RETOUCH to "美肌",
                            androidx.camera.extensions.ExtensionMode.AUTO         to "AUTO",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)) {
                            val noneSelected = extensionMode == androidx.camera.extensions.ExtensionMode.NONE
                            Box(modifier = Modifier
                                .background(if (noneSelected) Color.White else Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                .clickable(enabled = !isRecording) { viewModel.setExtensionMode(androidx.camera.extensions.ExtensionMode.NONE) }
                                .padding(horizontal = 12.dp, vertical = 5.dp)) {
                                Text("通常", color = if (noneSelected) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge)
                            }
                            availableExtensions.forEach { mode ->
                                val label = extLabels[mode] ?: return@forEach
                                val sel = extensionMode == mode
                                Box(modifier = Modifier
                                    .background(if (sel) Color(0xFFFFD600) else Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                    .clickable(enabled = !isRecording) { viewModel.setExtensionMode(mode) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                                    Text(label, color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }

                    // モードセレクタ（通常 / スロー / タイムラプス）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        val isNormal = !isSlowMotionMode && !isTimelapseMode
                        listOf(
                            Triple("通常", isNormal, fun() {
                                if (isSlowMotionMode) viewModel.toggleSlowMotionMode()
                                if (isTimelapseMode) viewModel.toggleTimelapseMode()
                            }),
                            Triple("スロー", isSlowMotionMode, fun() {
                                if (isTimelapseMode) viewModel.toggleTimelapseMode()
                                if (!isSlowMotionMode) viewModel.toggleSlowMotionMode()
                            }),
                            Triple("タイムラプス", isTimelapseMode, fun() {
                                if (isSlowMotionMode) viewModel.toggleSlowMotionMode()
                                if (!isTimelapseMode) viewModel.toggleTimelapseMode()
                            }),
                        ).forEach { (label, isActive, onSelect) ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isActive) Color.White else Color.Black.copy(alpha = 0.45f),
                                        RoundedCornerShape(20.dp),
                                    )
                                    .clickable(enabled = !isRecording) { onSelect() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (isActive) Color.Black else Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }

                    } // Column (折りたたみ内)
                    } // AnimatedVisibility

                    // メインシャッター行：[クリップ一覧] [録画ボタン] [カメラ切り替え]
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 左：クリップ一覧（ギャラリーボタン）
                        BadgedBox(
                            badge = { if (clips.isNotEmpty()) Badge { Text(clips.size.toString()) } },
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            FilledIconButton(
                                onClick = onNavigateClips,
                                modifier = Modifier.size(56.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                ),
                            ) {
                                Icon(Icons.Default.VideoLibrary, contentDescription = "クリップ一覧",
                                    tint = Color.White, modifier = Modifier.rotate(iconRotation))
                            }
                        }

                        // 中央：録画ボタン
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val btnColor = when {
                                isPostProcessing       -> Color.Gray
                                countdownValue != null -> Color(0xFFFF6F00)
                                isRecording            -> Color(0xFF8B0000)
                                else                   -> Color.Red
                            }
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(btnColor)
                                    .clickable(enabled = !isPostProcessing) {
                                        when {
                                            countdownValue != null          -> viewModel.onRecordButtonPressed(settings)
                                            isTimelapseMode && isRecording  -> viewModel.stopTimelapse()
                                            isTimelapseMode                 -> viewModel.recordTimelapse(settings)
                                            else                            -> viewModel.onRecordButtonPressed(settings)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    when {
                                        isRecording            -> Icons.Default.Stop
                                        countdownValue != null -> Icons.Default.Close
                                        else                   -> Icons.Default.FiberManualRecord
                                    },
                                    contentDescription = if (isRecording) "タップで録画停止" else "撮影",
                                    modifier = Modifier.size(48.dp).rotate(iconRotation),
                                    tint = Color.White,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            if (isRecording) {
                                val totalSec = recordingElapsedMs / 1000
                                val timeStr  = "%d:%02d".format(totalSec / 60, totalSec % 60)
                                val burstStr = if (burstTotal > 1) " ${burstTotal - burstRemaining + 1}/$burstTotal" else ""
                                val dot = if (isRecordingPaused) "⏸" else "●"
                                Text("$dot $timeStr$burstStr", color = Color.Red,
                                    style = MaterialTheme.typography.bodyLarge)
                                if (!isTimelapseMode) {
                                    Spacer(Modifier.height(4.dp))
                                    IconButton(
                                        onClick = { viewModel.togglePause() },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            if (isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = if (isRecordingPaused) "再開" else "一時停止",
                                            tint = Color.White,
                                            modifier = Modifier.rotate(iconRotation),
                                        )
                                    }
                                }
                            } else {
                                Text("タップで${durationSec}秒撮影", color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }

                        // 右：カメラ切り替え
                        FilledIconButton(
                            onClick = { viewModel.switchCamera() },
                            enabled = !isRecording,
                            modifier = Modifier.align(Alignment.CenterEnd).size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.3f),
                                disabledContainerColor = Color.White.copy(alpha = 0.1f),
                            ),
                        ) {
                            Icon(Icons.Default.FlipCameraAndroid,
                                contentDescription = "カメラ切り替え", tint = Color.White,
                                modifier = Modifier.rotate(iconRotation))
                        }
                    }
                } // Column end
            }
        }
    }
}

/** ON/OFF 切り替えアイコン。OFF 時はアイコンに白い斜め「\」を重ねる */
@Composable
private fun ToggleIcon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = modifier,
        )
        if (!active) {
            androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.22f, size.height * 0.08f),
                    end   = Offset(size.width * 0.78f, size.height * 0.92f),
                    strokeWidth = 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun LevelOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // センサーから取得した生のロール角（向き補正なし）
    var rawRoll by remember { mutableFloatStateOf(0f) }
    var reliable by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val g = kotlin.math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
                reliable = abs(gz) < g * 0.85f
                // Xiaomi は gy が標準逆（正が下）のため gy をそのまま使う
                rawRoll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    if (!reliable) return

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val baseRoll = (rawRoll / 90f).roundToInt() * 90f
        var tiltDeg = rawRoll - baseRoll
        if (tiltDeg >  180f) tiltDeg -= 360f
        if (tiltDeg < -180f) tiltDeg += 360f

        val isLevel = abs(tiltDeg) < 1.5f
        val lineColor = if (isLevel) Color(0xFF00E676) else Color.White

        // テキストとバーをまとめて rotate → テキストは常にバーの真上
        Column(
            modifier = Modifier.rotate(rawRoll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (isLevel) "水平" else "${"%.1f".format(abs(tiltDeg))}°",
                color = lineColor,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(modifier = Modifier.size(140.dp, 24.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val gap = 20.dp.toPx()
                val tickH = 10.dp.toPx()
                val sw = 2.5f.dp.toPx()
                drawLine(lineColor, Offset(0f, cy), Offset(cx - gap, cy), sw)
                drawLine(lineColor, Offset(cx + gap, cy), Offset(size.width, cy), sw)
                drawLine(lineColor, Offset(0f, cy - tickH / 2), Offset(0f, cy + tickH / 2), sw)
                drawLine(lineColor, Offset(size.width, cy - tickH / 2), Offset(size.width, cy + tickH / 2), sw)
                drawCircle(
                    color = lineColor,
                    radius = if (isLevel) 8.dp.toPx() else 5.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }
    }
}
