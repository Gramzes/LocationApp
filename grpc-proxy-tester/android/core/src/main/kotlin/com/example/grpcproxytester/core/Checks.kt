package com.example.grpcproxytester.core

import com.example.grpcproxytester.health.HealthCheckRequest
import com.example.grpcproxytester.health.HealthCheckResponse
import com.example.grpcproxytester.health.HealthGrpcKt.HealthCoroutineStub
import com.example.grpcproxytester.proto.Request
import com.example.grpcproxytester.proto.Response
import com.example.grpcproxytester.proto.StreamRequest
import com.example.grpcproxytester.proto.TesterGrpcKt.TesterCoroutineStub
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.random.Random

/** Описание одной проверки. */
class CheckInfo internal constructor(
    val name: String,
    val description: String,
    /** Что, скорее всего, не так с прокси, если проверка упала. */
    internal val hint: String?,
    /** Добавка к таймауту проверки. */
    internal val extraTimeoutMs: (CheckConfig) -> Long = { 0 },
    internal val run: suspend CheckContext.() -> String,
)

/** Проверка не прошла; [code] — gRPC-код, если провал связан с ошибкой вызова. */
internal class CheckFailure(message: String, val code: Status.Code? = null) : Exception(message)

internal class CheckSkipped(message: String) : Exception(message)

internal fun fail(message: String): Nothing = throw CheckFailure(message)

internal class CheckContext(
    val channel: ManagedChannel,
    val cfg: CheckConfig,
    /** Заголовки, которые клиент отправляет сам (чтобы не считать их добавленными прокси). */
    val sentHeaders: Set<String>,
    maxMessageSize: Int,
    timeoutMs: Long,
) {
    val stub: TesterCoroutineStub = TesterCoroutineStub(channel)
        .withDeadlineAfter(timeoutMs, MILLISECONDS)
        .withMaxOutboundMessageSize(maxMessageSize)
    val health: HealthCoroutineStub = HealthCoroutineStub(channel).withDeadlineAfter(timeoutMs, MILLISECONDS)
}

// Служебные заголовки, которыми обмениваются клиент и тестовый сервер.
private val SERVER_ID_KEY = Metadata.Key.of("x-tester-server-id", Metadata.ASCII_STRING_MARSHALLER)
private val ECHO_KEY = Metadata.Key.of("x-tester-echo", Metadata.ASCII_STRING_MARSHALLER)
private val ECHO_BIN_KEY = Metadata.Key.of("x-tester-echo-bin", Metadata.BINARY_BYTE_MARSHALLER)
private val TRAILER_KEY = Metadata.Key.of("x-tester-trailer", Metadata.ASCII_STRING_MARSHALLER)

private const val SERVER_STREAM_INTERVAL_MS = 100L

internal val ALL_CHECKS: List<CheckInfo> = listOf(
    CheckInfo(
        name = "health",
        description = "стандартный grpc.health.v1.Health/Check",
        hint = "если прокси пропускает только /proxytester.v1.Tester/*, эта проверка может падать — это нормально",
        run = { checkHealth() },
    ),
    CheckInfo(
        name = "unary",
        description = "простой unary-вызов",
        hint = null,
        run = { checkUnary() },
    ),
    CheckInfo(
        name = "metadata",
        description = "заголовки запроса, заголовки ответа, трейлеры, -bin заголовки",
        hint = "прокси теряет или меняет заголовки/трейлеры (частая проблема HTTP/1.1-прокси и прокси без поддержки трейлеров)",
        run = { checkMetadata() },
    ),
    CheckInfo(
        name = "error-status",
        description = "код и текст ошибки (grpc-status/grpc-message) + трейлеры в trailers-only ответе",
        hint = "прокси подменяет статус ошибки или теряет трейлеры в ответе без тела",
        run = { checkErrorStatus() },
    ),
    CheckInfo(
        name = "deadline",
        description = "grpc-timeout доходит до сервера, DEADLINE_EXCEEDED приходит вовремя",
        hint = "прокси не передаёт заголовок grpc-timeout на бэкенд",
        run = { checkDeadline() },
    ),
    CheckInfo(
        name = "large-message",
        description = "большое сообщение в обе стороны без повреждений",
        hint = "у прокси лимит на размер сообщения (например, client_max_body_size у nginx) — увеличьте его или уменьшите размер",
        run = { checkLargeMessage() },
    ),
    CheckInfo(
        name = "compression",
        description = "сообщения, сжатые gzip",
        hint = "прокси не пропускает grpc-encoding или ломает сжатые сообщения",
        run = { checkCompression() },
    ),
    CheckInfo(
        name = "server-stream",
        description = "серверный поток приходит по сообщению, а не пачкой в конце",
        hint = "прокси буферизует ответ целиком (отключите буферизацию ответа)",
        extraTimeoutMs = { it.streamCount * SERVER_STREAM_INTERVAL_MS },
        run = { checkServerStream() },
    ),
    CheckInfo(
        name = "client-stream",
        description = "клиентский поток",
        hint = "прокси не поддерживает клиентские потоки или буферизует запрос",
        run = { checkClientStream() },
    ),
    CheckInfo(
        name = "bidi-stream",
        description = "двунаправленный поток в режиме пинг-понг",
        hint = "прокси ждёт окончания запроса, прежде чем отдать его бэкенду (буферизует bidi)",
        run = { checkBidiStream() },
    ),
    CheckInfo(
        name = "stream-error",
        description = "ошибка после нескольких сообщений потока",
        hint = "прокси теряет трейлеры со статусом после тела ответа",
        run = { checkStreamError() },
    ),
    CheckInfo(
        name = "concurrency",
        description = "много параллельных вызовов (мультиплексирование HTTP/2)",
        hint = "прокси ограничивает число одновременных потоков или обрабатывает их последовательно",
        run = { checkConcurrency() },
    ),
    CheckInfo(
        name = "slow-unary",
        description = "unary-вызов, который длится заданное время",
        hint = "прокси обрывает долгие вызовы (у Envoy таймаут маршрута по умолчанию 15s, у nginx — grpc_read_timeout 60s)",
        extraTimeoutMs = { it.longMs },
        run = { checkSlowUnary() },
    ),
    CheckInfo(
        name = "idle-stream",
        description = "поток, который заданное время молчит, а потом продолжает работу",
        hint = "прокси закрывает простаивающие потоки (idle timeout; у nginx — grpc_read_timeout)",
        extraTimeoutMs = { it.longMs },
        run = { checkIdleStream() },
    ),
)

private inline fun request(block: Request.Builder.() -> Unit): Request =
    Request.newBuilder().apply(block).build()

private suspend fun CheckContext.checkHealth(): String {
    val resp = health.check(HealthCheckRequest.getDefaultInstance())
    if (resp.status != HealthCheckResponse.ServingStatus.SERVING) fail("статус ${resp.status}")
    return "SERVING"
}

private suspend fun CheckContext.checkUnary(): String {
    val msg = "привет, прокси!"
    val resp = stub.unary(request { setMessage(msg) })
    if (resp.message != msg) fail("вернулось \"${resp.message}\" вместо \"$msg\"")
    var detail = "сервер \"${resp.serverId}\", видит клиента как ${resp.peer}"
    resp.receivedMetadataMap[":authority"]?.takeIf { it.isNotEmpty() }?.let { detail += ", authority=$it" }
    return detail
}

/** Заголовки, которые клиент gRPC отправляет сам. */
private val STANDARD_HEADERS = setOf(
    ":authority", "content-type", "user-agent", "grpc-accept-encoding", "grpc-encoding", "grpc-timeout", "te",
)

private suspend fun CheckContext.checkMetadata(): String {
    val token = randomToken()
    val binVal = byteArrayOf(0x00, 0xff.toByte(), '\n'.code.toByte(), 0x80.toByte()) + token.toByteArray()
    val md = Metadata().apply {
        put(ECHO_KEY, token)
        put(ECHO_BIN_KEY, binVal)
    }
    val cap = CallCapture()
    val resp = stub.withOption(CAPTURE, cap).unary(request { setMessage("metadata") }, md)

    val problems = mutableListOf<String>()
    val got = resp.receivedMetadataMap
    if (got[ECHO_KEY.name()] != token) {
        problems += "сервер получил ${ECHO_KEY.name()}=\"${got[ECHO_KEY.name()]}\" вместо \"$token\""
    }
    if (got[ECHO_BIN_KEY.name()] != Base64.getEncoder().encodeToString(binVal)) {
        problems += "бинарный заголовок ${ECHO_BIN_KEY.name()} не дошёл до сервера или повреждён"
    }
    if (cap.headers?.get(SERVER_ID_KEY).isNullOrEmpty()) {
        problems += "нет заголовка ответа ${SERVER_ID_KEY.name()}"
    }
    cap.headers?.get(ECHO_KEY).let {
        if (it != token) problems += "заголовок ответа ${ECHO_KEY.name()}=\"$it\" вместо \"$token\""
    }
    if (cap.trailers?.get(TRAILER_KEY) != "done") {
        problems += "нет трейлера ${TRAILER_KEY.name()}"
    }
    if (!(cap.trailers?.get(ECHO_BIN_KEY) contentEquals binVal)) {
        problems += "бинарный трейлер ${ECHO_BIN_KEY.name()} не дошёл или повреждён"
    }
    if (problems.isNotEmpty()) fail(problems.joinToString("; "))

    val sent = sentHeaders + ECHO_KEY.name() + ECHO_BIN_KEY.name()
    val added = got.keys.filter { it !in sent && it !in STANDARD_HEADERS }.sorted()
    return if (added.isEmpty()) {
        "всё дошло, прокси не добавил своих заголовков"
    } else {
        "всё дошло; прокси добавил: " + added.joinToString(", ")
    }
}

private suspend fun CheckContext.checkErrorStatus(): String {
    val msg = "тестовая ошибка: юникод ✓, проценты 100%, перевод\nстроки"
    val cap = CallCapture()
    val err = expectError("вызов завершился успешно, хотя сервер вернул ошибку") {
        stub.withOption(CAPTURE, cap).unary(request {
            setFailCode(Status.Code.NOT_FOUND.value())
            setFailMessage(msg)
        })
    }
    val st = Status.fromThrowable(err)
    if (st.code != Status.Code.NOT_FOUND) fail("код ${st.code} вместо NOT_FOUND (\"${st.description}\")")
    if (st.description != msg) fail("текст ошибки искажён: \"${st.description}\"")
    val trailers = Status.trailersFromThrowable(err) ?: cap.trailers
    if (trailers?.get(TRAILER_KEY) != "done") fail("статус дошёл, но трейлер ${TRAILER_KEY.name()} потерян")
    return "NOT_FOUND с исходным текстом и трейлерами"
}

private suspend fun CheckContext.checkDeadline(): String {
    // 1. Видит ли сервер дедлайн клиента.
    val budget = 5_000L
    val resp = stub.withDeadlineAfter(budget, MILLISECONDS).unary(request { setMessage("deadline") })
    val serverSees = resp.deadlineMs
    if (serverSees == 0L) fail("сервер не видит дедлайн клиента — grpc-timeout не дошёл")
    if (serverSees > budget) fail("сервер видит дедлайн ${fmtMs(serverSees)}, а клиент ставил ${fmtMs(budget)}")

    // 2. Вызов дольше дедлайна прерывается вовремя.
    val short = 500L
    val start = System.nanoTime()
    val err = expectError("вызов с задержкой 3s успешно завершился при дедлайне 500ms") {
        stub.withDeadlineAfter(short, MILLISECONDS).unary(request { setDelayMs(3000) })
    }
    val elapsed = sinceNanos(start)
    val st = Status.fromThrowable(err)
    if (st.code != Status.Code.DEADLINE_EXCEEDED) fail("код ${st.code} вместо DEADLINE_EXCEEDED: ${st.description}")
    if (elapsed > (short + 1000) * 1_000_000) fail("DEADLINE_EXCEEDED пришёл только через ${fmtNanos(elapsed)}")
    return "сервер видит дедлайн (${fmtMs(serverSees)} из ${fmtMs(budget)}), DEADLINE_EXCEEDED через ${fmtNanos(elapsed)}"
}

private suspend fun CheckContext.checkLargeMessage(): String {
    val n = cfg.largeSize
    unaryWithPayload(Random.nextBytes(n), n, stub)
    return "${humanBytes(n.toLong())} туда и обратно, данные целы"
}

private suspend fun CheckContext.checkCompression(): String {
    val payload = "сжимаемые данные ".repeat(4096).toByteArray()
    unaryWithPayload(payload, 64 shl 10, stub.withCompression("gzip"))
    return "gzip в обе стороны"
}

/** Отправляет payload и просит вернуть respSize байт, проверяя целостность обоих. */
private suspend fun unaryWithPayload(payload: ByteArray, respSize: Int, stub: TesterCoroutineStub) {
    val resp = stub.unary(request {
        setPayload(ByteString.copyFrom(payload))
        setResponseSize(respSize)
    })
    if (resp.receivedPayloadSize != payload.size) {
        fail("сервер получил ${resp.receivedPayloadSize} байт вместо ${payload.size}")
    }
    if (resp.receivedPayloadSha256 != sha256Hex(payload)) fail("данные запроса повреждены по пути к серверу")
    verifyPayload(resp.payload.toByteArray(), respSize)?.let { fail("ответ: $it") }
}

private suspend fun CheckContext.checkServerStream(): String {
    val n = cfg.streamCount
    val req = StreamRequest.newBuilder()
        .setRequest(request { setMessage("stream") })
        .setCount(n)
        .setIntervalMs(SERVER_STREAM_INTERVAL_MS.toInt())
        .build()
    val start = System.nanoTime()
    var firstAt = 0L
    var got = 0
    withErrorContext({ "после $got сообщений из $n" }) {
        stub.serverStream(req).collect { resp ->
            got++
            if (got == 1) firstAt = sinceNanos(start)
            if (resp.seq != got.toLong()) fail("нарушен порядок: сообщение №${resp.seq} пришло $got-м")
        }
    }
    val total = sinceNanos(start)
    if (got != n) fail("получено $got сообщений из $n")
    val spanNanos = (n - 1) * SERVER_STREAM_INTERVAL_MS * 1_000_000
    if (n > 2 && firstAt > spanNanos / 2) {
        fail("первое сообщение пришло через ${fmtNanos(firstAt)} при длительности потока ${fmtNanos(total)} — ответ буферизуется")
    }
    return "$n сообщений, первое через ${fmtNanos(firstAt)}, все за ${fmtNanos(total)}"
}

private suspend fun CheckContext.checkClientStream(): String {
    val n = cfg.streamCount
    val total = 100L * n * (n + 1) / 2
    val requests = flow {
        for (i in 1..n) {
            val p = makePayload(i * 100)
            emit(request {
                setMessage("msg-$i")
                setPayload(ByteString.copyFrom(p))
            })
        }
    }
    val sum = stub.clientStream(requests)
    if (sum.count != n.toLong() || sum.totalBytes != total) {
        fail("сервер получил ${sum.count} сообщений (${sum.totalBytes} байт) вместо $n ($total байт)")
    }
    if (sum.lastMessage != "msg-$n") fail("последнее сообщение \"${sum.lastMessage}\" вместо \"msg-$n\"")
    return "$n сообщений, ${humanBytes(total)}"
}

private suspend fun CheckContext.checkBidiStream(): String = coroutineScope {
    val n = cfg.streamCount
    val session = BidiSession(this, stub)
    try {
        var rttSum = 0L
        for (i in 1..n) {
            rttSum += withErrorContext("сообщение $i из $n") { session.pingPong("ping-$i", i.toLong()) }
        }
        session.closeSend()
        session.awaitEnd()?.let { fail("после закрытия потока ожидалось завершение, получено: ${describeError(it)}") }
        "$n пинг-понгов, средний RTT ${fmtNanos(rttSum / n)}"
    } finally {
        session.cancel()
    }
}

private suspend fun CheckContext.checkStreamError(): String {
    val n = 3
    val msg = "ошибка после данных"
    val req = StreamRequest.newBuilder()
        .setRequest(request {
            setMessage("x")
            setFailCode(Status.Code.ABORTED.value())
            setFailMessage(msg)
        })
        .setCount(n)
        .build()
    var got = 0
    val err = catching { stub.serverStream(req).collect { got++ } }
    if (got != n) fail("получено $got сообщений из $n до ошибки")
    if (err == null) fail("поток завершился успешно, хотя сервер вернул ABORTED")
    val st = Status.fromThrowable(err)
    if (st.code != Status.Code.ABORTED || st.description != msg) {
        fail("получено ${st.code} \"${st.description}\" вместо ABORTED \"$msg\"")
    }
    return "$n сообщения, затем ABORTED"
}

private suspend fun CheckContext.checkConcurrency(): String {
    val n = cfg.concurrency
    val delayMs = 200
    val start = System.nanoTime()
    val results: List<Result<Response>> = coroutineScope {
        (0 until n).map { i ->
            async {
                val msg = "parallel-$i"
                val r = catchingResult { stub.unary(request { setMessage(msg); setDelayMs(delayMs) }) }
                r.fold(
                    onSuccess = { if (it.message == msg) r else Result.failure(CheckFailure("ответ \"${it.message}\" вместо \"$msg\"")) },
                    onFailure = { r },
                )
            }
        }.awaitAll()
    }
    val elapsed = sinceNanos(start)
    val errors = results.mapNotNull { it.exceptionOrNull() }
    if (errors.isNotEmpty()) {
        throw CheckFailure(
            "${errors.size} из $n вызовов с ошибкой, первая: ${describeError(errors[0])}",
            codeOf(errors[0]),
        )
    }
    val serial = n.toLong() * delayMs * 1_000_000
    if (n > 4 && elapsed > serial / 4) {
        fail("$n вызовов по ${delayMs}ms заняли ${fmtNanos(elapsed)} — они выполняются почти последовательно")
    }
    var detail = "$n вызовов по ${delayMs}ms за ${fmtNanos(elapsed)}"
    val servers = results.map { it.getOrThrow().serverId }.groupingBy { it }.eachCount()
    if (servers.size > 1) {
        detail += "; ответили серверы: " + servers.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}×${it.value}" }
    }
    return detail
}

private suspend fun CheckContext.checkSlowUnary(): String {
    if (cfg.longMs <= 0) throw CheckSkipped("задайте длительность долгих проверок, например 30 с")
    val start = System.nanoTime()
    val resp = withErrorContext({ "через ${fmtNanos(sinceNanos(start))}" }) {
        stub.unary(request {
            setMessage("slow")
            setDelayMs(cfg.longMs.toInt())
        })
    }
    if (resp.message != "slow") fail("ответ \"${resp.message}\" вместо \"slow\"")
    return "ответ через ${fmtNanos(sinceNanos(start))}"
}

private suspend fun CheckContext.checkIdleStream(): String = coroutineScope {
    if (cfg.longMs <= 0) throw CheckSkipped("задайте длительность долгих проверок, например 30 с")
    val session = BidiSession(this, stub)
    try {
        withErrorContext("до паузы") { session.pingPong("before-idle", 1) }
        delay(cfg.longMs)
        withErrorContext("после паузы ${fmtMs(cfg.longMs)}") { session.pingPong("after-idle", 2) }
        session.closeSend()
        "поток пережил паузу ${fmtMs(cfg.longMs)}"
    } finally {
        session.cancel()
    }
}

/** Двунаправленный поток, в который можно по очереди отправлять запросы и читать ответы. */
private class BidiSession(scope: CoroutineScope, stub: TesterCoroutineStub) {
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val responses = Channel<Response>(Channel.UNLIMITED)
    private val job = scope.launch {
        try {
            stub.bidiStream(requests.consumeAsFlow()).collect { responses.send(it) }
            responses.close()
        } catch (e: Throwable) {
            responses.close(e)
        }
    }

    /** Отправляет сообщение, ждёт ответ и возвращает время туда-обратно в наносекундах. */
    suspend fun pingPong(msg: String, seq: Long): Long {
        val start = System.nanoTime()
        // Если поток уже оборван, отправка не удастся, а настоящая причина придёт в ответах.
        requests.trySend(request { setMessage(msg) })
        val resp = responses.receiveCatching().let {
            it.getOrNull() ?: throw it.exceptionOrNull() ?: CheckFailure("сервер закрыл поток, не ответив")
        }
        if (resp.message != msg || resp.seq != seq) {
            fail("ответ \"${resp.message}\" (seq ${resp.seq}) вместо \"$msg\" (seq $seq)")
        }
        return sinceNanos(start)
    }

    fun closeSend() {
        requests.close()
    }

    /** Ждёт завершения потока со стороны сервера; возвращает ошибку, если он завершился с ней. */
    suspend fun awaitEnd(): Throwable? {
        val r = responses.receiveCatching()
        if (r.isSuccess) return CheckFailure("лишнее сообщение \"${r.getOrNull()?.message}\"")
        return r.exceptionOrNull()
    }

    fun cancel() {
        job.cancel()
    }
}

/** Добавляет к ошибке вызова контекст ("после 3 сообщений из 10: ..."), сохраняя её код. */
private suspend fun <T> withErrorContext(context: () -> String, block: suspend () -> T): T = try {
    block()
} catch (e: CheckFailure) {
    throw e
} catch (e: Exception) {
    rethrowIfCancelled(e)
    throw CheckFailure("${context()}: ${describeError(e)}", codeOf(e))
}

private suspend fun <T> withErrorContext(context: String, block: suspend () -> T): T = withErrorContext({ context }, block)

/** Выполняет блок и возвращает ошибку (или null), не перехватывая отмену корутины. */
private suspend fun catching(block: suspend () -> Unit): Throwable? = catchingResult(block).exceptionOrNull()

private suspend fun <T> catchingResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    rethrowIfCancelled(e)
    Result.failure(e)
}

/**
 * CancellationException приходит не только когда отменили нас самих: grpc-kotlin
 * так же оборачивает ошибку вызова (например, поток, оборванный прокси).
 * Пробрасываем её дальше, только если отменена именно текущая корутина.
 */
internal suspend fun rethrowIfCancelled(e: Exception) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}

private suspend fun expectError(messageIfSuccess: String, block: suspend () -> Unit): Throwable =
    catching(block) ?: fail(messageIfSuccess)

private fun randomToken(): String = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
