package com.vellum.studio.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pure-JVM coverage of the PIN and lockout rules; SyncServerTest covers the same rules over HTTP. */
class PairingGuardTest {

    @Test
    fun `generated PINs are always exactly six digits including leading zeros`() {
        repeat(2_000) { assertTrue(PairingGuard.generatePin().matches(Regex("\\d{6}"))) }
        // A value below 100000 must be zero-padded, not shortened.
        assertEquals("000042", PairingGuard.generatePin(FixedRandom(42)))
        assertEquals("000000", PairingGuard.generatePin(FixedRandom(0)))
        assertEquals("999999", PairingGuard.generatePin(FixedRandom(999_999)))
    }

    @Test
    fun `generated PINs are not a constant`() {
        val distinct = (0 until 50).map { PairingGuard.generatePin() }.toSet()
        assertTrue("50 generated PINs should not all be identical: $distinct", distinct.size > 1)
    }

    @Test
    fun `a malformed PIN cannot be installed`() {
        for (bad in listOf("", "12345", "1234567", "12345a", "١٢٣٤٥٦", " 12345")) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { PairingGuard(bad) }
        }
    }

    @Test
    fun `right PIN passes, wrong PIN fails, blank and absent are missing`() {
        val g = PairingGuard("123456")
        assertEquals(PairingGuard.Verdict.OK, g.check("123456"))
        assertEquals("surrounding whitespace from a header is tolerated", PairingGuard.Verdict.OK, g.check(" 123456 "))
        assertEquals(PairingGuard.Verdict.MISSING, g.check(null))
        assertEquals(PairingGuard.Verdict.MISSING, g.check(""))
        assertEquals(PairingGuard.Verdict.MISSING, g.check("   "))
        assertEquals(0, g.failureCount)
        assertEquals(PairingGuard.Verdict.WRONG, g.check("123457"))
        assertEquals(PairingGuard.Verdict.WRONG, g.check("12345"))
        assertEquals(PairingGuard.Verdict.WRONG, g.check("1234567"))
        assertEquals(3, g.failureCount)
    }

    @Test
    fun `constant time comparison agrees with equality for every kind of near miss`() {
        assertTrue(PairingGuard.constantTimeEquals("123456", "123456"))
        assertFalse(PairingGuard.constantTimeEquals("123456", "123457")) // last digit
        assertFalse(PairingGuard.constantTimeEquals("123456", "023456")) // first digit
        assertFalse(PairingGuard.constantTimeEquals("123456", "12345"))
        assertFalse(PairingGuard.constantTimeEquals("123456", "1234567"))
        assertFalse(PairingGuard.constantTimeEquals("123456", ""))
        assertFalse(PairingGuard.constantTimeEquals("123456", "١٢٣٤٥٦"))
    }

    @Test
    fun `the fifth wrong PIN locks and a correct PIN afterwards is refused`() {
        val g = PairingGuard("123456")
        repeat(4) { assertEquals(PairingGuard.Verdict.WRONG, g.check("00000$it")) }
        assertFalse(g.isLocked)
        assertEquals("the fifth guess is still just wrong", PairingGuard.Verdict.WRONG, g.check("000009"))
        assertTrue(g.isLocked)
        assertEquals(PairingGuard.Verdict.LOCKED, g.check("123456"))
        assertEquals(PairingGuard.Verdict.LOCKED, g.check("000000"))
        assertEquals(PairingGuard.Verdict.LOCKED, g.check(null))
        assertEquals("locked requests are not counted further", 5, g.failureCount)
    }

    @Test
    fun `a correct PIN does not refund earlier failures`() {
        val g = PairingGuard("123456")
        repeat(4) { g.check("00000$it") }
        assertEquals(PairingGuard.Verdict.OK, g.check("123456"))
        assertEquals("success must not reset the budget or an attacker interleaves with the owner", 4, g.failureCount)
        assertEquals(PairingGuard.Verdict.WRONG, g.check("000009"))
        assertEquals(PairingGuard.Verdict.LOCKED, g.check("123456"))
    }

    @Test
    fun `parallel wrong guesses are evaluated exactly maxFailures times`() {
        repeat(20) {
            val g = PairingGuard("123456")
            val threads = 64
            val pool = Executors.newFixedThreadPool(threads)
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val wrong = AtomicInteger()
            val locked = AtomicInteger()
            repeat(threads) { i ->
                pool.execute {
                    ready.countDown()
                    go.await()
                    when (g.check("9%05d".format(i))) {
                        PairingGuard.Verdict.WRONG -> wrong.incrementAndGet()
                        PairingGuard.Verdict.LOCKED -> locked.incrementAndGet()
                        else -> Unit
                    }
                }
            }
            ready.await()
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals("only five guesses may ever be evaluated", 5, wrong.get())
            assertEquals(threads - 5, locked.get())
        }
    }

    /** A Random whose nextInt(bound) returns a fixed value, so the zero-padding path is deterministic. */
    private class FixedRandom(private val value: Int) : Random() {
        override fun nextInt(bound: Int): Int = value
    }
}
