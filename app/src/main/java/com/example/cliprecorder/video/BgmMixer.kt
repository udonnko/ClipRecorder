package com.example.cliprecorder.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object BgmMixer {

    /**
     * videoUri の映像トラック + bgmUri の音声トラックを合成して MediaStore に保存する。
     * videoUri に既存の音声がある場合はそれを破棄し、BGM のみを付ける。
     */
    suspend fun mix(
        context: Context,
        videoUri: Uri,
        bgmUri: Uri,
        outputName: String,
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "bgm_${System.currentTimeMillis()}.mp4")

        // ─── 映像 extractor ─────────────────────────────────────
        val videoEx = MediaExtractor()
        videoEx.setDataSource(context, videoUri, null)
        val videoTrackIdx = (0 until videoEx.trackCount).firstOrNull { i ->
            videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { videoEx.release(); return@withContext }

        val videoFormat = videoEx.getTrackFormat(videoTrackIdx)
        val videoDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)

        // ─── BGM extractor ──────────────────────────────────────
        val bgmEx = MediaExtractor()
        bgmEx.setDataSource(context, bgmUri, null)
        val bgmTrackIdx = (0 until bgmEx.trackCount).firstOrNull { i ->
            bgmEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { videoEx.release(); bgmEx.release(); return@withContext }

        val bgmFormat = bgmEx.getTrackFormat(bgmTrackIdx)

        // ─── muxer ──────────────────────────────────────────────
        val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxVideoTrack = muxer.addTrack(videoFormat)
        val muxAudioTrack = muxer.addTrack(bgmFormat)
        muxer.start()

        val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()

        // 映像を書き出す
        videoEx.selectTrack(videoTrackIdx)
        while (true) {
            val size = videoEx.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0; info.size = size
            info.presentationTimeUs = videoEx.sampleTime
            info.flags = if (videoEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(muxVideoTrack, buffer, info)
            videoEx.advance()
        }

        // BGM を動画の長さぶんだけ書き出す（ループなし・切り捨て）
        bgmEx.selectTrack(bgmTrackIdx)
        while (true) {
            val size = bgmEx.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = bgmEx.sampleTime
            if (pts > videoDurationUs) break
            info.offset = 0; info.size = size
            info.presentationTimeUs = pts
            info.flags = if (bgmEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(muxAudioTrack, buffer, info)
            bgmEx.advance()
        }

        muxer.stop(); muxer.release()
        videoEx.release(); bgmEx.release()

        // MediaStore に保存
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext
        resolver.openOutputStream(uri)?.use { out ->
            tempFile.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        tempFile.delete()
    }
}
