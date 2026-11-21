package com.zoomix.camera

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Envia a foto para a IA em segundo plano e salva só a versão melhorada na galeria.
 * A original só é apagada depois que a melhorada foi salva. Se a IA falhar de vez
 * (ou não houver chave), a original vai para a galeria para você não perder a foto.
 * O resultado de cada tentativa fica registrado e aparece em Ajustes (diagnóstico).
 */
class EnhanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun log(text: String) {
        try {
            val t = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())
            SecureStore.setLastAi(applicationContext, "$t · $text")
        } catch (_: Exception) {
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_PATH) ?: return@withContext Result.failure()
        val file = File(path)
        if (!file.exists()) return@withContext Result.failure()

        try {
            if (inputData.getBoolean(KEY_SKIP_AI, false)) {
                saveOriginal(file)
                log("selfie salva sem IA (dá para ligar em Ajustes)")
                return@withContext Result.success()
            }
            val provider = SecureStore.provider(applicationContext)
            val key = SecureStore.apiKey(applicationContext, provider)
            if (key.isNullOrBlank()) {
                saveOriginal(file)
                log("sem chave de API: original salva sem melhoria")
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
            log("sucesso: foto melhorada salva na galeria (${provider.label})")
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) {
                log("tentando de novo (${runAttemptCount + 1}): ${e.message}")
                Result.retry()
            } else {
                log("falha da IA: ${e.message}")
                fallback(file)
            }
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                log("erro de rede ou de gravação, tentando de novo: ${e.message}")
                Result.retry()
            } else {
                log("erro de rede ou de gravação: ${e.message}")
                fallback(file)
            }
        } catch (e: Exception) {
            log("erro: ${e.javaClass.simpleName}: ${e.message}")
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
        const val KEY_SKIP_AI = "skip_ai"
        const val MAX_ATTEMPTS = 3
    }
}
