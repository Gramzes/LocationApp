package com.example.locationapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    object Settings : Screen()
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.DeckList) }

    when (val s = screen) {
        is Screen.DeckList -> DeckListScreen(
            onOpenDeck = { screen = Screen.Study(it) },
            onOpenAi = { screen = Screen.Ai },
            onOpenSettings = { screen = Screen.Settings },
            onCreateDeck = { screen = Screen.DeckEditor(null) },
            onEditDeck = { screen = Screen.DeckEditor(it) }
        )
        is Screen.Study -> {
            BackHandler { screen = Screen.DeckList }
            StudyScreen(deckId = s.deckId, onBack = { screen = Screen.DeckList })
        }
        is Screen.DeckEditor -> {
            BackHandler { screen = Screen.DeckList }
            DeckEditorScreen(deckId = s.deckId, onBack = { screen = Screen.DeckList })
        }
        is Screen.Ai -> {
            BackHandler { screen = Screen.DeckList }
            AiAssistantScreen(
                onBack = { screen = Screen.DeckList },
                onOpenChat = { screen = Screen.Chat }
            )
        }
        is Screen.Chat -> {
            BackHandler { screen = Screen.Ai }
            ChatScreen(onBack = { screen = Screen.Ai })
        }
        is Screen.Settings -> {
            BackHandler { screen = Screen.DeckList }
            SettingsScreen(onBack = { screen = Screen.DeckList })
        }
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
            .background(Brand)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
