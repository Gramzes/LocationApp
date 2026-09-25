package com.example.grpcproxytester

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.grpcproxytester.core.AUTOMATION_TAG
import com.example.grpcproxytester.core.AutomationRequest
import com.example.grpcproxytester.core.CheckConfig
import com.example.grpcproxytester.core.CheckResult
import com.example.grpcproxytester.core.ConnectionConfig
import com.example.grpcproxytester.core.ProxyTester
import com.example.grpcproxytester.core.Settings
import com.example.grpcproxytester.core.TesterEvent
import com.example.grpcproxytester.core.automationError
import com.example.grpcproxytester.core.automationLine
import com.example.grpcproxytester.core.formatReport
import com.example.grpcproxytester.ui.StatusKind
import com.example.grpcproxytester.ui.StatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Состояние экрана; живёт дольше активности, поэтому прогон не прерывается при повороте. */
class TesterViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var settings by mutableStateOf(store.load())
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf<StatusLine?>(null)
        private set
    var results by mutableStateOf<Map<String, CheckResult>>(emptyMap())
        private set
    var current by mutableStateOf<String?>(null)
        private set
    var summary by mutableStateOf<StatusLine?>(null)
        private set

    private var target = ""
    private var job: Job? = null

    /** run_id прогона, запущенного через интент (см. Automation.kt); null — прогон запустил человек. */
    private var automationRunId: String? = null

    fun updateSettings(s: Settings) {
        settings = s
        store.save(s)
    }

    /**
     * Прогон по интенту: параметры из интента поверх сохранённых настроек,
     * каждое событие — JSON-строкой в logcat. Сохранённые настройки не меняются.
     */
    fun startAutomation(req: AutomationRequest) {
        if (running) {
            Log.i(AUTOMATION_TAG, automationError(req.runId, "Уже идёт другой прогон"))
            return
        }
        settings = try {
            req.applyTo(store.load())
        } catch (e: IllegalArgumentException) {
            Log.i(AUTOMATION_TAG, automationError(req.runId, e.message ?: e.toString()))
            return
        }
        automationRunId = req.runId
        start()
        if (!running) automationRunId = null
    }

    fun start() {
        if (running) return
        results = emptyMap()
        summary = null
        current = null
        val conn: ConnectionConfig
        val cfg: CheckConfig
        try {
            conn = settings.toConnectionConfig()
            cfg = settings.toCheckConfig()
        } catch (e: IllegalArgumentException) {
            status = StatusLine(e.message ?: "Ошибка в настройках", StatusKind.ERROR)
            automationRunId?.let { Log.i(AUTOMATION_TAG, automationError(it, status!!.text)) }
            return
        }
        val selected = ProxyTester.checks.map { it.name }.filterNot { it in settings.disabledChecks }.toSet()
        if (selected.isEmpty()) {
            status = StatusLine("Не выбрано ни одной проверки", StatusKind.ERROR)
            automationRunId?.let { Log.i(AUTOMATION_TAG, automationError(it, status!!.text)) }
            return
        }

        running = true
        job = viewModelScope.launch {
            try {
                ProxyTester.run(conn, cfg, selected)
                    .flowOn(Dispatchers.Default)
                    .collect { handle(it) }
            } finally {
                if (summary == null && status?.kind != StatusKind.ERROR) {
                    summary = StatusLine("Остановлено")
                }
                running = false
                current = null
                automationRunId = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun handle(event: TesterEvent) {
        automationRunId?.let { id -> automationLine(id, event)?.let { Log.i(AUTOMATION_TAG, it) } }
        when (event) {
            is TesterEvent.Connecting -> {
                target = event.target
                status = StatusLine("Подключаюсь к ${event.target}…")
            }
            is TesterEvent.Connected ->
                status = StatusLine("Соединение с $target установлено за ${event.elapsed}", StatusKind.OK)
            is TesterEvent.ConnectFailed ->
                status = StatusLine("Не удалось подключиться: ${event.message}", StatusKind.ERROR, event.hint)
            is TesterEvent.CheckStarted -> current = event.name
            is TesterEvent.CheckFinished -> {
                results = results + (event.result.name to event.result)
                current = null
            }
            is TesterEvent.Done -> summary = StatusLine(
                "Итого: пройдено ${event.passed}, провалено ${event.failed}, пропущено ${event.skipped}",
                if (event.failed > 0) StatusKind.ERROR else StatusKind.OK,
            )
        }
    }

    fun report(): String = formatReport(
        target = target,
        connection = status?.let { listOfNotNull(it.text, it.hint?.let { h -> "↳ $h" }).joinToString("\n") },
        results = ProxyTester.checks.mapNotNull { results[it.name] },
        summary = summary?.text,
    )
}
