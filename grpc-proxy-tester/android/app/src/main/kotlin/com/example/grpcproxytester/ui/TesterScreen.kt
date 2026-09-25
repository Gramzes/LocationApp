package com.example.grpcproxytester.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.grpcproxytester.core.CheckInfo
import com.example.grpcproxytester.core.CheckResult
import com.example.grpcproxytester.core.ProxyTester
import com.example.grpcproxytester.core.ResultKind
import com.example.grpcproxytester.core.Settings

enum class StatusKind { INFO, OK, ERROR }

/** Строка состояния под кнопкой запуска (подключение) и в конце списка (итог). */
data class StatusLine(val text: String, val kind: StatusKind = StatusKind.INFO, val hint: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterScreen(
    settings: Settings,
    running: Boolean,
    status: StatusLine?,
    results: Map<String, CheckResult>,
    current: String?,
    summary: StatusLine?,
    onSettingsChange: (Settings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("gRPC Proxy Tester") },
                actions = {
                    if (onShare != null) {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "Поделиться отчётом")
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionCard(settings, enabled = !running, onSettingsChange) }
            item { AdvancedCard(settings, enabled = !running, onSettingsChange) }
            item {
                if (running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Остановить")
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Запустить проверки")
                    }
                }
            }
            if (status != null) {
                item { StatusText(status) }
            }
            item { HorizontalDivider() }
            items(ProxyTester.checks, key = { it.name }) { check ->
                val selected = check.name !in settings.disabledChecks
                CheckRow(
                    check = check,
                    result = results[check.name],
                    state = when {
                        check.name == current -> RowState.RUNNING
                        running && selected && check.name !in results -> RowState.PENDING
                        else -> RowState.IDLE
                    },
                    selected = selected,
                    enabled = !running,
                    onSelectedChange = { on ->
                        val disabled = if (on) settings.disabledChecks - check.name else settings.disabledChecks + check.name
                        onSettingsChange(settings.copy(disabledChecks = disabled))
                    },
                )
            }
            if (summary != null) {
                item { StatusText(summary) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(settings: Settings, enabled: Boolean, onChange: (Settings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field(
                label = "Адрес прокси (host:port)",
                value = settings.address,
                onChange = { onChange(settings.copy(address = it)) },
                enabled = enabled,
                supporting = "Из эмулятора компьютер доступен как 10.0.2.2",
                keyboardType = KeyboardType.Uri,
            )
            SwitchRow("TLS", settings.useTls || settings.skipTlsVerify, enabled) {
                onChange(settings.copy(useTls = it, skipTlsVerify = settings.skipTlsVerify && it))
            }
            SwitchRow("Не проверять сертификат", settings.skipTlsVerify, enabled) {
                onChange(settings.copy(skipTlsVerify = it, useTls = settings.useTls || it))
            }
        }
    }
}

@Composable
private fun AdvancedCard(settings: Settings, enabled: Boolean, onChange: (Settings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Дополнительно", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Field(
                    label = "Заголовки",
                    value = settings.headers,
                    onChange = { onChange(settings.copy(headers = it)) },
                    enabled = enabled,
                    supporting = "По одному на строку: authorization: Bearer ...",
                    singleLine = false,
                )
                Field(
                    label = ":authority",
                    value = settings.authority,
                    onChange = { onChange(settings.copy(authority = it)) },
                    enabled = enabled,
                    supporting = "Пусто — как в адресе",
                    keyboardType = KeyboardType.Uri,
                )
                Field(
                    label = "HTTP CONNECT-прокси (host:port)",
                    value = settings.connectProxy,
                    onChange = { onChange(settings.copy(connectProxy = it)) },
                    enabled = enabled,
                    supporting = "Пусто — подключаться напрямую",
                    keyboardType = KeyboardType.Uri,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        label = "Таймаут, с",
                        value = settings.timeoutSeconds,
                        onChange = { onChange(settings.copy(timeoutSeconds = it)) },
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    Field(
                        label = "Долгие проверки, с",
                        value = settings.longSeconds,
                        onChange = { onChange(settings.copy(longSeconds = it)) },
                        enabled = enabled,
                        supporting = "0 — пропустить",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        label = "Большое сообщение, КиБ",
                        value = settings.largeSizeKb,
                        onChange = { onChange(settings.copy(largeSizeKb = it)) },
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    Field(
                        label = "Сообщений в потоке",
                        value = settings.streamCount,
                        onChange = { onChange(settings.copy(streamCount = it)) },
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Field(
                    label = "Параллельных вызовов",
                    value = settings.concurrency,
                    onChange = { onChange(settings.copy(concurrency = it)) },
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private enum class RowState { IDLE, PENDING, RUNNING }

@Composable
private fun CheckRow(
    check: CheckInfo,
    result: CheckResult?,
    state: RowState,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = enabled)
        Column(
            Modifier
                .weight(1f)
                .padding(top = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Mark(state, result?.kind)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    check.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (result != null && result.duration.isNotEmpty()) {
                    Text(result.duration, style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
            Text(
                result?.detail ?: check.description,
                style = MaterialTheme.typography.bodySmall,
                color = when (result?.kind) {
                    ResultKind.FAILED -> MaterialTheme.colorScheme.error
                    ResultKind.PASSED -> MaterialTheme.colorScheme.onSurface
                    else -> muted
                },
            )
            result?.hint?.let {
                Text("↳ $it", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
private fun Mark(state: RowState, kind: ResultKind?) {
    when {
        state == RowState.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        kind == ResultKind.PASSED -> Text("✓", color = successColor(), fontWeight = FontWeight.Bold)
        kind == ResultKind.FAILED -> Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        kind == ResultKind.SKIPPED -> Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state == RowState.PENDING -> Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusText(line: StatusLine) {
    Column {
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = when (line.kind) {
                StatusKind.OK -> successColor()
                StatusKind.ERROR -> MaterialTheme.colorScheme.error
                StatusKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        line.hint?.let {
            Text("↳ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** В Material 3 нет «зелёного» цвета — берём свой, под светлую или тёмную тему. */
@Composable
private fun successColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)
