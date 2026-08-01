package com.example.locationapp.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Простая обёртка над Android TextToSpeech для озвучки английских слов. */
class Speaker(context: Context) {

    private var ready = false
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                ready = true
            }
        }
    }

    fun speak(text: String) {
        val e = engine ?: return
        if (text.isBlank()) return
        if (ready) e.language = Locale.US
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

/** Доступ к озвучке из любого Composable. */
val LocalSpeaker = staticCompositionLocalOf<Speaker?> { null }
