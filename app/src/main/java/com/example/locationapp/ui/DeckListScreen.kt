package com.example.locationapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.CustomDeckStore
import com.example.locationapp.english.Deck
import com.example.locationapp.english.DeckRepository
import com.example.locationapp.english.Progress
import com.example.locationapp.english.Streak

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckListScreen(
    onOpenDeck: (String) -> Unit,
    onOpenAi: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreateDeck: () -> Unit,
    onEditDeck: (String) -> Unit,
    onOpenStats: () -> Unit
) {
    val context = LocalContext.current
    val progress = remember { Progress(context) }
    val streak = remember { Streak(context) }
    val store = remember { CustomDeckStore(context) }

    var custom by remember { mutableStateOf(store.all()) }
    var refresh by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Deck?>(null) }

    val allDecks = DeckRepository.decks + custom

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "Английский по карточкам") {
            HeaderIcon("＋", onCreateDeck)
            HeaderIcon("✨", onOpenAi)
            HeaderIcon("⚙", onOpenSettings)
        }

        StreakBanner(streak, onOpenStats)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(allDecks) { deck ->
                DeckRow(
                    deck = deck,
                    progress = progress,
                    refreshKey = refresh,
                    onClick = { onOpenDeck(deck.id) },
                    onLongClick = { selected = deck }
                )
            }
        }
    }

    selected?.let { deck ->
        val isCustom = CustomDeckStore.isCustom(deck.id)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${deck.emoji} ${deck.title}") },
            text = {
                Column {
                    if (isCustom) {
                        DialogRow("✏️ Редактировать") { onEditDeck(deck.id); selected = null }
                        DialogRow("🗑 Удалить набор") {
                            store.delete(deck.id)
                            progress.resetDeck(deck.id)
                            custom = store.all()
                            refresh++
                            selected = null
                        }
                    }
                    DialogRow("🔄 Сбросить прогресс") {
                        progress.resetDeck(deck.id)
                        refresh++
                        selected = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun DialogRow(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.fillMaxWidth(), color = TextPrimary)
    }
}

@Composable
private fun StreakBanner(streak: Streak, onClick: () -> Unit) {
    val days = streak.currentStreak()
    val count = streak.todayCount()
    val goal = streak.dailyGoal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandLight)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔥 $days дн.", color = BrandDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text("Сегодня $count/$goal  📊", color = TextSecondary, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderIcon(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(BrandDark, CircleShape)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Color.White, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckRow(
    deck: Deck,
    progress: Progress,
    refreshKey: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // refreshKey меняется после сброса/удаления и заставляет строку пересчитать прогресс.
    val total = deck.cards.size
    val learned = progress.learnedCount(deck.id)
    val due = progress.dueCount(deck.id)
    val fraction = if (total == 0) 0f else learned.toFloat() / total

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(deck.emoji, fontSize = 32.sp, modifier = Modifier.padding(end = 16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(deck.title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Выучено $learned / $total" + if (due > 0) "   •   🔁 к повторению: $due" else "",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                LinearProgressIndicator(
                    progress = fraction,
                    color = Brand,
                    trackColor = BrandLight,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}
