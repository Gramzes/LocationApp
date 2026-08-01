package com.example.locationapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.AiClient
import com.example.locationapp.english.AiSettings
import com.example.locationapp.english.Card as WordCard
import com.example.locationapp.english.DeckRepository
import com.example.locationapp.english.Mode
import com.example.locationapp.english.Progress
import com.example.locationapp.english.Question
import com.example.locationapp.english.Streak
import com.example.locationapp.english.StudySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudyScreen(deckId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val deck = remember(deckId) { DeckRepository.deckById(deckId) }
    if (deck == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val progress = remember { Progress(context) }
    val streak = remember { Streak(context) }
    val session = remember(deckId) { StudySession(deck, progress) }
    val aiClient = remember { AiClient(AiSettings(context)) }

    var question by remember { mutableStateOf(session.next()) }
    var answered by remember { mutableStateOf(0) }
    var correct by remember { mutableStateOf(0) }

    fun advance(isCorrect: Boolean) {
        session.record(question.cardIndex, isCorrect)
        streak.onAnswered()
        answered++
        if (isCorrect) correct++
        question = session.next()
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = deck.title) {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Готово", color = Color.White, fontSize = 14.sp) }
        }

        Text(
            text = "Верно: $correct из $answered",
            color = TextSecondary,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = TextAlign.Center
        )

        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            when (question.mode) {
                Mode.FLASHCARD -> FlashcardMode(
                    question = question,
                    aiClient = aiClient,
                    onResult = { advance(it) }
                )
                Mode.CHOOSE_TRANSLATION, Mode.CHOOSE_WORD -> ChoiceMode(
                    question = question,
                    onResult = { advance(it) }
                )
                Mode.TYPING -> TypingMode(
                    question = question,
                    session = session,
                    onResult = { advance(it) }
                )
            }
        }
    }
}

@Composable
private fun FlashcardMode(
    question: Question,
    aiClient: AiClient,
    onResult: (Boolean) -> Unit
) {
    val card = question.card
    var flipped by remember(question) { mutableStateOf(false) }
    var aiText by remember(question) { mutableStateOf("") }
    var aiLoading by remember(question) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speaker = LocalSpeaker.current

    // Автоозвучка английского слова при показе новой карточки.
    LaunchedEffect(question) { speaker?.speak(card.term) }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { flipped = !flipped },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (flipped) BrandLight else Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (flipped) card.translation else card.term,
                    color = TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                val example = if (flipped) "" else (aiText.ifEmpty { card.example })
                if (example.isNotEmpty()) {
                    Text(
                        text = example,
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                if (aiLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }
                Text(
                    text = "нажми, чтобы перевернуть",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Button(
                onClick = { speaker?.speak(card.term) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) { Text("🔊 Озвучить", fontSize = 15.sp) }
            Button(
                onClick = {
                    aiLoading = true
                    scope.launch {
                        val sys = "Ты помощник по изучению английского для русскоязычного ученика. Пиши кратко."
                        val prompt = "Придумай 2 простых примера предложений на английском со словом " +
                            "\"${card.term}\" (${card.translation}). После каждого дай перевод на русский в скобках. " +
                            "Только предложения, без вступления."
                        val res = withContext(Dispatchers.IO) { runCatching { aiClient.ask(sys, prompt) } }
                        aiLoading = false
                        res.onSuccess { aiText = it; flipped = false }
                            .onFailure { aiText = "⚠ ${it.message}"; flipped = false }
                    }
                },
                enabled = !aiLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) { Text("✨ Пример", fontSize = 15.sp) }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Button(
                onClick = { onResult(false) },
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) { Text("Учу ещё", fontSize = 16.sp) }
            Button(
                onClick = { onResult(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) { Text("Знаю ✓", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ChoiceMode(question: Question, onResult: (Boolean) -> Unit) {
    val prompt = if (question.mode == Mode.CHOOSE_TRANSLATION) "Выберите перевод" else "Выберите слово (англ.)"
    val shown = if (question.mode == Mode.CHOOSE_TRANSLATION) question.card.term else question.card.translation
    var selected by remember(question) { mutableStateOf<String?>(null) }

    if (selected != null) {
        LaunchedEffect(selected) {
            delay(900)
            onResult(selected == question.correctOption)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(prompt, color = TextMuted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            shown,
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp)
        )
        question.options.forEach { option ->
            val color = when {
                selected == null -> Brand
                option == question.correctOption -> Green
                option == selected -> Red
                else -> Brand
            }
            Button(
                onClick = { if (selected == null) selected = option },
                colors = ButtonDefaults.buttonColors(containerColor = color),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) { Text(option, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun TypingMode(
    question: Question,
    session: StudySession,
    onResult: (Boolean) -> Unit
) {
    val card: WordCard = question.card
    var input by remember(question) { mutableStateOf("") }
    var checked by remember(question) { mutableStateOf(false) }
    var isCorrect by remember(question) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Напишите по-английски", color = TextMuted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            card.translation,
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)
        )
        OutlinedTextField(
            value = input,
            onValueChange = { if (!checked) input = it },
            enabled = !checked,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (checked) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isCorrect) "✓ Верно!" else "✗ Правильно: ${card.term}",
                color = if (isCorrect) Green else Red,
                fontSize = 17.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = {
                if (!checked) {
                    isCorrect = session.checkTyped(card, input)
                    checked = true
                } else {
                    onResult(isCorrect)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) { Text(if (!checked) "Проверить" else "Дальше", fontSize = 16.sp) }
    }
}
