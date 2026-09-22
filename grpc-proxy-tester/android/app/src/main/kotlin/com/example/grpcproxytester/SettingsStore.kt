package com.example.grpcproxytester

import android.content.Context
import androidx.core.content.edit
import com.example.grpcproxytester.core.Settings

/** Хранит настройки между запусками приложения. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        fun str(key: String, default: String) = prefs.getString(key, default) ?: default
        return Settings(
            address = str("address", d.address),
            useTls = prefs.getBoolean("useTls", d.useTls),
            skipTlsVerify = prefs.getBoolean("skipTlsVerify", d.skipTlsVerify),
            authority = str("authority", d.authority),
            headers = str("headers", d.headers),
            connectProxy = str("connectProxy", d.connectProxy),
            timeoutSeconds = str("timeoutSeconds", d.timeoutSeconds),
            longSeconds = str("longSeconds", d.longSeconds),
            largeSizeKb = str("largeSizeKb", d.largeSizeKb),
            streamCount = str("streamCount", d.streamCount),
            concurrency = str("concurrency", d.concurrency),
            disabledChecks = prefs.getStringSet("disabledChecks", null)?.toSet() ?: d.disabledChecks,
        )
    }

    fun save(s: Settings) {
        prefs.edit {
            putString("address", s.address)
            putBoolean("useTls", s.useTls)
            putBoolean("skipTlsVerify", s.skipTlsVerify)
            putString("authority", s.authority)
            putString("headers", s.headers)
            putString("connectProxy", s.connectProxy)
            putString("timeoutSeconds", s.timeoutSeconds)
            putString("longSeconds", s.longSeconds)
            putString("largeSizeKb", s.largeSizeKb)
            putString("streamCount", s.streamCount)
            putString("concurrency", s.concurrency)
            putStringSet("disabledChecks", s.disabledChecks)
        }
    }
}
