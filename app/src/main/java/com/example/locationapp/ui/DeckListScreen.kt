package com.example.locationapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.CustomDeckStore
import com.example.locationapp.english.Deck
import com.example.locationapp.english.DeckRepository
import com.example.locationapp.english.Progress
import com.example.locationapp.english.Streak

private enum class DeckFilter(val label: String) { ALL("Все"), BUILTIN("Готовые"), MINE("Мои") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckListScreen(
    onOpenDeck: (String) -> Unit,
    onCreateDeck: () -> Unit,
    onEditDeck: (String) -> Unit,
    onOpenStats: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress = remember { Progress(context) }
    val streak = remember { Streak(context) }
    val store = remember { CustomDeckStore(context) }

    var custom by remember { mutableStateOf(store.all()) }
    var refresh by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(DeckFilter.ALL) }
    var selected by remember { mutableStateOf<Deck?>(null) }

    val builtIn = DeckRepository.decks
    val decks = when (filter) {
        DeckFilter.ALL -> builtIn + custom
        DeckFilter.BUILTIN -> builtIn
        DeckFilter.MINE -> custom
    }
    // «Фичевая» карточка — набор с наибольшим числом слов к повторению (иначе первый).
    val featured = decks.maxByOrNull { progress.dueCount(it.id) }?.takeIf { decks.isNotEmpty() }
    val rest = decks.filter { it !== featured }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Продолжай учиться 👋", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Выбери\nнабор слов", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Brand, CircleShape)
                        .clickable { onCreateDeck() },
                    contentAlignment = Alignment.Center
                ) { Text("＋", color = OnAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            }
        }

        item {
            // Плашка стрика.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(16.dp))
                    .clickable { onOpenStats() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔥 ${streak.currentStreak()} дн. подряд", color = Brand, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("сегодня ${streak.todayCount()}/${streak.dailyGoal}  ›", color = TextSecondary, fontSize = 13.sp)
            }
        }

        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                DeckFilter.values().forEach { f ->
                    FilterChip(f.label, f == filter) { filter = f }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (featured != null) {
            item {
                FeaturedCard(featured, progress, refresh) { onOpenDeck(featured.id) }
            }
        }

        items(rest) { deck ->
            DeckCard(
                deck = deck,
                progress = progress,
                refreshKey = refresh,
                onClick = { onOpenDeck(deck.id) },
                onLongClick = { selected = deck }
            )
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
                            store.delete(deck.id); progress.resetDeck(deck.id)
                            custom = store.all(); refresh++; selected = null
                        }
                    }
                    DialogRow("🔄 Сбросить прогресс") {
                        progress.resetDeck(deck.id); refresh++; selected = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) Brand else Surface, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) OnAccent else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun FeaturedCard(deck: Deck, progress: Progress, refreshKey: Int, onClick: () -> Unit) {
    val total = deck.cards.size
    val learned = progress.learnedCount(deck.id)
    val due = progress.dueCount(deck.id)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(20.dp)
    ) {
        Text("РЕКОМЕНДУЕМ", color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${deck.emoji} ${deck.title}", color = OnAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (due > 0) "$due слов к повторению · выучено $learned/$total"
            else "выучено $learned/$total",
            color = OnAccent, fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .background(OnAccent, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) { Text("Начать занятие  →", color = Brand, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckCard(
    deck: Deck,
    progress: Progress,
    refreshKey: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val total = deck.cards.size
    val learned = progress.learnedCount(deck.id)
    val due = progress.dueCount(deck.id)
    val fraction = if (total == 0) 0f else learned.toFloat() / total

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(BrandLight, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Text(deck.emoji, fontSize = 24.sp) }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(deck.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Выучено $learned / $total" + if (due > 0) "  ·  🔁 $due" else "",
                color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)
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

@Composable
private fun DialogRow(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.fillMaxWidth(), color = TextPrimary)
    }
}
