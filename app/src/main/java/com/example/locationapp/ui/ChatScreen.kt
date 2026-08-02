package com.example.locationapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.AiClient
import com.example.locationapp.english.AiSettings
import com.example.locationapp.english.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Scenario(val label: String, val greeting: String, val system: String)

private val SHARED_RULES =
    " Reply in short, simple English suitable for an A2–B1 learner (1–3 sentences) and keep the " +
        "conversation going with a question. If the learner makes a mistake, add a brief correction " +
        "at the end in parentheses, explained in Russian."

private val SCENARIOS = listOf(
    Scenario("💬 Свободный", "Hi! What would you like to talk about today?",
        "You are a friendly English conversation partner for a Russian learner." + SHARED_RULES),
    Scenario("☕ Кафе", "Hello! Welcome to our cafe. What can I get for you?",
        "Role-play as a barista taking an order in a cafe." + SHARED_RULES),
    Scenario("🤝 Знакомство", "Hi! Nice to meet you. What's your name?",
        "Role-play meeting a new person and getting to know them." + SHARED_RULES),
    Scenario("💼 Собеседование", "Hello, thanks for coming. Tell me a little about yourself.",
        "Role-play as a job interviewer." + SHARED_RULES),
    Scenario("🛒 В магазине", "Hi! Are you looking for anything special today?",
        "Role-play as a shop assistant helping a customer." + SHARED_RULES)
)

@Composable
fun ChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { AiClient(AiSettings(context)) }
    val scope = rememberCoroutineScope()
    val speaker = LocalSpeaker.current

    var scenario by remember { mutableStateOf(SCENARIOS.first()) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Приветствие при старте и смене сценария.
    LaunchedEffect(scenario) {
        messages.clear()
        messages.add(ChatMessage("assistant", scenario.greeting))
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        messages.add(ChatMessage("user", text))
        input = ""
        loading = true
        val history = messages.toList()
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { client.chat(scenario.system, history) } }
            loading = false
            res.onSuccess { messages.add(ChatMessage("assistant", it)) }
                .onFailure { messages.add(ChatMessage("assistant", "⚠ ${it.message}")) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "🗣 Разговор") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        // Выбор сценария.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            SCENARIOS.forEach { sc ->
                val selected = sc == scenario
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(if (selected) Brand else Surface, RoundedCornerShape(20.dp))
                        .clickable { scenario = sc }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(sc.label, color = if (selected) OnAccent else TextPrimary, fontSize = 14.sp)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg -> ChatBubble(msg) { speaker?.speak(msg.content) } }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ваше сообщение…") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { send() },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(14.dp)
            ) { Text("▶", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, onSpeak: () -> Unit) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .background(if (isUser) Brand else Surface, RoundedCornerShape(14.dp))
                .clickable(enabled = !isUser) { onSpeak() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                msg.content,
                color = if (isUser) OnAccent else TextPrimary,
                fontSize = 15.sp
            )
            if (!isUser) {
                Text("🔊 нажмите, чтобы озвучить", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
            }
        }
    }
}
