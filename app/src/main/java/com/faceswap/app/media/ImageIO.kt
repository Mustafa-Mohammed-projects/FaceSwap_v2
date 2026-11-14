package com.faceswap.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.random.Random

object ImageIO {

    /** Decodes with EXIF rotation applied. Downscales only if the longest side exceeds [maxSide]. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int, mutable: Boolean = true): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = mutable
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > maxSide) {
                val s = maxSide.toFloat() / m
                dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i) ?: "image"
            }
        }
        return "image"
    }

    fun isPng(ctx: Context, uri: Uri) = ctx.contentResolver.getType(uri)?.contains("png") == true

    /**
     * حفظ الصورة مع طبقة حماية متقدمة للـ Dataset (مقاومة لكشف ELA والذكاء الاصطناعي):
     */
    fun save(ctx: Context, bmp: Bitmap, baseName: String, png: Boolean): Uri {
        val r = ctx.contentResolver
        val tempFile = File(ctx.cacheDir, "sec_${System.currentTimeMillis()}.jpg")
        
        try {
            // 1. تطبيق طبقة إخفاء التعديل العميقة (Anti-Forensic & Noise Layer)
            val antiForensicBmp = applyAdvancedAntiForensicLayer(bmp)

            // 2. الضغط المرحلي الأول (Quality 94) لدمج الترددات
            FileOutputStream(tempFile).use { fos ->
                antiForensicBmp.compress(Bitmap.CompressFormat.JPEG, 94, fos)
            }

            // 3. الضغط المرحلي الثاني (Quality 91) لتدمير أي بصمات تدرج رقمية (Gradient Traces)
            val decodedIntermediate = BitmapFactory.decodeFile(tempFile.absolutePath)
            if (decodedIntermediate != null) {
                FileOutputStream(tempFile).use { fos ->
                    decodedIntermediate.compress(Bitmap.CompressFormat.JPEG, 91, fos)
                }
                decodedIntermediate.recycle()
            }
            if (antiForensicBmp != bmp) {
                antiForensicBmp.recycle()
            }

            // 4. اختيار عشوائي لكاميرا هاتف حقيقية وحقن بيانات الـ Exif متكاملة
            val makes = arrayOf("samsung", "Apple", "Xiaomi", "Google")
            val models = arrayOf("Samsung Galaxy S23 Ultra", "iPhone 14 Pro", "Xiaomi 13 Pro", "Pixel 7 Pro")
            val idx = Random.nextInt(makes.size)

            val exif = ExifInterface(tempFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, makes[idx])
            exif.setAttribute(ExifInterface.TAG_MODEL, models[idx])
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:11:15 01:30:00")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:15 01:30:00")
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "2026:11:15 01:30:00")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/100")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.2")
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, "80")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "4.2")
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, ExifInterface.WHITE_BALANCE_AUTO.toString())
            exif.saveAttributes()

            // 5. التصدير النهائي للمجلد العام
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, baseName + ".jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FaceSwap")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create output file")
            r.openOutputStream(uri)!!.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, values, null, null)
            
            return uri
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * طبقة تشتيت متطورة تحاكي الضجيج البصري لمستشعرات الهواتف وتكسر تحليلات ELA
     */
    private fun applyAdvancedAntiForensicLayer(bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint()

        // تعديل طفيف جداً في توزيع القنوات اللونية لإخفاء حواف الدمج الفردية
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1.004f, 0f, 0f, 0f, Random.nextFloat() * 1.0f - 0.5f,
                0f, 1.004f, 0f, 0f, Random.nextFloat() * 1.0f - 0.5f,
                0f, 0f, 1.004f, 0f, Random.nextFloat() * 1.0f - 0.5f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(mutableBitmap, 0f, 0f, paint)

        return mutableBitmap
    }
}
