package com.example.locationapp.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.locationapp.english.Card as WordCard
import com.example.locationapp.english.CustomDeckStore
import com.example.locationapp.english.Deck
import com.example.locationapp.english.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Редактируемая карточка (наблюдаемые поля для Compose). */
private class CardDraft(term: String = "", translation: String = "", example: String = "") {
    var term by mutableStateOf(term)
    var translation by mutableStateOf(translation)
    var example by mutableStateOf(example)
    var loading by mutableStateOf(false)
}

@Composable
fun DeckEditorScreen(deckId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CustomDeckStore(context) }
    val client = remember { AiClient(AiSettings(context)) }
    val scope = rememberCoroutineScope()

    val existing = remember(deckId) { deckId?.let { store.deckById(it) } }
    val id = remember(deckId) { existing?.id ?: store.newId() }

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "📝") }
    val drafts = remember {
        mutableStateListOf<CardDraft>().apply {
            existing?.cards?.forEach { add(CardDraft(it.term, it.translation, it.example)) }
            if (isEmpty()) add(CardDraft())
        }
    }

    var showImport by remember { mutableStateOf(false) }

    fun save() {
        val cards = drafts
            .filter { it.term.isNotBlank() && it.translation.isNotBlank() }
            .map { WordCard(it.term.trim(), it.translation.trim(), it.example.trim()) }
        if (title.isBlank() || cards.isEmpty()) {
            Toast.makeText(context, "Нужны название и хотя бы одно слово с переводом", Toast.LENGTH_LONG).show()
            return
        }
        store.upsert(Deck(id = id, title = title.trim(), emoji = emoji.ifBlank { "📝" }, cards = cards))
        Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = if (existing != null) "Редактор набора" else "Новый набор") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(2) },
                        singleLine = true,
                        label = { Text("Эмодзи") },
                        modifier = Modifier.width(96.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Название набора") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Button(
                    onClick = { showImport = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📥 Импорт из CSV / вставки", fontSize = 15.sp) }
            }

            itemsIndexed(drafts) { index, draft ->
                CardDraftEditor(
                    index = index,
                    draft = draft,
                    onDelete = { if (drafts.size > 1) drafts.removeAt(index) else { draft.term = ""; draft.translation = ""; draft.example = "" } },
                    onAiFill = {
                        if (draft.term.isBlank()) {
                            Toast.makeText(context, "Сначала впишите английское слово", Toast.LENGTH_SHORT).show()
                        } else {
                            draft.loading = true
                            scope.launch {
                                val sys = "Ты помощник по изучению английского. Отвечай строго в 2 строки."
                                val prompt = "Для английского слова \"${draft.term.trim()}\" верни ровно две строки: " +
                                    "первая — русский перевод; вторая — короткий пример предложения на английском. " +
                                    "Без нумерации и лишнего текста."
                                val res = withContext(Dispatchers.IO) { runCatching { client.ask(sys, prompt) } }
                                draft.loading = false
                                res.onSuccess { text ->
                                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                    if (lines.isNotEmpty()) draft.translation = lines[0].removePrefix("1.").removePrefix("1)").trim()
                                    if (lines.size > 1) draft.example = lines[1].removePrefix("2.").removePrefix("2)").trim()
                                }.onFailure {
                                    Toast.makeText(context, "ИИ: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )
            }

            item {
                Button(
                    onClick = { drafts.add(CardDraft()) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandLight, contentColor = BrandDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ Добавить слово", fontSize = 15.sp) }
            }

            item {
                Button(
                    onClick = { save() },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Сохранить набор", fontSize = 16.sp) }
            }

            if (existing != null) {
                item {
                    TextButton(
                        onClick = {
                            store.delete(id)
                            Progress(context).resetDeck(id)
                            Toast.makeText(context, "Набор удалён", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Удалить набор", color = Red) }
                }
            }
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                val cards = CustomDeckStore.parseCsv(text)
                if (cards.isEmpty()) {
                    Toast.makeText(context, "Не удалось разобрать строки", Toast.LENGTH_LONG).show()
                } else {
                    if (drafts.size == 1 && drafts[0].term.isBlank()) drafts.clear()
                    cards.forEach { drafts.add(CardDraft(it.term, it.translation, it.example)) }
                    Toast.makeText(context, "Добавлено слов: ${cards.size}", Toast.LENGTH_SHORT).show()
                }
                showImport = false
            }
        )
    }
}

@Composable
private fun CardDraftEditor(
    index: Int,
    draft: CardDraft,
    onDelete: () -> Unit,
    onAiFill: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                OutlinedTextField(
                    value = draft.term,
                    onValueChange = { draft.term = it },
                    singleLine = true,
                    label = { Text("Слово (англ.)") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(BrandLight, RoundedCornerShape(10.dp))
                        .clickable { onDelete() }
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                ) { Text("🗑", fontSize = 16.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.translation,
                onValueChange = { draft.translation = it },
                singleLine = true,
                label = { Text("Перевод") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.example,
                onValueChange = { draft.example = it },
                singleLine = true,
                label = { Text("Пример (необязательно)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAiFill,
                enabled = !draft.loading,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (draft.loading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.height(18.dp).width(18.dp))
                } else {
                    Text("✨ Заполнить перевод и пример", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт слов") },
        text = {
            Column {
                Text(
                    "Каждая строка: слово, перевод, пример (через запятую, ; или таб). Пример необязателен.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("hello, привет, Hello!") },
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
