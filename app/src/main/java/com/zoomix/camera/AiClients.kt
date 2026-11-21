package com.zoomix.camera

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AiException(message: String, val retryable: Boolean) : Exception(message)

object AiClients {
    // Nomes de modelo podem mudar com o tempo: se der erro 404/400, confira na documentação do provedor.
    const val GEMINI_MODEL = "gemini-2.5-flash-image"
    const val OPENAI_MODEL = "gpt-image-1"

    const val PROMPT =
        "Enhance this photograph: increase sharpness and fine detail, reduce noise and " +
            "compression artifacts, and balance exposure and colors naturally. Keep the exact " +
            "same composition, framing, people, faces, text and objects. Do not add, remove or " +
            "change anything in the scene. Return only the enhanced image."

    private class Resp(val code: Int, val body: String)

    private fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun toException(r: Resp): AiException {
        val detail = try {
            JSONObject(r.body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        val base = when (r.code) {
            400 -> "Pedido recusado pelo provedor"
            401, 403 -> "Chave inválida ou sem permissão"
            402 -> "Sem crédito ou cobrança não ativada"
            404 -> "Modelo não encontrado (confira o nome do modelo)"
            429 -> "Limite de uso ou crédito esgotado"
            else -> "Erro ${r.code}"
        }
        val msg = if (detail.isNullOrBlank()) base else "$base: $detail"
        return AiException(msg, retryable = r.code >= 500 || r.code == 408)
    }

    /** Devolve a imagem melhorada (bytes de PNG ou JPEG). */
    fun enhance(provider: Provider, apiKey: String, jpeg: ByteArray): ByteArray =
        when (provider) {
            Provider.GEMINI -> enhanceGemini(apiKey, jpeg)
            Provider.OPENAI -> enhanceOpenAi(apiKey, jpeg)
        }

    private fun enhanceGemini(apiKey: String, jpeg: ByteArray): ByteArray {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put(
                    "parts", JSONArray()
                        .put(JSONObject().put("text", PROMPT))
                        .put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                            )
                        )
                )
            )
        ).toString().toByteArray()

        val r = call(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
            "POST",
            mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
            body
        )
        if (r.code !in 200..299) throw toException(r)

        val parts = JSONObject(r.body)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                val data = inline?.optString("data")
                if (!data.isNullOrEmpty()) return Base64.decode(data, Base64.DEFAULT)
            }
        }
        throw AiException("A IA não devolveu uma imagem", false)
    }

    private fun enhanceOpenAi(apiKey: String, jpeg: ByteArray): ByteArray {
        val boundary = "----Zoomix${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                    .toByteArray()
            )
        }
        field("model", OPENAI_MODEL)
        field("prompt", PROMPT)
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; " +
                "filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
        )
        out.write(jpeg)
        out.write("\r\n--$boundary--\r\n".toByteArray())

        val r = call(
            "https://api.openai.com/v1/images/edits",
            "POST",
            mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "multipart/form-data; boundary=$boundary"
            ),
            out.toByteArray()
        )
        if (r.code !in 200..299) throw toException(r)

        val b64 = JSONObject(r.body).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
        if (b64.isNullOrEmpty()) throw AiException("A IA não devolveu uma imagem", false)
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /** Retorna null se a chave foi aceita, ou a mensagem de erro. Não confirma cobrança/crédito. */
    fun testKey(provider: Provider, key: String): String? {
        if (key.isBlank()) return "Cole a chave primeiro"
        return try {
            val r = when (provider) {
                Provider.GEMINI -> call(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1",
                    "GET", mapOf("x-goog-api-key" to key)
                )
                Provider.OPENAI -> call(
                    "https://api.openai.com/v1/models",
                    "GET", mapOf("Authorization" to "Bearer $key")
                )
            }
            if (r.code in 200..299) null else toException(r).message
        } catch (e: IOException) {
            "Sem conexão com a internet"
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }
}
