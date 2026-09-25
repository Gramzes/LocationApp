package com.example.grpcproxytester.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationTest {
    @Test
    fun appliesOnlyWhatWasGiven() {
        val base = Settings(address = "10.0.2.2:50051", headers = "x-a: 1")
        val s = AutomationRequest(runId = "r", address = "10.0.2.2:9", useTls = true, longSeconds = 2)
            .applyTo(base)
        assertEquals("10.0.2.2:9", s.address)
        assertEquals(true, s.useTls)
        assertEquals("2", s.longSeconds)
        assertEquals("x-a: 1", s.headers)
        assertEquals(base.disabledChecks, s.disabledChecks)
    }

    @Test
    fun onlyDisablesTheRest() {
        val s = AutomationRequest(runId = "r", only = setOf("unary", "health")).applyTo(Settings())
        assertEquals(ProxyTester.checks.map { it.name }.toSet() - setOf("unary", "health"), s.disabledChecks)
        assertThrows(IllegalArgumentException::class.java) {
            AutomationRequest(runId = "r", only = setOf("нет-такой")).applyTo(Settings())
        }
    }

    @Test
    fun linesAreJson() {
        val r = CheckResult("unary", ResultKind.FAILED, "сервер \"x\"\nстрока", hint = "h", durationNanos = 1_500_000)
        assertEquals(
            """{"run_id":"r1","event":"result","name":"unary","status":"failed","detail":"сервер \"x\"\nстрока","hint":"h","duration_ms":1.5}""",
            automationLine("r1", TesterEvent.CheckFinished(r)),
        )
        assertEquals(
            """{"run_id":"r1","event":"done","passed":1,"failed":2,"skipped":3}""",
            automationLine("r1", TesterEvent.Done(1, 2, 3)),
        )
        assertNull(automationLine("r1", TesterEvent.CheckStarted("unary")))
        assertEquals("\"\\u0001\"", jsonString("\u0001"))
    }
}
