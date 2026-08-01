package com.example.locationapp.english

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityTypingBinding

/**
 * Режим ввода: показывается русское слово, ученик вписывает английский перевод.
 * Ответ засчитывается без учёта регистра и необязательного "to " у глаголов.
 */
class TypingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTypingBinding
    private lateinit var progress: Progress
    private lateinit var deck: Deck
    private lateinit var order: List<Int>

    private var index = 0
    private var score = 0
    private var checked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTypingBinding.inflate(layoutInflater)
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

        binding.actionBtn.setOnClickListener { onAction() }
        showWord()
    }

    private fun showWord() {
        checked = false
        val card = deck.cards[order[index]]
        binding.progressText.text = "Слово ${index + 1} / ${order.size}   •   Очки: $score"
        binding.wordText.text = card.translation
        binding.answerInput.text?.clear()
        binding.answerInput.isEnabled = true
        binding.feedbackText.text = ""
        binding.actionBtn.text = "Проверить"
    }

    private fun onAction() {
        if (!checked) check() else next()
    }

    private fun check() {
        checked = true
        val card = deck.cards[order[index]]
        val given = normalize(binding.answerInput.text.toString())
        val expected = normalize(card.term)

        binding.answerInput.isEnabled = false
        if (given.isNotEmpty() && given == expected) {
            score++
            binding.feedbackText.setTextColor(0xFF22B573.toInt())
            binding.feedbackText.text = "✓ Верно!"
        } else {
            binding.feedbackText.setTextColor(0xFFE15241.toInt())
            binding.feedbackText.text = "✗ Правильно: ${card.term}"
        }
        binding.progressText.text = "Слово ${index + 1} / ${order.size}   •   Очки: $score"
        binding.actionBtn.text = if (index < order.size - 1) "Дальше" else "Завершить"
    }

    private fun next() {
        if (index < order.size - 1) {
            index++
            showWord()
        } else {
            hideKeyboard()
            progress.updateBestQuizScore(deck.id, score)
            val best = progress.bestQuizScore(deck.id)
            AlertDialog.Builder(this)
                .setTitle("Готово")
                .setMessage("Ваш результат: $score из ${order.size}\nЛучший результат: $best из ${order.size}")
                .setPositiveButton("Ещё раз") { _, _ ->
                    index = 0
                    score = 0
                    order = deck.cards.indices.shuffled()
                    showWord()
                }
                .setNegativeButton("Выйти") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    /** Приводит ответ к нормальной форме: нижний регистр, без пробелов по краям и без "to ". */
    private fun normalize(s: String): String =
        s.trim().lowercase().removePrefix("to ").trim()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.answerInput.windowToken, 0)
    }
}
