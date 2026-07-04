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

object SlowMotionProcessor {

    /** 動画のタイムスタンプを 2 倍にして 0.5 倍速動画として MediaStore に保存する（再エンコードなし）*/
    suspend fun process(
        context: Context,
        inputUri: Uri,
        outputName: String,
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "slow_${System.currentTimeMillis()}.mp4")

        val extractor = MediaExtractor()
        extractor.setDataSource(context, inputUri, null)

        val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 全トラックを追加
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                trackMap[i] = muxer.addTrack(format)
            }
        }
        muxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()

        // 映像トラックは PTS を 2 倍、音声はスキップ（無音）
        trackMap.forEach { (srcTrack, muxTrack) ->
            extractor.selectTrack(srcTrack)
        }

        // 映像のみ 2 倍 PTS で書き出し
        val videoTrackSrc = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val videoTrackMux = videoTrackSrc?.let { trackMap[it] }

        if (videoTrackSrc != null && videoTrackMux != null) {
            extractor.unselectAllTracks()
            extractor.selectTrack(videoTrackSrc)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime * 2  // 0.5倍速
                info.flags = if (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(videoTrackMux, buffer, info)
                extractor.advance()
            }
        }

        muxer.stop()
        muxer.release()
        extractor.release()

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
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, values, null, null)
            }
        }
        tempFile.delete()
    }

    private fun MediaExtractor.unselectAllTracks() {
        for (i in 0 until trackCount) {
            try { unselectTrack(i) } catch (_: Exception) {}
        }
    }
}
