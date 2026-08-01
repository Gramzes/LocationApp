package com.example.locationapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.AiClient
import com.example.locationapp.english.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiAssistantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val client = remember { AiClient(AiSettings(context)) }
    val scope = rememberCoroutineScope()

    var word by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "✨ ИИ-помощник") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Введите английское слово или фразу — ИИ даст перевод, примеры предложений и синонимы.",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedTextField(
                value = word,
                onValueChange = { word = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (word.isBlank()) return@Button
                    loading = true
                    result = ""
                    scope.launch {
                        val sys = "Ты помощник по изучению английского для русскоязычного ученика. " +
                            "Отвечай кратко и на русском (кроме английских примеров)."
                        val prompt = "Разбери английское слово/фразу \"${word.trim()}\": " +
                            "1) перевод; 2) три примера предложений на английском с переводом; " +
                            "3) 2–3 синонима. Без лишнего вступления."
                        val res = withContext(Dispatchers.IO) { runCatching { client.ask(sys, prompt) } }
                        loading = false
                        res.onSuccess { result = it }.onFailure { result = "⚠ ${it.message}" }
                    }
                },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("Разобрать слово", fontSize = 16.sp) }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (result.isNotEmpty()) {
                Text(result, color = TextPrimary, fontSize = 16.sp)
            }
        }
    }
}
