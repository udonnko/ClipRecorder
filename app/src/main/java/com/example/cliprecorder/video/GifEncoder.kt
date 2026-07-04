package com.example.cliprecorder.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

object GifEncoder {

    /** 動画 URI からフレームを抽出して GIF を MediaStore に保存する */
    suspend fun exportFromVideo(
        context: Context,
        videoUri: Uri,
        outputName: String,
        maxFrames: Int = 20,
        frameDelayMs: Int = 100,
        maxWidth: Int = 320,
    ) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return@withContext
        val stepUs = (durationMs * 1000L) / maxFrames

        val frames = mutableListOf<Bitmap>()
        for (i in 0 until maxFrames) {
            val timeUs = i * stepUs
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: continue
            val scaled = scaleBitmap(bmp, maxWidth)
            if (scaled !== bmp) bmp.recycle()
            frames.add(scaled)
        }
        retriever.release()

        if (frames.isEmpty()) return@withContext

        val tempFile = File(context.cacheDir, "gif_${System.currentTimeMillis()}.gif")
        tempFile.outputStream().use { out ->
            writeGif(out, frames, frameDelayMs)
        }
        frames.forEach { it.recycle() }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipRecorder")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext
        resolver.openOutputStream(uri)?.use { out ->
            tempFile.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        tempFile.delete()
    }

    private fun scaleBitmap(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, h, true)
    }

    // ─── GIF 書き込み ───────────────────────────────────────────────

    private fun writeGif(out: OutputStream, frames: List<Bitmap>, delayCs: Int) {
        val w = frames[0].width
        val h = frames[0].height

        // Header
        out.write("GIF89a".toByteArray())
        // Logical Screen Descriptor
        out.writeShortLE(w)
        out.writeShortLE(h)
        out.write(0xF7)    // GCT flag on, color depth 8 (256 colors)
        out.write(0)       // background color index
        out.write(0)       // pixel aspect ratio

        // Global Color Table (256 entries, 768 bytes) – will be filled per-frame but we need a placeholder
        val palette = IntArray(256)
        val gct = ByteArray(768)
        out.write(gct) // temporary; overwritten per frame using local tables

        // Application Extension for looping
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3); out.write(1)
        out.writeShortLE(0) // loop count 0 = infinite
        out.write(0)

        for (frame in frames) {
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

            val (localPalette, indices) = quantize(pixels, 256)

            // Graphic Control Extension
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0x00) // disposal: do not clear
            out.writeShortLE(delayCs / 10) // delay in 1/100 s
            out.write(0)    // transparent color index (none)
            out.write(0)

            // Image Descriptor
            out.write(0x2C)
            out.writeShortLE(0); out.writeShortLE(0)
            out.writeShortLE(frame.width); out.writeShortLE(frame.height)
            out.write(0x87) // local color table flag, 8-bit (256 colors)

            // Local Color Table
            val lct = ByteArray(768)
            for (i in localPalette.indices) {
                lct[i * 3]     = (localPalette[i] shr 16 and 0xFF).toByte()
                lct[i * 3 + 1] = (localPalette[i] shr 8  and 0xFF).toByte()
                lct[i * 3 + 2] = (localPalette[i]        and 0xFF).toByte()
            }
            out.write(lct)

            // Image Data
            val compressed = lzwCompress(indices, 8)
            out.write(8) // LZW minimum code size
            var pos = 0
            while (pos < compressed.size) {
                val blockSize = minOf(255, compressed.size - pos)
                out.write(blockSize)
                out.write(compressed, pos, blockSize)
                pos += blockSize
            }
            out.write(0) // block terminator
        }
        out.write(0x3B) // trailer
    }

    // ─── メディアンカット 量子化 ────────────────────────────────────

    private fun quantize(pixels: IntArray, maxColors: Int): Pair<IntArray, ByteArray> {
        // シンプルなメディアンカット
        data class Box(val rMin: Int, val rMax: Int, val gMin: Int, val gMax: Int,
                       val bMin: Int, val bMax: Int, val indices: IntArray)

        fun pixelBox(idx: IntArray): Box {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0
            for (i in idx) {
                val r = pixels[i] shr 16 and 0xFF
                val g = pixels[i] shr 8  and 0xFF
                val b = pixels[i]        and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
            return Box(rMin, rMax, gMin, gMax, bMin, bMax, idx)
        }

        val allIdx = IntArray(pixels.size) { it }
        val boxes = ArrayDeque<Box>()
        boxes.add(pixelBox(allIdx))

        while (boxes.size < maxColors) {
            val box = boxes.maxByOrNull {
                maxOf(it.rMax - it.rMin, it.gMax - it.gMin, it.bMax - it.bMin)
            } ?: break
            if (box.indices.size <= 1) break
            boxes.remove(box)
            val rRange = box.rMax - box.rMin
            val gRange = box.gMax - box.gMin
            val bRange = box.bMax - box.bMin
            val sorted = when {
                rRange >= gRange && rRange >= bRange ->
                    box.indices.sortedBy { pixels[it] shr 16 and 0xFF }
                gRange >= bRange ->
                    box.indices.sortedBy { pixels[it] shr 8  and 0xFF }
                else ->
                    box.indices.sortedBy { pixels[it]        and 0xFF }
            }.toIntArray()
            val mid = sorted.size / 2
            boxes.add(pixelBox(sorted.copyOfRange(0, mid)))
            boxes.add(pixelBox(sorted.copyOfRange(mid, sorted.size)))
        }

        val palette = IntArray(maxColors)
        val repColor = IntArray(boxes.size)
        for ((ci, box) in boxes.withIndex()) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L
            for (idx in box.indices) {
                rSum += pixels[idx] shr 16 and 0xFF
                gSum += pixels[idx] shr 8  and 0xFF
                bSum += pixels[idx]        and 0xFF
            }
            val n = box.indices.size
            val r = (rSum / n).toInt()
            val g = (gSum / n).toInt()
            val b = (bSum / n).toInt()
            repColor[ci] = (r shl 16) or (g shl 8) or b
            palette[ci] = repColor[ci]
        }

        // Map each pixel to nearest palette index
        val indexMap = HashMap<Int, Byte>(pixels.size)
        val result = ByteArray(pixels.size)
        for (pi in pixels.indices) {
            val c = pixels[pi] and 0xFFFFFF
            result[pi] = indexMap.getOrPut(c) {
                var best = 0; var bestDist = Int.MAX_VALUE
                for (ci in repColor.indices) {
                    val rc = repColor[ci]
                    val dr = (c shr 16 and 0xFF) - (rc shr 16 and 0xFF)
                    val dg = (c shr 8  and 0xFF) - (rc shr 8  and 0xFF)
                    val db = (c        and 0xFF) - (rc        and 0xFF)
                    val dist = dr * dr + dg * dg + db * db
                    if (dist < bestDist) { bestDist = dist; best = ci }
                }
                best.toByte()
            }
        }

        return Pair(palette, result)
    }

    // ─── LZW 圧縮 ──────────────────────────────────────────────────

    private fun lzwCompress(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val eofCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = eofCode + 1

        val table = HashMap<String, Int>(4096)
        fun initTable() {
            table.clear()
            for (i in 0 until clearCode) table[i.toChar().toString()] = i
            nextCode = eofCode + 1
            codeSize = minCodeSize + 1
        }
        initTable()

        val out = ByteArrayOutputStream()
        var bitBuf = 0; var bitCount = 0

        fun emitCode(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                out.write(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        emitCode(clearCode)
        var prefix = (indices[0].toInt() and 0xFF).toChar().toString()
        for (i in 1 until indices.size) {
            val c = (indices[i].toInt() and 0xFF).toChar().toString()
            val pc = prefix + c
            if (table.containsKey(pc)) {
                prefix = pc
            } else {
                emitCode(table[prefix]!!)
                if (nextCode < 4096) {
                    table[pc] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emitCode(clearCode)
                    initTable()
                }
                prefix = c
            }
        }
        emitCode(table[prefix]!!)
        emitCode(eofCode)
        if (bitCount > 0) out.write(bitBuf and 0xFF)

        return out.toByteArray()
    }

    private fun OutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }
}
