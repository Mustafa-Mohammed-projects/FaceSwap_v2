package com.faceswap.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.faceswap.app.ml.FaceEngine
import java.nio.ByteBuffer
import kotlin.math.max

/** Frame-by-frame swap: decode -> swap -> H.264 encode, original audio track is copied untouched. */
object VideoProcessor {

    fun process(
        ctx: Context, uri: Uri, engine: FaceEngine, latent: FloatArray,
        allFaces: Boolean, enhance: Float, maxSide: Int, selected: List<FloatArray>?,
        onProgress: (Float, String) -> Unit, cancelled: () -> Boolean,
    ): Uri? {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(ctx, uri)
        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("Unreadable video")
        val frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
        val fps = (if (frameCount != null && durMs > 0) frameCount * 1000f / durMs else 30f).coerceIn(5f, 60f)
        val origBitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        val total = max(1, (durMs / 1000f * fps).toInt())

        fun even(v: Int) = max(2, v and 1.inv())

        fun frame(i: Int): Bitmap? {
            val t = (i * 1_000_000f / fps).toLong()
            val raw = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val m = max(raw.width, raw.height)
            var b = if (maxSide > 0 && m > maxSide) {
                val s = maxSide.toFloat() / m
                Bitmap.createScaledBitmap(raw, even((raw.width * s).toInt()), even((raw.height * s).toInt()), true)
            } else if (raw.width % 2 != 0 || raw.height % 2 != 0) {
                Bitmap.createBitmap(raw, 0, 0, even(raw.width), even(raw.height))
            } else raw
            if (b !== raw) raw.recycle()
            if (!b.isMutable || b.config != Bitmap.Config.ARGB_8888) {
                val c = b.copy(Bitmap.Config.ARGB_8888, true)
                b.recycle(); b = c
            }
            return b
        }

        val first = frame(0) ?: error("Could not read the first video frame")
        val w = first.width
        val h = first.height
        val bitrate = max((origBitrate * 1.25).toInt(), (w * h * fps * 0.12f).toInt()).coerceIn(1_000_000, 45_000_000)

        // audio track (copied as-is)
        var audioEx: MediaExtractor? = null
        var audioFormat: MediaFormat? = null
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    ex.selectTrack(i); audioEx = ex; audioFormat = f; break
                }
            }
            if (audioEx == null) ex.release()
        }

        // output file in Movies/FaceSwap
        val name = ImageIO.displayName(ctx, uri).substringBeforeLast('.') + "_swap"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/FaceSwap")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create output video")
        val pfd = resolver.openFileDescriptor(outUri, "rw")!!
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        val info = MediaCodec.BufferInfo()
        var started = false
        var vTrack = -1
        var aTrack = -1
        val px = IntArray(w * h)

        fun drain(eos: Boolean) {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        vTrack = muxer.addTrack(enc.outputFormat)
                        audioFormat?.let { aTrack = muxer.addTrack(it) }
                        muxer.start(); started = true
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(vTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun feed(bmp: Bitmap, ptsUs: Long) {
            while (true) {
                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    fillYuv(enc.getInputImage(idx)!!, bmp, px)
                    enc.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs, 0)
                    break
                }
                drain(false)
            }
            drain(false)
        }

        var ok = false
        var wasCancelled = false
        try {
            var last: Bitmap = first
            for (i in 0 until total) {
                if (cancelled()) { wasCancelled = true; break }
                val bmp = if (i == 0) first else (frame(i) ?: break)
                engine.processBitmap(bmp, latent, allFaces, enhance, selected)
                feed(bmp, (i * 1_000_000f / fps).toLong())
                if (last !== bmp) last.recycle()
                last = bmp
                onProgress((i + 1f) / total, "Frame ${i + 1} of $total")
            }
            if (!wasCancelled) {
                while (true) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        enc.queueInputBuffer(idx, 0, 0, (total * 1_000_000f / fps).toLong(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                    drain(false)
                }
                drain(true)
                val ex = audioEx
                if (started && ex != null && aTrack >= 0) {
                    val buf = ByteBuffer.allocate(4 shl 20)
                    val bi = MediaCodec.BufferInfo()
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        bi.set(0, n, ex.sampleTime, ex.sampleFlags)
                        muxer.writeSampleData(aTrack, buf, bi)
                        ex.advance()
                    }
                }
                ok = started
            }
        } finally {
            runCatching { enc.stop() }
            runCatching { enc.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { pfd.close() }
            runCatching { mmr.release() }
            runCatching { audioEx?.release() }
            if (ok) {
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            } else {
                runCatching { resolver.delete(outUri, null, null) }
            }
        }
        return if (ok) outUri else null
    }

    /** BT.709 limited-range RGB -> YUV420, written through the codec's Image planes (stride-safe). */
    private fun fillYuv(img: Image, bmp: Bitmap, px: IntArray) {
        val w = bmp.width
        val h = bmp.height
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        for (y in 0 until h) {
            val row = y * yp.rowStride
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                yb.put(row + x * yp.pixelStride, (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte())
            }
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = px[(cy * 2 + dy) * w + cx * 2 + dx]
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
            }
            r = r shr 2; g = g shr 2; b = b shr 2
            val u = (((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
            val v = (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).coerceIn(0, 255)
            ub.put(cy * up.rowStride + cx * up.pixelStride, u.toByte())
            vb.put(cy * vp.rowStride + cx * vp.pixelStride, v.toByte())
        }
    }
}
