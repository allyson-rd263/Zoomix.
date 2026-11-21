package com.zoomix.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {
    private fun rotationOf(file: File): Int = try {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** Abre a foto já na orientação certa, com no máximo [maxSide] pixels no lado maior. */
    fun decodeUpright(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IOException("Imagem inválida")

        val rot = rotationOf(file)
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true
            )
        }
        return bmp
    }

    fun toJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Foto reduzida para enviar à IA (lado maior de até 2048 px). */
    fun prepareForUpload(file: File): ByteArray = toJpeg(decodeUpright(file, 2048), 90)

    /** Converte a resposta da IA (PNG ou JPEG) em JPEG para salvar na galeria. */
    fun reencodeJpeg(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("A IA devolveu uma imagem inválida")
        return toJpeg(bmp, 95)
    }

    /** Zoom digital: grava em [dst] o recorte central de [src] (factor > 1). [src] não é alterada. */
    fun applyDigitalCrop(src: File, dst: File, factor: Float) {
        val bmp = decodeUpright(src, 4096)
        val w = (bmp.width / factor).roundToInt().coerceAtLeast(1)
        val h = (bmp.height / factor).roundToInt().coerceAtLeast(1)
        val x = (bmp.width - w) / 2
        val y = (bmp.height - h) / 2
        val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
        dst.writeBytes(toJpeg(cropped, 95))
        copyExif(src, dst)
    }

    /** Largura x altura reais do arquivo, sem carregar a imagem inteira. */
    fun sizeOf(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    /** Copia dados da câmera (data, modelo, exposição, ISO etc.) para um JPEG gerado pelo app. */
    fun copyExif(src: File, dst: File) {
        try {
            val from = ExifInterface(src.absolutePath)
            val to = ExifInterface(dst.absolutePath)
            val tags = listOf(
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP
            )
            tags.forEach { t -> from.getAttribute(t)?.let { to.setAttribute(t, it) } }
            // A imagem já está na orientação certa (pixels girados), então a orientação é "normal".
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.saveAttributes()
        } catch (e: Exception) {
            // Sem EXIF não é motivo para perder a foto.
        }
    }

    /** Devolve [bytes] (JPEG) com os dados de câmera de [source]; se falhar, devolve [bytes] como estavam. */
    fun withExifFrom(cacheDir: File, bytes: ByteArray, source: File): ByteArray {
        val tmp = File(cacheDir, "exif_${System.nanoTime()}.jpg")
        return try {
            tmp.writeBytes(bytes)
            copyExif(source, tmp)
            tmp.readBytes()
        } catch (e: Exception) {
            bytes
        } finally {
            tmp.delete()
        }
    }
}
