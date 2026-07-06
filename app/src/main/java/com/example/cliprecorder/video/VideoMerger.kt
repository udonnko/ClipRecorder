package com.example.cliprecorder.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.nio.ByteBuffer

object VideoMerger {

    sealed class CompatibilityResult {
        /** 全クリップが同じ解像度 — 高速結合可能 */
        object Compatible : CompatibilityResult()

        /** アスペクト比は同じだが解像度が異なる — スケールすれば結合可能 */
        data class CanScale(
            val targetWidth: Int,
            val targetHeight: Int,
            val description: String,
        ) : CompatibilityResult()

        /** アスペクト比が異なる（縦横混在など）— 結合不可 */
        data class AspectMismatch(val description: String) : CompatibilityResult()
    }

    /**
     * 全クリップの解像度・アスペクト比をチェックする。
     * 回転メタデータを考慮した実効サイズで比較する。
     */
    fun checkCompatibility(context: Context, uris: List<Uri>): CompatibilityResult {
        if (uris.size < 2) return CompatibilityResult.Compatible

        data class Size(val w: Int, val h: Int) {
            val aspectRatio get() = maxOf(w, h).toFloat() / minOf(w, h)
            override fun toString() = "${w}×${h}"
        }

        val sizes = uris.mapNotNull { uri ->
            val ext = MediaExtractor()
            try {
                ext.setDataSource(context, uri, null)
                for (i in 0 until ext.trackCount) {
                    val fmt = ext.getTrackFormat(i)
                    if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    // KEY_ROTATION は端末によって取れないため MediaMetadataRetriever で確実に取得
                    val rot = MediaMetadataRetriever().use { r ->
                        r.setDataSource(context, uri)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    }
                    return@mapNotNull if (rot == 90 || rot == 270) Size(h, w) else Size(w, h)
                }
                null
            } finally {
                ext.release()
            }
        }

        if (sizes.isEmpty()) return CompatibilityResult.Compatible
        val unique = sizes.distinct()
        if (unique.size == 1) return CompatibilityResult.Compatible

        val desc = unique.joinToString(" / ")
        val firstAr = unique.first().aspectRatio
        val sameAspect = unique.all { kotlin.math.abs(it.aspectRatio - firstAr) / firstAr < 0.05f }

        return if (sameAspect) {
            val target = sizes.minByOrNull { it.w.toLong() * it.h }!!
            CompatibilityResult.CanScale(target.w, target.h, desc)
        } else {
            CompatibilityResult.AspectMismatch(desc)
        }
    }

    fun merge(
        context: Context,
        inputUris: List<Uri>,
        outputFd: FileDescriptor,
        metadataUri: Uri = inputUris.first(),
        onProgress: (Float) -> Unit = {}
    ) {
        require(inputUris.isNotEmpty()) { "結合するファイルがありません" }

        var bufferSize = 4 * 1024 * 1024
        var videoFrameDurationUs = 33_333L
        var audioFrameDurationUs = 23_220L

        MediaExtractor().use { probe ->
            probe.setDataSource(context, metadataUri, null)
            for (i in 0 until probe.trackCount) {
                val fmt = probe.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            val maxSize = fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            if (maxSize > bufferSize) bufferSize = maxSize
                        }
                        if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            val fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                            if (fps > 0) videoFrameDurationUs = 1_000_000L / fps
                        }
                    }
                    mime.startsWith("audio/") -> {
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (sr > 0) audioFrameDurationUs = 1024L * 1_000_000L / sr
                        }
                    }
                }
            }
        }

        // 向きメタデータは音声付きクリップ（カメラ録画）から取得する。
        // タイトルカードが先頭にある場合でも rotation=0 が使われないようにするため。
        val rotationSourceUri = inputUris.firstOrNull { uri ->
            val ext = MediaExtractor()
            try {
                ext.setDataSource(context, uri, null)
                (0 until ext.trackCount).any { i ->
                    ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            } finally {
                ext.release()
            }
        } ?: metadataUri

        val orientationHint = MediaMetadataRetriever().use { r ->
            r.setDataSource(context, rotationSourceUri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }

        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (orientationHint != 0) muxer.setOrientationHint(orientationHint)
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoTimeOffset = 0L
        var audioTimeOffset = 0L

        // addTrack() は muxer.start() より前にすべて呼ばなければならない。
        // 映像フォーマット（SPS/PPS = avcC ボックス）は音声付きクリップ（カメラ録画）のものを優先する。
        // 別変数に分けることで、タイトルカードが先頭にあってもカメラ録画の SPS が使われる。
        var videoFmtCamera: MediaFormat? = null   // 音声付き（カメラ録画）: 優先
        var videoFmtFallback: MediaFormat? = null  // 音声なし（タイトルカード等）: 代替
        var audioFmt: MediaFormat? = null

        for (uri in inputUris) {
            val ext = MediaExtractor()
            try {
                ext.setDataSource(context, uri, null)
                var localVideo: MediaFormat? = null
                var localAudio: MediaFormat? = null
                for (i in 0 until ext.trackCount) {
                    val fmt = ext.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && localVideo == null) localVideo = fmt
                    if (mime.startsWith("audio/") && localAudio == null) localAudio = fmt
                }
                if (localAudio != null) {
                    if (videoFmtCamera == null && localVideo != null) videoFmtCamera = localVideo
                    if (audioFmt == null) audioFmt = localAudio
                } else if (videoFmtFallback == null && localVideo != null) {
                    videoFmtFallback = localVideo
                }
            } finally {
                ext.release()
            }
            if (videoFmtCamera != null && audioFmt != null) break
        }

        val videoFmt = videoFmtCamera ?: videoFmtFallback
        if (videoFmt != null) videoTrackIndex = muxer.addTrack(videoFmt)
        if (audioFmt != null) audioTrackIndex = muxer.addTrack(audioFmt)
        muxer.start()

        // 直前の音声サンプルをキャッシュして、音声なしクリップの後のギャップ埋めに使う
        var lastAudioBuffer: ByteBuffer? = null
        var lastAudioBufferSize = 0

        try {
            inputUris.forEachIndexed { fileIndex, uri ->
                val extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)

                val trackMap = mutableMapOf<Int, Int>()

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoTrackIndex >= 0 -> trackMap[i] = videoTrackIndex
                        mime.startsWith("audio/") && audioTrackIndex >= 0 -> trackMap[i] = audioTrackIndex
                    }
                    extractor.selectTrack(i)
                }

                var maxVideoUs = 0L
                var maxAudioUs = 0L

                while (true) {
                    bufferInfo.offset = 0
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val muxerTrack = trackMap[extractor.sampleTrackIndex]
                    if (muxerTrack == null) {
                        extractor.advance()
                        continue
                    }

                    val pts = extractor.sampleTime
                    val adjustedPts = when (muxerTrack) {
                        videoTrackIndex -> (pts + videoTimeOffset).also {
                            if (pts > maxVideoUs) maxVideoUs = pts
                        }
                        audioTrackIndex -> (pts + audioTimeOffset).also {
                            if (pts > maxAudioUs) maxAudioUs = pts
                            // 後でギャップ埋めに使うため最後の音声フレームをコピーしておく
                            if (lastAudioBuffer == null || lastAudioBuffer!!.capacity() < sampleSize) {
                                lastAudioBuffer = ByteBuffer.allocateDirect(sampleSize)
                            }
                            val tmp = ByteArray(sampleSize)
                            buffer.position(0); buffer.get(tmp, 0, sampleSize); buffer.position(0)
                            lastAudioBuffer!!.clear(); lastAudioBuffer!!.put(tmp)
                            lastAudioBufferSize = sampleSize
                        }
                        else -> pts
                    }

                    bufferInfo.presentationTimeUs = adjustedPts
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    extractor.advance()
                }

                val videoClipDuration = maxVideoUs + videoFrameDurationUs
                videoTimeOffset += videoClipDuration

                if (maxAudioUs > 0) {
                    audioTimeOffset += maxAudioUs + audioFrameDurationUs
                } else {
                    // 音声なしクリップ（タイトルカード等）: 音声タイムラインを映像長で進める。
                    // 既存音声がある場合、クリップ末尾に1フレーム書いて音声トラックを延長する。
                    if (audioTrackIndex >= 0 && lastAudioBuffer != null) {
                        val fillerPts = audioTimeOffset + videoClipDuration - audioFrameDurationUs
                        lastAudioBuffer!!.rewind()
                        muxer.writeSampleData(
                            audioTrackIndex,
                            lastAudioBuffer!!,
                            MediaCodec.BufferInfo().apply {
                                offset = 0
                                size = lastAudioBufferSize
                                presentationTimeUs = fillerPts
                                flags = 0
                            }
                        )
                    }
                    audioTimeOffset += videoClipDuration
                }

                extractor.release()
                onProgress((fileIndex + 1).toFloat() / inputUris.size)
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }
}

private fun MediaExtractor.use(block: (MediaExtractor) -> Unit) {
    try { block(this) } finally { release() }
}
