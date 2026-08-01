package com.example.locationapp.english

import android.content.Context

/**
 * Хранит прогресс изучения в SharedPreferences.
 * Для каждого набора помним, какие карточки (по индексу) отмечены как выученные,
 * а также лучший результат теста.
 */
class Progress(context: Context) {

    private val prefs = context.getSharedPreferences("english_progress", Context.MODE_PRIVATE)

    private fun learnedKey(deckId: String) = "learned_$deckId"
    private fun bestKey(deckId: String) = "best_$deckId"

    /** Множество индексов выученных карточек в наборе. */
    fun learnedIndices(deckId: String): MutableSet<Int> {
        val raw = prefs.getStringSet(learnedKey(deckId), emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    fun setLearned(deckId: String, index: Int, learned: Boolean) {
        val set = learnedIndices(deckId)
        if (learned) set.add(index) else set.remove(index)
        prefs.edit()
            .putStringSet(learnedKey(deckId), set.map { it.toString() }.toSet())
            .apply()
    }

    fun learnedCount(deckId: String): Int = learnedIndices(deckId).size

    fun resetDeck(deckId: String) {
        prefs.edit().remove(learnedKey(deckId)).apply()
    }

    fun bestQuizScore(deckId: String): Int = prefs.getInt(bestKey(deckId), 0)

    fun updateBestQuizScore(deckId: String, score: Int) {
        if (score > bestQuizScore(deckId)) {
            prefs.edit().putInt(bestKey(deckId), score).apply()
        }
    }
}
