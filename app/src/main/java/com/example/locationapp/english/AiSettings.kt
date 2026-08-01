package com.example.locationapp.english

import android.content.Context

/** Хранит настройки ИИ: API-ключ Anthropic и выбранную модель. */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
