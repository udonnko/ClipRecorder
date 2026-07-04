package com.example.cliprecorder.viewmodel

import android.Manifest
import com.example.cliprecorder.BuildConfig
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import com.example.cliprecorder.video.BgmMixer
import com.example.cliprecorder.video.GifEncoder
import com.example.cliprecorder.video.SlowMotionProcessor
import com.example.cliprecorder.video.TimelapseRecorder
import com.example.cliprecorder.settings.TimelapseInterval
import com.example.cliprecorder.settings.TimelapseDuration
import java.util.concurrent.TimeUnit
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.cliprecorder.settings.AspectRatio
import com.example.cliprecorder.settings.FlashMode
import com.example.cliprecorder.settings.Fps
import com.example.cliprecorder.settings.NamingFormat
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.settings.VideoQuality
import com.example.cliprecorder.settings.WhiteBalance
import com.example.cliprecorder.video.ClipItem
import com.example.cliprecorder.video.VideoMerger
import com.example.cliprecorder.video.VideoTranscoder
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** リアカメラ1台分の情報 */
data class CameraOption(
    val cameraId: String,
    val label: String,       // 超広角 / 広角 / 望遠 など
    val focalLengthMm: Float,
    val targetZoomRatio: Float? = null,  // logical camera のとき zoom で切り替え
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val appSettings = SettingsManager(context)

    // ---- State ----
    private val _clips = MutableStateFlow<List<ClipItem>>(emptyList())
    val clips: StateFlow<List<ClipItem>> = _clips.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused: StateFlow<Boolean> = _isRecordingPaused.asStateFlow()

    private val _isMerging = MutableStateFlow(false)
    val isMerging: StateFlow<Boolean> = _isMerging.asStateFlow()

    private val _mergeProgress = MutableStateFlow(0f)
    val mergeProgress: StateFlow<Float> = _mergeProgress.asStateFlow()

    // emit() がサスペンドしないよう DROP_OLDEST バッファを持たせる
    private val _snackbarMessage = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    /** 非null のとき「解像度が異なる、スケールして結合しますか？」ダイアログを UI に表示させる */
    data class ScaleConfirmInfo(val description: String, val targetWidth: Int, val targetHeight: Int)
    private val _mergeScaleInfo = MutableStateFlow<ScaleConfirmInfo?>(null)
    val mergeScaleInfo: StateFlow<ScaleConfirmInfo?> = _mergeScaleInfo.asStateFlow()

    /** 非null のときメタデータ取得元選択ダイアログを UI に表示させる */
    data class MergeMetaSelectInfo(
        val clips: List<com.example.cliprecorder.video.ClipItem>,
        val compatResult: com.example.cliprecorder.video.VideoMerger.CompatibilityResult,
    )
    private val _mergeMetaSelectInfo = MutableStateFlow<MergeMetaSelectInfo?>(null)
    val mergeMetaSelectInfo: StateFlow<MergeMetaSelectInfo?> = _mergeMetaSelectInfo.asStateFlow()
    private var pendingMergeMetaUri: android.net.Uri? = null

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _minZoom = MutableStateFlow(1f)
    val minZoom: StateFlow<Float> = _minZoom.asStateFlow()
    private val _maxZoom = MutableStateFlow(1f)
    val maxZoom: StateFlow<Float> = _maxZoom.asStateFlow()

    // Camera2 の物理カメラ焦点距離から計算した光学ズームプリセット
    private val _zoomPresets = MutableStateFlow(listOf(1.0f, 2.0f, 5.0f, 10.0f))
    val zoomPresets: StateFlow<List<Float>> = _zoomPresets.asStateFlow()

    private val _hdrSupported = MutableStateFlow(false)
    val hdrSupported: StateFlow<Boolean> = _hdrSupported.asStateFlow()
    private val _hdrEnabled = MutableStateFlow(false)
    val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

    private val _isPortrait = MutableStateFlow(true)
    val isPortrait: StateFlow<Boolean> = _isPortrait.asStateFlow()

    private val _hasFlash = MutableStateFlow(false)
    val hasFlash: StateFlow<Boolean> = _hasFlash.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.AUTO)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    /** 録画中の経過時間（ミリ秒）。録画していないときは 0 */
    /** 録画開始時の SystemClock.elapsedRealtime()。非録画時は null */
    private val _recordingStartTimeMs = MutableStateFlow<Long?>(null)
    val recordingStartTimeMs: StateFlow<Long?> = _recordingStartTimeMs.asStateFlow()

    /** 無料版: 録画後の透かし処理中フラグ */
    private val _isPostProcessing = MutableStateFlow(false)
    val isPostProcessing: StateFlow<Boolean> = _isPostProcessing.asStateFlow()

    /** 無料版: 起動ごとにランダムで決まる透かし位置 */
    private val watermarkCorner = com.example.cliprecorder.video.WatermarkCorner.entries.random()

    /** カウントダウン残り秒数（null = カウントダウンなし）*/
    private val _countdownValue = MutableStateFlow<Int?>(null)
    val countdownValue: StateFlow<Int?> = _countdownValue.asStateFlow()
    private var countdownJob: kotlinx.coroutines.Job? = null

    /** タイムラプスモード */
    private val _isTimelapseMode = MutableStateFlow(false)
    val isTimelapseMode: StateFlow<Boolean> = _isTimelapseMode.asStateFlow()

    /** スローモーション */
    private val _isSlowMotionMode = MutableStateFlow(false)
    val isSlowMotionMode: StateFlow<Boolean> = _isSlowMotionMode.asStateFlow()

    fun toggleSlowMotionMode() { _isSlowMotionMode.value = !_isSlowMotionMode.value }

    private val _isExportingGif = MutableStateFlow(false)
    val isExportingGif: StateFlow<Boolean> = _isExportingGif.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _editProgress = MutableStateFlow(0f)
    val editProgress: StateFlow<Float> = _editProgress.asStateFlow()

    fun applyEffects(effects: List<com.example.cliprecorder.video.VideoEffect>) {
        val clip = _clips.value.firstOrNull { it.selected } ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isEditing.value = true
            _editProgress.value = 0f
            val suffix = effects.joinToString("") { e ->
                when (e) {
                    is com.example.cliprecorder.video.VideoEffect.FadeOut              -> "_fo"
                    is com.example.cliprecorder.video.VideoEffect.FadeIn               -> "_fi"
                    is com.example.cliprecorder.video.VideoEffect.Grayscale            -> "_bw"
                    is com.example.cliprecorder.video.VideoEffect.Sepia                -> "_sep"
                    is com.example.cliprecorder.video.VideoEffect.Vignette             -> "_vig"
                    is com.example.cliprecorder.video.VideoEffect.Cinematic            -> "_cin"
                    is com.example.cliprecorder.video.VideoEffect.KenBurns             -> "_kb"
                    is com.example.cliprecorder.video.VideoEffect.Brightness           -> "_br"
                    is com.example.cliprecorder.video.VideoEffect.Contrast             -> "_co"
                    is com.example.cliprecorder.video.VideoEffect.Saturation           -> "_sat"
                    is com.example.cliprecorder.video.VideoEffect.Warm                 -> "_warm"
                    is com.example.cliprecorder.video.VideoEffect.Cool                 -> "_cool"
                    is com.example.cliprecorder.video.VideoEffect.Vivid                -> "_vivid"
                    is com.example.cliprecorder.video.VideoEffect.Matte                -> "_matte"
                    is com.example.cliprecorder.video.VideoEffect.FilmGrain            -> "_grain"
                    is com.example.cliprecorder.video.VideoEffect.ChromaticAberration  -> "_ca"
                }
            }
            val outName = clip.name.substringBeforeLast(".") + "${suffix}_edit.mp4"
            runCatching {
                com.example.cliprecorder.video.VideoEditor.apply(
                    context = context,
                    inputUri = clip.uri,
                    outputName = outName,
                    effects = effects,
                    onProgress = { _editProgress.value = it },
                )
                clearSelection()
                loadClips()
                _snackbarMessage.emit("保存しました: $outName")
            }.onFailure {
                _snackbarMessage.emit("編集失敗: ${it.message}")
            }
            _isEditing.value = false
        }
    }

    private val _isMixingBgm = MutableStateFlow(false)
    val isMixingBgm: StateFlow<Boolean> = _isMixingBgm.asStateFlow()

    fun addBgmToSelected(bgmUri: android.net.Uri) {
        val clip = _clips.value.firstOrNull { it.selected } ?: return
        viewModelScope.launch {
            _isMixingBgm.value = true
            val outName = clip.name.substringBeforeLast(".") + "_bgm.mp4"
            runCatching {
                BgmMixer.mix(context, clip.uri, bgmUri, outName)
            }.onSuccess {
                loadClips()
                _snackbarMessage.emit("BGM追加完了: $outName")
            }.onFailure {
                _snackbarMessage.emit("BGM追加失敗: ${it.message}")
            }
            _isMixingBgm.value = false
        }
    }

    fun exportSelectedAsGif() {
        val clip = _clips.value.firstOrNull { it.selected } ?: return
        viewModelScope.launch {
            _isExportingGif.value = true
            val gifName = clip.name.substringBeforeLast(".") + ".gif"
            runCatching {
                GifEncoder.exportFromVideo(context, clip.uri, gifName)
            }.onSuccess {
                _snackbarMessage.emit("GIF保存完了: $gifName")
            }.onFailure {
                _snackbarMessage.emit("GIF書き出し失敗: ${it.message}")
            }
            _isExportingGif.value = false
        }
    }
    private val _timelapseProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val timelapseProgress: StateFlow<Pair<Int, Int>?> = _timelapseProgress.asStateFlow()
    private var imageCapture: ImageCapture? = null

    /** 連続録画: 残り本数 / 合計本数（1 のときは非表示）*/
    private val _burstRemaining = MutableStateFlow(0)
    val burstRemaining: StateFlow<Int> = _burstRemaining.asStateFlow()
    private val _burstTotal = MutableStateFlow(0)
    val burstTotal: StateFlow<Int> = _burstTotal.asStateFlow()

    /** 利用可能なリアカメラの一覧（複数ある場合のみ 2件以上になる）*/
    private val _rearCameraOptions = MutableStateFlow<List<CameraOption>>(emptyList())
    val rearCameraOptions: StateFlow<List<CameraOption>> = _rearCameraOptions.asStateFlow()

    /** 現在選択中のリアカメラ ID（null = 自動）*/
    private val _selectedRearCameraId = MutableStateFlow<String?>(null)
    val selectedRearCameraId: StateFlow<String?> = _selectedRearCameraId.asStateFlow()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var sequenceCounter = 1
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreviewView: PreviewView? = null
    private var currentQuality: VideoQuality = VideoQuality.FHD
    private var currentFps: Fps = Fps.FPS_30
    private var currentRearCameraId: String? = null
    private var currentAspectRatio: AspectRatio = AspectRatio.RATIO_16_9
    private var currentStabilization: Boolean = true
    private var orientationEventListener: OrientationEventListener? = null
    private var currentTargetRotation: Int = Surface.ROTATION_0

    // zoomState の継続観測（pinch-to-zoom 反映 + バインド直後の null 対策）
    private var zoomStateObserver: androidx.lifecycle.Observer<androidx.camera.core.ZoomState>? = null
    private var zoomStateLiveData: androidx.lifecycle.LiveData<androidx.camera.core.ZoomState>? = null
    private var zoomStateInitialized = false

    // ユーザーオーバーライド適用前の元のカメラオプション（"デフォルトに戻す"用）
    private var baseRearCameraOptions: List<CameraOption> = emptyList()

    // 露出補正 (EV)
    private val _evIndex = MutableStateFlow(0)
    val evIndex: StateFlow<Int> = _evIndex.asStateFlow()
    private val _evRange = MutableStateFlow(0..0)
    val evRange: StateFlow<IntRange> = _evRange.asStateFlow()
    private val _evStep = MutableStateFlow(0.33f)
    val evStep: StateFlow<Float> = _evStep.asStateFlow()

    fun setEvIndex(index: Int) {
        val clamped = index.coerceIn(_evRange.value)
        _evIndex.value = clamped
        camera?.cameraControl?.setExposureCompensationIndex(clamped)
    }

    // CameraX Extensions
    private var extensionsManager: ExtensionsManager? = null
    private val _availableExtensions = MutableStateFlow<List<Int>>(emptyList())
    val availableExtensions: StateFlow<List<Int>> = _availableExtensions.asStateFlow()
    private val _extensionMode = MutableStateFlow(ExtensionMode.NONE)
    val extensionMode: StateFlow<Int> = _extensionMode.asStateFlow()

    private val _whiteBalance = MutableStateFlow(WhiteBalance.AUTO)
    val whiteBalance: StateFlow<WhiteBalance> = _whiteBalance.asStateFlow()

    // ---- カメラ初期化 ----
    fun initCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        quality: VideoQuality,
        fps: Fps,
        rearCameraId: String? = null,
        stabilization: Boolean = true,
        aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    ) {
        currentQuality = quality
        currentFps = fps
        currentRearCameraId = rearCameraId
        currentAspectRatio = aspectRatio
        currentStabilization = stabilization
        _selectedRearCameraId.value = rearCameraId
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        loadClips()

        // 端末の物理的な向きを監視して動画のメタデータに正しい rotation を埋め込む
        orientationEventListener?.disable()
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134  -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else        -> Surface.ROTATION_0
                }
                currentTargetRotation = rotation
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }.also { it.enable() }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            enumerateRearCameras()
            // ExtensionsManager 初期化（対応 Extension を検出してから起動）
            val extFuture = ExtensionsManager.getInstanceAsync(context, provider)
            extFuture.addListener({
                runCatching {
                    extensionsManager = extFuture.get()
                    val baseSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    _availableExtensions.value = listOf(
                        ExtensionMode.NIGHT,
                        ExtensionMode.BOKEH,
                        ExtensionMode.HDR,
                        ExtensionMode.FACE_RETOUCH,
                        ExtensionMode.AUTO,
                    ).filter { mode ->
                        extensionsManager?.isExtensionAvailable(baseSelector, mode) == true
                    }
                }
            }, ContextCompat.getMainExecutor(context))
            // 保存済み設定を読み込んでからカメラを起動
            viewModelScope.launch {
                _flashMode.value = appSettings.flashMode.first()
                bindCamera()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** リアカメラを列挙してラベルを付ける（CameraManager で物理カメラを直接取得）*/
    private fun enumerateRearCameras() {
        val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager

        // カメラID昇順（小さいIDが物理カメラである可能性が高い）で取得
        val all = cm.cameraIdList.sorted().mapNotNull { id ->
            runCatching {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING)
                        != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: 0f
                CameraOption(cameraId = id, label = "", focalLengthMm = focalLength)
            }.getOrNull()
        }

        // 焦点距離が既知（> 0）のカメラは10%以内を同一とみなしdedup
        // 焦点距離が不明（0f）のカメラは強制的に追加（dedup しない）
        val deduped = mutableListOf<CameraOption>()
        for (opt in all) {
            val isDuplicate = opt.focalLengthMm > 0f && deduped.any { existing ->
                existing.focalLengthMm > 0f &&
                kotlin.math.abs(existing.focalLengthMm - opt.focalLengthMm) / existing.focalLengthMm < 0.10f
            }
            if (!isDuplicate) deduped.add(opt)
        }
        // 全カメラの焦点距離が判明しているなら焦点距離順（超広角→望遠）
        // 不明カメラが1つでもあればカメラID昇順（焦点距離でソートできない）
        val anyUnknownFl = deduped.any { it.focalLengthMm == 0f }
        var options = if (anyUnknownFl) {
            deduped.sortedBy { it.cameraId }
        } else {
            deduped.sortedBy { it.focalLengthMm }
        }

        Log.e("CameraVM", "リアカメラ検出数: ${options.size}")

        // logical カメラ 1 件だけ検出された場合：物理カメラの焦点距離を使って
        // zoom 切り替え用の仮想オプションに展開する
        if (options.size == 1) {
            val singleId = options[0].cameraId
            val singleChars = runCatching { cm.getCameraCharacteristics(singleId) }.getOrNull()
            // 物理カメラ ID から焦点距離を収集（physicalCameraIds が空なら自カメラの FL を使う）
            val physIds = singleChars?.physicalCameraIds?.toList() ?: emptyList()
            val allFLs: List<Float> = if (physIds.isNotEmpty()) {
                physIds.mapNotNull { physId ->
                    runCatching {
                        cm.getCameraCharacteristics(physId)
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.minOrNull()
                    }.getOrNull()
                }.distinct().sorted()
            } else {
                singleChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()?.distinct()?.sorted() ?: emptyList()
            }

            if (allFLs.size >= 2) {
                // 1.0x 基準焦点距離：3 種以上かつ最短と次の比が 1.5x 超なら超広角として 2 番目を基準に
                val refFL = if (allFLs.size >= 3 && allFLs[1] / allFLs[0] > 1.5f) {
                    allFLs[1]
                } else {
                    allFLs[0]
                }
                options = allFLs.map { fl ->
                    val zr = kotlin.math.round(fl / refFL * 10f) / 10f
                    CameraOption(cameraId = singleId, label = "", focalLengthMm = fl, targetZoomRatio = zr)
                }
                Log.e("CameraVM", "logical カメラ展開: ${allFLs.size} 種 refFL=$refFL")
            } else {
                Log.e("CameraVM", "logical カメラ展開スキップ: physIds=${physIds.size} allFLs=$allFLs")
            }
        }

        // 不明焦点距離のカメラがあるとどのカメラが超広角/望遠か判定できないため汎用名にする
        val labels = if (anyUnknownFl && options.size > 1) {
            options.indices.map { i -> "カメラ ${i + 1}" }
        } else {
            when (options.size) {
                0 -> emptyList()
                1 -> listOf("リアカメラ")
                2 -> listOf("広角", "望遠")
                3 -> listOf("超広角", "広角", "望遠")
                else -> listOf("超広角", "広角", "望遠") +
                        (4..options.size).map { "望遠 (${it - 2}x)" }
            }
        }

        _rearCameraOptions.value = options.mapIndexed { i, opt ->
            opt.copy(label = labels.getOrElse(i) { "カメラ ${i + 1}" })
        }
    }

    private fun bindCamera() {
        if (_isRecording.value) return   // 録画中は絶対に再バインドしない
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val previewView = currentPreviewView ?: return

        val cxQuality = when (currentQuality) {
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
            VideoQuality.UHD -> Quality.UHD
        }

        val recordFps = if (_isSlowMotionMode.value) 60 else currentFps.value

        // カメラがサポートする AE FPS レンジを調べて最適なものを選ぶ
        // Range(fps, fps) を固定指定するとサポートしない機種で黒画面になる
        val fpsRange: Range<Int> = try {
            val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            val isFront = _lensFacing.value == CameraSelector.LENS_FACING_FRONT
            val cameraId = if (isFront) {
                // フロントカメラの ID を取得（リアカメラの ID を使わない）
                cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                currentRearCameraId ?: cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }
            val ranges = cameraId?.let { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }
            // 上限が recordFps に一致するレンジを探し、下限が最大（より厳密）のものを優先
            ranges?.filter { it.upper == recordFps }
                  ?.maxByOrNull { it.lower }
                ?: Range(recordFps / 2, recordFps)   // 見つからなければ緩やかな範囲で代替
        } catch (e: Exception) {
            Range(recordFps / 2, recordFps)
        }

        val isFrontCamera = _lensFacing.value == CameraSelector.LENS_FACING_FRONT

        val resolutionSelector = when (currentAspectRatio) {
            AspectRatio.RATIO_16_9 -> androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            AspectRatio.RATIO_4_3 -> androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            AspectRatio.RATIO_1_1 -> androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1080, 1080),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()
        }

        val previewBuilder = Preview.Builder().setResolutionSelector(resolutionSelector)
        Camera2Interop.Extender(previewBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                fpsRange
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (currentStabilization && !isFrontCamera)
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            val sceneMode = when (_extensionMode.value) {
                ExtensionMode.NIGHT        -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                ExtensionMode.HDR          -> CaptureRequest.CONTROL_SCENE_MODE_HDR
                ExtensionMode.FACE_RETOUCH -> CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
                else -> null
            }
            if (sceneMode != null) {
                setCaptureRequestOption(CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, sceneMode)
            }
            val awbMode = when (_whiteBalance.value) {
                WhiteBalance.AUTO        -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                WhiteBalance.DAYLIGHT    -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalance.CLOUDY      -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalance.SHADE       -> CaptureRequest.CONTROL_AWB_MODE_SHADE
                WhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                WhiteBalance.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                WhiteBalance.TWILIGHT    -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
            }
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        }
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(cxQuality, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
            )
            .build()

        val videoCaptureBuilder = if (_hdrEnabled.value) {
            VideoCapture.Builder(recorder).setDynamicRange(DynamicRange.HDR_UNSPECIFIED_10_BIT)
        } else {
            VideoCapture.Builder(recorder)
        }
        // 16:9 以外は録画解像度もアスペクト比に合わせる
        if (currentAspectRatio != AspectRatio.RATIO_16_9) {
            videoCaptureBuilder.setResolutionSelector(resolutionSelector)
        }

        videoCapture = try {
            videoCaptureBuilder.build()
        } catch (e: Exception) {
            VideoCapture.withOutput(recorder)
        }

        val cameraSelector = buildCameraSelector()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // バインド前に現在の向きを適用（横向き起動・品質変更直後でも正しいメタデータになる）
        imageCapture?.targetRotation = currentTargetRotation
        videoCapture?.targetRotation = currentTargetRotation

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture, imageCapture!!)

            // zoomState を observeForever で継続観測
            // → バインド直後に .value が null のデバイスでも初期化できる
            // → pinch-to-zoom など外部からの zoom 変化も _zoomRatio に反映できる
            zoomStateObserver?.let { obs -> zoomStateLiveData?.removeObserver(obs) }
            zoomStateInitialized = false
            val newObserver = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { z ->
                _zoomRatio.value = z.zoomRatio
                _minZoom.value  = z.minZoomRatio
                _maxZoom.value  = z.maxZoomRatio
                if (!zoomStateInitialized) {
                    zoomStateInitialized = true
                    onFirstZoomState(z)
                }
            }
            zoomStateObserver = newObserver
            zoomStateLiveData = camera?.cameraInfo?.zoomState?.also {
                it.observeForever(newObserver)
            }

            val hdrSupport = camera?.let { cam ->
                Recorder.getVideoCapabilities(cam.cameraInfo)
                    .supportedDynamicRanges
                    .any { it != DynamicRange.SDR }
            } ?: false
            _hdrSupported.value = hdrSupport

            _hasFlash.value = camera?.cameraInfo?.hasFlashUnit() ?: false
            applyFlashMode()

            // 露出補正レンジ取得（デバイスごとに異なる）
            camera?.cameraInfo?.exposureState?.let { state ->
                val r = state.exposureCompensationRange
                _evRange.value = r.lower..r.upper
                _evStep.value  = state.exposureCompensationStep.let {
                    it.numerator.toFloat() / it.denominator.toFloat()
                }
                _evIndex.value = state.exposureCompensationIndex
            }

        } catch (e: Exception) {
            Log.e("CameraVM", "カメラバインド失敗: ${e.javaClass.simpleName} ${e.message}", e)
            viewModelScope.launch {
                _snackbarMessage.emit("カメラ起動失敗: ${e.javaClass.simpleName}")
            }
        }
    }

    /** バインド後に zoomState が最初に届いたときだけ呼ばれる初期化処理 */
    private fun onFirstZoomState(z: androidx.camera.core.ZoomState) {
        // 超広角を zoom でアクセスできるデバイスは minZoomRatio < 1.0 を報告する
        if (z.minZoomRatio < 0.9f) {
            val minZR = kotlin.math.round(z.minZoomRatio * 10f) / 10f
            val current = _zoomPresets.value.toMutableList()
            val withoutSubOne = current.filter { it >= 0.99f }
            val refined = (listOf(minZR) + withoutSubOne).distinct().sorted()
            _zoomPresets.value = refined
        }

        // フロントカメラ使用中は rearCameraOptions を触らない
        // （フロントの zoom 範囲でクランプするとリアのオプションが破損する）
        if (_lensFacing.value != CameraSelector.LENS_FACING_BACK) return

        // 焦点距離計算による targetZoomRatio が CameraX の実際の min/max と乖離している場合に修正
        val currentOpts = _rearCameraOptions.value
        if (currentOpts.any { it.targetZoomRatio != null }) {
            val clamped = currentOpts.map { opt ->
                if (opt.targetZoomRatio != null) {
                    opt.copy(targetZoomRatio = opt.targetZoomRatio
                        .coerceAtLeast(z.minZoomRatio)
                        .coerceAtMost(z.maxZoomRatio))
                } else opt
            }
            if (clamped.map { it.targetZoomRatio } != currentOpts.map { it.targetZoomRatio }) {
                val deduped = mutableListOf<CameraOption>()
                for (opt in clamped) {
                    val tgt = opt.targetZoomRatio
                    if (tgt == null || deduped.none { it.targetZoomRatio != null &&
                            kotlin.math.abs(it.targetZoomRatio - tgt) < 0.1f }) {
                        deduped.add(opt)
                    }
                }
                _rearCameraOptions.value = deduped
            }
        }

        // Camera2 で論理カメラ1件しか取得できなかった場合、zoom 範囲から仮想オプションを生成
        if (_lensFacing.value == CameraSelector.LENS_FACING_BACK &&
            _rearCameraOptions.value.size <= 1) {
            val minZR = z.minZoomRatio
            val maxZR = z.maxZoomRatio
            if (minZR < 0.9f || maxZR >= 2.5f) {
                val singleId = _rearCameraOptions.value.firstOrNull()?.cameraId
                    ?: currentRearCameraId ?: ""
                val virtualOptions = mutableListOf<CameraOption>()
                if (minZR < 0.9f) {
                    val minRounded = kotlin.math.round(minZR * 10f) / 10f
                    virtualOptions.add(CameraOption(singleId, "超広角", minRounded * 10f, minRounded))
                }
                virtualOptions.add(CameraOption(singleId, "広角", 10f, 1.0f))
                if (maxZR >= 2.5f) {
                    val teleZR = 3.0f.coerceAtMost(maxZR)
                    virtualOptions.add(CameraOption(singleId, "望遠", teleZR * 10f, teleZR))
                }
                if (virtualOptions.size >= 2) {
                    _rearCameraOptions.value = virtualOptions
                }
            }
        }

        // オーバーライド適用前の状態を保存（"デフォルトに戻す"の復元ポイント）
        baseRearCameraOptions = _rearCameraOptions.value

        // 設定で手動保存したズームオーバーライドがあれば適用する
        viewModelScope.launch {
            val overrides = appSettings.cameraZoomOverrides.first()
            if (overrides.isNotEmpty()) {
                val current = _rearCameraOptions.value.toMutableList()
                overrides.forEach { (idx, rawZoom) ->
                    if (idx < current.size) {
                        val clamped = rawZoom.coerceAtLeast(z.minZoomRatio)
                            .coerceAtMost(z.maxZoomRatio)
                        current[idx] = current[idx].copy(targetZoomRatio = clamped)
                    }
                }
                _rearCameraOptions.value = current
            }
        }
    }

    private fun buildCameraSelector(): CameraSelector {
        val baseSelector = if (_lensFacing.value == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
        } else {
            val targetId = currentRearCameraId
            if (targetId == null) {
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
            } else {
                CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        val match = infos.filter { Camera2CameraInfo.from(it).cameraId == targetId }
                        match.ifEmpty {
                            infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                        }.toMutableList()
                    }
                    .build()
            }
        }

        return baseSelector
    }

    /** CameraX Extension モードを切り替えてリバインドする（録画中は無視）*/
    fun setExtensionMode(mode: Int) {
        if (_isRecording.value) return
        _extensionMode.value = mode
        bindCamera()
    }

    fun setWhiteBalance(wb: WhiteBalance) {
        if (_isRecording.value) return
        _whiteBalance.value = wb
        bindCamera()
    }

    // ---- カメラ切り替え（録画中は不可）----
    fun switchCamera() {
        if (_isRecording.value) return
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        _hdrEnabled.value = false
        _extensionMode.value = ExtensionMode.NONE
        _whiteBalance.value = WhiteBalance.AUTO
        _evIndex.value = 0
        bindCamera()
    }

    // ---- リアカメラをIDで直接切り替え（録画中は不可）----
    fun switchToRearCamera(cameraId: String?) {
        if (_isRecording.value) return
        currentRearCameraId = cameraId
        _selectedRearCameraId.value = cameraId
        _lensFacing.value = CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(_minZoom.value, _maxZoom.value)
        _zoomRatio.value = clamped
        camera?.cameraControl?.setZoomRatio(clamped)
    }

    /** カメラボタンのズーム倍率を手動で上書きし、設定保存 + 現在のオプションリストを即時更新する */
    fun applyCameraZoomOverride(index: Int, zoom: Float) {
        viewModelScope.launch {
            appSettings.setCameraZoomOverride(index, zoom)
            val current = _rearCameraOptions.value.toMutableList()
            if (index < current.size) {
                val clamped = zoom.coerceAtLeast(_minZoom.value).coerceAtMost(_maxZoom.value)
                current[index] = current[index].copy(targetZoomRatio = clamped)
                _rearCameraOptions.value = current
            }
        }
    }

    /** すべてのカメラボタンズームオーバーライドをリセットして元の値に戻す */
    fun clearCameraZoomOverrides() {
        viewModelScope.launch {
            appSettings.clearCameraZoomOverrides()
            if (baseRearCameraOptions.isNotEmpty()) {
                _rearCameraOptions.value = baseRearCameraOptions
            }
        }
    }

    /** カメラ診断情報を文字列で返す（設定画面のクリップボードコピー用）*/
    fun buildDiagnosticInfo(context: android.content.Context): String {
        val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
        val sb = StringBuilder()
        sb.appendLine("=== ClipRecorder カメラ診断情報 ===")
        sb.appendLine("端末: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine()
        cm.cameraIdList.forEach { id ->
            runCatching {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "前面"
                    CameraCharacteristics.LENS_FACING_BACK  -> "背面"
                    else -> "外部"
                }
                val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.map { "%.2f".format(it) }
                val physIds = c.physicalCameraIds
                sb.appendLine("カメラID: $id ($facing)")
                sb.appendLine("  焦点距離: $fl")
                sb.appendLine("  physicalCameraIds: $physIds")
                physIds.forEach { physId ->
                    runCatching {
                        val pc = cm.getCameraCharacteristics(physId)
                        val pfl = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.map { "%.2f".format(it) }
                        sb.appendLine("  └ phys $physId: fl=$pfl")
                    }
                }
            }
        }
        sb.appendLine()
        sb.appendLine("=== 検出結果 ===")
        sb.appendLine("カメラオプション: ${_rearCameraOptions.value.size}件")
        _rearCameraOptions.value.forEach { opt ->
            sb.appendLine("  ${opt.label}: id=${opt.cameraId} fl=${"%.2f".format(opt.focalLengthMm)}mm zoom=${opt.targetZoomRatio}")
        }
        sb.appendLine("minZoom: ${_minZoom.value}  maxZoom: ${_maxZoom.value}")
        sb.appendLine("zoomPresets: ${_zoomPresets.value}")
        return sb.toString()
    }

    /** Camera2 の物理カメラ焦点距離から光学ズームプリセットを計算して更新する */
    fun initZoomPresets(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mgr = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                // logical カメラは自分の焦点距離リストにメインカメラしか返さない場合がある
                // physicalCameraIds で物理カメラの焦点距離も取得して全て収集する
                val backFocalLengths = mgr.cameraIdList.flatMap { id ->
                    runCatching {
                        val chars = mgr.getCameraCharacteristics(id)
                        if (chars.get(CameraCharacteristics.LENS_FACING) !=
                            CameraCharacteristics.LENS_FACING_BACK) return@runCatching emptyList()
                        val ownFLs = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            ?.toList() ?: emptyList()
                        // 物理カメラの焦点距離を追加取得（logical camera の場合に有効）
                        val physFLs = chars.physicalCameraIds.flatMap { physId ->
                            runCatching {
                                mgr.getCameraCharacteristics(physId)
                                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                    ?.toList() ?: emptyList()
                            }.getOrElse { emptyList() }
                        }
                        ownFLs + physFLs
                    }.getOrElse { emptyList() }
                }.distinct()

                val optical: List<Float> = if (backFocalLengths.size >= 2) {
                    val sorted = backFocalLengths.sorted()
                    // 焦点距離レベルでも 10% 以内の近似値を集約する
                    // （Zenfone 5Z の 9.4mm + 9.87mm など重複物理カメラを除去）
                    val focalDeduped = mutableListOf<Float>()
                    for (fl in sorted) {
                        if (focalDeduped.none { kotlin.math.abs(it - fl) / it < 0.10f }) {
                            focalDeduped.add(fl)
                        }
                    }
                    if (focalDeduped.size < 2) {
                        listOf(1.0f)
                    } else {
                        // 基準焦点距離 = 1.0x の物理カメラ
                        // 3 種以上かつ最短と次の比が 1.5x 超 → 最短は超広角、2番目が 1.0x 基準
                        // それ以外（主カメラ+望遠のみ）→ 最短が主カメラ = 1.0x 基準
                        val refFL = if (focalDeduped.size >= 3 && focalDeduped[1] / focalDeduped[0] > 1.5f) {
                            focalDeduped[1]
                        } else {
                            focalDeduped[0]
                        }
                        val ratios = focalDeduped
                            .map { fl -> kotlin.math.round(fl / refFL * 10f) / 10f }
                            .sorted()
                        // 0.15x 以内の近似値は同じプリセットとみなし1つに集約
                        val deduped = mutableListOf<Float>()
                        for (r in ratios) {
                            if (deduped.none { kotlin.math.abs(it - r) < 0.15f }) deduped.add(r)
                        }
                        deduped
                    }
                } else {
                    listOf(1.0f)
                }

                // デジタルズーム固定値（光学プリセットと0.15x以内の値は追加しない）
                val digital = listOf(2.0f, 5.0f, 10.0f).filter { d ->
                    optical.none { kotlin.math.abs(it - d) < 0.15f }
                }
                _zoomPresets.value = (optical + digital).sorted()
            } catch (e: Exception) {
                // 取得失敗時はデフォルトのまま
            }
        }
    }

    // ---- HDR切り替え（録画中・非対応時は不可）----
    fun toggleHdr() {
        if (_isRecording.value || !_hdrSupported.value) return
        _hdrEnabled.value = !_hdrEnabled.value
        bindCamera()
    }

    fun toggleOrientation() {
        _isPortrait.value = !_isPortrait.value
    }

    fun tapToFocus(factory: MeteringPointFactory, x: Float, y: Float) {
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun cycleFlash() {
        val next = when (_flashMode.value) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON  -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        _flashMode.value = next
        applyFlashMode()
        viewModelScope.launch { appSettings.setFlashMode(next) }
    }

    private fun applyFlashMode() {
        val cam = camera ?: return
        when (_flashMode.value) {
            FlashMode.ON  -> cam.cameraControl.enableTorch(true)
            FlashMode.OFF,
            FlashMode.AUTO -> cam.cameraControl.enableTorch(false)
        }
    }

    // ---- 一時停止 / 再開 ----
    fun togglePause() {
        if (!_isRecording.value) return
        if (_isRecordingPaused.value) {
            recording?.resume()
            _isRecordingPaused.value = false
        } else {
            recording?.pause()
            _isRecordingPaused.value = true
        }
    }

    // ---- 録画ボタン押下（カウントダウン → 録画） ----
    fun onRecordButtonPressed(settings: SettingsManager) {
        // 録画中はボタンで即停止
        if (_isRecording.value) {
            recordingJob?.cancel()
            recordingJob = null
            _isRecordingPaused.value = false
            return
        }
        // カウントダウン中はキャンセル
        if (_countdownValue.value != null) {
            countdownJob?.cancel()
            _countdownValue.value = null
            return
        }
        viewModelScope.launch {
            val burst = settings.burstCount.first()
            _burstTotal.value = burst
            _burstRemaining.value = burst
            val enabled = settings.countdownEnabled.first()
            if (enabled) {
                countdownJob = launch {
                    for (i in 3 downTo 1) {
                        _countdownValue.value = i
                        kotlinx.coroutines.delay(1000)
                    }
                    _countdownValue.value = null
                    recordClip(settings)
                }
            } else {
                recordClip(settings)
            }
        }
    }

    // ---- 録画（設定された秒数）----
    fun recordClip(settings: SettingsManager) {
        if (_isRecording.value) return

        recordingJob = viewModelScope.launch {
            val naming = settings.namingFormat.first()
            val prefix = settings.fileNamePrefix.first()
            val durationMs = settings.recordDurationSec.first() * 1000L
            val fileName = buildFileName(naming, prefix) + ".mp4"

            val gpsEnabled = !BuildConfig.IS_FREE_TIER && settings.gpsEnabled.first()
            val location = if (gpsEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
            ) getLastLocation() else null

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
                location?.let { loc ->
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.LATITUDE, loc.latitude)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.LONGITUDE, loc.longitude)
                }
            }
            val outputOptions = MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()

            val vc = videoCapture ?: return@launch
            _isRecording.value = true
            _isRecordingPaused.value = false
            _recordingStartTimeMs.value = SystemClock.elapsedRealtime()

            // VideoRecordEvent.Start（最初のフレームが取り込まれた瞬間）を待つための Deferred
            val recordingStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

            val rec = vc.output
                .prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> recordingStarted.complete(Unit)
                        is VideoRecordEvent.Finalize -> {
                            // Start が来る前に Finalize が来た場合（エラー時）もブロックを解除
                            recordingStarted.complete(Unit)
                        }
                        else -> {}
                    }
                    if (event is VideoRecordEvent.Finalize) {
                        _recordingStartTimeMs.value = null
                        if (event.hasError()) {
                            Log.e("CameraVM", "録画エラー: ${event.error}")
                            viewModelScope.launch {
                                _snackbarMessage.emit("録画エラー (コード: ${event.error})")
                            }
                            _isRecording.value = false
                            recording = null
                        } else {
                            sequenceCounter++
                            val clipUri = event.outputResults.outputUri
                            if (com.example.cliprecorder.BuildConfig.IS_FREE_TIER) {
                                // 録画完了後に透かしを焼き込む（_isRecording は処理完了まで true を維持）
                                viewModelScope.launch {
                                    _isPostProcessing.value = true
                                    _snackbarMessage.emit("透かし追加中...")
                                    withContext(Dispatchers.IO) { applyWatermark(clipUri) }
                                    _isPostProcessing.value = false
                                    loadClips()
                                    val remaining = _burstRemaining.value - 1
                                    _burstRemaining.value = remaining
                                    if (remaining > 0) {
                                        _isRecording.value = false
                                        recording = null
                                        kotlinx.coroutines.delay(300)
                                        recordClip(settings)
                                    } else {
                                        _burstTotal.value = 0
                                        _isRecording.value = false
                                        recording = null
                                    }
                                }
                            } else {
                                viewModelScope.launch {
                                    if (_isSlowMotionMode.value) {
                                        _isPostProcessing.value = true
                                        _snackbarMessage.emit("スロー処理中...")
                                        val slowName = fileName.removeSuffix(".mp4") + "_slow.mp4"
                                        withContext(Dispatchers.IO) {
                                            SlowMotionProcessor.process(context, clipUri, slowName)
                                        }
                                        _isPostProcessing.value = false
                                    }
                                    if (currentAspectRatio == AspectRatio.RATIO_1_1) {
                                        _isPostProcessing.value = true
                                        _snackbarMessage.emit("1:1に変換中...")
                                        withContext(Dispatchers.IO) { applySquareCrop(clipUri) }
                                        _isPostProcessing.value = false
                                    }
                                    loadClips()
                                    val remaining = _burstRemaining.value - 1
                                    _burstRemaining.value = remaining
                                    if (remaining > 0) {
                                        _isRecording.value = false
                                        recording = null
                                        kotlinx.coroutines.delay(300)
                                        recordClip(settings)
                                    } else {
                                        _burstTotal.value = 0
                                        _isRecording.value = false
                                        recording = null
                                    }
                                }
                            }
                        }
                    }
                }
            recording = rec

            // 最初のフレームが取り込まれてからタイマーをスタート → 正確な録画時間になる
            recordingStarted.await()
            try {
                delay(durationMs)
            } finally {
                rec.stop()
            }
        }
    }

    fun toggleTimelapseMode() {
        _isTimelapseMode.value = !_isTimelapseMode.value
    }

    private var timelapseJob: kotlinx.coroutines.Job? = null

    fun recordTimelapse(settings: SettingsManager) {
        if (_isRecording.value) return
        val ic = imageCapture ?: return
        timelapseJob = viewModelScope.launch {
            val interval = settings.timelapseInterval.first()
            val duration = settings.timelapseDuration.first()
            val naming = settings.namingFormat.first()
            val prefix = settings.fileNamePrefix.first()
            val outputName = buildFileName(naming, prefix) + "_tl.mp4"

            _isRecording.value = true
            _timelapseProgress.value = Pair(0, ((duration.seconds * 1000L) / interval.ms).toInt())

            var savedFrames = 0
            try {
                TimelapseRecorder.record(
                    context = context,
                    imageCapture = ic,
                    executor = ContextCompat.getMainExecutor(context),
                    intervalMs = interval.ms,
                    durationSec = duration.seconds,
                    outputName = outputName,
                    targetRotation = currentTargetRotation,
                    aspectRatio = currentAspectRatio,
                    onProgress = { captured, total ->
                        _timelapseProgress.value = Pair(captured, total)
                    },
                    onSaved = { frames -> savedFrames = frames },
                )
                _snackbarMessage.emit("タイムラプス完了: $outputName")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (savedFrames > 0) {
                    _snackbarMessage.tryEmit("タイムラプス停止: ${savedFrames}フレームを保存しました")
                }
                throw e
            } catch (e: Exception) {
                _snackbarMessage.emit("タイムラプス失敗: ${e.message}")
            } finally {
                _isRecording.value = false
                _timelapseProgress.value = null
                timelapseJob = null
                withContext(kotlinx.coroutines.NonCancellable) { loadClips() }
            }
        }
    }

    fun stopTimelapse() {
        timelapseJob?.cancel()
        timelapseJob = null
    }

    // ---- GPS ----
    @SuppressLint("MissingPermission")
    private fun getLastLocation(): android.location.Location? {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    }

    // ---- 無料版: 録画後に TRIAL 透かしを焼き込む ----
    private suspend fun applyWatermark(uri: android.net.Uri) {
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            val needSquare = currentAspectRatio == AspectRatio.RATIO_1_1
            val side = minOf(w, h)
            val targetW = if (needSquare) side else w
            val targetH = if (needSquare) side else h

            val tmp = java.io.File(context.cacheDir, "wm_${System.currentTimeMillis()}.mp4")
            try {
                com.example.cliprecorder.video.VideoTranscoder.transcode(
                    context = context,
                    inputUri = uri,
                    outputFile = tmp,
                    targetWidth = targetW,
                    targetHeight = targetH,
                    watermarkText = "TRIAL",
                    watermarkCorner = watermarkCorner,
                    cropToSquare = needSquare,
                    rotation = if (needSquare) rot else 0,
                )
                // MediaStore ファイルを透かし版で上書き
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
                context.contentResolver.update(uri, cv, null, null)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
            } finally {
                tmp.delete()
            }
        }.onFailure { e -> Log.e("CameraVM", "透かし追加失敗", e) }
    }

    // ---- 1:1 選択時: 録画後に正方形クロップ ----
    private suspend fun applySquareCrop(uri: android.net.Uri) {
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            if (w == h) return@runCatching  // already square, skip
            val side = minOf(w, h)

            val tmp = java.io.File(context.cacheDir, "sq_${System.currentTimeMillis()}.mp4")
            try {
                com.example.cliprecorder.video.VideoTranscoder.transcode(
                    context = context,
                    inputUri = uri,
                    outputFile = tmp,
                    targetWidth = side,
                    targetHeight = side,
                    cropToSquare = true,
                    rotation = rot,
                )
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
                context.contentResolver.update(uri, cv, null, null)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
            } finally {
                tmp.delete()
            }
        }.onFailure { e -> Log.e("CameraVM", "1:1クロップ失敗", e) }
    }

    // ---- MediaStore クエリでクリップ一覧更新 ----
    private fun loadClips() {
        viewModelScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.ORIENTATION,
                MediaStore.Video.Media.DURATION,
            )
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("Movies/ClipRecorder%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"

            val current = _clips.value
            val updated = mutableListOf<ClipItem>()

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val orientCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ORIENTATION)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val rawW    = cursor.getInt(widthCol)
                    val rawH    = cursor.getInt(heightCol)
                    val orient  = cursor.getInt(orientCol)
                    // 90°/270° 回転は縦動画：表示上の幅と高さを swap する
                    val dispW = if (orient == 90 || orient == 270) rawH else rawW
                    val dispH = if (orient == 90 || orient == 270) rawW else rawH
                    updated += ClipItem(
                        uri = uri,
                        name = cursor.getString(nameCol) ?: "",
                        createdAt = cursor.getLong(dateCol) * 1000L,
                        sizeBytes = cursor.getLong(sizeCol),
                        width = dispW,
                        height = dispH,
                        durationMs = cursor.getLong(durationCol),
                        selected = current.find { it.uri == uri }?.selected ?: false,
                    )
                }
            }
            _clips.value = updated
        }
    }

    // ---- 選択操作 ----
    fun toggleSelect(clip: ClipItem) {
        _clips.value = _clips.value.map {
            if (it.uri == clip.uri) it.copy(selected = !it.selected) else it
        }
    }

    fun selectAll() { _clips.value = _clips.value.map { it.copy(selected = true) } }
    fun clearSelection() { _clips.value = _clips.value.map { it.copy(selected = false) } }

    /** ドラッグ&ドロップによる並び替え（一時的・次回 loadClips で日付順にリセット）*/
    fun reorderClips(fromIndex: Int, toIndex: Int) {
        _clips.value = _clips.value.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
    }

    // ---- 結合 ----

    /** MergePreviewScreen から順序指定で呼ぶ */
    fun mergeOrdered(settings: SettingsManager, orderedClips: List<com.example.cliprecorder.video.ClipItem>) {
        if (orderedClips.size < 2) {
            viewModelScope.launch { _snackbarMessage.emit("2つ以上のクリップを選択してください") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                VideoMerger.checkCompatibility(context, orderedClips.map { it.uri })
            }
            if (result is VideoMerger.CompatibilityResult.AspectMismatch) {
                _snackbarMessage.emit("縦向きと横向きのクリップは結合できません (${result.description})")
                return@launch
            }
            _mergeMetaSelectInfo.value = MergeMetaSelectInfo(orderedClips, result)
        }
    }

    fun mergeSelected(settings: SettingsManager) {
        val selectedClips = _clips.value.filter { it.selected }
        if (selectedClips.size < 2) {
            viewModelScope.launch { _snackbarMessage.emit("2つ以上のクリップを選択してください") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                VideoMerger.checkCompatibility(context, selectedClips.map { it.uri })
            }
            if (result is VideoMerger.CompatibilityResult.AspectMismatch) {
                _snackbarMessage.emit("縦向きと横向きのクリップは結合できません (${result.description})")
                return@launch
            }
            // メタデータ取得元選択ダイアログを表示（CompatibilityResult も保持）
            _mergeMetaSelectInfo.value = MergeMetaSelectInfo(selectedClips, result)
        }
    }

    /** メタデータ選択ダイアログで確認後に呼ぶ */
    fun confirmMergeMetaSelect(settings: SettingsManager, metaUri: android.net.Uri) {
        val info = _mergeMetaSelectInfo.value ?: return
        _mergeMetaSelectInfo.value = null
        pendingMergeMetaUri = metaUri

        when (val compat = info.compatResult) {
            is VideoMerger.CompatibilityResult.CanScale -> {
                _mergeScaleInfo.value = ScaleConfirmInfo(compat.description, compat.targetWidth, compat.targetHeight)
            }
            else -> {
                val selected = info.clips.map { it.uri }
                viewModelScope.launch { doMerge(settings, selected, metaUri) }
            }
        }
    }

    fun dismissMergeMetaSelect() { _mergeMetaSelectInfo.value = null }

    private suspend fun doMerge(settings: SettingsManager, uris: List<android.net.Uri>, metaUri: android.net.Uri) {
        _isMerging.value = true
        _mergeProgress.value = 0f

        val naming = settings.namingFormat.first()
        val prefix = settings.fileNamePrefix.first()
        val outputName = "${prefix}merged_${buildFileName(naming)}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outputUri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        if (outputUri == null) {
            _snackbarMessage.emit("出力ファイル作成失敗")
            _isMerging.value = false
            return
        }

        var mergeSuccess = false
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
                if (pfd == null) {
                    context.contentResolver.delete(outputUri, null, null)
                    launch { _snackbarMessage.emit("結合失敗: 出力ファイルを開けませんでした") }
                } else {
                    pfd.use {
                        VideoMerger.merge(context, uris, it.fileDescriptor, metaUri) { progress ->
                            _mergeProgress.value = progress
                        }
                    }
                    mergeSuccess = true
                }
            } catch (e: Exception) {
                Log.e("CameraVM", "結合エラー", e)
                context.contentResolver.delete(outputUri, null, null)
                launch { _snackbarMessage.emit("結合失敗: ${e.message}") }
            }
        }

        if (mergeSuccess) {
            val update = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(outputUri, update, null, null)
            if (settings.autoDeleteAfterMerge.first()) {
                uris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            }
            clearSelection()
            loadClips()
            _snackbarMessage.emit("結合完了: $outputName")
        }

        _isMerging.value = false
    }

    // ---- 1件削除 ----
    fun deleteClip(clip: ClipItem) {
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.delete(clip.uri, null, null)
            loadClips()
        }
    }

    // ---- 選択中をまとめて削除 ----
    fun deleteSelected() {
        val selected = _clips.value.filter { it.selected }.map { it.uri }
        if (selected.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            selected.forEach { uri -> context.contentResolver.delete(uri, null, null) }
            loadClips()
        }
    }

    fun dismissScaleConfirm() { _mergeScaleInfo.value = null }

    /** スケールダウンしながら結合（ダイアログで確認済み後に呼ぶ） */
    fun mergeSelectedWithScale(settings: SettingsManager) {
        val info = _mergeScaleInfo.value ?: return
        _mergeScaleInfo.value = null
        val targetW = info.targetWidth
        val targetH = info.targetHeight
        val selected = _clips.value.filter { it.selected }
        if (selected.size < 2) return

        viewModelScope.launch {
            _isMerging.value = true
            _mergeProgress.value = 0f

            val cacheDir = context.cacheDir
            val tempFiles = mutableListOf<File>()
            val transcodedUris = mutableListOf<Uri>()
            var transcodeSuccess = true

            withContext(Dispatchers.IO) {
                selected.forEachIndexed { idx, clip ->
                    if (!transcodeSuccess) return@forEachIndexed
                    val needsScale = clip.width != targetW || clip.height != targetH
                    if (!needsScale) {
                        transcodedUris.add(clip.uri)
                    } else {
                        val tmp = File(cacheDir, "scale_${idx}_${System.currentTimeMillis()}.mp4")
                        tempFiles.add(tmp)
                        try {
                            val baseProgress = idx.toFloat() / selected.size * 0.8f
                            val stepRange = 0.8f / selected.size
                            VideoTranscoder.transcode(context, clip.uri, tmp, targetW, targetH) { p ->
                                _mergeProgress.value = baseProgress + p * stepRange
                            }
                            transcodedUris.add(Uri.fromFile(tmp))
                        } catch (e: Exception) {
                            Log.e("CameraVM", "スケール失敗", e)
                            launch { _snackbarMessage.emit("スケール失敗: ${e.message}") }
                            transcodeSuccess = false
                        }
                    }
                }
            }

            if (!transcodeSuccess) {
                tempFiles.forEach { it.delete() }
                _isMerging.value = false
                return@launch
            }

            // スケール済みクリップを結合
            val naming = settings.namingFormat.first()
            val prefix = settings.fileNamePrefix.first()
            val outputName = "${prefix}merged_${buildFileName(naming)}.mp4"
            val metaUri2 = pendingMergeMetaUri ?: selected.first().uri
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            if (outputUri == null) {
                _snackbarMessage.emit("出力ファイル作成失敗")
                tempFiles.forEach { it.delete() }
                _isMerging.value = false
                return@launch
            }

            var mergeSuccess = false
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openFileDescriptor(outputUri, "w")?.use { pfd ->
                        VideoMerger.merge(context, transcodedUris, pfd.fileDescriptor, metaUri2) { p ->
                            _mergeProgress.value = 0.8f + p * 0.2f
                        }
                    }
                    mergeSuccess = true
                } catch (e: Exception) {
                    Log.e("CameraVM", "結合エラー", e)
                    context.contentResolver.delete(outputUri, null, null)
                    launch { _snackbarMessage.emit("結合失敗: ${e.message}") }
                } finally {
                    tempFiles.forEach { it.delete() }
                }
            }

            if (mergeSuccess) {
                val update = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(outputUri, update, null, null)
                if (settings.autoDeleteAfterMerge.first()) {
                    selected.forEach { clip -> context.contentResolver.delete(clip.uri, null, null) }
                }
                clearSelection()
                loadClips()
                _snackbarMessage.emit("結合完了: $outputName")
            }

            _isMerging.value = false
        }
    }

    private fun buildFileName(naming: NamingFormat, prefix: String = "VID_"): String =
        prefix + when (naming) {
            NamingFormat.DATETIME -> SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            NamingFormat.SEQUENTIAL -> "%05d".format(sequenceCounter)
        }

    override fun onCleared() {
        super.onCleared()
        zoomStateObserver?.let { obs -> zoomStateLiveData?.removeObserver(obs) }
        orientationEventListener?.disable()
        orientationEventListener = null
    }
}
