package com.example.locationapp.english

import kotlin.random.Random

/** Режим одного вопроса в занятии. */
enum class Mode { FLASHCARD, CHOOSE_TRANSLATION, CHOOSE_WORD, TYPING }

/** Один вопрос занятия. */
data class Question(
    val cardIndex: Int,
    val card: Card,
    val mode: Mode,
    val options: List<String> = emptyList(),
    val correctOption: String = ""
)

/**
 * Адаптивное занятие: режимы чередуются, а слова выбираются с весом —
 * проблемные (где было больше ошибок) выпадают чаще, а выученные — реже.
 */
class StudySession(val deck: Deck, private val progress: Progress) {

    private val modes = listOf(
        Mode.FLASHCARD,
        Mode.CHOOSE_TRANSLATION,
        Mode.CHOOSE_WORD,
        Mode.TYPING
    )
    private var modeCounter = Random.nextInt(modes.size)
    private var lastIndex = -1

    fun next(): Question {
        val idx = pickWeightedIndex()
        lastIndex = idx
        val card = deck.cards[idx]
        // Режимы с выбором варианта требуют минимум 4 карточек.
        val mode = nextMode(deck.cards.size >= 4)
        return when (mode) {
            Mode.CHOOSE_TRANSLATION -> buildChoice(idx, card, askTerm = true)
            Mode.CHOOSE_WORD -> buildChoice(idx, card, askTerm = false)
            else -> Question(idx, card, mode)
        }
    }

    private fun nextMode(allowChoice: Boolean): Mode {
        while (true) {
            val m = modes[modeCounter % modes.size]
            modeCounter++
            if (!allowChoice && (m == Mode.CHOOSE_TRANSLATION || m == Mode.CHOOSE_WORD)) continue
            return m
        }
    }

    /** askTerm=true: показываем англ. слово, выбираем перевод; иначе наоборот. */
    private fun buildChoice(idx: Int, card: Card, askTerm: Boolean): Question {
        val correct = if (askTerm) card.translation else card.term
        val distractors = deck.cards
            .filter { it != card }
            .shuffled()
            .map { if (askTerm) it.translation else it.term }
            .filter { it != correct }
            .distinct()
            .take(3)
        val options = (distractors + correct).shuffled()
        return Question(
            cardIndex = idx,
            card = card,
            mode = if (askTerm) Mode.CHOOSE_TRANSLATION else Mode.CHOOSE_WORD,
            options = options,
            correctOption = correct
        )
    }

    private fun pickWeightedIndex(): Int {
        val stats = progress.stats(deck.id)
        val now = System.currentTimeMillis()
        val weights = deck.cards.indices.map { i ->
            val s = stats[i]
            var w: Double = when {
                s == null -> 1.5                     // новое слово — вводим в оборот
                s.dueAt in 1..now -> 3.0 + s.wrong * 2.0  // пора повторить (проблемные — чаще)
                s.dueAt == 0L -> 2.0                 // ещё не планировалось
                else -> 0.3                          // ещё рано повторять
            }
            if (i == lastIndex) w *= 0.15            // избегаем повтора подряд
            w.coerceAtLeast(0.05)
        }
        val total = weights.sum()
        var r = Random.nextDouble() * total
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0) return i
        }
        return weights.indices.last
    }

    fun record(cardIndex: Int, correct: Boolean) = progress.record(deck.id, cardIndex, correct)

    /** Нормализует ответ для режима ввода: нижний регистр, без пробелов и "to ". */
    fun checkTyped(card: Card, typed: String): Boolean {
        fun norm(s: String) = s.trim().lowercase().removePrefix("to ").trim()
        return typed.isNotBlank() && norm(typed) == norm(card.term)
    }
}
