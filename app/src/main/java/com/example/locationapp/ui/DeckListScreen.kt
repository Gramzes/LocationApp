package com.example.locationapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
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
import com.example.locationapp.english.Deck
import com.example.locationapp.english.DeckRepository
import com.example.locationapp.english.Progress

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckListScreen(
    onOpenDeck: (String) -> Unit,
    onOpenAi: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val progress = remember { Progress(context) }
    var resetDeck by remember { mutableStateOf<Deck?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "Английский по карточкам") {
            HeaderIcon("✨", onOpenAi)
            HeaderIcon("⚙", onOpenSettings)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(DeckRepository.decks) { deck ->
                DeckRow(
                    deck = deck,
                    progress = progress,
                    onClick = { onOpenDeck(deck.id) },
                    onLongClick = { resetDeck = deck }
                )
            }
        }
    }

    resetDeck?.let { deck ->
        AlertDialog(
            onDismissRequest = { resetDeck = null },
            title = { Text("Сбросить прогресс?") },
            text = { Text("Статистика набора «${deck.title}» будет очищена.") },
            confirmButton = {
                TextButton(onClick = {
                    progress.resetDeck(deck.id)
                    resetDeck = null
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { resetDeck = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun HeaderIcon(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(BrandDark, CircleShape)
            .combinedClickableSafe(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Color.White, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSafe(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckRow(
    deck: Deck,
    progress: Progress,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val total = deck.cards.size
    val learned = progress.learnedCount(deck.id)
    val problems = progress.problemCount(deck.id)
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
                Text(
                    deck.title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Выучено $learned / $total" + if (problems > 0) "   •   🔁 сложных: $problems" else "",
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
