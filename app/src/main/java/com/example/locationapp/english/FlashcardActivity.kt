package com.example.locationapp.english

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityFlashcardBinding

/**
 * Режим карточек: показывает английское слово, по тапу переворачивается на перевод.
 * Кнопки "Знаю" / "Учу ещё" отмечают карточку. Кнопка "Пример от ИИ" запрашивает у
 * нейросети примеры предложений с текущим словом.
 */
class FlashcardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlashcardBinding
    private lateinit var progress: Progress
    private lateinit var aiClient: AiClient
    private lateinit var deck: Deck
    private var order: List<Int> = emptyList()
    private var pos = 0
    private var showingTranslation = false
    private var aiExamples = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlashcardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)
        aiClient = AiClient(AiSettings(this))

        val deckId = intent.getStringExtra(DeckListActivity.EXTRA_DECK_ID)
        val found = deckId?.let { DeckRepository.deckById(it) }
        if (found == null) {
            finish()
            return
        }
        deck = found
        order = deck.cards.indices.shuffled()

        binding.card.setOnClickListener { flip() }
        binding.knowBtn.setOnClickListener { markAndNext(true) }
        binding.stillLearningBtn.setOnClickListener { markAndNext(false) }
        binding.aiExampleBtn.setOnClickListener { requestAiExamples() }

        showCard()
    }

    /** Текст примеров для английской стороны: сгенерированные ИИ или встроенный пример. */
    private fun termSideExample(card: Card) = if (aiExamples.isNotEmpty()) aiExamples else card.example

    private fun showCard() {
        showingTranslation = false
        aiExamples = ""
        val card = deck.cards[order[pos]]
        binding.counterText.text = "${pos + 1} / ${order.size}"
        binding.cardText.text = card.term
        binding.cardExample.text = card.example
        binding.card.setCardBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun flip() {
        val card = deck.cards[order[pos]]
        showingTranslation = !showingTranslation
        if (showingTranslation) {
            binding.cardText.text = card.translation
            binding.cardExample.text = ""
            binding.card.setCardBackgroundColor(0xFFEAF0FF.toInt())
        } else {
            binding.cardText.text = card.term
            binding.cardExample.text = termSideExample(card)
            binding.card.setCardBackgroundColor(0xFFFFFFFF.toInt())
        }
        binding.card.animate().alpha(0.4f).setDuration(90).withEndAction {
            binding.card.animate().alpha(1f).setDuration(90).start()
        }.start()
    }

    private fun requestAiExamples() {
        val card = deck.cards[order[pos]]
        binding.aiProgress.visibility = View.VISIBLE
        binding.aiExampleBtn.isEnabled = false
        binding.cardExample.text = ""

        val system = "Ты помощник по изучению английского для русскоязычного ученика. " +
            "Пиши кратко."
        val prompt = "Придумай 2 простых примера предложений на английском со словом " +
            "\"${card.term}\" (${card.translation}). После каждого предложения дай перевод на " +
            "русский в скобках. Только предложения, без вступления."

        AiRunner.run(aiClient, system, prompt,
            onSuccess = { text ->
                binding.aiProgress.visibility = View.GONE
                binding.aiExampleBtn.isEnabled = true
                aiExamples = text
                if (showingTranslation) {
                    Toast.makeText(this, "Примеры готовы — переверните карточку", Toast.LENGTH_SHORT).show()
                } else {
                    binding.cardExample.text = text
                }
            },
            onError = { err ->
                binding.aiProgress.visibility = View.GONE
                binding.aiExampleBtn.isEnabled = true
                binding.cardExample.text = "⚠ $err"
            }
        )
    }

    private fun markAndNext(known: Boolean) {
        progress.setLearned(deck.id, order[pos], known)
        if (pos < order.size - 1) {
            pos++
            showCard()
        } else {
            val learned = progress.learnedCount(deck.id)
            Toast.makeText(
                this,
                "Готово! Выучено $learned из ${deck.cards.size}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
}
