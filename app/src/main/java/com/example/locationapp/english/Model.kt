package com.example.locationapp.english

/** Одна карточка: английский термин и его перевод, плюс необязательный пример. */
data class Card(
    val term: String,       // английское слово/фраза
    val translation: String, // перевод на русский
    val example: String = "" // пример употребления (необязательно)
)

/** Набор карточек ("модуль" в терминах Quizlet). */
data class Deck(
    val id: String,
    val title: String,
    val emoji: String,
    val cards: List<Card>
)
