package com.example.locationapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import com.example.locationapp.reminder.Reminders
import com.example.locationapp.ui.AppRoot
import com.example.locationapp.ui.AppTheme
import com.example.locationapp.ui.LocalSpeaker
import com.example.locationapp.ui.Speaker

/** Единственная активность приложения — весь UI на Jetpack Compose. */
class MainActivity : ComponentActivity() {

    private lateinit var speaker: Speaker

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* результат не важен */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)

        Reminders.createChannel(this)
        Reminders.scheduleDaily(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                CompositionLocalProvider(LocalSpeaker provides speaker) {
                    AppRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
