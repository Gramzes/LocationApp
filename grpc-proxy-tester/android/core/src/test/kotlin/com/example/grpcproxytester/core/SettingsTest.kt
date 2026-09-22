package com.example.grpcproxytester.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun parsesHeaders() {
        val h = parseHeaders("Authorization: Bearer a:b\n\n  x-foo=bar  \n")
        assertEquals(listOf(Header("authorization", "Bearer a:b"), Header("x-foo", "bar")), h)
    }

    @Test
    fun rejectsBadHeaders() {
        assertThrows(IllegalArgumentException::class.java) { parseHeaders("без разделителя") }
        assertThrows(IllegalArgumentException::class.java) { parseHeaders("ключ: значение") }
    }

    @Test
    fun parsesHostPort() {
        assertEquals("10.0.2.2" to 50051, parseHostPort("10.0.2.2:50051", "адрес"))
        assertEquals("::1" to 8080, parseHostPort("[::1]:8080", "адрес"))
        assertThrows(IllegalArgumentException::class.java) { parseHostPort("example.com", "адрес") }
        assertThrows(IllegalArgumentException::class.java) { parseHostPort("example.com:99999", "адрес") }
    }

    @Test
    fun convertsSettings() {
        val s = Settings(address = " proxy:443 ", skipTlsVerify = true, headers = "x-a: 1", longSeconds = "30", largeSizeKb = "64")
        val conn = s.toConnectionConfig()
        assertEquals("proxy:443", conn.address)
        assertTrue(conn.useTls)
        assertEquals(listOf(Header("x-a", "1")), conn.headers)
        val cfg = s.toCheckConfig()
        assertEquals(30_000L, cfg.longMs)
        assertEquals(64 * 1024, cfg.largeSize)
    }

    @Test
    fun validatesNumbers() {
        val e = assertThrows(IllegalArgumentException::class.java) { Settings(streamCount = "0").toCheckConfig() }
        assertTrue(e.message!!.startsWith("Сообщений в потоке"))
        assertThrows(IllegalArgumentException::class.java) { Settings(concurrency = "abc").toCheckConfig() }
    }

    @Test
    fun formatsDurations() {
        assertEquals("850µs", fmtNanos(850_000))
        assertEquals("501ms", fmtNanos(501_000_000))
        assertEquals("2s", fmtNanos(2_000_000_000))
        assertEquals("4.99s", fmtNanos(4_990_000_000))
    }
}
