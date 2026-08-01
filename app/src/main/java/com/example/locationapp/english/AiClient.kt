package com.example.locationapp.english

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент для двух ИИ-провайдеров:
 *  - Anthropic Messages API (x-api-key);
 *  - OpenRouter Chat Completions (Authorization: Bearer, есть бесплатные модели).
 *
 * Прямые HTTPS-запросы через HttpsURLConnection — мобильному клиенту не нужен
 * тяжёлый серверный SDK. Все методы блокирующие: вызывайте на фоне.
 */
class AiClient(private val settings: AiSettings) {

    @Throws(IOException::class)
    fun ask(systemPrompt: String?, userPrompt: String): String {
        if (!settings.isConfigured) {
            throw IOException("Не указан API-ключ. Откройте настройки (⚙) и вставьте ключ выбранного провайдера.")
        }
        return when (settings.provider) {
            AiProvider.ANTHROPIC -> askAnthropic(systemPrompt, userPrompt)
            AiProvider.OPENROUTER -> askOpenRouter(systemPrompt, userPrompt)
        }
    }

    // --- Anthropic ---
    private fun askAnthropic(systemPrompt: String?, userPrompt: String): String {
        val body = JSONObject().apply {
            put("model", settings.anthropicModel)
            put("max_tokens", 1024)
            put("output_config", JSONObject().put("effort", "low"))
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        }
        val conn = open("https://api.anthropic.com/v1/messages")
        conn.setRequestProperty("x-api-key", settings.anthropicKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        return send(conn, body) { json ->
            val obj = JSONObject(json)
            if (obj.optString("stop_reason") == "refusal") return@send "Модель отклонила запрос."
            val content = obj.optJSONArray("content") ?: return@send "(пустой ответ)"
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            sb.toString().trim().ifEmpty { "(пустой ответ)" }
        }
    }

    // --- OpenRouter ---
    private fun askOpenRouter(systemPrompt: String?, userPrompt: String): String {
        val messages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))
        val body = JSONObject().apply {
            put("model", settings.openRouterModel)
            put("messages", messages)
        }
        val conn = open("https://openrouter.ai/api/v1/chat/completions")
        conn.setRequestProperty("Authorization", "Bearer ${settings.openRouterKey}")
        conn.setRequestProperty("HTTP-Referer", "https://github.com/Gramzes/LocationApp")
        conn.setRequestProperty("X-Title", "English Flashcards")
        return send(conn, body) { json ->
            val obj = JSONObject(json)
            val choices = obj.optJSONArray("choices")
            val msg = choices?.optJSONObject(0)?.optJSONObject("message")
            (msg?.optString("content") ?: "(пустой ответ)").trim().ifEmpty { "(пустой ответ)" }
        }
    }

    // --- Общая отправка ---
    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("content-type", "application/json")
        }
    }

    private fun send(conn: HttpURLConnection, body: JSONObject, parse: (String) -> String): String {
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { readAll(it) } ?: ""
            if (code !in 200..299) throw IOException(parseError(text, code))
            return parse(text)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Ошибка сети: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

    private fun parseError(json: String, code: Int): String {
        val friendly = when (code) {
            401 -> "Неверный API-ключ (401). Проверьте ключ в настройках."
            402 -> "Недостаточно средств/кредитов (402)."
            403 -> "Нет доступа (403)."
            404 -> "Модель не найдена (404). Проверьте название модели."
            429 -> "Слишком много запросов (429). Попробуйте позже."
            in 500..599 -> "Сервис временно недоступен ($code)."
            else -> null
        }
        val detail = try {
            val err = JSONObject(json).opt("error")
            when (err) {
                is JSONObject -> err.optString("message")
                is String -> err
                else -> null
            }
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() }
        val base = friendly ?: "Ошибка запроса ($code)"
        // Показываем и точный текст от провайдера — по нему видно настоящую причину
        // (например, «No endpoints found» = у бесплатных моделей выключена приватность).
        return if (detail != null && detail != base) "$base\n\n$detail" else base
    }
}
