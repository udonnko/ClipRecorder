package com.example.cliprecorder.video

sealed class VideoEffect {
    // フェード
    data class FadeOut(val durationMs: Long) : VideoEffect()
    data class FadeIn(val durationMs: Long) : VideoEffect()
    // カラーフィルター
    data object Grayscale : VideoEffect()
    data object Sepia : VideoEffect()
    // 色調整
    data class Brightness(val value: Float = 0f) : VideoEffect()   // -0.5 〜 +0.5
    data class Contrast(val value: Float = 1f) : VideoEffect()     // 0.5 〜 2.0
    data class Saturation(val value: Float = 1f) : VideoEffect()   // 0.0 〜 2.0
    // プリセット（排他）
    data object Warm : VideoEffect()
    data object Cool : VideoEffect()
    data object Vivid : VideoEffect()
    data object Matte : VideoEffect()
    // 雰囲気
    data class FilmGrain(val strength: Float = 0.12f) : VideoEffect()
    data class ChromaticAberration(val strength: Float = 0.012f) : VideoEffect()
    // 構図
    data class Vignette(val strength: Float = 0.5f) : VideoEffect()
    data object Cinematic : VideoEffect()
    data class KenBurns(val zoomTo: Float = 1.3f) : VideoEffect()
}
