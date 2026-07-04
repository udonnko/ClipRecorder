package com.example.cliprecorder.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import android.media.MediaCodec
import com.example.cliprecorder.settings.AspectRatio
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object TimelapseRecorder {

    suspend fun record(
        context: Context,
        imageCapture: ImageCapture,
        executor: Executor,
        intervalMs: Long,
        durationSec: Int,
        outputName: String,
        targetRotation: Int,
        aspectRatio: AspectRatio,
        onProgress: (captured: Int, total: Int) -> Unit,
        onSaved: (frames: Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val totalFrames = ((durationSec * 1000L) / intervalMs).toInt().coerceAtLeast(1)
        val tempFile = File(context.cacheDir, "tl_${System.currentTimeMillis()}.mp4")

        val bitmaps = mutableListOf<Bitmap>()
        try {
            for (i in 0 until totalFrames) {
                if (i > 0) delay(intervalMs)
                val bmp = captureFrame(imageCapture, executor, targetRotation, aspectRatio) ?: continue
                bitmaps.add(bmp)
                onProgress(bitmaps.size, totalFrames)
            }
        } finally {
            // キャンセル（途中停止）時も含めて保存する
            withContext(kotlinx.coroutines.NonCancellable) {
                if (bitmaps.isEmpty()) return@withContext
                val w = bitmaps[0].width.roundEven()
                val h = bitmaps[0].height.roundEven()
                encodeBitmaps(bitmaps, tempFile, w, h)
                val savedCount = bitmaps.size
                bitmaps.forEach { it.recycle() }

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
                    ?: return@withContext
                resolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                tempFile.delete()
                onSaved(savedCount)
            }
        }
    }

    private suspend fun captureFrame(
        imageCapture: ImageCapture,
        executor: Executor,
        targetRotation: Int,
        aspectRatio: AspectRatio,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                image.close()

                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (raw == null) { cont.resume(null); return }

                // targetRotation とビットマップの実サイズで回転量を決定する。
                // EXIF/rotationDegrees は機種によって信頼できないため使用しない。
                val needPortrait = targetRotation == Surface.ROTATION_0 || targetRotation == Surface.ROTATION_180
                val rotateDeg = when {
                    needPortrait && raw.width > raw.height -> 90
                    !needPortrait && raw.height > raw.width -> 90
                    else -> 0
                }
                val oriented = if (rotateDeg == 0) {
                    raw
                } else {
                    val m = android.graphics.Matrix().apply { postRotate(rotateDeg.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
                }
                cont.resume(cropToAspectRatio(oriented, aspectRatio, needPortrait))
            }
            override fun onError(e: ImageCaptureException) { cont.resume(null) }
        })
    }

    private fun cropToAspectRatio(bitmap: Bitmap, aspectRatio: AspectRatio, needPortrait: Boolean): Bitmap {
        val (rW, rH) = when (aspectRatio) {
            AspectRatio.RATIO_16_9 -> if (needPortrait) 9 to 16 else 16 to 9
            AspectRatio.RATIO_4_3  -> if (needPortrait) 3 to 4 else 4 to 3
            AspectRatio.RATIO_1_1  -> 1 to 1
        }
        val targetRatio = rW.toFloat() / rH
        val srcRatio = bitmap.width.toFloat() / bitmap.height
        if (kotlin.math.abs(srcRatio - targetRatio) < 0.005f) return bitmap

        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            cropH = bitmap.height
            cropW = (bitmap.height * targetRatio).toInt().roundEven()
        } else {
            cropW = bitmap.width
            cropH = (bitmap.width / targetRatio).toInt().roundEven()
        }
        val x = (bitmap.width - cropW) / 2
        val y = (bitmap.height - cropH) / 2
        val cropped = Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
        bitmap.recycle()
        return cropped
    }

    private fun encodeBitmaps(bitmaps: List<Bitmap>, output: File, width: Int, height: Int) {
        val fps = 30
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val frameUs = 1_000_000L / fps

        fun drain(eos: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx) ?: run {
                            codec.releaseOutputBuffer(idx, false); return@drain
                        }
                        if (muxerStarted && info.size > 0) muxer.writeSampleData(trackIndex, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!eos) return
                }
            }
        }

        bitmaps.forEachIndexed { i, bmp ->
            val canvas = surface.lockCanvas(null)
            val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled !== bmp) scaled.recycle()
            surface.unlockCanvasAndPost(canvas)
            // presentationTimeUs を手動で設定するため dummy buffer は使わない
            drain(false)
        }

        codec.signalEndOfInputStream()
        drain(true)

        muxer.stop()
        muxer.release()
        codec.stop()
        codec.release()
        surface.release()
    }

    private fun Int.roundEven() = if (this % 2 == 0) this else this - 1
}
