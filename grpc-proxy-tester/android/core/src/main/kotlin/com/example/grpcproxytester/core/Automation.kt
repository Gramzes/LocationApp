package com.example.grpcproxytester.core

/**
 * Запуск проверок без участия человека: приложение открывают через
 * `adb shell am start ... --ez autorun true --es address host:port`, а результат
 * забирают из logcat — по одной JSON-строке на событие с тегом [AUTOMATION_TAG].
 *
 * Так приложение гоняет сквозной тест интерсептора (tools/e2e.py в
 * GrpcInterceptor): эмулятор, системный прокси, приложение, сервер.
 * Формат строк — контракт с этим скриптом, менять осторожно.
 */
const val AUTOMATION_TAG = "GrpcProxyTesterE2E"

/** Параметры автозапуска; всё, чего нет в интенте, берётся из сохранённых настроек. */
data class AutomationRequest(
    val runId: String,
    val address: String? = null,
    val useTls: Boolean? = null,
    val skipTlsVerify: Boolean? = null,
    val longSeconds: Int? = null,
    val only: Set<String>? = null,
    /** host:port HTTP CONNECT-прокси; пустая строка — напрямую (или через системный прокси). */
    val connectProxy: String? = null,
) {
    /** Настройки для этого прогона. Сохранённые пользователем настройки не меняются. */
    fun applyTo(base: Settings): Settings {
        var s = base
        address?.let { s = s.copy(address = it) }
        useTls?.let { s = s.copy(useTls = it) }
        skipTlsVerify?.let { s = s.copy(skipTlsVerify = it) }
        longSeconds?.let { s = s.copy(longSeconds = it.toString()) }
        connectProxy?.let { s = s.copy(connectProxy = it) }
        only?.let { names ->
            val unknown = names - ProxyTester.checks.map { it.name }.toSet()
            require(unknown.isEmpty()) { "Неизвестные проверки: ${unknown.joinToString()}" }
            s = s.copy(disabledChecks = ProxyTester.checks.map { it.name }.toSet() - names)
        }
        return s
    }
}

/** Одна строка для logcat: JSON-объект с run_id и типом события; null — событие не публикуется. */
fun automationLine(runId: String, event: TesterEvent): String? {
    val fields: List<Pair<String, Any>> = when (event) {
        is TesterEvent.Connecting -> listOf("event" to "connecting", "target" to event.target)
        is TesterEvent.Connected -> listOf("event" to "connected")
        is TesterEvent.ConnectFailed -> listOfNotNull(
            "event" to "connect_failed",
            "error" to event.message,
            event.hint?.let { "hint" to it },
        )
        is TesterEvent.CheckStarted -> return null
        is TesterEvent.CheckFinished -> listOfNotNull(
            "event" to "result",
            "name" to event.result.name,
            "status" to event.result.kind.name.lowercase(),
            "detail" to event.result.detail,
            event.result.hint?.let { "hint" to it },
            "duration_ms" to event.result.durationNanos / 1_000_000.0,
        )
        is TesterEvent.Done -> listOf(
            "event" to "done",
            "passed" to event.passed,
            "failed" to event.failed,
            "skipped" to event.skipped,
        )
    }
    return automationObject(listOf("run_id" to runId) + fields)
}

/** Строка для ошибки, случившейся до начала прогона (например, неверные параметры). */
fun automationError(runId: String, message: String): String =
    automationObject(listOf("run_id" to runId, "event" to "connect_failed", "error" to message))

private fun automationObject(fields: List<Pair<String, Any>>): String =
    fields.joinToString(",", "{", "}") { (k, v) ->
        jsonString(k) + ":" + when (v) {
            is String -> jsonString(v)
            else -> v.toString()
        }
    }

internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
