package com.example.locationapp.english

import android.content.Context
import java.util.Calendar

/**
 * Счётчик серии занятий (стрик) и дневная цель.
 * Стрик растёт на +1 при первом ответе в новый день, если накануне тоже занимались.
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
        prefs.edit()
            .putInt("last_day", today)
            .putInt("streak", streak)
            .putInt("today_count", count)
            .apply()
    }

    /** Текущая серия (0, если вчера/сегодня не занимались — серия прервана). */
    fun currentStreak(): Int {
        val last = prefs.getInt("last_day", 0)
        return if (last == today() || last == yesterday()) prefs.getInt("streak", 0) else 0
    }

    /** Сколько ответов дано сегодня. */
    fun todayCount(): Int =
        if (prefs.getInt("last_day", 0) == today()) prefs.getInt("today_count", 0) else 0
}
