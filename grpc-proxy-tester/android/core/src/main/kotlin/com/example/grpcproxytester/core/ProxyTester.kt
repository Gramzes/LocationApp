package com.example.grpcproxytester.core

import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

enum class ResultKind { PASSED, FAILED, SKIPPED }

data class CheckResult(
    val name: String,
    val kind: ResultKind,
    val detail: String,
    val hint: String? = null,
    val durationNanos: Long = 0,
) {
    val duration: String get() = if (kind == ResultKind.SKIPPED) "" else fmtNanos(durationNanos)
}

/** События прогона — по ним UI обновляет экран. */
sealed interface TesterEvent {
    data class Connecting(val target: String) : TesterEvent
    data class Connected(val elapsed: String) : TesterEvent
    data class ConnectFailed(val message: String, val hint: String?) : TesterEvent
    data class CheckStarted(val name: String) : TesterEvent
    data class CheckFinished(val result: CheckResult) : TesterEvent
    data class Done(val passed: Int, val failed: Int, val skipped: Int) : TesterEvent
}

object ProxyTester {
    val checks: List<CheckInfo> get() = ALL_CHECKS

    /**
     * Подключается к прокси и по очереди выполняет выбранные проверки.
     * Отмена корутины, собирающей поток, останавливает прогон.
     */
    fun run(conn: ConnectionConfig, cfg: CheckConfig, selected: Set<String>): Flow<TesterEvent> = flow {
        emit(TesterEvent.Connecting(conn.describe()))
        val start = System.nanoTime()
        val channel = try {
            buildChannel(conn)
        } catch (e: IllegalArgumentException) {
            emit(TesterEvent.ConnectFailed(e.message ?: e.toString(), null))
            return@flow
        }
        try {
            try {
                withTimeout(cfg.timeoutMs) { awaitReady(channel) }
            } catch (e: TimeoutCancellationException) {
                emit(TesterEvent.ConnectFailed("соединение не установлено за ${fmtMs(cfg.timeoutMs)}", null))
                return@flow
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                emit(TesterEvent.ConnectFailed(describeError(e), codeHint(codeOf(e))))
                return@flow
            }
            emit(TesterEvent.Connected(fmtNanos(sinceNanos(start))))

            val counts = IntArray(ResultKind.entries.size)
            for (check in ALL_CHECKS) {
                if (check.name !in selected) continue
                emit(TesterEvent.CheckStarted(check.name))
                val result = runCheck(check, channel, conn, cfg)
                counts[result.kind.ordinal]++
                emit(TesterEvent.CheckFinished(result))
            }
            emit(
                TesterEvent.Done(
                    passed = counts[ResultKind.PASSED.ordinal],
                    failed = counts[ResultKind.FAILED.ordinal],
                    skipped = counts[ResultKind.SKIPPED.ordinal],
                ),
            )
        } finally {
            channel.shutdownNow()
        }
    }

    private suspend fun runCheck(
        check: CheckInfo,
        channel: ManagedChannel,
        conn: ConnectionConfig,
        cfg: CheckConfig,
    ): CheckResult {
        val timeout = cfg.timeoutMs + check.extraTimeoutMs(cfg)
        val ctx = CheckContext(channel, cfg, conn.headers.map { it.key }.toSet(), conn.maxMessageSize, timeout)
        val start = System.nanoTime()
        fun result(kind: ResultKind, detail: String, hint: String? = null) =
            CheckResult(check.name, kind, detail, hint, sinceNanos(start))

        return try {
            // Дедлайн вызовов — timeout; withTimeout — страховка на случай, если зависнет что-то кроме вызовов.
            result(ResultKind.PASSED, withTimeout(timeout + 2_000) { check.run(ctx) })
        } catch (e: CheckSkipped) {
            result(ResultKind.SKIPPED, e.message.orEmpty())
        } catch (e: TimeoutCancellationException) {
            result(ResultKind.FAILED, "проверка не уложилась в ${fmtMs(timeout)}", check.hint)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            result(ResultKind.FAILED, describeError(e), pickHint(check.hint, codeOf(e)))
        }
    }
}

private fun pickHint(checkHint: String?, code: Status.Code?): String? = when (code) {
    // Проблема маршрутизации/авторизации, а не того, что проверяется.
    Status.Code.UNIMPLEMENTED, Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> codeHint(code)
    else -> checkHint ?: codeHint(code)
}

/** Подсказки для кодов, которые говорят о проблеме с соединением, а не с конкретной проверкой. */
internal fun codeHint(code: Status.Code?): String? = when (code) {
    Status.Code.UNAVAILABLE ->
        "прокси недоступен, не может достучаться до бэкенда или оборвал соединение; проверьте адрес и TLS"
    Status.Code.UNIMPLEMENTED -> "метод не найден — прокси не маршрутизирует этот путь на тестовый сервер"
    Status.Code.RESOURCE_EXHAUSTED -> "превышен лимит размера сообщения или число потоков"
    Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED ->
        "прокси требует авторизацию — добавьте заголовок, например \"authorization: Bearer ...\""
    else -> null
}

/** gRPC-код ошибки, если она связана с вызовом. */
internal fun codeOf(e: Throwable): Status.Code? {
    if (e is CheckFailure) return e.code
    var t: Throwable? = e
    while (t != null) {
        if (t is StatusException || t is StatusRuntimeException) return Status.fromThrowable(t).code
        t = t.cause
    }
    return null
}

/**
 * Короткое описание ошибки: "КОД: описание (причина)". Если прокси ответил не-gRPC
 * ответом (например, HTML-страницей 413/504), остаётся только первая строка.
 */
internal fun describeError(e: Throwable): String {
    if (e is CheckFailure) return e.message.orEmpty()
    val text = if (codeOf(e) != null) {
        val st = Status.fromThrowable(e)
        val parts = listOfNotNull(st.description, st.cause?.let { it.message ?: it.javaClass.simpleName }).distinct()
        if (parts.isEmpty()) st.code.name else "${st.code}: ${parts.joinToString(": ")}"
    } else {
        e.message ?: e.javaClass.simpleName
    }
    return text.lineSequence().first()
}

/** Текстовый отчёт о прогоне, чтобы им можно было поделиться. */
fun formatReport(target: String, connection: String?, results: List<CheckResult>, summary: String?): String =
    buildString {
        appendLine("gRPC Proxy Tester: $target")
        connection?.let { appendLine(it) }
        appendLine()
        for (r in results) {
            val mark = when (r.kind) {
                ResultKind.PASSED -> "✓"
                ResultKind.FAILED -> "✗"
                ResultKind.SKIPPED -> "–"
            }
            appendLine("$mark ${r.name} ${r.duration}".trimEnd())
            appendLine("    ${r.detail}")
            r.hint?.let { appendLine("    ↳ $it") }
        }
        summary?.let {
            appendLine()
            appendLine(it)
        }
    }.trimEnd()
