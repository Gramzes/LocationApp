package com.example.grpcproxytester.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Прогоняет проверки против настоящего тестового сервера (`grpc-proxy-tester server`).
 * Адреса задаются переменными окружения, без них тесты пропускаются:
 *
 *  - TESTER_ADDR — сервер или прокси перед ним, plaintext;
 *  - TESTER_TLS_ADDR — то же по TLS (сертификат не проверяется);
 *  - TESTER_CONNECT_PROXY — HTTP CONNECT-прокси, через который идти к TESTER_ADDR.
 */
class ProxyTesterIntegrationTest {
    private val cfg = CheckConfig(timeoutMs = 5_000, streamCount = 5, largeSize = 256 shl 10, concurrency = 10, longMs = 300)
    private val all = ProxyTester.checks.map { it.name }.toSet()

    private fun run(conn: ConnectionConfig, selected: Set<String> = all): List<TesterEvent> = runBlocking {
        ProxyTester.run(conn, cfg, selected).toList().onEach(::println)
    }

    private fun assertAllPassed(events: List<TesterEvent>, expected: Int) {
        assertTrue("нет соединения: $events", events.any { it is TesterEvent.Connected })
        val results = events.filterIsInstance<TesterEvent.CheckFinished>().map { it.result }
        assertEquals(expected, results.size)
        val failed = results.filter { it.kind != ResultKind.PASSED }
        assertTrue("не прошли: $failed", failed.isEmpty())
    }

    private fun env(name: String): String {
        val v = System.getenv(name).orEmpty()
        assumeTrue("$name не задан", v.isNotEmpty())
        return v
    }

    @Test
    fun allChecksPass() {
        val conn = ConnectionConfig(address = env("TESTER_ADDR"), headers = listOf(Header("authorization", "Bearer test")))
        assertAllPassed(run(conn), all.size)
    }

    @Test
    fun tlsWithoutVerification() {
        val conn = ConnectionConfig(address = env("TESTER_TLS_ADDR"), useTls = true, skipTlsVerify = true)
        assertAllPassed(run(conn, setOf("unary", "metadata", "bidi-stream")), 3)
    }

    @Test
    fun throughConnectProxy() {
        val conn = ConnectionConfig(address = env("TESTER_ADDR"), connectProxy = env("TESTER_CONNECT_PROXY"))
        assertAllPassed(run(conn, setOf("unary", "server-stream", "bidi-stream")), 3)
    }

    @Test
    fun unreachableAddressGivesSingleError() {
        val port = ServerSocket(0).use { it.localPort } // порт свободен — соединение будет отвергнуто
        val events = run(ConnectionConfig(address = "127.0.0.1:$port"))
        val failed = events.filterIsInstance<TesterEvent.ConnectFailed>().single()
        assertTrue(failed.message, failed.message.startsWith("UNAVAILABLE"))
        assertTrue(events.none { it is TesterEvent.CheckStarted })
    }
}
