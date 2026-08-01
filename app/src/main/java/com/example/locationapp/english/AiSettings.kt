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
        // Бесплатная модель OpenRouter по умолчанию (можно сменить в настройках).
        const val DEFAULT_OPENROUTER = "deepseek/deepseek-chat-v3-0324:free"

        /**
         * Популярные бесплатные модели OpenRouter для быстрого выбора.
         * Слаги со временем меняются — если какая-то даёт 404, попробуйте другую
         * или скопируйте точный id со страницы openrouter.ai/models (фильтр Free).
         */
        val OPENROUTER_FREE_PRESETS = listOf(
            "deepseek/deepseek-chat-v3-0324:free",
            "deepseek/deepseek-r1:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "qwen/qwen-2.5-72b-instruct:free",
            "mistralai/mistral-small-3.2-24b-instruct:free"
        )
    }
}
