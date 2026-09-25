package com.example.grpcproxytester.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.grpcproxytester.core.CheckResult
import com.example.grpcproxytester.core.ResultKind
import com.example.grpcproxytester.core.Settings

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun TesterScreenPreview() {
    MaterialTheme {
        TesterScreen(
            settings = Settings(address = "10.0.2.2:8080"),
            running = true,
            status = StatusLine("Соединение с 10.0.2.2:8080 (plaintext/h2c) установлено за 3ms", StatusKind.OK),
            results = listOf(
                CheckResult("health", ResultKind.PASSED, "SERVING", durationNanos = 935_000),
                CheckResult("unary", ResultKind.PASSED, "сервер \"srv-1\", видит клиента как 127.0.0.1:57952", durationNanos = 1_200_000),
                CheckResult(
                    "large-message", ResultKind.FAILED,
                    "UNKNOWN: HTTP status code 413",
                    "у прокси лимит на размер сообщения (например, client_max_body_size у nginx)",
                    durationNanos = 10_000_000,
                ),
            ).associateBy { it.name },
            current = "compression",
            summary = null,
            onSettingsChange = {},
            onStart = {},
            onStop = {},
            onShare = {},
        )
    }
}
