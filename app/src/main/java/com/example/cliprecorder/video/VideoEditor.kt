package com.example.cliprecorder.video

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.media.MediaCodecInfo.CodecCapabilities
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

object VideoEditor {

    private const val TIMEOUT_US = 10_000L

    fun apply(
        context: Context,
        inputUri: Uri,
        outputName: String,
        effects: List<VideoEffect>,
        onProgress: (Float) -> Unit = {},
    ) {
        val tempFile = File(context.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
        try {
            encode(context, inputUri, tempFile, effects, onProgress)
            saveToMediaStore(context, tempFile, outputName)
        } finally {
            tempFile.delete()
        }
    }

    private fun encode(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        effects: List<VideoEffect>,
        onProgress: (Float) -> Unit,
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

        val inputFmt  = extractor.getTrackFormat(videoTrackIdx)
        val inputMime = inputFmt.getString(MediaFormat.KEY_MIME)!!
        val duration  = runCatching { inputFmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val w = runCatching { inputFmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920)
        val h = runCatching { inputFmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080)

        val rotation = MediaMetadataRetriever().use { r ->
            r.setDataSource(context, inputUri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }
        val encW = if (rotation == 90 || rotation == 270) h else w
        val encH = if (rotation == 90 || rotation == 270) w else h
        val bitrate = (encW * encH * 30 * 0.1f).toInt().coerceIn(2_000_000, 8_000_000)

        val outFmt = MediaFormat.createVideoFormat("video/avc", encW, encH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(outFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        // EGL
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)
        val eglConfig = chooseConfig(eglDisplay)
        val eglCtx = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)

        val texId  = createOESTexture()
        val glProg = buildProgram(effects)

        val stThread = HandlerThread("ve-st").also { it.start() }
        val st = SurfaceTexture(texId).also { it.setDefaultBufferSize(w, h) }
        val frameLock = Object()
        val frameReady = AtomicBoolean(false)
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameReady.set(true); frameLock.notifyAll() }
        }, Handler(stThread.looper))
        val decoderSurface = Surface(st)

        val decoder = MediaCodec.createDecoderByType(inputMime)
        decoder.configure(inputFmt, decoderSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxVideo   = -1
        var muxAudio   = -1
        var muxStarted = false

        extractor.selectTrack(videoTrackIdx)
        val bufInfo     = MediaCodec.BufferInfo()
        var inputDone   = false
        var decoderDone = false
        var encoderDone = false

        // エフェクト値を取り出す
        val fadeOutDur   = (effects.filterIsInstance<VideoEffect.FadeOut>().firstOrNull()?.durationMs ?: 0L) * 1000L
        val fadeInDur    = (effects.filterIsInstance<VideoEffect.FadeIn>().firstOrNull()?.durationMs ?: 0L) * 1000L
        val kenBurns     = effects.filterIsInstance<VideoEffect.KenBurns>().firstOrNull()
        val hasCinematic = effects.any { it is VideoEffect.Cinematic }
        val vigStrength  = effects.filterIsInstance<VideoEffect.Vignette>().firstOrNull()?.strength ?: 0f
        val brightness   = effects.filterIsInstance<VideoEffect.Brightness>().firstOrNull()?.value ?: 0f
        val contrast     = effects.filterIsInstance<VideoEffect.Contrast>().firstOrNull()?.value ?: 1f
        val saturation   = effects.filterIsInstance<VideoEffect.Saturation>().firstOrNull()?.value ?: 1f
        val grainStr     = effects.filterIsInstance<VideoEffect.FilmGrain>().firstOrNull()?.strength ?: 0f
        val caStr        = effects.filterIsInstance<VideoEffect.ChromaticAberration>().firstOrNull()?.strength ?: 0f

        // uniform ロケーション
        GLES20.glUseProgram(glProg)
        fun loc(name: String) = GLES20.glGetUniformLocation(glProg, name)
        val uFadeOut     = loc("uFadeOut")
        val uFadeIn      = loc("uFadeIn")
        val uVignette    = loc("uVignette")
        val uLetterbox   = loc("uLetterbox")
        val uKbZoom      = loc("uKbZoom")
        val uKbOffset    = loc("uKbOffset")
        val uProgress    = loc("uProgress")
        val uBrightness  = loc("uBrightness")
        val uContrast    = loc("uContrast")
        val uSaturation  = loc("uSaturation")
        val uGrainStr    = loc("uGrainStrength")
        val uCaStr       = loc("uCaStrength")

        // 固定 uniform を設定（フレームごとに変わらない値）
        if (uVignette   >= 0) GLES20.glUniform1f(uVignette,   vigStrength)
        if (uLetterbox  >= 0) GLES20.glUniform1f(uLetterbox,  if (hasCinematic) 0.1f else 0f)
        if (uBrightness >= 0) GLES20.glUniform1f(uBrightness, brightness)
        if (uContrast   >= 0) GLES20.glUniform1f(uContrast,   contrast)
        if (uSaturation >= 0) GLES20.glUniform1f(uSaturation, saturation)
        if (uGrainStr   >= 0) GLES20.glUniform1f(uGrainStr,   grainStr)
        if (uCaStr      >= 0) GLES20.glUniform1f(uCaStr,      caStr)

        while (!encoderDone) {
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val n   = extractor.readSampleData(buf, 0)
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

            if (!decoderDone) {
                val idx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (idx >= 0) {
                    val pts    = bufInfo.presentationTimeUs
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

                        val progress = if (duration > 0) pts.toFloat() / duration else 0f

                        val fadeOut = if (fadeOutDur > 0 && duration > 0) {
                            val start = duration - fadeOutDur
                            if (pts >= start) ((duration - pts).toFloat() / fadeOutDur).coerceIn(0f, 1f)
                            else 1f
                        } else 1f

                        val fadeIn = if (fadeInDur > 0) {
                            if (pts < fadeInDur) (pts.toFloat() / fadeInDur).coerceIn(0f, 1f)
                            else 1f
                        } else 1f

                        val kbZoom = if (kenBurns != null) 1f + (kenBurns.zoomTo - 1f) * progress else 1f
                        val kbOff  = (1f - 1f / kbZoom) / 2f

                        GLES20.glUseProgram(glProg)
                        if (uFadeOut  >= 0) GLES20.glUniform1f(uFadeOut,  fadeOut)
                        if (uFadeIn   >= 0) GLES20.glUniform1f(uFadeIn,   fadeIn)
                        if (uKbZoom   >= 0) GLES20.glUniform1f(uKbZoom,   kbZoom)
                        if (uKbOffset >= 0) GLES20.glUniform1f(uKbOffset, kbOff)
                        if (uProgress >= 0) GLES20.glUniform1f(uProgress, progress)

                        GLES20.glViewport(0, 0, encW, encH)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        drawFrame(glProg, texId, stMat)
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts * 1000L)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    }
                }
            }

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
                    ) muxer.writeSampleData(muxVideo, encoder.getOutputBuffer(encIdx)!!, bufInfo)
                    encoder.releaseOutputBuffer(encIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        }

        // 音声パススルー
        if (audioTrackIdx >= 0 && muxStarted && muxAudio >= 0) {
            extractor.selectTrack(audioTrackIdx)
            val audioBuf  = ByteBuffer.allocate(512 * 1024)
            val audioInfo = MediaCodec.BufferInfo()
            while (true) {
                val n = extractor.readSampleData(audioBuf, 0)
                if (n < 0) break
                audioInfo.set(0, n, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxAudio, audioBuf, audioInfo)
                extractor.advance()
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        if (muxStarted) { muxer.stop() }
        muxer.release()
        decoderSurface.release()
        st.release()
        stThread.quit()
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        GLES20.glDeleteProgram(glProg)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglCtx)
        EGL14.eglTerminate(eglDisplay)
        extractor.release()
        onProgress(1f)
    }

    private fun saveToMediaStore(context: Context, tempFile: File, outputName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipRecorder")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    // ---- シェーダー生成 ----

    private fun buildProgram(effects: List<VideoEffect>): Int {
        val hasGrayscale  = effects.any { it is VideoEffect.Grayscale }
        val hasSepia      = effects.any { it is VideoEffect.Sepia }
        val hasVignette   = effects.any { it is VideoEffect.Vignette }
        val hasFadeOut    = effects.any { it is VideoEffect.FadeOut }
        val hasFadeIn     = effects.any { it is VideoEffect.FadeIn }
        val hasCinematic  = effects.any { it is VideoEffect.Cinematic }
        val hasKenBurns   = effects.any { it is VideoEffect.KenBurns }
        val hasBrightness = effects.any { it is VideoEffect.Brightness }
        val hasContrast   = effects.any { it is VideoEffect.Contrast }
        val hasSaturation = effects.any { it is VideoEffect.Saturation }
        val hasWarm       = effects.any { it is VideoEffect.Warm }
        val hasCool       = effects.any { it is VideoEffect.Cool }
        val hasVivid      = effects.any { it is VideoEffect.Vivid }
        val hasMatte      = effects.any { it is VideoEffect.Matte }
        val hasGrain      = effects.any { it is VideoEffect.FilmGrain }
        val hasCa         = effects.any { it is VideoEffect.ChromaticAberration }
        val hasColorAdj   = hasBrightness || hasContrast || hasWarm || hasCool || hasVivid || hasMatte || hasSaturation

        val vs = """
            attribute vec4 aPos; attribute vec2 aTex;
            uniform mat4 uST; varying vec2 vTex; varying vec2 vPos;
            uniform float uKbZoom;
            uniform float uKbOffset;
            void main() {
                gl_Position = aPos;
                vec2 t = (uST * vec4(aTex, 0.0, 1.0)).xy;
                t = t / uKbZoom + uKbOffset;
                vTex = t;
                vPos = aTex;
            }
        """.trimIndent()

        val fs = buildString {
            appendLine("#extension GL_OES_EGL_image_external : require")
            appendLine("precision mediump float;")
            appendLine("varying vec2 vTex; varying vec2 vPos;")
            appendLine("uniform samplerExternalOES sTex;")
            if (hasFadeOut)    appendLine("uniform float uFadeOut;")
            if (hasFadeIn)     appendLine("uniform float uFadeIn;")
            if (hasVignette)   appendLine("uniform float uVignette;")
            if (hasCinematic)  appendLine("uniform float uLetterbox;")
            if (hasBrightness) appendLine("uniform float uBrightness;")
            if (hasContrast)   appendLine("uniform float uContrast;")
            if (hasSaturation) appendLine("uniform float uSaturation;")
            if (hasCa)         appendLine("uniform float uCaStrength;")
            if (hasGrain) {
                appendLine("uniform float uGrainStrength;")
                appendLine("uniform float uProgress;")
                appendLine("float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }")
            }

            appendLine("void main() {")

            // シネマスコープ：上下 10% を黒帯
            if (hasCinematic) {
                appendLine("  if (vPos.y < uLetterbox || vPos.y > 1.0 - uLetterbox) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }")
            }

            // テクスチャサンプリング（CA があれば RGB を別 UV でサンプル）
            if (hasCa) {
                appendLine("  vec2 caDir = (vPos - 0.5) * uCaStrength;")
                appendLine("  float r_ca = texture2D(sTex, vTex + caDir).r;")
                appendLine("  vec4 c     = texture2D(sTex, vTex);")
                appendLine("  float b_ca = texture2D(sTex, vTex - caDir).b;")
                appendLine("  c.r = r_ca; c.b = b_ca;")
            } else {
                appendLine("  vec4 c = texture2D(sTex, vTex);")
            }

            // カラープリセット（排他：最後に宣言されたものが有効）
            if (hasWarm) {
                appendLine("  c.r = min(c.r * 1.15, 1.0);")
                appendLine("  c.g = min(c.g * 1.05, 1.0);")
                appendLine("  c.b = c.b * 0.85;")
            }
            if (hasCool) {
                appendLine("  c.b = min(c.b * 1.15, 1.0);")
                appendLine("  c.g = min(c.g * 0.97, 1.0);")
                appendLine("  c.r = c.r * 0.85;")
            }
            if (hasVivid) {
                appendLine("  float vividLum = dot(c.rgb, vec3(0.299, 0.587, 0.114));")
                appendLine("  c.rgb = mix(vec3(vividLum), c.rgb, 1.5);")
                appendLine("  c.rgb = (c.rgb - 0.5) * 1.2 + 0.5;")
            }
            if (hasMatte) {
                appendLine("  c.rgb = c.rgb * 0.85 + 0.08;")
            }

            // 彩度調整
            if (hasSaturation) {
                appendLine("  float satLum = dot(c.rgb, vec3(0.299, 0.587, 0.114));")
                appendLine("  c.rgb = mix(vec3(satLum), c.rgb, uSaturation);")
            }

            // 明るさ・コントラスト
            if (hasBrightness) appendLine("  c.rgb = c.rgb + uBrightness;")
            if (hasContrast)   appendLine("  c.rgb = (c.rgb - 0.5) * uContrast + 0.5;")

            // 色調整後クランプ
            if (hasColorAdj) appendLine("  c.rgb = clamp(c.rgb, 0.0, 1.0);")

            // カラーフィルター（Sepia / Grayscale は排他）
            when {
                hasSepia     -> {
                    appendLine("  float gray = dot(c.rgb, vec3(0.299, 0.587, 0.114));")
                    appendLine("  c.rgb = vec3(gray * 1.08 + 0.07, gray * 0.74, gray * 0.43);")
                }
                hasGrayscale -> {
                    appendLine("  float gray = dot(c.rgb, vec3(0.299, 0.587, 0.114));")
                    appendLine("  c.rgb = vec3(gray);")
                }
            }

            // ビネット
            if (hasVignette) {
                appendLine("  vec2 uv = vPos * 2.0 - 1.0;")
                appendLine("  float vig = 1.0 - uVignette * dot(uv, uv);")
                appendLine("  c.rgb *= clamp(vig, 0.0, 1.0);")
            }

            // フィルムグレイン（progress でシードを変えてフレームごとに異なるノイズ）
            if (hasGrain) {
                appendLine("  float grain = (hash(vTex * 137.0 + uProgress * 7.3) - 0.5) * uGrainStrength;")
                appendLine("  c.rgb = clamp(c.rgb + grain, 0.0, 1.0);")
            }

            // フェード（最後に乗算）
            if (hasFadeOut) appendLine("  c.rgb *= uFadeOut;")
            if (hasFadeIn)  appendLine("  c.rgb *= uFadeIn;")

            appendLine("  gl_FragColor = c;")
            appendLine("}")
        }

        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
            GLES20.glUseProgram(it)
            // Ken Burns の初期値（フレームループ内で毎フレーム更新）
            val zoomLoc = GLES20.glGetUniformLocation(it, "uKbZoom")
            val offLoc  = GLES20.glGetUniformLocation(it, "uKbOffset")
            if (zoomLoc >= 0) GLES20.glUniform1f(zoomLoc, 1f)
            if (offLoc  >= 0) GLES20.glUniform1f(offLoc,  0f)
        }
    }

    // ---- GL ヘルパー ----

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

    private fun drawFrame(program: Int, texId: Int, stMat: FloatArray) {
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,   1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,   1f,  1f, 1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(verts); it.position(0) }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, stMat, 0)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        buf.position(0); GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(posLoc)
        buf.position(2); GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
