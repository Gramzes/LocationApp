package com.example.locationapp.english

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Счётчик серии занятий (стрик), дневная цель и история активности по дням
 * (для тепловой карты и статистики).
 */
class Streak(context: Context) {

    private val prefs = context.getSharedPreferences("streak", Context.MODE_PRIVATE)

    var dailyGoal: Int
        get() = prefs.getInt("goal", 20).coerceAtLeast(1)
        set(v) = prefs.edit().putInt("goal", v.coerceAtLeast(1)).apply()

    private fun dayId(cal: Calendar): Int =
        cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)

    private fun today() = dayId(Calendar.getInstance())
    private fun yesterday() = dayId(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })

    /** Вызывать на каждый ответ во время занятия. */
    fun onAnswered() {
        val today = today()
        val last = prefs.getInt("last_day", 0)
        var streak = prefs.getInt("streak", 0)
        var count = prefs.getInt("today_count", 0)
        if (last != today) {
            streak = if (last == yesterday()) streak + 1 else 1
            count = 0
        }
        count += 1
        val best = maxOf(prefs.getInt("best", 0), streak)

        val hist = history().apply { put(today.toString(), (optInt(today.toString(), 0)) + 1) }
        pruneHistory(hist)

        prefs.edit()
            .putInt("last_day", today)
            .putInt("streak", streak)
            .putInt("today_count", count)
            .putInt("best", best)
            .putString("hist", hist.toString())
            .apply()
    }

    fun currentStreak(): Int {
        val last = prefs.getInt("last_day", 0)
        return if (last == today() || last == yesterday()) prefs.getInt("streak", 0) else 0
    }

    fun bestStreak(): Int = prefs.getInt("best", 0)

    fun todayCount(): Int =
        if (prefs.getInt("last_day", 0) == today()) prefs.getInt("today_count", 0) else 0

    private fun history(): JSONObject =
        try {
            JSONObject(prefs.getString("hist", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

    /** Количество ответов в конкретный день (dayId = ГГГГММДД в числовом виде). */
    fun countForDay(dayId: Int): Int = history().optInt(dayId.toString(), 0)

    /** dayId для дня «daysAgo» дней назад (0 = сегодня). */
    fun dayIdDaysAgo(daysAgo: Int): Int =
        dayId(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -daysAgo) })

    private fun pruneHistory(hist: JSONObject) {
        // Оставляем только последние ~180 дней.
        val cutoff = dayIdDaysAgo(180)
        val toRemove = hist.keys().asSequence().filter { (it.toIntOrNull() ?: 0) < cutoff }.toList()
        toRemove.forEach { hist.remove(it) }
    }
}
