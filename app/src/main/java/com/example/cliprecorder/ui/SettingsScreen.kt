package com.example.cliprecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.cliprecorder.BuildConfig
import com.example.cliprecorder.settings.Fps
import com.example.cliprecorder.settings.NamingFormat
import com.example.cliprecorder.settings.TimelapseInterval
import com.example.cliprecorder.settings.TimelapseDuration
import com.example.cliprecorder.settings.RECORD_DURATION_MAX
import com.example.cliprecorder.settings.RECORD_DURATION_MIN
import com.example.cliprecorder.settings.SettingsManager
import com.example.cliprecorder.settings.VideoQuality
import com.example.cliprecorder.viewmodel.CameraViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val currentQuality by settings.quality.collectAsState(initial = VideoQuality.FHD)
    val currentFps by settings.fps.collectAsState(initial = Fps.FPS_30)
    val currentDurationSec by settings.recordDurationSec.collectAsState(initial = RECORD_DURATION_MIN)
    val countdownEnabled by settings.countdownEnabled.collectAsState(initial = false)
    val stabilizationEnabled by settings.stabilizationEnabled.collectAsState(initial = true)
    val burstCount by settings.burstCount.collectAsState(initial = 1)
    val tlInterval by settings.timelapseInterval.collectAsState(initial = TimelapseInterval.SEC_2)
    val tlDuration by settings.timelapseDuration.collectAsState(initial = TimelapseDuration.MIN_1)
    val gpsEnabled by settings.gpsEnabled.collectAsState(initial = false)
    val autoDeleteAfterMerge by settings.autoDeleteAfterMerge.collectAsState(initial = false)
    val currentNaming by settings.namingFormat.collectAsState(initial = NamingFormat.DATETIME)
    val fileNamePrefix by settings.fileNamePrefix.collectAsState(initial = "VID_")

    var locationPermGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    // システム設定で権限変更後に画面に戻ったときも反映する
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationPermGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationPermGranted = granted
        if (granted) scope.launch { settings.setGpsEnabled(true) }
    }
    val rearCameraOptions by viewModel.rearCameraOptions.collectAsState()
    val currentRearCameraId by settings.rearCameraId.collectAsState(initial = null)
    val cameraZoomOverrides by settings.cameraZoomOverrides.collectAsState(initial = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // リアカメラ選択（複数ある場合のみ表示）
            if (rearCameraOptions.size > 1) {
                Text("リアカメラ", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // 「自動」選択肢
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentRearCameraId == null,
                            onClick = { scope.launch { settings.setRearCameraId(null) } },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentRearCameraId == null,
                        onClick = { scope.launch { settings.setRearCameraId(null) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("自動 (システム標準)")
                }

                rearCameraOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentRearCameraId == option.cameraId,
                                onClick = { scope.launch { settings.setRearCameraId(option.cameraId) } },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentRearCameraId == option.cameraId,
                            onClick = { scope.launch { settings.setRearCameraId(option.cameraId) } },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(option.label)
                            Text(
                                "焦点距離: ${"%.1f".format(option.focalLengthMm)}mm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
            }

            // 画質
            Text("画質", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "実際の最大解像度はデバイスのカメラ性能により異なります",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            VideoQuality.entries.forEach { quality ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentQuality == quality,
                            onClick = { scope.launch { settings.setQuality(quality) } },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentQuality == quality,
                        onClick = { scope.launch { settings.setQuality(quality) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(quality.label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // フレームレート
            Text("フレームレート", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "端末が非対応の場合は近い値に自動調整されます",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Fps.entries.forEach { fps ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentFps == fps,
                            onClick = { scope.launch { settings.setFps(fps) } },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentFps == fps,
                        onClick = { scope.launch { settings.setFps(fps) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fps.label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // 録画時間
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "録画時間",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (BuildConfig.IS_FREE_TIER) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${currentDurationSec}秒",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (BuildConfig.IS_FREE_TIER) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary,
                )
            }
            if (BuildConfig.IS_FREE_TIER) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "無料版は 1 秒固定です。有料版にアップグレードすると 1〜10 秒で設定できます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                // 目盛りラベル（1 秒刻み）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    (RECORD_DURATION_MIN..RECORD_DURATION_MAX).forEach { sec ->
                        Text(
                            if (sec % 5 == 0 || sec == RECORD_DURATION_MIN || sec == RECORD_DURATION_MAX)
                                "${sec}s" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Slider(
                    value = currentDurationSec.toFloat(),
                    onValueChange = { scope.launch { settings.setRecordDurationSec(it.roundToInt()) } },
                    valueRange = RECORD_DURATION_MIN.toFloat()..RECORD_DURATION_MAX.toFloat(),
                    steps = RECORD_DURATION_MAX - RECORD_DURATION_MIN - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // 連続録画本数
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "連続録画本数",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (BuildConfig.IS_FREE_TIER) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${burstCount}本",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (BuildConfig.IS_FREE_TIER) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary,
                )
            }
            if (BuildConfig.IS_FREE_TIER) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "無料版は 1 本固定です。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Slider(
                    value = burstCount.toFloat(),
                    onValueChange = { scope.launch { settings.setBurstCount(it.roundToInt()) } },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!BuildConfig.IS_FREE_TIER) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))

                // 位置情報 (GPS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("位置情報を記録 (GPS)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "録画した動画の撮影場所をメタデータに保存します",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (gpsEnabled && !locationPermGranted) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "位置情報の権限が許可されていません",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = gpsEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (locationPermGranted) scope.launch { settings.setGpsEnabled(true) }
                                else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            } else {
                                scope.launch { settings.setGpsEnabled(false) }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // タイムラプス
            Text("タイムラプス", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("フレーム間隔", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            TimelapseInterval.entries.forEach { iv ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = tlInterval == iv, onClick = { scope.launch { settings.setTimelapseInterval(iv) } })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = tlInterval == iv, onClick = { scope.launch { settings.setTimelapseInterval(iv) } })
                    Spacer(Modifier.width(8.dp))
                    Text(iv.label)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("録画時間", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            TimelapseDuration.entries.forEach { dur ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = tlDuration == dur, onClick = { scope.launch { settings.setTimelapseDuration(dur) } })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = tlDuration == dur, onClick = { scope.launch { settings.setTimelapseDuration(dur) } })
                    Spacer(Modifier.width(8.dp))
                    Text(dur.label)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // カウントダウン
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("録画前カウントダウン", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "録画開始前に 3・2・1 をカウントします",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = countdownEnabled,
                    onCheckedChange = { scope.launch { settings.setCountdownEnabled(it) } },
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // 手ブレ補正
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("手ブレ補正", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "非対応端末では効果がありません",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = stabilizationEnabled,
                    onCheckedChange = { scope.launch { settings.setStabilizationEnabled(it) } },
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // 結合後に元クリップを自動削除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("結合後に元クリップを自動削除", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "クリップ結合が完了したら元の動画ファイルを削除します",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = autoDeleteAfterMerge,
                    onCheckedChange = { scope.launch { settings.setAutoDeleteAfterMerge(it) } },
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ファイル名形式
            Text("ファイル名形式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // プレフィックス入力
            var prefixInput by remember(fileNamePrefix) { mutableStateOf(fileNamePrefix) }
            OutlinedTextField(
                value = prefixInput,
                onValueChange = { prefixInput = it },
                label = { Text("ファイル名の先頭文字列") },
                placeholder = { Text("VID_") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && prefixInput != fileNamePrefix) {
                            scope.launch { settings.setFileNamePrefix(prefixInput) }
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    scope.launch { settings.setFileNamePrefix(prefixInput) }
                }),
                supportingText = {
                    val sample = if (currentNaming == NamingFormat.DATETIME)
                        "${prefixInput}20260702_153045.mp4"
                    else
                        "${prefixInput}00001.mp4"
                    Text("例: $sample", style = MaterialTheme.typography.labelSmall)
                },
            )
            Spacer(Modifier.height(8.dp))

            NamingFormat.entries.forEach { fmt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentNaming == fmt,
                            onClick = { scope.launch { settings.setNamingFormat(fmt) } },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentNaming == fmt,
                        onClick = { scope.launch { settings.setNamingFormat(fmt) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fmt.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // カメラボタン手動ズーム設定（有料版 + 仮想カメラオプションあり）
            if (!BuildConfig.IS_FREE_TIER &&
                rearCameraOptions.size > 1 &&
                rearCameraOptions.any { it.targetZoomRatio != null }) {
                Text("カメラボタン ズーム調整", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "ボタンをタップしたときの実際のズーム倍率を微調整できます",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                rearCameraOptions.forEachIndexed { index, option ->
                    if (option.targetZoomRatio != null) {
                        val stored = cameraZoomOverrides[index] ?: option.targetZoomRatio
                        var sliderValue by remember(stored) { mutableFloatStateOf(stored) }
                        var isEditing by remember { mutableStateOf(false) }
                        var editText by remember(stored) { mutableStateOf("%.2f".format(stored)) }
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                if (isEditing) {
                                    OutlinedTextField(
                                        value = editText,
                                        onValueChange = { editText = it },
                                        modifier = Modifier.width(96.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        suffix = { Text("x") },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Decimal,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(onDone = {
                                            val parsed = editText.toFloatOrNull()
                                                ?.coerceIn(0.1f, 10f)
                                            if (parsed != null) {
                                                sliderValue = parsed
                                                viewModel.applyCameraZoomOverride(
                                                    index,
                                                    (parsed * 10f).roundToInt() / 10f,
                                                )
                                            }
                                            isEditing = false
                                        }),
                                    )
                                } else {
                                    Text(
                                        "${"%.2f".format(sliderValue)}x",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            editText = "%.2f".format(sliderValue)
                                            isEditing = true
                                        },
                                    )
                                }
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    sliderValue = it
                                    editText = "%.2f".format(it)
                                    isEditing = false
                                },
                                onValueChangeFinished = {
                                    viewModel.applyCameraZoomOverride(index,
                                        (sliderValue * 10f).roundToInt() / 10f)
                                },
                                valueRange = 0.1f..10f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                TextButton(
                    onClick = { viewModel.clearCameraZoomOverrides() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("デフォルトに戻す", color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
            }

            // カメラ診断情報
            Text("カメラ診断", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "不具合報告時に診断情報をコピーして送付してください",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val info = viewModel.buildDiagnosticInfo(context)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("camera_diag", info))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy,
                    contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("診断情報をクリップボードにコピー")
            }

        }
    }
}
