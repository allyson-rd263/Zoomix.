package com.zoomix.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** Abre a foto na orientação correta, limitada a [maxSide] pixels. */
    fun decodeUpright(file: File, maxSide: Int): Bitmap {
        require(maxSide > 0) { "Tamanho máximo inválido" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2
        }

        var bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IOException("Imagem inválida")

        try {
            val rot = rotationOf(file)
            if (rot != 0) {
                val rotated = Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(rot.toFloat()) }, true
                )
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            val longSide = max(bmp.width, bmp.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).roundToInt().coerceAtLeast(1),
                    (bmp.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }

            return bmp
        } catch (e: Throwable) {
            if (!bmp.isRecycled) bmp.recycle()
            throw e
        }
    }

    fun toJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            throw IOException("Falha ao comprimir a imagem")
        }
        return out.toByteArray()
    }

    /** Foto reduzida para enviar à IA (lado maior de até 2048 px). */
    fun prepareForUpload(file: File): ByteArray {
        val bmp = decodeUpright(file, 2048)
        return try {
            toJpeg(bmp, 90)
        } finally {
            bmp.recycle()
        }
    }

    /** Converte a resposta da IA (PNG ou JPEG) em JPEG para salvar na galeria. */
    fun reencodeJpeg(bytes: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("A IA devolveu uma imagem inválida")
        return try {
            toJpeg(bmp, 95)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Horizon lock: gira a imagem [rollDeg] graus (positivo = sentido horário) e recorta o maior
     * retângulo com a mesma proporção que cabe dentro da imagem girada (sem bordas pretas).
     */
    fun levelHorizon(src: Bitmap, rollDeg: Float): Bitmap {
        val w = src.width
        val h = src.height
        val rad = Math.toRadians(abs(rollDeg).toDouble())
        val ratio = max(w, h).toDouble() / min(w, h)
        val f = 1.0 / (cos(rad) + sin(rad) * ratio)
        val ow = (w * f).roundToInt().coerceAtLeast(1)
        val oh = (h * f).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(-w / 2f, -h / 2f)
        m.postRotate(rollDeg)
        m.postTranslate(ow / 2f, oh / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Pós-processamento da foto tirada: horizon lock (se [rollDeg] != 0) e zoom digital
     * (recorte central por [factor] > 1). Regrava o mesmo arquivo.
     */
    fun processStill(
        file: File,
        factor: Float,
        rollDeg: Float,
        mirrorHorizontal: Boolean = false
    ) {
        var bmp = decodeUpright(file, 4096)

        try {
            if (abs(rollDeg) > 0.01f) {
                val leveled = levelHorizon(bmp, rollDeg)
                if (leveled !== bmp) bmp.recycle()
                bmp = leveled
            }

            if (factor > 1.01f) {
                val w = (bmp.width / factor).roundToInt().coerceAtLeast(1)
                val h = (bmp.height / factor).roundToInt().coerceAtLeast(1)
                val cropped = Bitmap.createBitmap(
                    bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h
                )
                if (cropped !== bmp) bmp.recycle()
                bmp = cropped
            }

            if (mirrorHorizontal) {
                val matrix = Matrix().apply { setScale(-1f, 1f) }
                val mirrored = Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height, matrix, true
                )
                if (mirrored !== bmp) bmp.recycle()
                bmp = mirrored
            }

            val bytes = toJpeg(bmp, 95)
            if (bytes.isEmpty()) throw IOException("Falha ao converter a foto")
            file.writeBytes(bytes)

            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                saveAttributes()
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** Copia dados da câmera (data, modelo, exposição, ISO etc.) e a orientação de [src] para [dst]. */
    fun copyExif(src: File, dst: File) {
        try {
            val from = ExifInterface(src.absolutePath)
            val to = ExifInterface(dst.absolutePath)
            val tags = listOf(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                "PhotographicSensitivity", ExifInterface.TAG_FOCAL_LENGTH
            )
            tags.forEach { t -> from.getAttribute(t)?.let { to.setAttribute(t, it) } }
            to.saveAttributes()
        } catch (e: Exception) {
            // Sem EXIF não é motivo para perder a foto.
        }
    }
}
