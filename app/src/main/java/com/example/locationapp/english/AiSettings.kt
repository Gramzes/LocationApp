package com.example.locationapp.english

import android.content.Context

enum class AiProvider { ANTHROPIC, OPENROUTER }

/**
 * Настройки ИИ: выбранный провайдер и, для каждого, ключ и модель.
 * Всё хранится только на устройстве.
 */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings_v2", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() = runCatching { AiProvider.valueOf(prefs.getString("provider", AiProvider.OPENROUTER.name)!!) }
            .getOrDefault(AiProvider.OPENROUTER)
        set(value) = prefs.edit().putString("provider", value.name).apply()

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", "") ?: ""
        set(v) = prefs.edit().putString("anthropic_key", v.trim()).apply()

    var anthropicModel: String
        get() = prefs.getString("anthropic_model", DEFAULT_ANTHROPIC)!!.ifBlank { DEFAULT_ANTHROPIC }
        set(v) = prefs.edit().putString("anthropic_model", v.trim().ifBlank { DEFAULT_ANTHROPIC }).apply()

    var openRouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(v) = prefs.edit().putString("openrouter_key", v.trim()).apply()

    var openRouterModel: String
        get() = prefs.getString("openrouter_model", DEFAULT_OPENROUTER)!!.ifBlank { DEFAULT_OPENROUTER }
        set(v) = prefs.edit().putString("openrouter_model", v.trim().ifBlank { DEFAULT_OPENROUTER }).apply()

    val currentKey: String
        get() = if (provider == AiProvider.ANTHROPIC) anthropicKey else openRouterKey

    val currentModel: String
        get() = if (provider == AiProvider.ANTHROPIC) anthropicModel else openRouterModel

    val isConfigured: Boolean
        get() = currentKey.isNotBlank()

    companion object {
        const val DEFAULT_ANTHROPIC = "claude-opus-5"
        // Бесплатная модель OpenRouter (можно сменить в настройках).
        const val DEFAULT_OPENROUTER = "meta-llama/llama-3.3-70b-instruct:free"
    }
}
