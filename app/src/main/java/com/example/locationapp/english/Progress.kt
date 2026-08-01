package com.example.locationapp.english

import android.content.Context
import org.json.JSONObject

/**
 * Прогресс с интервальными повторениями (SRS, упрощённый Leitner/SM-2).
 * По каждой карточке хранит статистику ответов и дату следующего показа (dueAt),
 * чтобы выученные слова повторялись реже, а трудные — чаще и вовремя.
 */
class Progress(context: Context) {

    private val prefs = context.getSharedPreferences("english_progress_v2", Context.MODE_PRIVATE)

    data class Stat(
        var correct: Int = 0,
        var wrong: Int = 0,
        var reps: Int = 0,          // число верных ответов подряд (уровень ящика)
        var intervalDays: Int = 0,  // текущий интервал в днях
        var dueAt: Long = 0L        // когда карточку снова показать (0 = сразу)
    ) {
        val isLearned: Boolean get() = reps >= 3
        val isProblem: Boolean get() = wrong > 0 && wrong >= correct
    }

    private fun key(deckId: String) = "stats_$deckId"

    fun stats(deckId: String): MutableMap<Int, Stat> {
        val raw = prefs.getString(key(deckId), null) ?: return mutableMapOf()
        val result = mutableMapOf<Int, Stat>()
        try {
            val obj = JSONObject(raw)
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                result[k.toInt()] = Stat(
                    correct = o.optInt("c", 0),
                    wrong = o.optInt("w", 0),
                    reps = o.optInt("r", 0),
                    intervalDays = o.optInt("iv", 0),
                    dueAt = o.optLong("due", 0L)
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun save(deckId: String, map: Map<Int, Stat>) {
        val obj = JSONObject()
        for ((idx, s) in map) {
            obj.put(
                idx.toString(),
                JSONObject()
                    .put("c", s.correct)
                    .put("w", s.wrong)
                    .put("r", s.reps)
                    .put("iv", s.intervalDays)
                    .put("due", s.dueAt)
            )
        }
        prefs.edit().putString(key(deckId), obj.toString()).apply()
    }

    /** Интервал следующего показа по числу верных ответов подряд. */
    private fun intervalFor(reps: Int): Int = when (reps) {
        0, 1 -> 1
        2 -> 3
        3 -> 7
        4 -> 16
        5 -> 35
        else -> (35 * (1 shl (reps - 5))).coerceAtMost(365)
    }

    fun record(deckId: String, index: Int, correct: Boolean) {
        val map = stats(deckId)
        val s = map.getOrPut(index) { Stat() }
        val now = System.currentTimeMillis()
        if (correct) {
            s.correct++
            s.reps++
            s.intervalDays = intervalFor(s.reps)
            s.dueAt = now + s.intervalDays * DAY_MS
        } else {
            s.wrong++
            s.reps = 0
            s.intervalDays = 0
            s.dueAt = now + 60_000L // показать снова совсем скоро (в этой же сессии)
        }
        save(deckId, map)
    }

    fun learnedCount(deckId: String): Int = stats(deckId).values.count { it.isLearned }

    fun problemCount(deckId: String): Int = stats(deckId).values.count { it.isProblem }

    /** Сколько уже показанных карточек готовы к повторению прямо сейчас. */
    fun dueCount(deckId: String, now: Long = System.currentTimeMillis()): Int =
        stats(deckId).values.count { it.dueAt in 1..now }

    fun resetDeck(deckId: String) {
        prefs.edit().remove(key(deckId)).apply()
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
