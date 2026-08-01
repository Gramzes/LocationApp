package com.example.locationapp.english

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Минимальный клиент Anthropic Messages API для Android.
 *
 * Обращается напрямую к https://api.anthropic.com/v1/messages через HttpsURLConnection
 * с ключом пользователя — так мобильному клиенту не нужен тяжёлый серверный SDK.
 * Все вызовы блокирующие: выполняйте их на фоновом потоке.
 */
class AiClient(private val settings: AiSettings) {

    /**
     * Отправляет один запрос к модели и возвращает текст ответа.
     * Бросает [IOException] с понятным сообщением при ошибке.
     */
    @Throws(IOException::class)
    fun ask(systemPrompt: String?, userPrompt: String): String {
        if (!settings.isConfigured) {
            throw IOException("Не указан API-ключ. Откройте настройки (⚙) и вставьте ключ Anthropic.")
        }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", 1024)
            put("output_config", JSONObject().put("effort", "low"))
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", userPrompt)
                )
            )
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", settings.apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { readAll(it) } ?: ""

            if (code !in 200..299) {
                throw IOException(parseError(text, code))
            }
            return parseText(text)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Ошибка сети: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: java.io.InputStream): String {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            return reader.readText()
        }
    }

    /** Достаёт текст из блоков content (пропускает блоки thinking и т.п.). */
    private fun parseText(json: String): String {
        val obj = JSONObject(json)
        if (obj.optString("stop_reason") == "refusal") {
            return "Модель отклонила запрос по соображениям безопасности."
        }
        val content = obj.optJSONArray("content") ?: return "(пустой ответ)"
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                sb.append(block.optString("text"))
            }
        }
        val result = sb.toString().trim()
        return result.ifEmpty { "(пустой ответ)" }
    }

    private fun parseError(json: String, code: Int): String {
        val friendly = when (code) {
            401 -> "Неверный API-ключ (401). Проверьте ключ в настройках."
            403 -> "Нет доступа (403). Ключ не имеет прав на эту модель."
            404 -> "Модель не найдена (404). Проверьте название модели в настройках."
            429 -> "Слишком много запросов (429). Попробуйте позже."
            in 500..599 -> "Сервис временно недоступен ($code). Попробуйте позже."
            else -> null
        }
        val detail = try {
            JSONObject(json).optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
        return friendly ?: detail ?: "Ошибка запроса ($code)"
    }
}
