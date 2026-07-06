package com.example.cliprecorder.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TitleResolution(val label: String, val longSide: Int, val shortSide: Int) {
    HD("HD (720p)",   1280, 720),
    FHD("FHD (1080p)", 1920, 1080),
    UHD("4K (2160p)",  3840, 2160),
}

data class TitleConfig(
    val title: String,
    val subtitle: String = "",
    val durationSec: Int = 3,
    val portrait: Boolean = true,
    val resolution: TitleResolution = TitleResolution.FHD,
    val bgColor: Int = Color.BLACK,
    val textColor: Int = Color.WHITE,
    val textVertical: Boolean = false,
)

object TitleVideoGenerator {

    private const val MIME = "video/avc"
    private const val FPS = 30
    private const val BITRATE = 4_000_000

    /**
     * EGL を使うため呼び出し元は単一スレッドのコルーチンコンテキストで実行すること。
     * suspend ではないので withContext(singleThread) 内で直接呼ぶ。
     */
    fun generate(
        context: Context,
        config: TitleConfig,
        fileNamePrefix: String,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val w = if (config.portrait) config.resolution.shortSide else config.resolution.longSide
        val h = if (config.portrait) config.resolution.longSide  else config.resolution.shortSide

        val bmp = createTitleBitmap(config, w, h)

        // ---- MediaCodec encoder ----
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // カメラ録画（High Profile）と SPS 互換にするため同プロファイルを指定
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        val encoder = MediaCodec.createEncoderByType(MIME)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // ---- EGL setup ----
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) { "eglInitialize failed" }

        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val numCfg = IntArray(1)
        val eglConfigs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrib, 0, eglConfigs, 0, 1, numCfg, 0)
                && numCfg[0] > 0) { "eglChooseConfig failed" }

        val eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfigs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfigs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        // ---- GL texture from bitmap ----
        val texId = IntArray(1)
        GLES20.glGenTextures(1, texId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()

        // ---- GL program ----
        val program = buildProgram()
        GLES20.glUseProgram(program)

        val verts = floatArrayOf(
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f,
        )
        val vbo = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
        GLES20.glViewport(0, 0, w, h)

        // ---- MediaStore 出力先 ----
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${fileNamePrefix}title_$timestamp.mp4"

        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = context.contentResolver
            .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: run {
            cleanupEgl(eglDisplay, eglSurface, eglContext)
            inputSurface.release(); encoder.stop(); encoder.release()
            return false
        }
        val pfd = context.contentResolver.openFileDescriptor(outUri, "rw") ?: run {
            context.contentResolver.delete(outUri, null, null)
            cleanupEgl(eglDisplay, eglSurface, eglContext)
            inputSurface.release(); encoder.stop(); encoder.release()
            return false
        }

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufInfo = MediaCodec.BufferInfo()
        // drain() から変更できるように配列で保持
        val videoTrack = IntArray(1) { -1 }
        val muxerStarted = BooleanArray(1) { false }

        fun drain(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            // EOS 時は 100ms タイムアウト、非 EOS 時は 10ms ですぐ戻る
            val timeoutUs = if (endOfStream) 100_000L else 10_000L
            var eosDone = false
            while (!eosDone) {
                val idx = encoder.dequeueOutputBuffer(bufInfo, timeoutUs)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 非EOS: 次フレーム投入後に再トライ
                        if (!endOfStream) return
                        // EOS: エンコーダが全フレームを吐き出すまでループを続ける
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack[0] = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted[0] = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)
                        val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isEos   = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!isConfig && muxerStarted[0] && buf != null && bufInfo.size > 0)
                            muxer.writeSampleData(videoTrack[0], buf, bufInfo)
                        encoder.releaseOutputBuffer(idx, false)
                        if (isEos) eosDone = true
                    }
                }
            }
        }

        // ---- フレームエンコードループ ----
        val totalFrames = config.durationSec * FPS
        val frameDurUs = 1_000_000L / FPS

        for (frame in 0..totalFrames) {
            vbo.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vbo)
            vbo.position(2)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vbo)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frame * frameDurUs * 1000L)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            drain(endOfStream = false)
            onProgress(frame.toFloat() / totalFrames)
        }

        // すべてのフレームを送信し終えたのでエンコーダを終了させ、残りをすべて書き出す
        drain(endOfStream = true)

        // ---- クリーンアップ ----
        muxer.stop()
        muxer.release()
        pfd.close()
        cleanupEgl(eglDisplay, eglSurface, eglContext)
        inputSurface.release()
        encoder.stop()
        encoder.release()

        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(outUri, cv, null, null)
        return muxerStarted[0]  // データが書けていない場合 false を返す
    }

    private fun cleanupEgl(display: android.opengl.EGLDisplay,
                            surface: android.opengl.EGLSurface,
                            context: android.opengl.EGLContext) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    // ---- タイトル Bitmap 生成 ----
    private fun createTitleBitmap(config: TitleConfig, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(config.bgColor)
        if (config.title.isEmpty() && config.subtitle.isEmpty()) return bmp

        if (config.textVertical) {
            drawVertical(canvas, config, w, h)
        } else {
            drawHorizontal(canvas, config, w, h)
        }
        return bmp
    }

    /** 横書きレイアウト（StaticLayout で自動折り返し） */
    private fun drawHorizontal(canvas: Canvas, config: TitleConfig, w: Int, h: Int) {
        val maxW = (w * 0.85f).toInt()
        val shadow = Color.argb(160, 0, 0, 0)

        val titlePaint = TextPaint().apply {
            color = config.textColor; textSize = h / 9f
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
            setShadowLayer(6f, 2f, 2f, shadow)
        }
        val titleLayout = if (config.title.isNotEmpty())
            StaticLayout.Builder.obtain(config.title, 0, config.title.length, titlePaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build() else null

        val subPaint = TextPaint().apply {
            color = config.textColor; textSize = h / 18f
            typeface = Typeface.DEFAULT; isAntiAlias = true; alpha = 210
            setShadowLayer(4f, 1f, 1f, shadow)
        }
        val subLayout = if (config.subtitle.isNotEmpty())
            StaticLayout.Builder.obtain(config.subtitle, 0, config.subtitle.length, subPaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build() else null

        val gap = h / 24f
        val totalH = (titleLayout?.height?.toFloat() ?: 0f) +
                if (subLayout != null) gap + subLayout.height else 0f
        val startY = (h - totalH) / 2f
        val startX = (w - maxW) / 2f

        titleLayout?.let {
            canvas.save(); canvas.translate(startX, startY); it.draw(canvas); canvas.restore()
        }
        subLayout?.let {
            val sy = startY + (titleLayout?.height?.toFloat() ?: 0f) + gap
            canvas.save(); canvas.translate(startX, sy); it.draw(canvas); canvas.restore()
        }
    }

    /**
     * 縦書きレイアウト。
     * タイトルは1文字ずつ上から下へ並べ、複数列になる場合は右から左へ並べる。
     * サブタイトルは縦列の下（または横書きで中央下）に小さく表示。
     */
    private fun drawVertical(canvas: Canvas, config: TitleConfig, w: Int, h: Int) {
        val shadow = Color.argb(160, 0, 0, 0)
        val fontSize = minOf(w, h) / 9f
        val titlePaint = TextPaint().apply {
            color = config.textColor; textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
            setShadowLayer(6f, 2f, 2f, shadow)
        }
        val fm = titlePaint.fontMetrics
        val charH = (fm.descent - fm.ascent) * 1.1f
        val charW = fontSize * 1.05f  // 全角文字の目安
        val colGap = fontSize * 0.15f

        val title = config.title
        if (title.isEmpty()) return

        val maxCharsPerCol = maxOf(1, ((h * 0.75f) / charH).toInt())
        // 文字を列に分割（最初の列=一番右）
        val cols = mutableListOf<String>()
        var rem = title
        while (rem.isNotEmpty()) {
            cols.add(rem.take(maxCharsPerCol))
            rem = rem.drop(maxCharsPerCol)
        }

        val numCols = cols.size
        val totalColW = numCols * charW + (numCols - 1) * colGap
        // 列全体の中心を画面中央に合わせる（右→左の順）
        val colsStartX = (w + totalColW) / 2f - charW / 2f  // 一番右の列の中心 X

        for ((colIdx, col) in cols.withIndex()) {
            val colCenterX = colsStartX - colIdx * (charW + colGap)
            val colTotalH = col.length * charH
            var charY = (h - colTotalH) / 2f - fm.ascent

            for (ch in col) {
                val cStr = ch.toString()
                val cW = titlePaint.measureText(cStr)
                canvas.drawText(cStr, colCenterX - cW / 2f, charY, titlePaint)
                charY += charH
            }
        }

        // サブタイトルは縦列の真下に横書きで小さく
        if (config.subtitle.isNotEmpty()) {
            val subPaint = TextPaint().apply {
                color = config.textColor; textSize = fontSize / 2f
                typeface = Typeface.DEFAULT; isAntiAlias = true; alpha = 200
                setShadowLayer(3f, 1f, 1f, shadow)
            }
            val maxSubW = (totalColW * 1.5f).toInt().coerceAtMost((w * 0.85f).toInt())
            val subLayout = StaticLayout.Builder
                .obtain(config.subtitle, 0, config.subtitle.length, subPaint, maxSubW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()

            val lastCol = cols.last()
            val colsEndY = (h + lastCol.length * charH) / 2f + h / 20f
            val subX = (w - maxSubW) / 2f
            if (colsEndY + subLayout.height < h * 0.95f) {
                canvas.save()
                canvas.translate(subX, colsEndY)
                subLayout.draw(canvas)
                canvas.restore()
            }
        }
    }

    // ---- GL シェーダー ----
    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex, vec2(vTex.x, 1.0 - vTex.y)); }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src); GLES20.glCompileShader(id); return id
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        return prog
    }
}
