package com.example.locationapp.english

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityQuizBinding

/**
 * Режим теста с выбором варианта. Поддерживает два направления:
 *  - EN→RU: показывается английское слово, выбираем русский перевод;
 *  - RU→EN: показывается русское слово, выбираем английское.
 */
class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private lateinit var progress: Progress
    private lateinit var deck: Deck
    private lateinit var order: List<Int>
    private lateinit var optionButtons: List<Button>

    private var ruToEn = false
    private var index = 0
    private var score = 0
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)

        val deckId = intent.getStringExtra(DeckListActivity.EXTRA_DECK_ID)
        ruToEn = intent.getBooleanExtra(EXTRA_RU_TO_EN, false)
        val found = deckId?.let { DeckRepository.deckById(it) }
        if (found == null || found.cards.size < 4) {
            finish()
            return
        }
        deck = found
        order = deck.cards.indices.shuffled()
        optionButtons = listOf(binding.option0, binding.option1, binding.option2, binding.option3)
        binding.promptLabel.text = if (ruToEn) "Выберите перевод (англ.)" else "Как переводится?"

        showQuestion()
    }

    /** Текст вопроса для карточки. */
    private fun question(card: Card) = if (ruToEn) card.translation else card.term

    /** Правильный ответ для карточки. */
    private fun answer(card: Card) = if (ruToEn) card.term else card.translation

    private fun showQuestion() {
        answered = false
        val correctCard = deck.cards[order[index]]
        binding.progressText.text = "Вопрос ${index + 1} / ${order.size}   •   Очки: $score"
        binding.questionText.text = question(correctCard)

        val correct = answer(correctCard)
        val wrong = deck.cards
            .filter { answer(it) != correct }
            .shuffled()
            .take(3)
            .map { answer(it) }
        val options = (wrong + correct).shuffled()

        optionButtons.forEachIndexed { i, btn ->
            btn.text = options[i]
            btn.isEnabled = true
            tint(btn, 0xFF4F6DF5.toInt())
            btn.setOnClickListener { onAnswer(btn, options[i], correct) }
        }
    }

    private fun onAnswer(clicked: Button, chosen: String, correct: String) {
        if (answered) return
        answered = true

        optionButtons.forEach { it.isEnabled = false }
        if (chosen == correct) {
            score++
            tint(clicked, 0xFF22B573.toInt())
        } else {
            tint(clicked, 0xFFE15241.toInt())
            optionButtons.firstOrNull { it.text.toString() == correct }
                ?.let { tint(it, 0xFF22B573.toInt()) }
        }
        binding.progressText.text = "Вопрос ${index + 1} / ${order.size}   •   Очки: $score"

        Handler(Looper.getMainLooper()).postDelayed({
            if (index < order.size - 1) {
                index++
                showQuestion()
            } else {
                finishQuiz()
            }
        }, 850)
    }

    private fun finishQuiz() {
        progress.updateBestQuizScore(deck.id, score)
        val best = progress.bestQuizScore(deck.id)
        AlertDialog.Builder(this)
            .setTitle("Тест завершён")
            .setMessage("Ваш результат: $score из ${order.size}\nЛучший результат: $best из ${order.size}")
            .setPositiveButton("Ещё раз") { _, _ ->
                index = 0
                score = 0
                order = deck.cards.indices.shuffled()
                showQuestion()
            }
            .setNegativeButton("Выйти") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun tint(btn: Button, color: Int) {
        btn.backgroundTintList = ColorStateList.valueOf(color)
    }

    companion object {
        const val EXTRA_RU_TO_EN = "ru_to_en"
    }
}
