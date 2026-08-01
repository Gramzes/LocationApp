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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateListOf
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
    var answeredCount by remember { mutableStateOf(0) }
    var correctCount by remember { mutableStateOf(0) }

    fun recordCard(idx: Int, ok: Boolean) {
        session.record(idx, ok)
        streak.onAnswered()
        answeredCount++
        if (ok) correctCount++
    }

    fun advance() {
        question = session.next()
    }

    // Для одиночных карточек: записать текущую карточку и перейти дальше.
    val onResult: (Boolean) -> Unit = { ok ->
        recordCard(question.cardIndex, ok)
        advance()
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
            text = "Верно: $correctCount из $answeredCount",
            color = TextSecondary,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = TextAlign.Center
        )

        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            when (question.mode) {
                Mode.FLASHCARD -> FlashcardMode(question, aiClient, onResult)
                Mode.CHOOSE_TRANSLATION -> OptionsMode(
                    prompt = "Выберите перевод",
                    question = question,
                    speakText = question.card.term,
                    stimulus = question.card.term,
                    onResult = onResult
                )
                Mode.CHOOSE_WORD -> OptionsMode(
                    prompt = "Выберите слово (англ.)",
                    question = question,
                    speakText = null,
                    stimulus = question.card.translation,
                    onResult = onResult
                )
                Mode.CLOZE -> OptionsMode(
                    prompt = "Вставьте пропущенное слово",
                    question = question,
                    speakText = null,
                    stimulus = question.sentence,
                    stimulusSize = 20.sp,
                    onResult = onResult
                )
                Mode.TYPING -> TypingMode(question, session, onResult)
                Mode.WORD_ORDER -> WordOrderMode(question, onResult)
                Mode.MATCH -> MatchMode(question, ::recordCard, ::advance)
            }
        }
    }
}

/* ---------- Общие элементы (единый вид режимов) ---------- */

@Composable
private fun PromptLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
    )
}

@Composable
private fun StimulusCard(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 30.sp,
    speak: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = TextPrimary,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (speak != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(BrandLight, RoundedCornerShape(10.dp))
                        .clickable { speak() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("🔊", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun OptionButton(text: String, container: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) { Text(text, fontSize = 16.sp) }
}

/* ---------- Режимы ---------- */

@Composable
private fun FlashcardMode(question: Question, aiClient: AiClient, onResult: (Boolean) -> Unit) {
    val card = question.card
    var flipped by remember(question) { mutableStateOf(false) }
    var aiText by remember(question) { mutableStateOf("") }
    var aiLoading by remember(question) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speaker = LocalSpeaker.current

    LaunchedEffect(question) { speaker?.speak(card.term) }

    Column(modifier = Modifier.fillMaxSize()) {
        PromptLabel("Карточка — нажмите, чтобы перевернуть")
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).clickable { flipped = !flipped },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (flipped) BrandLight else Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                val example = if (flipped) "" else aiText.ifEmpty { card.example }
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
                if (aiLoading) CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
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

/** Универсальный режим «стимул + варианты» (выбор перевода/слова и пропуск). */
@Composable
private fun OptionsMode(
    prompt: String,
    question: Question,
    speakText: String?,
    stimulus: String,
    stimulusSize: androidx.compose.ui.unit.TextUnit = 30.sp,
    onResult: (Boolean) -> Unit
) {
    val speaker = LocalSpeaker.current
    var selected by remember(question) { mutableStateOf<String?>(null) }

    if (speakText != null) LaunchedEffect(question) { speaker?.speak(speakText) }
    if (selected != null) {
        LaunchedEffect(selected) {
            delay(850)
            onResult(selected == question.correctOption)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PromptLabel(prompt)
        StimulusCard(
            text = stimulus,
            fontSize = stimulusSize,
            speak = if (speakText != null) ({ speaker?.speak(speakText) }) else null
        )
        Spacer(Modifier.height(24.dp))
        question.options.forEach { option ->
            val color = when {
                selected == null -> Brand
                option == question.correctOption -> Green
                option == selected -> Red
                else -> Brand
            }
            OptionButton(option, color, enabled = selected == null) {
                if (selected == null) selected = option
            }
        }
    }
}

@Composable
private fun TypingMode(question: Question, session: StudySession, onResult: (Boolean) -> Unit) {
    val card = question.card
    var input by remember(question) { mutableStateOf("") }
    var checked by remember(question) { mutableStateOf(false) }
    var isCorrect by remember(question) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PromptLabel("Напишите по-английски")
        StimulusCard(text = card.translation)
        Spacer(Modifier.height(20.dp))
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
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
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

@Composable
private fun WordOrderMode(question: Question, onResult: (Boolean) -> Unit) {
    val tokens = question.tokens
    val correctWords = remember(question) { question.sentence.split(Regex("\\s+")) }
    val used = remember(question) { mutableStateListOf<Int>() }
    var checked by remember(question) { mutableStateOf(false) }
    var isCorrect by remember(question) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PromptLabel("Соберите предложение")

        // Собранное предложение (тап по слову — убрать).
        val assembled = used.map { tokens[it] }
        StimulusCard(text = assembled.joinToString(" ").ifEmpty { "…" }, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        ChipRows(items = used.indices.toList(), label = { used[it].let { t -> tokens[t] } }) { pos ->
            if (!checked) used.removeAt(pos)
        }

        Spacer(Modifier.height(16.dp))
        Text("Доступные слова:", color = TextMuted, fontSize = 13.sp)
        val available = tokens.indices.filter { it !in used }
        ChipRows(items = available, label = { tokens[it] }) { tokenIdx ->
            if (!checked) used.add(tokenIdx)
        }

        if (checked) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (isCorrect) "✓ Верно!" else "✗ Правильно: ${question.sentence}",
                color = if (isCorrect) Green else Red,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                if (!checked) {
                    isCorrect = assembled == correctWords
                    checked = true
                } else {
                    onResult(isCorrect)
                }
            },
            enabled = checked || used.size == tokens.size,
            colors = ButtonDefaults.buttonColors(containerColor = Brand),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text(if (!checked) "Проверить" else "Дальше", fontSize = 16.sp) }
    }
}

/** Ряды «чипов»-слов (перенос по 3 в ряд). */
@Composable
private fun ChipRows(items: List<Int>, label: (Int) -> String, onClick: (Int) -> Unit) {
    Column {
        items.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                row.forEach { item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .background(BrandLight, RoundedCornerShape(10.dp))
                            .clickable { onClick(item) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label(item), color = BrandDark, fontSize = 15.sp) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MatchMode(question: Question, onCard: (Int, Boolean) -> Unit, onAdvance: () -> Unit) {
    val batch = question.batch // List<Pair<Int, Card>>
    val rightOrder = remember(question) { batch.indices.shuffled() }
    var selectedLeft by remember(question) { mutableStateOf<Int?>(null) }
    var wrongPos by remember(question) { mutableStateOf<Int?>(null) }
    val matched = remember(question) { mutableStateListOf<Int>() }

    if (matched.size == batch.size) {
        LaunchedEffect(Unit) { delay(500); onAdvance() }
    }
    if (wrongPos != null) {
        LaunchedEffect(wrongPos) { delay(400); wrongPos = null }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PromptLabel("Найдите пары")
        Row(modifier = Modifier.fillMaxWidth()) {
            // Левая колонка — английские слова.
            Column(modifier = Modifier.weight(1f)) {
                batch.indices.forEach { pos ->
                    MatchTile(
                        text = batch[pos].second.term,
                        done = matched.contains(pos),
                        selected = selectedLeft == pos,
                        wrong = false
                    ) { if (!matched.contains(pos)) selectedLeft = pos }
                }
            }
            Spacer(Modifier.width(12.dp))
            // Правая колонка — переводы (в перемешанном порядке).
            Column(modifier = Modifier.weight(1f)) {
                rightOrder.forEach { pos ->
                    MatchTile(
                        text = batch[pos].second.translation,
                        done = matched.contains(pos),
                        selected = false,
                        wrong = wrongPos == pos
                    ) {
                        val l = selectedLeft
                        if (l != null && !matched.contains(pos)) {
                            if (l == pos) {
                                matched.add(pos)
                                onCard(batch[pos].first, true)
                                selectedLeft = null
                            } else {
                                wrongPos = pos
                                selectedLeft = null
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchTile(text: String, done: Boolean, selected: Boolean, wrong: Boolean, onClick: () -> Unit) {
    val bg = when {
        done -> Green
        wrong -> Red
        selected -> Brand
        else -> Surface
    }
    val fg = if (done || wrong || selected) Color.White else TextPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(enabled = !done) { onClick() }
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 15.sp, textAlign = TextAlign.Center) }
}
