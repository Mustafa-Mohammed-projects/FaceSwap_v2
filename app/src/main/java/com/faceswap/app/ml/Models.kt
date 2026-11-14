package com.faceswap.app.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object ModelNames {
    const val SWAPPER = "inswapper_128.onnx"
    const val DETECTOR = "det_10g.onnx"
    const val RECOGNIZER = "w600k_r50.onnx"
    const val ENHANCER = "gfpgan.onnx" // optional
    val REQUIRED = listOf(SWAPPER, DETECTOR, RECOGNIZER)
    val ALL = REQUIRED + ENHANCER
}

data class ImportResult(val filled: List<String>, val ignored: List<String>)

/** Copies the model files the user picks into app-private storage (done once). */
class ModelStore(private val ctx: Context) {
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(name: String) = File(dir, name)
    fun status(): Map<String, Boolean> = ModelNames.ALL.associateWith { file(it).length() > 0 }

    fun clear() { ModelNames.ALL.forEach { file(it).delete() } }

    private fun slotFor(n: String): String? = when {
        "inswapper" in n -> ModelNames.SWAPPER
        "det_10g" in n -> ModelNames.DETECTOR
        "w600k" in n -> ModelNames.RECOGNIZER
        "gfpgan" in n -> ModelNames.ENHANCER
        else -> null
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                return (if (ni >= 0) c.getString(ni) else "file") to (if (si >= 0) c.getLong(si) else -1L)
            }
        }
        return "file" to -1L
    }

    private fun copy(input: InputStream, dest: File, total: Long, onProgress: (Float) -> Unit) {
        val tmp = File(dir, dest.name + ".part")
        tmp.outputStream().use { out ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) onProgress(done.toFloat() / total)
            }
        }
        dest.delete()
        tmp.renameTo(dest)
    }

    fun importFiles(uris: List<Uri>, onProgress: (String, Float) -> Unit): ImportResult {
        val filled = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        for (uri in uris) {
            val (name, size) = nameAndSize(uri)
            val lower = name.lowercase()
            if (lower.endsWith(".zip")) {
                ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val base = e.name.substringAfterLast('/').lowercase()
                        val slot = if (base.endsWith(".onnx")) slotFor(base) else null
                        if (slot != null) {
                            copy(zip, file(slot), -1L) { }
                            onProgress(base, 1f)
                            filled += slot
                        }
                    }
                }
            } else {
                val slot = slotFor(lower)
                if (slot == null) ignored += name
                else {
                    ctx.contentResolver.openInputStream(uri)!!.use { copy(it, file(slot), size) { p -> onProgress(name, p) } }
                    filled += slot
                }
            }
        }
        return ImportResult(filled, ignored)
    }
}

/**
 * inswapper_128.onnx keeps a 512x512 "emap" matrix as the LAST initializer of its graph.
 * The file is ~550 MB, so we scan the protobuf structure without loading it into memory.
 */
object EmapReader {
    private fun varint(raf: RandomAccessFile): Pair<Long, Int> {
        var r = 0L; var shift = 0; var n = 0
        while (true) {
            val b = raf.read()
            if (b < 0) throw EOFException()
            n++
            r = r or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return r to n
    }

    fun read(file: File): FloatArray {
        var lastOff = -1L
        var lastLen = 0
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            var end = raf.length()
            var inGraph = false
            while (pos < end) {
                raf.seek(pos)
                val (tag, n1) = varint(raf)
                pos += n1
                val field = (tag shr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> { raf.seek(pos); pos += varint(raf).second }
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> {
                        raf.seek(pos)
                        val (len, n2) = varint(raf)
                        pos += n2
                        if (!inGraph && field == 7) { inGraph = true; end = pos + len }
                        else {
                            if (inGraph && field == 5) { lastOff = pos; lastLen = len.toInt() }
                            pos += len
                        }
                    }
                    else -> throw IllegalStateException("Unsupported ONNX file")
                }
            }
            if (lastOff < 0) throw IllegalStateException("inswapper model has no initializers")
            val rec = ByteArray(lastLen)
            raf.seek(lastOff)
            raf.readFully(rec)
            return parseTensor(rec)
        }
    }

    private fun parseTensor(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b)
        fun v(): Long { var r = 0L; var s = 0; while (true) { val x = bb.get().toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); if (x and 0x80 == 0) break; s += 7 }; return r }
        var raw: ByteArray? = null
        while (bb.hasRemaining()) {
            val tag = v()
            val field = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> v()
                1 -> bb.position(bb.position() + 8)
                5 -> bb.position(bb.position() + 4)
                2 -> {
                    val len = v().toInt()
                    if (field == 9 || field == 4) { raw = ByteArray(len).also { bb.get(it) } }
                    else bb.position(bb.position() + len)
                }
                else -> break
            }
        }
        val data = raw ?: throw IllegalStateException("emap tensor not found")
        val fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        if (out.size != 512 * 512) throw IllegalStateException("Unexpected emap size ${out.size}; use the standard inswapper_128.onnx")
        return out
    }
}
