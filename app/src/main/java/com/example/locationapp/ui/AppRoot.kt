package com.example.locationapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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

/** Экраны приложения. */
sealed class Screen {
    object DeckList : Screen()
    data class Study(val deckId: String) : Screen()
    data class DeckEditor(val deckId: String?) : Screen()
    object Ai : Screen()
    object Chat : Screen()
    object Reading : Screen()
    object Stats : Screen()
    object Settings : Screen()
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.DeckList) }

    val isTab = screen is Screen.DeckList || screen is Screen.Ai ||
        screen is Screen.Stats || screen is Screen.Settings

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        when (val s = screen) {
            is Screen.DeckList -> DeckListScreen(
                onOpenDeck = { screen = Screen.Study(it) },
                onCreateDeck = { screen = Screen.DeckEditor(null) },
                onEditDeck = { screen = Screen.DeckEditor(it) },
                onOpenStats = { screen = Screen.Stats }
            )
            is Screen.Study -> {
                BackHandler { screen = Screen.DeckList }
                StudyScreen(deckId = s.deckId, onBack = { screen = Screen.DeckList })
            }
            is Screen.DeckEditor -> {
                BackHandler { screen = Screen.DeckList }
                DeckEditorScreen(deckId = s.deckId, onBack = { screen = Screen.DeckList })
            }
            is Screen.Ai -> AiAssistantScreen(
                onBack = { screen = Screen.DeckList },
                onOpenChat = { screen = Screen.Chat },
                onOpenReading = { screen = Screen.Reading }
            )
            is Screen.Chat -> {
                BackHandler { screen = Screen.Ai }
                ChatScreen(onBack = { screen = Screen.Ai })
            }
            is Screen.Reading -> {
                BackHandler { screen = Screen.Ai }
                ReadingScreen(onBack = { screen = Screen.Ai })
            }
            is Screen.Stats -> StatisticsScreen(onBack = { screen = Screen.DeckList })
            is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.DeckList })
        }

        if (isTab) {
            BottomBar(
                current = screen,
                onSelect = { screen = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
        }
    }
}

/** Плавающая нижняя панель навигации. */
@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Surface, RoundedCornerShape(30.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomTab("🏠", current is Screen.DeckList) { onSelect(Screen.DeckList) }
        BottomTab("✨", current is Screen.Ai) { onSelect(Screen.Ai) }
        BottomTab("📊", current is Screen.Stats) { onSelect(Screen.Stats) }
        BottomTab("⚙", current is Screen.Settings) { onSelect(Screen.Settings) }
    }
}

@Composable
private fun BottomTab(symbol: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .background(if (active) Brand else Surface, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 20.sp)
    }
}

/** Шапка экрана с заголовком и опциональными действиями справа. */
@Composable
fun Header(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Bg)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
