package com.example.locationapp.english

import kotlin.random.Random

/** Режим одного вопроса в занятии. */
enum class Mode { FLASHCARD, CHOOSE_TRANSLATION, CHOOSE_WORD, TYPING, CLOZE, WORD_ORDER, MATCH }

/** Один вопрос занятия. */
data class Question(
    val cardIndex: Int,
    val card: Card,
    val mode: Mode,
    val options: List<String> = emptyList(),      // варианты (выбор / пропуск)
    val correctOption: String = "",               // правильный вариант
    val sentence: String = "",                    // предложение (пропуск / порядок слов)
    val tokens: List<String> = emptyList(),       // перемешанные слова (порядок слов)
    val batch: List<Pair<Int, Card>> = emptyList() // набор пар (поиск пар)
)

/**
 * Адаптивное занятие: режимы чередуются, слова выбираются с весом
 * (просроченные по SRS и проблемные — чаще, выученные — реже).
 */
class StudySession(val deck: Deck, private val progress: Progress) {

    private val modes = listOf(
        Mode.FLASHCARD,
        Mode.CHOOSE_TRANSLATION,
        Mode.CHOOSE_WORD,
        Mode.TYPING,
        Mode.CLOZE,
        Mode.WORD_ORDER,
        Mode.MATCH
    )
    private var modeCounter = Random.nextInt(modes.size)
    private var lastIndex = -1

    fun next(): Question {
        // Идём по циклу режимов; если режим сейчас неприменим — берём следующий.
        repeat(modes.size * 2) {
            val mode = modes[modeCounter % modes.size]
            modeCounter++
            val q = tryBuild(mode)
            if (q != null) {
                lastIndex = q.cardIndex
                return q
            }
        }
        val idx = pickWeightedIndex()
        lastIndex = idx
        return Question(idx, deck.cards[idx], Mode.FLASHCARD)
    }

    private fun tryBuild(mode: Mode): Question? = when (mode) {
        Mode.FLASHCARD -> {
            val i = pickWeightedIndex(); Question(i, deck.cards[i], Mode.FLASHCARD)
        }
        Mode.CHOOSE_TRANSLATION ->
            if (deck.cards.size >= 4) pickWeightedIndex().let { buildChoice(it, deck.cards[it], true) } else null
        Mode.CHOOSE_WORD ->
            if (deck.cards.size >= 4) pickWeightedIndex().let { buildChoice(it, deck.cards[it], false) } else null
        Mode.TYPING -> {
            val i = pickWeightedIndex(); Question(i, deck.cards[i], Mode.TYPING)
        }
        Mode.CLOZE -> buildCloze()
        Mode.WORD_ORDER -> buildWordOrder()
        Mode.MATCH -> buildMatch()
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

    /** Пропуск слова в примере (нужен пример, содержащий само слово). */
    private fun buildCloze(): Question? {
        if (deck.cards.size < 4) return null
        repeat(6) {
            val i = pickWeightedIndex()
            val card = deck.cards[i]
            val blanked = blank(card.example, card.term)
            if (blanked != null) {
                val distractors = deck.cards
                    .filter { it != card }.shuffled()
                    .map { it.term }.filter { it != card.term }.distinct().take(3)
                val options = (distractors + card.term).shuffled()
                return Question(i, card, Mode.CLOZE, options, card.term, sentence = blanked)
            }
        }
        return null
    }

    /** Собери предложение из слов (нужен пример из 2–8 слов). */
    private fun buildWordOrder(): Question? {
        repeat(6) {
            val i = pickWeightedIndex()
            val card = deck.cards[i]
            val ex = card.example.trim()
            if (ex.isNotBlank()) {
                val words = ex.split(Regex("\\s+"))
                if (words.size in 2..8) {
                    var shuffled = words.shuffled()
                    if (shuffled == words) shuffled = words.reversed()
                    return Question(i, card, Mode.WORD_ORDER, sentence = ex, tokens = shuffled)
                }
            }
        }
        return null
    }

    /** Поиск пар: батч из нескольких слов. */
    private fun buildMatch(): Question? {
        if (deck.cards.size < 4) return null
        val n = minOf(4, deck.cards.size)
        val idxs = pickBatch(n)
        val batch = idxs.map { it to deck.cards[it] }
        return Question(idxs.first(), deck.cards[idxs.first()], Mode.MATCH, batch = batch)
    }

    private fun pickBatch(n: Int): List<Int> {
        val set = LinkedHashSet<Int>()
        var guard = 0
        while (set.size < n && guard < 50) {
            set.add(pickWeightedIndex()); guard++
        }
        if (set.size < n) {
            for (i in deck.cards.indices.shuffled()) {
                set.add(i); if (set.size == n) break
            }
        }
        return set.toList()
    }

    private fun blank(example: String, term: String): String? {
        if (example.isBlank()) return null
        val idx = example.lowercase().indexOf(term.lowercase())
        if (idx < 0) return null
        return example.substring(0, idx) + "_____" + example.substring(idx + term.length)
    }

    private fun pickWeightedIndex(): Int {
        val stats = progress.stats(deck.id)
        val now = System.currentTimeMillis()
        val weights = deck.cards.indices.map { i ->
            val s = stats[i]
            var w: Double = when {
                s == null -> 1.5
                s.dueAt in 1..now -> 3.0 + s.wrong * 2.0
                s.dueAt == 0L -> 2.0
                else -> 0.3
            }
            if (i == lastIndex) w *= 0.15
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
