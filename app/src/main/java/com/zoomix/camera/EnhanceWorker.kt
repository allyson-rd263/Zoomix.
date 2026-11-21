package com.zoomix.camera

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Envia a foto para a IA em segundo plano e salva a versão melhorada na galeria.
 * Nada é apagado antes de a melhorada estar salva. Se a IA falhar de vez (ou não houver chave),
 * a foto original vai para a galeria para você não perder nada.
 *
 * [file] é a foto enquadrada (já recortada quando houve zoom digital). Se existir "<nome>_full.jpg",
 * ela é a captura inteira do sensor, sem nenhum processamento.
 */
class EnhanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure()
        val full = File(file.parentFile, file.nameWithoutExtension + "_full.jpg")
        val untouched = if (full.exists()) full else file

        try {
            val provider = SecureStore.provider(applicationContext)
            val key = SecureStore.apiKey(applicationContext, provider)
            if (key.isNullOrBlank()) {
                saveOriginal(file, full)
                return@withContext Result.success()
            }
            val raw = AiClients.enhance(provider, key, ImageUtils.prepareForUpload(file))
            val enhanced = ImageUtils.withExifFrom(
                applicationContext.cacheDir, ImageUtils.reencodeJpeg(raw), untouched
            )
            Gallery.saveJpeg(applicationContext, enhanced, file.nameWithoutExtension)
            if (SecureStore.keepOriginal(applicationContext)) {
                Gallery.saveJpeg(
                    applicationContext, untouched.readBytes(), file.nameWithoutExtension + "_original"
                )
            }
            file.delete()
            full.delete()
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else fallback(file, full)
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fallback(file, full)
        } catch (e: Exception) {
            fallback(file, full)
        }
    }

    private fun saveOriginal(file: File, full: File) {
        Gallery.saveJpeg(applicationContext, file.readBytes(), file.nameWithoutExtension)
        file.delete()
        full.delete()
    }

    private fun fallback(file: File, full: File): Result = try {
        saveOriginal(file, full)
        Result.success()
    } catch (e: Exception) {
        Result.failure()
    }

    companion object {
        const val KEY_PATH = "path"
        const val MAX_ATTEMPTS = 3
    }
}
