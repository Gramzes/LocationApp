package com.example.locationapp.english

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

/** Выполняет запрос к ИИ на фоновом потоке и возвращает результат в главный поток. */
object AiRunner {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(
        client: AiClient,
        systemPrompt: String?,
        userPrompt: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val result = client.ask(systemPrompt, userPrompt)
                mainHandler.post { onSuccess(result) }
            } catch (e: IOException) {
                mainHandler.post { onError(e.message ?: "Неизвестная ошибка") }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Неизвестная ошибка") }
            }
        }
    }
}
