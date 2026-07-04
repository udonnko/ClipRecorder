package com.example.cliprecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cliprecorder.video.VideoEffect
import com.example.cliprecorder.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
) {
    val clips by viewModel.clips.collectAsState()
    val clip = clips.firstOrNull { it.selected }
    val isEditing   by viewModel.isEditing.collectAsState()
    val editProgress by viewModel.editProgress.collectAsState()

    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(isEditing) {
        if (wasEditing && !isEditing) onBack()
        wasEditing = isEditing
    }

    // ---- フェード ----
    var fadeInMs  by remember { mutableLongStateOf(0L) }
    var fadeOutMs by remember { mutableLongStateOf(0L) }
    val fadeOptions = listOf(0L to "なし", 500L to "0.5秒", 1000L to "1秒", 2000L to "2秒", 3000L to "3秒")

    // ---- カラーフィルター ----
    var colorFilter by remember { mutableStateOf("none") } // "none" / "bw" / "sepia"

    // ---- 色調整 ----
    var brightness by remember { mutableFloatStateOf(0f) }     // -0.5 〜 +0.5
    var contrast   by remember { mutableFloatStateOf(1f) }     // 0.5 〜 2.0
    var saturation by remember { mutableFloatStateOf(1f) }     // 0.0 〜 2.0
    var colorAdjOn by remember { mutableStateOf(false) }

    // ---- プリセット ----
    var preset by remember { mutableStateOf("none") } // "none"/"warm"/"cool"/"vivid"/"matte"

    // ---- ビネット ----
    var vignetteStrength by remember { mutableFloatStateOf(0f) }

    // ---- シネマスコープ ----
    var cinematic by remember { mutableStateOf(false) }

    // ---- ケン・バーンズ ----
    var kenBurns     by remember { mutableStateOf(false) }
    var kenBurnsZoom by remember { mutableFloatStateOf(1.3f) }

    // ---- 雰囲気 ----
    var grainOn       by remember { mutableStateOf(false) }
    var grainStrength by remember { mutableFloatStateOf(0.12f) }
    var caOn          by remember { mutableStateOf(false) }
    var caStrength    by remember { mutableFloatStateOf(0.012f) }

    // エフェクトリストを組み立て
    fun buildEffects(): List<VideoEffect> = buildList {
        if (fadeInMs > 0)  add(VideoEffect.FadeIn(fadeInMs))
        if (fadeOutMs > 0) add(VideoEffect.FadeOut(fadeOutMs))
        when (colorFilter) {
            "bw"    -> add(VideoEffect.Grayscale)
            "sepia" -> add(VideoEffect.Sepia)
        }
        if (colorAdjOn) {
            if (brightness != 0f) add(VideoEffect.Brightness(brightness))
            if (contrast != 1f)   add(VideoEffect.Contrast(contrast))
            if (saturation != 1f) add(VideoEffect.Saturation(saturation))
        }
        when (preset) {
            "warm"  -> add(VideoEffect.Warm)
            "cool"  -> add(VideoEffect.Cool)
            "vivid" -> add(VideoEffect.Vivid)
            "matte" -> add(VideoEffect.Matte)
        }
        if (vignetteStrength > 0f) add(VideoEffect.Vignette(vignetteStrength))
        if (cinematic)             add(VideoEffect.Cinematic)
        if (kenBurns)              add(VideoEffect.KenBurns(kenBurnsZoom))
        if (grainOn)               add(VideoEffect.FilmGrain(grainStrength))
        if (caOn)                  add(VideoEffect.ChromaticAberration(caStrength))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("編集") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isEditing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (clip == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("クリップが選択されていません")
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // クリップ情報
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("対象クリップ", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(4.dp))
                            Text(clip.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    // ---- フェードイン ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("フェードイン", style = MaterialTheme.typography.titleSmall)
                            Text("冒頭が徐々に明るくなります",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fadeOptions.forEach { (ms, label) ->
                                    FilterChip(
                                        selected = fadeInMs == ms,
                                        onClick = { fadeInMs = ms },
                                        label = { Text(label) },
                                        enabled = !isEditing,
                                    )
                                }
                            }
                        }
                    }

                    // ---- フェードアウト ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("フェードアウト", style = MaterialTheme.typography.titleSmall)
                            Text("末尾が徐々に暗くなります",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fadeOptions.forEach { (ms, label) ->
                                    FilterChip(
                                        selected = fadeOutMs == ms,
                                        onClick = { fadeOutMs = ms },
                                        label = { Text(label) },
                                        enabled = !isEditing,
                                    )
                                }
                            }
                        }
                    }

                    // ---- カラーフィルター ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("カラーフィルター", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("none" to "なし", "bw" to "モノクロ", "sepia" to "セピア").forEach { (key, label) ->
                                    FilterChip(
                                        selected = colorFilter == key,
                                        onClick = { colorFilter = key },
                                        label = { Text(label) },
                                        enabled = !isEditing,
                                    )
                                }
                            }
                        }
                    }

                    // ---- 色調整（明るさ・コントラスト・彩度） ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("色調整", style = MaterialTheme.typography.titleSmall)
                                    Text("明るさ・コントラスト・彩度",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Switch(checked = colorAdjOn, onCheckedChange = { colorAdjOn = it }, enabled = !isEditing)
                            }
                            if (colorAdjOn) {
                                Spacer(Modifier.height(12.dp))
                                Text("明るさ: ${if (brightness >= 0) "+%.2f".format(brightness) else "%.2f".format(brightness)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = brightness,
                                    onValueChange = { brightness = it },
                                    valueRange = -0.5f..0.5f,
                                    enabled = !isEditing,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("コントラスト: ${"%.2f".format(contrast)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = contrast,
                                    onValueChange = { contrast = it },
                                    valueRange = 0.5f..2.0f,
                                    enabled = !isEditing,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("彩度: ${"%.2f".format(saturation)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = saturation,
                                    onValueChange = { saturation = it },
                                    valueRange = 0.0f..2.0f,
                                    enabled = !isEditing,
                                )
                            }
                        }
                    }

                    // ---- カラープリセット ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("カラープリセット", style = MaterialTheme.typography.titleSmall)
                            Text("色味の雰囲気を変えます（排他選択）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "none"  to "なし",
                                    "warm"  to "暖色",
                                    "cool"  to "寒色",
                                    "vivid" to "ビビッド",
                                    "matte" to "マット",
                                ).forEach { (key, label) ->
                                    FilterChip(
                                        selected = preset == key,
                                        onClick = { preset = key },
                                        label = { Text(label) },
                                        enabled = !isEditing,
                                    )
                                }
                            }
                        }
                    }

                    // ---- ビネット ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("ビネット（周辺暗化）", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0f to "なし", 0.4f to "弱", 0.7f to "強").forEach { (v, label) ->
                                    FilterChip(
                                        selected = vignetteStrength == v,
                                        onClick = { vignetteStrength = v },
                                        label = { Text(label) },
                                        enabled = !isEditing,
                                    )
                                }
                            }
                        }
                    }

                    // ---- シネマスコープ ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("シネマスコープ", style = MaterialTheme.typography.titleSmall)
                                Text("上下に黒帯（映画風）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            Switch(checked = cinematic, onCheckedChange = { cinematic = it }, enabled = !isEditing)
                        }
                    }

                    // ---- ケン・バーンズ ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("ケン・バーンズ（ズーム）", style = MaterialTheme.typography.titleSmall)
                                    Text("徐々にズームインします",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Switch(checked = kenBurns, onCheckedChange = { kenBurns = it }, enabled = !isEditing)
                            }
                            if (kenBurns) {
                                Spacer(Modifier.height(8.dp))
                                Text("ズーム倍率: ${"%.1f".format(kenBurnsZoom)}x",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = kenBurnsZoom,
                                    onValueChange = { kenBurnsZoom = it },
                                    valueRange = 1.1f..2.0f,
                                    enabled = !isEditing,
                                )
                            }
                        }
                    }

                    // ---- 雰囲気エフェクト ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("雰囲気エフェクト", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(12.dp))

                            // フィルムグレイン
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("フィルムグレイン", style = MaterialTheme.typography.bodyMedium)
                                    Text("フィルム調のノイズ粒子",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Switch(checked = grainOn, onCheckedChange = { grainOn = it }, enabled = !isEditing)
                            }
                            if (grainOn) {
                                Spacer(Modifier.height(4.dp))
                                Text("強さ: ${"%.2f".format(grainStrength)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = grainStrength,
                                    onValueChange = { grainStrength = it },
                                    valueRange = 0.04f..0.35f,
                                    enabled = !isEditing,
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))

                            // クロマティックアベレーション
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("色収差", style = MaterialTheme.typography.bodyMedium)
                                    Text("RGB をわずかにズラしてレンズ収差風に",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Switch(checked = caOn, onCheckedChange = { caOn = it }, enabled = !isEditing)
                            }
                            if (caOn) {
                                Spacer(Modifier.height(4.dp))
                                Text("強さ: ${"%.3f".format(caStrength)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = caStrength,
                                    onValueChange = { caStrength = it },
                                    valueRange = 0.003f..0.04f,
                                    enabled = !isEditing,
                                )
                            }
                        }
                    }

                    // ---- 適用ボタン ----
                    val effects = buildEffects()
                    Button(
                        onClick = { viewModel.applyEffects(effects) },
                        enabled = !isEditing && effects.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (effects.isEmpty()) "エフェクトを選択してください" else "適用して保存")
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            // 処理中オーバーレイ
            if (isEditing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize()) {}
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "処理中... ${(editProgress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
