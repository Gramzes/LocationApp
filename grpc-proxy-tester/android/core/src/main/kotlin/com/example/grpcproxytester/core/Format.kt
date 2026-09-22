package com.example.grpcproxytester.core

import java.security.MessageDigest
import java.util.Locale

internal fun sinceNanos(start: Long): Long = System.nanoTime() - start

/** Длительность для вывода: доли миллисекунды — в мкс, до секунды — в мс, дальше — в секундах. */
fun fmtNanos(nanos: Long): String = when {
    nanos < 1_000_000 -> "${nanos / 1_000}µs"
    nanos < 1_000_000_000 -> "${nanos / 1_000_000}ms"
    else -> String.format(Locale.US, "%.3f", nanos / 1e9).trimEnd('0').trimEnd('.') + "s"
}

internal fun fmtMs(ms: Long): String = fmtNanos(ms * 1_000_000)

internal fun humanBytes(n: Long): String = when {
    n >= 1 shl 20 && n % (1 shl 20) == 0L -> "${n shr 20} MiB"
    n >= 1 shl 20 -> String.format(Locale.US, "%.1f MiB", n / 1048576.0)
    n >= 1 shl 10 -> String.format(Locale.US, "%.1f KiB", n / 1024.0)
    else -> "$n B"
}

/** n байт с предсказуемым содержимым — так же их генерирует тестовый сервер. */
internal fun makePayload(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

/** Проверяет, что данные совпадают с [makePayload]; возвращает описание проблемы или null. */
internal fun verifyPayload(b: ByteArray, n: Int): String? {
    if (b.size != n) return "получено ${b.size} байт вместо $n"
    for (i in b.indices) {
        if (b[i] != (i % 251).toByte()) return "данные повреждены начиная с байта $i"
    }
    return null
}

internal fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
