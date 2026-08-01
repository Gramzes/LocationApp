package com.example.locationapp.english

/** Встроенные наборы карточек для изучения английского. */
object DeckRepository {

    val decks: List<Deck> = listOf(
        Deck(
            id = "basic",
            title = "Базовые слова",
            emoji = "🔤",
            cards = listOf(
                Card("hello", "привет", "Hello! How are you?"),
                Card("goodbye", "до свидания", "Goodbye, see you tomorrow."),
                Card("please", "пожалуйста", "Please, help me."),
                Card("thank you", "спасибо", "Thank you very much!"),
                Card("yes", "да"),
                Card("no", "нет"),
                Card("sorry", "извините", "Sorry, I'm late."),
                Card("friend", "друг", "He is my best friend."),
                Card("family", "семья", "I love my family."),
                Card("water", "вода", "Can I have some water?"),
                Card("house", "дом", "This is my house."),
                Card("time", "время", "What time is it?")
            )
        ),
        Deck(
            id = "verbs",
            title = "Частые глаголы",
            emoji = "🏃",
            cards = listOf(
                Card("to be", "быть", "I want to be a doctor."),
                Card("to have", "иметь", "I have a car."),
                Card("to do", "делать", "What do you do?"),
                Card("to go", "идти, ехать", "Let's go home."),
                Card("to make", "делать, создавать", "Make a decision."),
                Card("to know", "знать", "I know the answer."),
                Card("to think", "думать", "I think you are right."),
                Card("to take", "брать", "Take your time."),
                Card("to see", "видеть", "I can see you."),
                Card("to come", "приходить", "Come here, please."),
                Card("to want", "хотеть", "I want some coffee."),
                Card("to give", "давать", "Give me a hand.")
            )
        ),
        Deck(
            id = "travel",
            title = "Путешествия",
            emoji = "✈️",
            cards = listOf(
                Card("airport", "аэропорт", "We arrived at the airport early."),
                Card("ticket", "билет", "I bought a ticket to London."),
                Card("luggage", "багаж", "Where is my luggage?"),
                Card("passport", "паспорт", "Show me your passport, please."),
                Card("hotel", "отель", "The hotel is near the beach."),
                Card("map", "карта", "Let's look at the map."),
                Card("station", "станция, вокзал", "The train station is far."),
                Card("flight", "рейс", "My flight is delayed."),
                Card("border", "граница", "We crossed the border at night."),
                Card("journey", "путешествие", "Have a safe journey!")
            )
        ),
        Deck(
            id = "food",
            title = "Еда и напитки",
            emoji = "🍎",
            cards = listOf(
                Card("apple", "яблоко", "An apple a day keeps the doctor away."),
                Card("bread", "хлеб", "I bought fresh bread."),
                Card("cheese", "сыр", "I like cheese on pizza."),
                Card("meat", "мясо", "She doesn't eat meat."),
                Card("vegetable", "овощ", "Eat more vegetables."),
                Card("breakfast", "завтрак", "Breakfast is ready."),
                Card("dinner", "ужин", "We had dinner together."),
                Card("delicious", "вкусный", "This cake is delicious!"),
                Card("spicy", "острый", "The soup is too spicy."),
                Card("juice", "сок", "A glass of orange juice, please.")
            )
        ),
        Deck(
            id = "business",
            title = "Деловой английский",
            emoji = "💼",
            cards = listOf(
                Card("meeting", "встреча, совещание", "The meeting starts at 10."),
                Card("deadline", "крайний срок", "We must meet the deadline."),
                Card("report", "отчёт", "Please send me the report."),
                Card("colleague", "коллега", "She is my colleague."),
                Card("salary", "зарплата", "He got a higher salary."),
                Card("contract", "договор, контракт", "Sign the contract here."),
                Card("customer", "клиент", "The customer is always right."),
                Card("profit", "прибыль", "The company made a profit."),
                Card("schedule", "расписание, график", "Check your schedule."),
                Card("goal", "цель", "Our goal is to grow fast.")
            )
        )
    )

    fun deckById(id: String): Deck? = decks.firstOrNull { it.id == id }
}
