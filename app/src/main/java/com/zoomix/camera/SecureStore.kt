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

    /** Por padrão a original é mantida (ligado) até a IA ser validada nos seus testes. */
    fun keepOriginal(ctx: Context): Boolean = prefs(ctx).getBoolean("keep_original", true)

    fun setKeepOriginal(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("keep_original", v).apply()
    }

    /** "auto", "leve" ou "completo". */
    fun glassMode(ctx: Context): String = prefs(ctx).getString("glass_mode", "auto") ?: "auto"

    fun setGlassMode(ctx: Context, v: String) {
        prefs(ctx).edit().putString("glass_mode", v).apply()
    }
}
