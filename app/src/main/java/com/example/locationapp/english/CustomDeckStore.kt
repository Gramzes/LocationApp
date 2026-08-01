package com.example.locationapp.english

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Хранилище пользовательских наборов карточек (в SharedPreferences как JSON). */
class CustomDeckStore(context: Context) {

    private val prefs = context.getSharedPreferences("custom_decks", Context.MODE_PRIVATE)

    fun all(): List<Deck> {
        val raw = prefs.getString("decks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val cardsArr = o.optJSONArray("cards") ?: JSONArray()
                val cards = (0 until cardsArr.length()).mapNotNull { j ->
                    val c = cardsArr.optJSONObject(j) ?: return@mapNotNull null
                    Card(c.optString("t"), c.optString("tr"), c.optString("ex"))
                }
                Deck(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    emoji = o.optString("emoji", "📝").ifBlank { "📝" },
                    cards = cards
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(decks: List<Deck>) {
        val arr = JSONArray()
        for (d in decks) {
            val cards = JSONArray()
            for (c in d.cards) {
                cards.put(JSONObject().put("t", c.term).put("tr", c.translation).put("ex", c.example))
            }
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("emoji", d.emoji)
                    .put("cards", cards)
            )
        }
        prefs.edit().putString("decks", arr.toString()).apply()
    }

    fun deckById(id: String): Deck? = all().firstOrNull { it.id == id }

    fun upsert(deck: Deck) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == deck.id }
        if (i >= 0) list[i] = deck else list.add(deck)
        save(list)
    }

    fun delete(id: String) {
        save(all().filterNot { it.id == id })
    }

    fun newId(): String = "custom_" + System.currentTimeMillis()

    companion object {
        fun isCustom(id: String) = id.startsWith("custom_")

        /** Разбор CSV/вставленного текста: строки «term,translation[,example]». */
        fun parseCsv(text: String): List<Card> {
            return text.lineSequence().mapNotNull { line ->
                val raw = line.trim()
                if (raw.isEmpty()) return@mapNotNull null
                val parts = raw.split('\t', ';', ',').map { it.trim() }
                val term = parts.getOrNull(0).orEmpty()
                val translation = parts.getOrNull(1).orEmpty()
                if (term.isBlank() || translation.isBlank()) return@mapNotNull null
                Card(term, translation, parts.getOrNull(2).orEmpty())
            }.toList()
        }
    }
}
