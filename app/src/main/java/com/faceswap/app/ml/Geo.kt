package com.faceswap.app.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

object Geo {
    // ArcFace 5-point template (112x112)
    val ARCFACE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
    // insightface uses this shifted template for the 128px swapper crop
    val DST128 = FloatArray(10) { i -> if (i % 2 == 0) ARCFACE[i] + 8f else ARCFACE[i] }
    // FFHQ 512 template used by GFPGAN
    val DST512 = floatArrayOf(192.98138f, 239.94708f, 318.90277f, 240.1936f, 256.63416f, 314.01935f, 201.26117f, 371.41043f, 313.08905f, 371.15118f)

    /** Least-squares similarity transform (scale + rotation + translation) mapping src points to dst points. */
    fun similarity(src: FloatArray, dst: FloatArray): Matrix {
        val n = 5
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) { sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
        sx /= n; sy /= n; dx /= n; dy /= n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val x = src[2 * i] - sx; val y = src[2 * i + 1] - sy
            val u = dst[2 * i] - dx; val v = dst[2 * i + 1] - dy
            num1 += x * u + y * v
            num2 += x * v - y * u
            den += x * x + y * y
        }
        val a = num1 / den
        val b = num2 / den
        val tx = dx - (a * sx - b * sy)
        val ty = dy - (b * sx + a * sy)
        return Matrix().apply {
            setValues(floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f))
        }
    }

    fun warp(src: Bitmap, m: Matrix, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** NCHW float tensor, RGB order, (v - mean) / std. */
    fun toTensor(bmp: Bitmap, mean: Float, std: Float): FloatArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = (((p shr 16) and 255) - mean) / std
            out[plane + i] = (((p shr 8) and 255) - mean) / std
            out[2 * plane + i] = ((p and 255) - mean) / std
        }
        return out
    }

    /** Face-shaped alpha mask: 1 inside radius r0 (normalised), smooth falloff to 0 at r1. */
    fun ellipseMask(size: Int, cx: Float, cy: Float, rx: Float, ry: Float, r0: Float, r1: Float): FloatArray {
        val m = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            val t = ((r1 - sqrt(dx * dx + dy * dy)) / (r1 - r0)).coerceIn(0f, 1f)
            m[y * size + x] = t * t * (3f - 2f * t)
        }
        return m
    }

    /** Gaussian blur of planar float data (channels stacked), edges replicated. */
    fun blur(data: FloatArray, size: Int, channels: Int, sigma: Float): FloatArray {
        val r = ceil(3 * sigma).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-(d * d) / (2 * sigma * sigma)) }
        val s = k.sum()
        for (i in k.indices) k[i] /= s
        val out = FloatArray(data.size)
        val tmp = FloatArray(data.size)
        for (c in 0 until channels) {
            val o = c * size * size
            for (y in 0 until size) for (x in 0 until size) {
                var acc = 0f
                for (t in -r..r) acc += data[o + y * size + (x + t).coerceIn(0, size - 1)] * k[t + r]
                tmp[o + y * size + x] = acc
            }
            for (y in 0 until size) for (x in 0 until size) {
                var acc = 0f
                for (t in -r..r) acc += tmp[o + (y + t).coerceIn(0, size - 1) * size + x] * k[t + r]
                out[o + y * size + x] = acc
            }
        }
        return out
    }

    /** Square alpha mask: 1 inside a margin, softly feathered with a Gaussian blur. */
    fun featherMask(size: Int, margin: Int, sigma: Float): FloatArray {
        val m = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            m[y * size + x] = if (x in margin until size - margin && y in margin until size - margin) 1f else 0f
        }
        val r = ceil(3 * sigma).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); exp(-(d * d) / (2 * sigma * sigma)) }
        val s = k.sum()
        for (i in k.indices) k[i] /= s
        val tmp = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var acc = 0f
            for (t in -r..r) { val xx = x + t; if (xx in 0 until size) acc += m[y * size + xx] * k[t + r] }
            tmp[y * size + x] = acc
        }
        for (y in 0 until size) for (x in 0 until size) {
            var acc = 0f
            for (t in -r..r) { val yy = y + t; if (yy in 0 until size) acc += tmp[yy * size + x] * k[t + r] }
            m[y * size + x] = acc
        }
        return m
    }
}
