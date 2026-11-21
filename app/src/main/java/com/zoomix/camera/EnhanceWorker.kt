package com.zoomix.camera

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Envia a foto para a IA em segundo plano e salva só a versão melhorada na galeria.
 * A original só é apagada depois que a melhorada foi salva. Se a IA falhar de vez
 * (ou não houver chave), a original vai para a galeria para você não perder a foto.
 */
class EnhanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure()

        try {
            val provider = SecureStore.provider(applicationContext)
            val key = SecureStore.apiKey(applicationContext, provider)
            if (key.isNullOrBlank()) {
                saveOriginal(file)
                return@withContext Result.success()
            }
            val raw = AiClients.enhance(provider, key, ImageUtils.prepareForUpload(file))
            val enhanced = ImageUtils.reencodeJpeg(raw)
            Gallery.saveJpeg(applicationContext, enhanced, file.nameWithoutExtension)
            if (SecureStore.keepOriginal(applicationContext)) {
                Gallery.saveJpeg(
                    applicationContext, file.readBytes(), file.nameWithoutExtension + "_original"
                )
            }
            file.delete()
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else fallback(file)
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fallback(file)
        } catch (e: Exception) {
            fallback(file)
        }
    }

    private fun saveOriginal(file: File) {
        Gallery.saveJpeg(applicationContext, file.readBytes(), file.nameWithoutExtension)
        file.delete()
    }

    private fun fallback(file: File): Result = try {
        saveOriginal(file)
        Result.success()
    } catch (e: Exception) {
        Result.failure()
    }

    companion object {
        const val KEY_PATH = "path"
        const val MAX_ATTEMPTS = 3
    }
}
