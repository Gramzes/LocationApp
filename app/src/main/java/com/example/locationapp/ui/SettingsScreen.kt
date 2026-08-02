package com.example.locationapp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.AiClient
import com.example.locationapp.english.AiProvider
import com.example.locationapp.english.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AiSettings(context) }

    val scope = rememberCoroutineScope()
    val client = remember { AiClient(settings) }

    var provider by remember { mutableStateOf(settings.provider) }
    var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
    var anthropicModel by remember { mutableStateOf(settings.anthropicModel) }
    var openRouterKey by remember { mutableStateOf(settings.openRouterKey) }
    var openRouterModel by remember { mutableStateOf(settings.openRouterModel) }

    var liveModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "Настройки ИИ") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("Провайдер", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

            ProviderOption(
                title = "OpenRouter (есть бесплатные модели)",
                selected = provider == AiProvider.OPENROUTER,
                onSelect = { provider = AiProvider.OPENROUTER }
            )
            ProviderOption(
                title = "Anthropic (Claude, платно)",
                selected = provider == AiProvider.ANTHROPIC,
                onSelect = { provider = AiProvider.ANTHROPIC }
            )

            if (provider == AiProvider.OPENROUTER) {
                Field(
                    label = "API-ключ OpenRouter",
                    hint = "Получите на openrouter.ai/keys. Ключ хранится только на устройстве.",
                    value = openRouterKey,
                    onChange = { openRouterKey = it }
                )
                Field(
                    label = "Модель",
                    hint = "Вставьте точный id со страницы openrouter.ai/models (фильтр Free) " +
                        "или выберите ниже. Если модель даёт 404 — попробуйте другую.",
                    value = openRouterModel,
                    onChange = { openRouterModel = it }
                )
                Button(
                    onClick = {
                        settings.openRouterKey = openRouterKey // сохраняем ключ перед запросом
                        loadingModels = true
                        modelsError = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { client.listFreeOpenRouterModels() }
                            }
                            loadingModels = false
                            r.onSuccess { list ->
                                liveModels = list
                                if (list.isEmpty()) modelsError = "Список пуст"
                            }.onFailure { modelsError = it.message }
                        }
                    },
                    enabled = !loadingModels,
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("🔄 Загрузить актуальные бесплатные модели", fontSize = 15.sp) }

                if (loadingModels) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
                modelsError?.let {
                    Text("⚠ $it", color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }

                val presetList = if (liveModels.isNotEmpty()) liveModels else AiSettings.OPENROUTER_FREE_PRESETS
                Text(
                    if (liveModels.isNotEmpty()) "Бесплатные модели (${liveModels.size}) — выберите:"
                    else "Быстрый выбор (список по умолчанию, нажмите кнопку выше для актуального):",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                presetList.forEach { preset ->
                    ModelPreset(
                        slug = preset,
                        selected = preset == openRouterModel,
                        onPick = { openRouterModel = preset }
                    )
                }
            } else {
                Field(
                    label = "API-ключ Anthropic",
                    hint = "Получите на console.anthropic.com. Ключ хранится только на устройстве.",
                    value = anthropicKey,
                    onChange = { anthropicKey = it }
                )
                Field(
                    label = "Модель",
                    hint = "По умолчанию ${AiSettings.DEFAULT_ANTHROPIC}",
                    value = anthropicModel,
                    onChange = { anthropicModel = it }
                )
            }

            Button(
                onClick = {
                    settings.provider = provider
                    settings.anthropicKey = anthropicKey
                    settings.anthropicModel = anthropicModel
                    settings.openRouterKey = openRouterKey
                    settings.openRouterModel = openRouterModel
                    Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp)
            ) { Text("Сохранить", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun ProviderOption(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(title, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ModelPreset(slug: String, selected: Boolean, onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (selected) Brand else Surface, RoundedCornerShape(10.dp))
            .clickable { onPick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            slug,
            color = if (selected) OnAccent else TextPrimary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun Field(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(hint, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
