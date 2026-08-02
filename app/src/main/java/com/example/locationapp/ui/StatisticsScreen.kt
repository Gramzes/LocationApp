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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locationapp.english.CustomDeckStore
import com.example.locationapp.english.DeckRepository
import com.example.locationapp.english.Progress
import com.example.locationapp.english.Streak

@Composable
fun StatisticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val progress = remember { Progress(context) }
    val streak = remember { Streak(context) }

    val decks = remember { DeckRepository.decks + CustomDeckStore(context).all() }
    val totalWords = decks.sumOf { it.cards.size }
    val totalLearned = decks.sumOf { progress.learnedCount(it.id) }
    val totalDue = decks.sumOf { progress.dueCount(it.id) }

    // Активность за последние 84 дня (oldest → newest).
    val days = remember { (83 downTo 0).map { streak.dayIdDaysAgo(it) } }
    val counts = days.map { streak.countForDay(it) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Header(title = "📊 Статистика") {
            Box(
                modifier = Modifier
                    .background(BrandDark, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("Назад", color = Color.White, fontSize = 14.sp) }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("🔥 Серия", "${streak.currentStreak()} дн.", Modifier.weight(1f).padding(end = 6.dp))
                StatCard("🏆 Рекорд серии", "${streak.bestStreak()} дн.", Modifier.weight(1f).padding(start = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("✅ Выучено", "$totalLearned / $totalWords", Modifier.weight(1f).padding(end = 6.dp))
                StatCard("🔁 К повторению", "$totalDue", Modifier.weight(1f).padding(start = 6.dp))
            }

            Spacer(Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Активность за неделю", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(14.dp))
                    val week = (6 downTo 0).map { streak.countForDay(streak.dayIdDaysAgo(it)) }
                    val labels = (6 downTo 0).map { dayLabel(it) }
                    WeekBars(week, labels)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Активность за 12 недель", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Каждая клетка — день; чем ярче, тем больше ответов.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Heatmap(counts)
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Heatmap(counts: List<Int>) {
    // 12 строк по 7 дней (неделями), старые вверху.
    Column {
        counts.chunked(7).forEach { week ->
            Row(modifier = Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(cellColor(c), RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekBars(counts: List<Int>, labels: List<String>) {
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        counts.forEachIndexed { i, c ->
            val isMax = c == max && c > 0
            val barHeight = (10 + (c.toFloat() / max) * 120).dp
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (c > 0) {
                    Text("$c", color = TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .background(if (isMax) Brand else BrandLight, RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(6.dp))
                Text(labels[i], color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

private fun dayLabel(daysAgo: Int): String {
    val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, -daysAgo) }
    val names = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    return names[(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
}

private fun cellColor(count: Int): Color = when {
    count <= 0 -> Color(0xFF26262B)
    count < 5 -> Color(0xFF4C5A2A)
    count < 15 -> Color(0xFF8FB63A)
    else -> Brand
}
