package com.faceswap.app.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Face(val kps: FloatArray, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float) {
    val area: Float get() = (x2 - x1) * (y2 - y1)
}

/** insightface pipeline (SCRFD detector + ArcFace + inswapper_128 + optional GFPGAN) on ONNX Runtime. */
class FaceEngine(private val store: ModelStore) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun opts() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
    }

    private val opened = ArrayList<OrtSession>()

    private fun session(name: String): OrtSession =
        env.createSession(store.file(name).absolutePath, opts()).also { synchronized(opened) { opened.add(it) } }

    private val det by lazy { session(ModelNames.DETECTOR) }
    private val rec by lazy { session(ModelNames.RECOGNIZER) }
    private val swp by lazy {
        session(ModelNames.SWAPPER).also { s ->
            s.inputInfo.values.forEach { ni ->
                val ti = ni.info as? TensorInfo
                if (ti != null && ti.type != OnnxJavaType.FLOAT) {
                    error("This swapper model uses ${ti.type} inputs. Please select the standard float32 inswapper_128.onnx.")
                }
            }
        }
    }
    private val enh by lazy { if (hasEnhancer) session(ModelNames.ENHANCER) else null }
    private val emap by lazy { EmapReader.read(store.file(ModelNames.SWAPPER)) }

    val hasEnhancer: Boolean get() = store.file(ModelNames.ENHANCER).length() > 0

    @Volatile private var feather128 = Geo.ellipseMask(128, 64f, 68f, 50f, 56f, 0.65f, 1f)
    private val feather512 by lazy { Geo.ellipseMask(512, 256f, 295f, 170f, 205f, 0.7f, 1f) }
    private val statMask by lazy { Geo.ellipseMask(128, 64f, 70f, 32f, 38f, 0.6f, 1f) }
    private val edgeWeight by lazy {
        val inner = Geo.ellipseMask(128, 64f, 68f, 50f, 56f, 0.35f, 0.85f)
        FloatArray(inner.size) { 1f - inner[it] }
    }

    /** 0..1: how strongly the swapped face is colour-matched to the target photo and blended at the seam. */
    @Volatile var colorMatch = 0.8f

    /** 0 = tight edge (like insightface), 1 = very soft blend around the face. */
    fun setSoftness(s: Float) {
        feather128 = Geo.ellipseMask(128, 64f, 68f, 50f, 56f, (0.80f - 0.30f * s).coerceIn(0.45f, 0.9f), 1f)
    }

    // ------------------------------------------------------------------ detection
    fun detect(bmp: Bitmap): List<Face> {
        val size = 640
        val ratio = bmp.height.toFloat() / bmp.width
        val nw: Int; val nh: Int
        if (ratio > 1f) { nh = size; nw = (size / ratio).toInt().coerceAtLeast(1) }
        else { nw = size; nh = (size * ratio).toInt().coerceAtLeast(1) }
        val scale = nh.toFloat() / bmp.height
        val small = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        val plane = size * size
        val data = FloatArray(3 * plane) { (0f - 127.5f) / 128f }
        val px = IntArray(nw * nh)
        small.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (small !== bmp) small.recycle()
        for (y in 0 until nh) for (x in 0 until nw) {
            val p = px[y * nw + x]; val i = y * size + x
            data[i] = (((p shr 16) and 255) - 127.5f) / 128f
            data[plane + i] = (((p shr 8) and 255) - 127.5f) / 128f
            data[2 * plane + i] = ((p and 255) - 127.5f) / 128f
        }
        val cands = ArrayList<Face>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { res ->
                val outs = (0 until 9).map {
                    val b = (res[it] as OnnxTensor).floatBuffer
                    FloatArray(b.remaining()).also { a -> b.get(a) }
                }
                val strides = intArrayOf(8, 16, 32)
                for ((idx, stride) in strides.withIndex()) {
                    val scores = outs[idx]; val bbox = outs[idx + 3]; val kps = outs[idx + 6]
                    val n = size / stride
                    var i = 0
                    for (r in 0 until n) for (c in 0 until n) for (a in 0 until 2) {
                        if (scores[i] >= 0.5f) {
                            val cx = (c * stride).toFloat(); val cy = (r * stride).toFloat()
                            val k = FloatArray(10)
                            for (j in 0 until 5) {
                                k[2 * j] = (cx + kps[i * 10 + 2 * j] * stride) / scale
                                k[2 * j + 1] = (cy + kps[i * 10 + 2 * j + 1] * stride) / scale
                            }
                            cands.add(Face(
                                k,
                                (cx - bbox[i * 4] * stride) / scale, (cy - bbox[i * 4 + 1] * stride) / scale,
                                (cx + bbox[i * 4 + 2] * stride) / scale, (cy + bbox[i * 4 + 3] * stride) / scale,
                                scores[i],
                            ))
                        }
                        i++
                    }
                }
            }
        }
        return nms(cands)
    }

    private fun nms(c: MutableList<Face>): List<Face> {
        c.sortByDescending { it.score }
        val keep = ArrayList<Face>()
        for (f in c) if (keep.none { iou(it, f) > 0.4f }) keep.add(f)
        return keep
    }

    private fun iou(a: Face, b: Face): Float {
        val w = min(a.x2, b.x2) - max(a.x1, b.x1)
        val h = min(a.y2, b.y2) - max(a.y1, b.y1)
        if (w <= 0 || h <= 0) return 0f
        val inter = w * h
        return inter / (a.area + b.area - inter)
    }

    // ------------------------------------------------------------------ identity
    private fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= n
    }

    fun embed(bmp: Bitmap, face: Face): FloatArray {
        val crop = Geo.warp(bmp, Geo.similarity(face.kps, Geo.ARCFACE), 112)
        val data = Geo.toTensor(crop, 127.5f, 127.5f)
        crop.recycle()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 112, 112)).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { res ->
                val b = (res[0] as OnnxTensor).floatBuffer
                val e = FloatArray(b.remaining()).also { b.get(it) }
                normalize(e)
                return e
            }
        }
    }

    /** Builds the swapper "source" latent from one or more photos of the same person. */
    fun latentFrom(sources: List<Bitmap>): FloatArray? {
        val embs = sources.mapNotNull { bmp ->
            detect(bmp).maxByOrNull { it.area }?.let { embed(bmp, it) }
        }
        if (embs.isEmpty()) return null
        val avg = FloatArray(512)
        embs.forEach { e -> for (i in 0 until 512) avg[i] += e[i] }
        normalize(avg)
        val lat = FloatArray(512)
        for (i in 0 until 512) {
            val a = avg[i]
            val row = i * 512
            for (j in 0 until 512) lat[j] += a * emap[row + j]
        }
        normalize(lat)
        return lat
    }

    // ------------------------------------------------------------------ swap + enhance
    private fun blendBack(img: Bitmap, data: FloatArray, size: Int, mask: FloatArray, strength: Float, lo: Float, scale: Float, m: Matrix) {
        val plane = size * size
        val px = IntArray(plane)
        for (i in 0 until plane) {
            val r = ((data[i] - lo) * scale).toInt().coerceIn(0, 255)
            val g = ((data[plane + i] - lo) * scale).toInt().coerceIn(0, 255)
            val b = ((data[2 * plane + i] - lo) * scale).toInt().coerceIn(0, 255)
            val a = (mask[i] * strength * 255f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val faceBmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        val inv = Matrix()
        m.invert(inv)
        Canvas(img).drawBitmap(faceBmp, inv, Paint(Paint.FILTER_BITMAP_FLAG))
        faceBmp.recycle()
    }

    /** Matches skin tone/lighting of the swapped crop to the original crop, then fixes the low-frequency seam. */
    private fun colorCorrect(target: FloatArray, out: FloatArray, s: Float) {
        if (s <= 0f) return
        val plane = 128 * 128
        var sw = 0f
        for (i in 0 until plane) sw += statMask[i]
        for (c in 0 until 3) {
            val o = c * plane
            var mt = 0f; var mo = 0f
            for (i in 0 until plane) { mt += target[o + i] * statMask[i]; mo += out[o + i] * statMask[i] }
            mt /= sw; mo /= sw
            var vt = 0f; var vo = 0f
            for (i in 0 until plane) {
                val a = target[o + i] - mt; val b = out[o + i] - mo
                vt += a * a * statMask[i]; vo += b * b * statMask[i]
            }
            val st = sqrt(vt / sw)
            val so = sqrt(vo / sw).coerceAtLeast(1e-4f)
            val k = 1f + 0.5f * ((st / so).coerceIn(0.8f, 1.25f) - 1f)
            for (i in 0 until plane) {
                val v = out[o + i]
                out[o + i] = v + s * ((v - mo) * k + mt - v)
            }
        }
        val bt = Geo.blur(target, 128, 3, 8f)
        val bo = Geo.blur(out, 128, 3, 8f)
        for (i in 0 until 3 * plane) out[i] += edgeWeight[i % plane] * s * (bt[i] - bo[i])
    }

    private fun swapFace(img: Bitmap, face: Face, latent: FloatArray) {
        val m = Geo.similarity(face.kps, Geo.DST128)
        val crop = Geo.warp(img, m, 128)
        val target = Geo.toTensor(crop, 0f, 255f)
        crop.recycle()
        var tName = ""; var sName = ""
        for ((n, ni) in swp.inputInfo) { if ((ni.info as TensorInfo).shape.size == 2) sName = n else tName = n }
        val out: FloatArray
        OnnxTensor.createTensor(env, FloatBuffer.wrap(target), longArrayOf(1, 3, 128, 128)).use { t ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, 512)).use { s ->
                swp.run(mapOf(tName to t, sName to s)).use { res ->
                    val b = (res[0] as OnnxTensor).floatBuffer
                    out = FloatArray(b.remaining()).also { b.get(it) }
                }
            }
        }
        colorCorrect(target, out, colorMatch)
        blendBack(img, out, 128, feather128, 1f, 0f, 255f, m)
    }

    private fun enhance(img: Bitmap, face: Face, strength: Float) {
        val s = enh ?: return
        val m = Geo.similarity(face.kps, Geo.DST512)
        val crop = Geo.warp(img, m, 512)
        val data = Geo.toTensor(crop, 127.5f, 127.5f)
        crop.recycle()
        val out: FloatArray
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 512, 512)).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val b = (res[0] as OnnxTensor).floatBuffer
                out = FloatArray(b.remaining()).also { b.get(it) }
            }
        }
        blendBack(img, out, 512, feather512, strength, -1f, 127.5f, m)
    }

    /** Swaps faces in-place. [img] must be a mutable ARGB_8888 bitmap. Returns how many faces were swapped. */
    fun processBitmap(
        img: Bitmap, latent: FloatArray, allFaces: Boolean, enhanceStrength: Float,
        selected: List<FloatArray>? = null,
    ): Int {
        val faces = detect(img)
        val targets = when {
            selected != null -> faces.filter { f ->
                val e = embed(img, f)
                selected.any { s -> dot(s, e) >= MATCH_THRESHOLD }
            }
            allFaces -> faces
            else -> listOfNotNull(faces.maxByOrNull { it.area })
        }
        for (f in targets) {
            swapFace(img, f, latent)
            if (enhanceStrength > 0f && hasEnhancer) enhance(img, f, enhanceStrength)
        }
        return targets.size
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Detects every face in [bmp] and returns a small preview crop plus an identity embedding for each, left to right. */
    fun listFaces(bmp: Bitmap): List<Pair<Bitmap, FloatArray>> =
        detect(bmp).sortedBy { it.x1 }.map { f ->
            val w = f.x2 - f.x1; val h = f.y2 - f.y1
            val x1 = (f.x1 - 0.3f * w).toInt().coerceIn(0, bmp.width - 1)
            val y1 = (f.y1 - 0.3f * h).toInt().coerceIn(0, bmp.height - 1)
            val x2 = (f.x2 + 0.3f * w).toInt().coerceIn(x1 + 1, bmp.width)
            val y2 = (f.y2 + 0.3f * h).toInt().coerceIn(y1 + 1, bmp.height)
            val crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
            val thumb = Bitmap.createScaledBitmap(crop, 160, (160f * crop.height / crop.width).toInt().coerceAtLeast(1), true)
            if (thumb !== crop) crop.recycle()
            thumb to embed(bmp, f)
        }

    companion object { const val MATCH_THRESHOLD = 0.35f }

    override fun close() {
        synchronized(opened) { opened.forEach { runCatching { it.close() } }; opened.clear() }
    }
}
