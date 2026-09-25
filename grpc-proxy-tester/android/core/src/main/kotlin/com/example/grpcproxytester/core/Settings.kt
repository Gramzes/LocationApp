package com.example.grpcproxytester.core

/** Параметры подключения к проверяемому прокси. */
data class ConnectionConfig(
    val address: String,
    val useTls: Boolean = false,
    val skipTlsVerify: Boolean = false,
    val authority: String = "",
    val headers: List<Header> = emptyList(),
    /** HTTP CONNECT-прокси в виде host:port; пусто — подключаться напрямую. */
    val connectProxy: String = "",
    val maxMessageSize: Int = 64 shl 20,
) {
    fun describe(): String = buildList {
        add(if (useTls) "TLS" + if (skipTlsVerify) " без проверки сертификата" else "" else "plaintext/h2c")
        if (authority.isNotEmpty()) add("authority=$authority")
        if (connectProxy.isNotEmpty()) add("через CONNECT $connectProxy")
        if (headers.isNotEmpty()) add("доп. заголовков: ${headers.size}")
    }.joinToString(", ", prefix = "$address (", postfix = ")")
}

data class Header(val key: String, val value: String)

/** Параметры самих проверок. */
data class CheckConfig(
    val timeoutMs: Long = 10_000,
    val streamCount: Int = 10,
    val largeSize: Int = 1 shl 20,
    val concurrency: Int = 50,
    /** Длительность для slow-unary и idle-stream; 0 — эти проверки пропускаются. */
    val longMs: Long = 0,
)

/**
 * Настройки в том виде, в котором их редактирует пользователь (числа — строками,
 * чтобы поле можно было временно оставить пустым).
 */
data class Settings(
    val address: String = "10.0.2.2:50051",
    val useTls: Boolean = false,
    val skipTlsVerify: Boolean = false,
    val authority: String = "",
    val headers: String = "",
    val connectProxy: String = "",
    val timeoutSeconds: String = "10",
    val longSeconds: String = "0",
    val largeSizeKb: String = "1024",
    val streamCount: String = "10",
    val concurrency: String = "50",
    val disabledChecks: Set<String> = emptySet(),
) {
    /** @throws IllegalArgumentException с понятным описанием ошибки. */
    fun toConnectionConfig(): ConnectionConfig {
        val addr = address.trim()
        require(addr.isNotEmpty()) { "Укажите адрес прокси" }
        parseHostPort(addr, "Адрес прокси")
        val proxy = connectProxy.trim()
        if (proxy.isNotEmpty()) parseHostPort(proxy, "CONNECT-прокси")
        return ConnectionConfig(
            address = addr,
            useTls = useTls || skipTlsVerify,
            skipTlsVerify = skipTlsVerify,
            authority = authority.trim(),
            headers = parseHeaders(headers),
            connectProxy = proxy,
        )
    }

    /** @throws IllegalArgumentException с понятным описанием ошибки. */
    fun toCheckConfig(): CheckConfig = CheckConfig(
        timeoutMs = number(timeoutSeconds, "Таймаут проверки", min = 1) * 1000,
        longMs = number(longSeconds, "Длительность долгих проверок", min = 0) * 1000,
        largeSize = number(largeSizeKb, "Размер большого сообщения", min = 0, max = 60 * 1024).toInt() * 1024,
        streamCount = number(streamCount, "Сообщений в потоке", min = 1, max = 10_000).toInt(),
        concurrency = number(concurrency, "Параллельных вызовов", min = 1, max = 10_000).toInt(),
    )

    private fun number(s: String, what: String, min: Long, max: Long = Long.MAX_VALUE): Long {
        val n = s.trim().toLongOrNull()
        require(n != null && n in min..max) {
            if (max == Long.MAX_VALUE) "$what: нужно целое число не меньше $min" else "$what: нужно целое число от $min до $max"
        }
        return n
    }
}

private val headerKeyRegex = Regex("[0-9a-z_.-]+")

/** Разбирает заголовки вида "ключ: значение" (или "ключ=значение"), по одному на строку. */
fun parseHeaders(text: String): List<Header> = text.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        val sep = line.indexOfFirst { it == ':' || it == '=' }
        require(sep > 0) { "Заголовок \"$line\": ожидается \"ключ: значение\"" }
        val key = line.substring(0, sep).trim().lowercase()
        require(headerKeyRegex.matches(key)) { "Заголовок \"$key\": допустимы только латиница, цифры и - _ ." }
        Header(key, line.substring(sep + 1).trim())
    }

/** Разбирает host:port (IPv6 — в квадратных скобках: [::1]:8080). */
internal fun parseHostPort(s: String, what: String): Pair<String, Int> {
    val colon = s.lastIndexOf(':')
    require(colon > 0 && colon < s.length - 1) { "$what: нужен формат host:port" }
    val host = s.substring(0, colon).removePrefix("[").removeSuffix("]")
    val port = s.substring(colon + 1).toIntOrNull()
    require(host.isNotEmpty() && port != null && port in 1..65535) { "$what: нужен формат host:port" }
    return host to port
}
