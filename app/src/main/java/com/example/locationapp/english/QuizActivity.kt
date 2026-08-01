package com.example.locationapp.english

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.locationapp.databinding.ActivityQuizBinding

/**
 * Режим теста: показывает английское слово и 4 варианта перевода.
 * За правильный ответ начисляется очко. В конце показывается результат и лучший счёт.
 */
class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private lateinit var progress: Progress
    private lateinit var deck: Deck
    private lateinit var order: List<Int>
    private lateinit var optionButtons: List<Button>

    private var index = 0
    private var score = 0
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progress = Progress(this)

        val deckId = intent.getStringExtra(DeckListActivity.EXTRA_DECK_ID)
        val found = deckId?.let { DeckRepository.deckById(it) }
        if (found == null || found.cards.size < 4) {
            finish()
            return
        }
        deck = found
        order = deck.cards.indices.shuffled()
        optionButtons = listOf(binding.option0, binding.option1, binding.option2, binding.option3)

        showQuestion()
    }

    private fun showQuestion() {
        answered = false
        val correctCard = deck.cards[order[index]]
        binding.progressText.text = "Вопрос ${index + 1} / ${order.size}   •   Очки: $score"
        binding.questionText.text = correctCard.term

        // Собираем варианты: правильный + 3 случайных неверных.
        val wrong = deck.cards
            .filter { it.translation != correctCard.translation }
            .shuffled()
            .take(3)
            .map { it.translation }
        val options = (wrong + correctCard.translation).shuffled()

        optionButtons.forEachIndexed { i, btn ->
            btn.text = options[i]
            btn.isEnabled = true
            btn.setBackgroundColor(0xFF3F6FE8.toInt())
            btn.setOnClickListener { onAnswer(btn, options[i], correctCard.translation) }
        }
    }

    private fun onAnswer(clicked: Button, chosen: String, correct: String) {
        if (answered) return
        answered = true

        optionButtons.forEach { it.isEnabled = false }
        if (chosen == correct) {
            score++
            clicked.setBackgroundColor(0xFF4CAF50.toInt())
        } else {
            clicked.setBackgroundColor(0xFFD9534F.toInt())
            // Подсвечиваем правильный вариант зелёным.
            optionButtons.firstOrNull { it.text.toString() == correct }
                ?.setBackgroundColor(0xFF4CAF50.toInt())
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
}
