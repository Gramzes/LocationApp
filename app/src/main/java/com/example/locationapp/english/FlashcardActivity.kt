package com.example.locationapp.english

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityFlashcardBinding

/**
 * Режим карточек: показывает английское слово, по тапу переворачивается на перевод.
 * Кнопки "Знаю" / "Учу ещё" отмечают карточку и переходят к следующей.
 */
class FlashcardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlashcardBinding
    private lateinit var progress: Progress
    private lateinit var deck: Deck
    private var order: List<Int> = emptyList()
    private var pos = 0
    private var showingTranslation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlashcardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)

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

        showCard()
    }

    private fun showCard() {
        showingTranslation = false
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
            binding.card.setCardBackgroundColor(0xFFEAF1FF.toInt())
        } else {
            binding.cardText.text = card.term
            binding.cardExample.text = card.example
            binding.card.setCardBackgroundColor(0xFFFFFFFF.toInt())
        }
        // Небольшая анимация переворота.
        binding.card.animate().rotationYBy(0f).alpha(0.4f).setDuration(90).withEndAction {
            binding.card.animate().alpha(1f).setDuration(90).start()
        }.start()
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
