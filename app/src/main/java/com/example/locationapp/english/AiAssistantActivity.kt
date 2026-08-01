package com.example.locationapp.english

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityAiAssistantBinding

/** Экран ИИ-помощника: разбор произвольного слова или фразы. */
class AiAssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiAssistantBinding
    private lateinit var client: AiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)
        client = AiClient(AiSettings(this))

        binding.analyzeBtn.setOnClickListener { analyze() }
    }

    private fun analyze() {
        val word = binding.wordInput.text.toString().trim()
        if (word.isEmpty()) {
            binding.resultText.text = "Введите слово или фразу."
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.analyzeBtn.isEnabled = false
        binding.resultText.text = ""

        val system = "Ты помощник по изучению английского для русскоязычного ученика. " +
            "Отвечай кратко, понятно и на русском (кроме самих английских примеров)."
        val prompt = """
            Разбери английское слово или фразу: "$word".
            Дай ответ в таком виде:
            1. Перевод на русский.
            2. Три примера предложений на английском с переводом на русский.
            3. 2–3 синонима на английском.
            Без лишнего вступления.
        """.trimIndent()

        AiRunner.run(client, system, prompt,
            onSuccess = { text ->
                binding.progress.visibility = View.GONE
                binding.analyzeBtn.isEnabled = true
                binding.resultText.text = text
            },
            onError = { err ->
                binding.progress.visibility = View.GONE
                binding.analyzeBtn.isEnabled = true
                binding.resultText.text = "⚠ $err"
            }
        )
    }
}
