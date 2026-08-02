package com.example.locationapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.AiClient
import com.example.locationapp.english.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReadingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { AiClient(AiSettings(context)) }
    val scope = rememberCoroutineScope()
    val speaker = LocalSpeaker.current

    var topic by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    var tappedWord by remember { mutableStateOf("") }
    var translation by remember { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }

    fun generate() {
        if (loading) return
        loading = true
        text = ""
        tappedWord = ""; translation = ""
        scope.launch {
            val sys = "Ты помощник по изучению английского."
            val t = topic.ifBlank { "everyday life" }
            val prompt = "Напиши короткий связный текст на английском (6–8 предложений, уровень A2–B1) " +
                "на тему \"$t\". Только текст, без заголовка и перевода."
            val res = withContext(Dispatchers.IO) { runCatching { client.ask(sys, prompt) } }
            loading = false
            res.onSuccess { text = it }.onFailure { text = "⚠ ${it.message}" }
        }
    }

    fun translate(word: String) {
        val clean = word.trim()
        if (clean.isEmpty()) return
        tappedWord = clean
        translation = ""
        translating = true
        speaker?.speak(clean)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    client.ask(
                        "Ты переводчик.",
                        "Переведи английское слово \"$clean\" на русский одним-двумя словами. Только перевод."
                    )
                }
            }
            translating = false
            res.onSuccess { translation = it }.onFailure { translation = "⚠ ${it.message}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "📖 Чтение") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Сгенерируйте текст и нажимайте на слова — появится перевод и озвучка.",
                color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    singleLine = true,
                    label = { Text("Тема (напр. travel)") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { generate() },
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Текст") }
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) { CircularProgressIndicator() }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (text.isNotEmpty()) {
                val annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = TextPrimary)) { append(text) }
                }
                ClickableText(
                    text = annotated,
                    style = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
                    onClick = { offset -> translate(wordAt(text, offset)) }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { speaker?.speak(text) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("🔊 Озвучить текст", fontSize = 14.sp) }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (tappedWord.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandLight)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tappedWord, color = BrandDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (translating) {
                        Text("…", color = TextSecondary, fontSize = 15.sp)
                    } else {
                        Text(translation, color = TextPrimary, fontSize = 16.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .background(Brand, RoundedCornerShape(10.dp))
                        .clickable { speaker?.speak(tappedWord) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) { Text("🔊", color = Color.White, fontSize = 16.sp) }
            }
        }
    }
}

/** Возвращает слово (только буквы) вокруг позиции offset. */
private fun wordAt(text: String, offset: Int): String {
    if (offset !in text.indices) return ""
    var s = offset
    while (s > 0 && text[s - 1].isLetter()) s--
    var e = offset
    while (e < text.length && text[e].isLetter()) e++
    return text.substring(s, e)
}
