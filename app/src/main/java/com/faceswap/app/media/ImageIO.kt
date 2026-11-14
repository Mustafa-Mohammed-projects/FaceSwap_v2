package com.faceswap.app.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.random.Random

object ImageIO {

    // تعريف ملفات الكاميرات لتنويع الـ Dataset بدون أخطاء تركيبية
    private data class DeviceProfile(val make: String, val model: String, val focalLength: String, val fNumber: String)
    
    private val DEVICE_PROFILES = listOf(
        DeviceProfile("samsung", "Samsung Galaxy S23 Ultra", "6.3", "1.7"),
        DeviceProfile("Apple", "iPhone 14 Pro", "6.86", "1.78"),
        DeviceProfile("Xiaomi", "Xiaomi 13 Pro", "8.6", "1.9"),
        DeviceProfile("Google", "Pixel 7 Pro", "6.81", "1.85")
    )

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
     * يحفظ الصورة مع تطبيق معايير مضادة للكشف:
     * 1. تطبيق طبقة التشويش وإخفاء التعديل (Anti-Forensic Layer).
     * 2. إعادة الضغط المرحلي (Multi-pass JPEG Compression: 95 ثم 90).
     * 3. تنويع وحقن بيانات Exif كاميرات الهواتف الحقيقية تلقائياً.
     */
    fun save(ctx: Context, bmp: Bitmap, baseName: String, png: Boolean): Uri {
        val r = ctx.contentResolver
        val tempFile = File(ctx.cacheDir, "sec_${System.currentTimeMillis()}.jpg")
        
        try {
            // الخطوة 1: تطبيق طبقة إخفاء التعديل والتشويش المجهري
            val antiForensicBmp = applyAntiForensicLayer(bmp)

            // الخطوة 2: مرحلة الضغط الأولى (Quality 95)
            val fos1 = FileOutputStream(tempFile)
            antiForensicBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos1)
            fos1.flush()
            fos1.close()

            // الخطوة 3: مرحلة الضغط الثانية (Multi-pass compression Quality 90) لتدمير حواف الدمج الفردية
            val decodedIntermediate = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
            val fos2 = FileOutputStream(tempFile)
            decodedIntermediate.compress(Bitmap.CompressFormat.JPEG, 90, fos2)
            fos2.flush()
            fos2.close()
            decodedIntermediate.recycle()
            if (antiForensicBmp != bmp) antiForensicBmp.recycle()

            // الخطوة 4: حقن Exif عشوائي لهواتف حقيقية (Device Diversification)
            val profile = DEVICE_PROFILES.random()
            val exif = ExifInterface(tempFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, profile.make)
            exif.setAttribute(ExifInterface.TAG_MODEL, profile.model)
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:11:14 20:00:00")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:14 20:00:00")
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "2026:11:14 20:00:00")
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/${listOf(30, 50, 60, 120, 250).random()}")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, profile.fNumber)
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, listOf("50", "100", "200", "400").random())
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, profile.focalLength)
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, ExifInterface.WHITE_BALANCE_AUTO.toString())
            exif.saveAttributes()

            // الخطوة 5: النقل النهائي إلى مجلد التخزين العام Pictures/FaceSwap
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
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * طبقة تشتيت مجهرية تمنع اكتشاف التلاعب عبر ELA وتحليلات التشويش
     */
    private fun applyAntiForensicLayer(bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint()

        // مصفوفة ألوان تحدث انحرافاً لا يلاحظه الإنسان لكسر الـ Gradients المتوقعة لبرمجيات التعديل
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                1.008f, 0f, 0f, 0f, Random.nextFloat() * 1.5f - 0.75f,
                0f, 1.008f, 0f, 0f, Random.nextFloat() * 1.5f - 0.75f,
                0f, 0f, 1.008f, 0f, Random.nextFloat() * 1.5f - 0.75f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(mutableBitmap, 0f, 0f, paint)

        return mutableBitmap
    }
}
