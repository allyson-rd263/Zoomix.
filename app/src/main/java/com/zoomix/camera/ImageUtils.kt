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
    fun processStill(file: File, factor: Float, rollDeg: Float) {
        var bmp = decodeUpright(file, 4096)
        if (abs(rollDeg) > 0.01f) bmp = levelHorizon(bmp, rollDeg)
        if (factor > 1.01f) {
            val w = (bmp.width / factor).roundToInt().coerceAtLeast(1)
            val h = (bmp.height / factor).roundToInt().coerceAtLeast(1)
            bmp = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
        }
        file.writeBytes(toJpeg(bmp, 95))
    }
}
