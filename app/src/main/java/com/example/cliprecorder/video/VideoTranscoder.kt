package com.example.cliprecorder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.*
import android.media.MediaCodecInfo.CodecCapabilities
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

enum class WatermarkCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * 動画を指定解像度に再エンコードする。
 * watermarkText を指定すると GL で文字をフレームに焼き込む。
 * 音声はそのままパススルー。
 */
object VideoTranscoder {

    private const val TIMEOUT_US = 10_000L

    fun transcode(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        targetWidth: Int,
        targetHeight: Int,
        watermarkText: String? = null,
        watermarkCorner: WatermarkCorner? = null,
        cropToSquare: Boolean = false,
        rotation: Int = 0,
        rotateContent90CCW: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, inputUri, null)

        var videoTrackIdx = -1
        var audioTrackIdx = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrackIdx < 0) videoTrackIdx = i
            else if (mime.startsWith("audio/") && audioTrackIdx < 0) audioTrackIdx = i
        }
        check(videoTrackIdx >= 0) { "No video track" }

        val inputFmt = extractor.getTrackFormat(videoTrackIdx)
        val inputMime = inputFmt.getString(MediaFormat.KEY_MIME)!!
        val duration = runCatching { inputFmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val inputW = runCatching { inputFmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(targetWidth)
        val inputH = runCatching { inputFmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(targetHeight)

        // 回転メタデータを考慮して表示解像度を求める
        // (MediaFormat KEY_WIDTH/HEIGHT は常に保存時の解像度、rotation=90/270 なら縦横が逆)
        val cropW = if (cropToSquare && (rotation == 90 || rotation == 270)) inputH else inputW
        val cropH = if (cropToSquare && (rotation == 90 || rotation == 270)) inputW else inputH

        // 正方形クロップ: UV はそのまま、頂点ポジションをオーバーサイズにして GL クリップで中央だけ残す
        val posScaleX: Float; val posScaleY: Float
        if (cropToSquare && cropW != cropH) {
            if (cropW > cropH) { posScaleX = cropW.toFloat() / cropH; posScaleY = 1f }
            else               { posScaleX = 1f; posScaleY = cropH.toFloat() / cropW }
        } else {
            posScaleX = 1f; posScaleY = 1f
        }

        // エンコーダ設定
        val bitrate = (targetWidth * targetHeight * 30 * 0.1f).toInt().coerceIn(500_000, 8_000_000)
        val outFmt = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        // EGL セットアップ
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)
        val eglConfig = chooseConfig(eglDisplay)
        val eglCtx = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)

        val texId = createOESTexture()

        // 透かし GL セットアップ
        val hasWatermark = watermarkText != null && watermarkCorner != null
        var wmTexId = 0
        val wmRect = FloatArray(4)
        val glProg = if (hasWatermark) {
            val textSizePx = (minOf(targetWidth, targetHeight) * 0.065f).coerceAtLeast(30f)
            val wmBmp = createWatermarkBitmap(watermarkText!!, textSizePx)
            wmTexId = loadTexture2D(wmBmp)
            val wmW = wmBmp.width.toFloat() / targetWidth
            val wmH = wmBmp.height.toFloat() / targetHeight
            val padX = 24f / targetWidth
            val padY = 24f / targetHeight
            when (watermarkCorner!!) {
                WatermarkCorner.TOP_LEFT     -> { wmRect[0] = padX;                wmRect[1] = 1f - wmH - padY; wmRect[2] = wmW; wmRect[3] = wmH }
                WatermarkCorner.TOP_RIGHT    -> { wmRect[0] = 1f - wmW - padX;    wmRect[1] = 1f - wmH - padY; wmRect[2] = wmW; wmRect[3] = wmH }
                WatermarkCorner.BOTTOM_LEFT  -> { wmRect[0] = padX;                wmRect[1] = padY;            wmRect[2] = wmW; wmRect[3] = wmH }
                WatermarkCorner.BOTTOM_RIGHT -> { wmRect[0] = 1f - wmW - padX;    wmRect[1] = padY;            wmRect[2] = wmW; wmRect[3] = wmH }
            }
            wmBmp.recycle()
            createGLProgramWithWatermark()
        } else {
            createGLProgram()
        }

        // SurfaceTexture（デコーダ出力）
        val stThread = HandlerThread("st-thread").also { it.start() }
        val st = SurfaceTexture(texId).also { it.setDefaultBufferSize(cropW, cropH) }
        val frameLock = Object()
        val frameReady = AtomicBoolean(false)
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameReady.set(true); frameLock.notifyAll() }
        }, Handler(stThread.looper))
        val decoderSurface = Surface(st)

        // デコーダ設定
        val decoder = MediaCodec.createDecoderByType(inputMime)
        decoder.configure(inputFmt, decoderSurface, null, 0)
        decoder.start()

        // Muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxVideo = -1
        var muxAudio = -1
        var muxStarted = false

        extractor.selectTrack(videoTrackIdx)
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false

        while (!encoderDone) {
            // デコーダへ投入
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(idx, 0, n, pts, 0)
                        extractor.advance()
                        if (duration > 0) onProgress((pts.toFloat() / duration).coerceIn(0f, 0.9f))
                    }
                }
            }

            // デコーダ出力 → OpenGL でスケール → エンコーダへ
            if (!decoderDone) {
                val idx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (idx >= 0) {
                    val pts = bufInfo.presentationTimeUs
                    val render = bufInfo.size > 0
                    decoder.releaseOutputBuffer(idx, render)
                    if (render) {
                        synchronized(frameLock) {
                            val deadline = System.currentTimeMillis() + 500
                            while (!frameReady.get() && System.currentTimeMillis() < deadline) frameLock.wait(50)
                            frameReady.set(false)
                        }
                        st.updateTexImage()
                        val stMat = FloatArray(16).also { st.getTransformMatrix(it) }
                        GLES20.glViewport(0, 0, targetWidth, targetHeight)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        if (hasWatermark) drawFrameWithWatermark(glProg, texId, stMat, wmTexId, wmRect, posScaleX, posScaleY)
                        else if (rotateContent90CCW) drawFrame90CCW(glProg, texId, stMat)
                        else drawFrame(glProg, texId, stMat, posScaleX, posScaleY)
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts * 1000L)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    }
                }
            }

            // エンコーダ出力 → Muxer
            val encIdx = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
            when {
                encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxVideo = muxer.addTrack(encoder.outputFormat)
                    if (audioTrackIdx >= 0) muxAudio = muxer.addTrack(extractor.getTrackFormat(audioTrackIdx))
                    muxer.start(); muxStarted = true
                }
                encIdx >= 0 -> {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && bufInfo.size > 0 && muxStarted
                    ) {
                        muxer.writeSampleData(muxVideo, encoder.getOutputBuffer(encIdx)!!, bufInfo)
                    }
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        }

        // 音声パススルー
        if (audioTrackIdx >= 0 && muxStarted && muxAudio >= 0) {
            extractor.selectTrack(audioTrackIdx)
            val audioBuf = ByteBuffer.allocate(512 * 1024)
            val audioInfo = MediaCodec.BufferInfo()
            while (true) {
                val n = extractor.readSampleData(audioBuf, 0)
                if (n < 0) break
                audioInfo.set(0, n, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxAudio, audioBuf, audioInfo)
                extractor.advance()
            }
        }

        // 後処理
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        if (muxStarted) muxer.stop()
        muxer.release()
        decoderSurface.release()
        st.release()
        stThread.quit()
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        if (wmTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(wmTexId), 0)
        GLES20.glDeleteProgram(glProg)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglCtx)
        EGL14.eglTerminate(eglDisplay)
        extractor.release()
        onProgress(1f)
    }

    // ---- EGL / GL ヘルパー ----

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, IntArray(1), 0)
        return configs[0]!!
    }

    private fun createOESTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun createGLProgram(): Int {
        val vs = """
            attribute vec4 aPos; attribute vec2 aTex;
            uniform mat4 uST; varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uST * vec4(aTex, 0.0, 1.0)).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    // ---- 透かし用 GL ヘルパー ----

    private fun createWatermarkBitmap(text: String, textSizePx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(textSizePx * 0.15f, textSizePx * 0.06f, textSizePx * 0.06f, Color.BLACK)
        }
        val fm = paint.fontMetrics
        val padX = textSizePx * 0.35f
        val padY = textSizePx * 0.25f
        val bmpW = (paint.measureText(text) + padX * 2).toInt().coerceAtLeast(1)
        val bmpH = (fm.bottom - fm.top + padY * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, padX, -fm.top + padY, paint)
        return bmp
    }

    private fun loadTexture2D(bitmap: Bitmap): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return tex[0]
    }

    private fun createGLProgramWithWatermark(): Int {
        val vs = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uST;
            varying vec2 vTex;
            varying vec2 vPos;
            void main() {
                gl_Position = aPos;
                vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
                vPos = aPos.xy;
            }
        """.trimIndent()
        // vPos は (-1..1, -1..1)。normPos = (0..1, 0..1) に変換後、透かし矩形と比較
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            varying vec2 vPos;
            uniform samplerExternalOES sTex;
            uniform sampler2D uWmTex;
            uniform vec4 uWmRect;
            void main() {
                vec4 video = texture2D(sTex, vTex);
                vec2 normPos = (vPos + 1.0) * 0.5;
                vec2 wmUV = (normPos - uWmRect.xy) / uWmRect.zw;
                if (wmUV.x >= 0.0 && wmUV.x <= 1.0 && wmUV.y >= 0.0 && wmUV.y <= 1.0) {
                    vec4 wm = texture2D(uWmTex, vec2(wmUV.x, 1.0 - wmUV.y));
                    gl_FragColor = mix(video, vec4(wm.rgb, 1.0), wm.a * 0.85);
                } else {
                    gl_FragColor = video;
                }
            }
        """.trimIndent()
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    private fun drawFrameWithWatermark(
        program: Int, texId: Int, stMat: FloatArray,
        wmTexId: Int, wmRect: FloatArray,
        posScaleX: Float = 1f, posScaleY: Float = 1f,
    ) {
        val verts = floatArrayOf(
            -posScaleX, -posScaleY, 0f, 0f,   posScaleX, -posScaleY, 1f, 0f,
            -posScaleX,  posScaleY, 0f, 1f,   posScaleX,  posScaleY, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(verts); it.position(0) }
        GLES20.glUseProgram(program)
        // テクスチャユニット 0: 動画 (OES)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        // テクスチャユニット 1: 透かし (2D)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uWmTex"), 1)
        // ユニフォーム
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, stMat, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uWmRect"), 1, wmRect, 0)
        // 頂点属性
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(posLoc)
        buf.position(2); GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // ユニット 0 に戻す
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawFrame(
        program: Int, texId: Int, stMat: FloatArray,
        posScaleX: Float = 1f, posScaleY: Float = 1f,
    ) {
        val verts = floatArrayOf(
            -posScaleX, -posScaleY, 0f, 0f,   posScaleX, -posScaleY, 1f, 0f,
            -posScaleX,  posScaleY, 0f, 1f,   posScaleX,  posScaleY, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(verts); it.position(0) }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        val matLoc = GLES20.glGetUniformLocation(program, "uST")
        GLES20.glUniformMatrix4fv(matLoc, 1, false, stMat, 0)
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(posLoc)
        buf.position(2); GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * ソースを 90° CCW 回転してビューポートに描画する。
     * カメラが rotation=90 メタデータ付きの 1920x1080 エンコードで縦動画を記録する場合、
     * タイトルカード（1080x1920 自然縦）をこの関数で 1920x1080 ランドスケープ領域に描画し、
     * プレイヤーが rotation=90 CW を適用すると元の縦向きに戻る。
     *
     * UV 導出:
     *   90° CCW 回転後の各コーナーがソースのどの位置を参照すべきか:
     *   BL position → source TL = UV(0,1)  → stMat(Y-flip) → GL(0,0)  = image top-left
     *   BR position → source BL = UV(0,0)  → stMat         → GL(0,1)  = image bottom-left
     *   TL position → source TR = UV(1,1)  → stMat         → GL(1,0)  = image top-right
     *   TR position → source BR = UV(1,0)  → stMat         → GL(1,1)  = image bottom-right
     */
    private fun drawFrame90CCW(program: Int, texId: Int, stMat: FloatArray) {
        // format: posX, posY, uvX, uvY  (BL, BR, TL, TR order for GL_TRIANGLE_STRIP)
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 0f,
            -1f,  1f, 1f, 1f,
             1f,  1f, 1f, 0f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(verts); it.position(0) }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        val matLoc = GLES20.glGetUniformLocation(program, "uST")
        GLES20.glUniformMatrix4fv(matLoc, 1, false, stMat, 0)
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(posLoc)
        buf.position(2); GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
