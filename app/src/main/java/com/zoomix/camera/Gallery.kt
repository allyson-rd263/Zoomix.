package com.zoomix.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException

data class Media(val uri: Uri, val isVideo: Boolean)

object Gallery {
    fun saveJpeg(ctx: Context, bytes: ByteArray, displayName: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zoomix")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Não foi possível salvar na galeria")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Não foi possível salvar na galeria")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Zoomix"
            )
            dir.mkdirs()
            val file = File(dir, "$displayName.jpg")
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
    }

    /** Copia um MP4 temporário para Movies/Zoomix, removendo arquivos incompletos em caso de erro. */
    fun saveVideoFile(ctx: Context, src: File, displayName: String) {
        if (!src.isFile || !src.canRead() || src.length() <= 0L) {
            throw IOException("O vídeo temporário está vazio ou não pode ser lido")
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zoomix")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Não foi possível criar o vídeo na galeria")

            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Não foi possível abrir o destino na galeria")

                output.use { out ->
                    src.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }

                val publish = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }

                if (resolver.update(uri, publish, null, null) <= 0) {
                    throw IOException("Não foi possível finalizar o vídeo na galeria")
                }
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Zoomix"
            )

            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Não foi possível criar a pasta Movies/Zoomix")
            }

            val dst = File(dir, "$displayName.mp4")

            try {
                src.copyTo(dst, overwrite = true)
                if (dst.length() != src.length()) {
                    throw IOException("A cópia do vídeo ficou incompleta")
                }
                MediaScannerConnection.scanFile(
                    ctx,
                    arrayOf(dst.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (e: Exception) {
                try {
                    dst.delete()
                } catch (_: Exception) {
                }
                throw e
            }
        }
    }

    private fun newest(ctx: Context, base: Uri, relPath: String): Pair<Uri, Long>? {
        val sel: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            sel = "relative_path LIKE ?"
            args = arrayOf("$relPath%")
        } else {
            sel = "_data LIKE ?"
            args = arrayOf("%/Zoomix/%")
        }
        return try {
            ctx.contentResolver.query(base, arrayOf("_id", "date_added"), sel, args, "date_added DESC")
                ?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(base, c.getLong(0)) to c.getLong(1)
                    else null
                }
        } catch (e: Exception) {
            null
        }
    }

    /** Última foto ou vídeo salvos pelo Zoomix. */
    fun latest(ctx: Context): Media? {
        val img = newest(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Zoomix")
        val vid = newest(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Zoomix")
        return when {
            img == null && vid == null -> null
            vid == null -> Media(img!!.first, false)
            img == null -> Media(vid.first, true)
            vid.second > img.second -> Media(vid.first, true)
            else -> Media(img.first, false)
        }
    }

    /** Miniatura (Android 10+). Em versões mais antigas devolve null. */
    fun thumbnail(ctx: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            ctx.contentResolver.loadThumbnail(uri, Size(192, 192), null)
        } catch (e: Exception) {
            null
        }
    }
}
