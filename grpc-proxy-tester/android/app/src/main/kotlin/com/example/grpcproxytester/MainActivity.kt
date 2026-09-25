package com.example.grpcproxytester

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.grpcproxytester.core.AutomationRequest
import com.example.grpcproxytester.ui.TesterScreen
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val vm: TesterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TesterTheme {
                TesterScreen(
                    settings = vm.settings,
                    running = vm.running,
                    status = vm.status,
                    results = vm.results,
                    current = vm.current,
                    summary = vm.summary,
                    onSettingsChange = vm::updateSettings,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onShare = if (vm.results.isNotEmpty() || vm.status != null) ::shareReport else null,
                )
            }
        }
        // Только при первом создании: после поворота экрана интент тот же, и
        // запускать прогон второй раз не нужно.
        if (savedInstanceState == null) handleAutomation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomation(intent)
    }

    /**
     * `adb shell am start -n com.example.grpcproxytester/.MainActivity --ez autorun true
     *   --es run_id ID --es address host:port [--ez tls B] [--ez insecure B] [--ei long S] [--es only a,b]
     *   [--es connect_proxy host:port]`
     */
    private fun handleAutomation(intent: Intent?) {
        if (intent?.getBooleanExtra("autorun", false) != true) return
        fun bool(key: String) = if (intent.hasExtra(key)) intent.getBooleanExtra(key, false) else null
        vm.startAutomation(
            AutomationRequest(
                runId = intent.getStringExtra("run_id") ?: UUID.randomUUID().toString(),
                address = intent.getStringExtra("address"),
                useTls = bool("tls"),
                skipTlsVerify = bool("insecure"),
                longSeconds = if (intent.hasExtra("long")) intent.getIntExtra("long", 0) else null,
                only = intent.getStringExtra("only")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
                connectProxy = intent.getStringExtra("connect_proxy"),
            ),
        )
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, vm.report())
        }
        startActivity(Intent.createChooser(send, "Отчёт gRPC Proxy Tester"))
    }
}

@Composable
private fun TesterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
