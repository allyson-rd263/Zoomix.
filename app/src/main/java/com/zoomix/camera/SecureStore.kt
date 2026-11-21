package com.zoomix.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider(val label: String) {
    GEMINI("Gemini (Google)"),
    OPENAI("OpenAI")
}

/** Guarda a chave de API criptografada no aparelho (nunca no código). */
object SecureStore {
    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val p = EncryptedSharedPreferences.create(
                app,
                "zoomix_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = p
            return p
        }
    }

    fun provider(ctx: Context): Provider {
        val saved = prefs(ctx).getString("provider", null)
        return Provider.values().firstOrNull { it.name == saved } ?: Provider.GEMINI
    }

    fun setProvider(ctx: Context, p: Provider) {
        prefs(ctx).edit().putString("provider", p.name).apply()
    }

    fun apiKey(ctx: Context, p: Provider): String? =
        prefs(ctx).getString("key_${p.name}", null)

    fun setApiKey(ctx: Context, p: Provider, key: String) {
        prefs(ctx).edit().putString("key_${p.name}", key.trim()).apply()
    }

    /** Por padrão a original é descartada depois que a melhorada é salva. */
    fun keepOriginal(ctx: Context): Boolean = prefs(ctx).getBoolean("keep_original", false)

    fun setKeepOriginal(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("keep_original", v).apply()
    }

    /** "auto", "leve" ou "completo". */
    fun glassMode(ctx: Context): String = prefs(ctx).getString("glass_mode", "auto") ?: "auto"

    fun setGlassMode(ctx: Context, v: String) {
        prefs(ctx).edit().putString("glass_mode", v).apply()
    }

    /** "max" (até 4K), "fhd" ou "hd". */
    fun videoQuality(ctx: Context): String = prefs(ctx).getString("video_quality", "max") ?: "max"

    fun setVideoQuality(ctx: Context, v: String) {
        prefs(ctx).edit().putString("video_quality", v).apply()
    }

    fun micOn(ctx: Context): Boolean = prefs(ctx).getBoolean("mic_on", true)

    fun setMicOn(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("mic_on", v).apply()
    }

    fun horizon(ctx: Context): Boolean = prefs(ctx).getBoolean("horizon", false)

    fun setHorizon(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("horizon", v).apply()
    }

    /** Resultado da última tentativa de melhorar uma foto (para diagnóstico). */
    fun lastAi(ctx: Context): String = prefs(ctx).getString("last_ai", "") ?: ""

    fun setLastAi(ctx: Context, v: String) {
        prefs(ctx).edit().putString("last_ai", v).apply()
    }

    /** "off", "auto", "natural" ou "forte". */
    fun hdrMode(ctx: Context): String = prefs(ctx).getString("hdr_mode", "auto") ?: "auto"

    fun setHdrMode(ctx: Context, v: String) {
        prefs(ctx).edit().putString("hdr_mode", v).apply()
    }

    /** "natural", "medio" ou "forte". */
    fun hdrIntensity(ctx: Context): String = prefs(ctx).getString("hdr_intensity", "natural") ?: "natural"

    fun setHdrIntensity(ctx: Context, v: String) {
        prefs(ctx).edit().putString("hdr_intensity", v).apply()
    }

    /** Melhorar selfies com IA (pode alterar rostos). Desligado por padrão. */
    fun aiFront(ctx: Context): Boolean = prefs(ctx).getBoolean("ai_front", false)

    fun setAiFront(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("ai_front", v).apply()
    }

    /** "auto" (liga só em celular forte), "on" ou "off". */
    fun zoomMap(ctx: Context): String = prefs(ctx).getString("zoom_map", "auto") ?: "auto"

    fun setZoomMap(ctx: Context, v: String) {
        prefs(ctx).edit().putString("zoom_map", v).apply()
    }


    /** Melhorar selfies com IA (padrão: desligado, a IA pode alterar rostos). */
    fun selfieAi(ctx: Context): Boolean = prefs(ctx).getBoolean("selfie_ai", false)

    fun setSelfieAi(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("selfie_ai", v).apply()
    }

}
