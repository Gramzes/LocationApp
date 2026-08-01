package com.example.locationapp.english

import android.content.Context
import org.json.JSONObject

/**
 * Прогресс изучения с пословной статистикой.
 * Для каждого набора хранит по каждой карточке число верных и неверных ответов —
 * это позволяет показывать проблемные слова чаще.
 */
class Progress(context: Context) {

    private val prefs = context.getSharedPreferences("english_progress_v2", Context.MODE_PRIVATE)

    data class Stat(var correct: Int = 0, var wrong: Int = 0) {
        /** Слово считается выученным после 2+ верных ответов при перевесе над ошибками. */
        val isLearned: Boolean get() = correct - wrong >= 2
        /** Проблемное — если ошибок больше, чем верных ответов. */
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
                result[k.toInt()] = Stat(o.optInt("c", 0), o.optInt("w", 0))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun save(deckId: String, map: Map<Int, Stat>) {
        val obj = JSONObject()
        for ((idx, s) in map) {
            obj.put(idx.toString(), JSONObject().put("c", s.correct).put("w", s.wrong))
        }
        prefs.edit().putString(key(deckId), obj.toString()).apply()
    }

    fun record(deckId: String, index: Int, correct: Boolean) {
        val map = stats(deckId)
        val s = map.getOrPut(index) { Stat() }
        if (correct) s.correct++ else s.wrong++
        save(deckId, map)
    }

    fun learnedCount(deckId: String): Int = stats(deckId).values.count { it.isLearned }

    fun problemCount(deckId: String): Int = stats(deckId).values.count { it.isProblem }

    fun resetDeck(deckId: String) {
        prefs.edit().remove(key(deckId)).apply()
    }
}
