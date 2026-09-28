package com.vellum.studio.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * The per-session pairing PIN for [SyncServer]: a 6-digit code shown on the tablet that the PC
 * companion must present with every request, plus the lockout that makes a 6-digit code safe.
 *
 * Why the lockout is the actual security here: the space is only 1,000,000 values, so on its own the
 * PIN is brute-forceable in minutes over a LAN. After [maxFailures] wrong guesses the guard locks for
 * the rest of the session (a fresh PIN needs Stop then Start on the tablet), so a peer gets five
 * guesses in 1,000,000 no matter how fast it can send. Deliberately session-wide rather than per
 * source IP: a per-IP budget is multiplied by however many devices the attacker has on the network,
 * and the cost of the choice (a hostile peer can force a restart) is one tap for a single-user app.
 *
 * A request with NO PIN is [Verdict.MISSING], not a failure: it guesses nothing, and counting it would
 * let a browser tab that merely opens the address lock the real user out.
 *
 * All state changes happen under one lock. The server answers on up to 8 connection threads, and an
 * unsynchronized check-then-increment would let those threads each pass the "not locked yet" test
 * before any of them recorded a failure, turning five guesses into five-plus-seven.
 */
class PairingGuard(
    val pin: String = generatePin(),
    private val maxFailures: Int = MAX_FAILURES,
) {
    enum class Verdict { OK, MISSING, WRONG, LOCKED }

    init {
        require(PIN_FORMAT.matches(pin)) { "PIN must be exactly $PIN_LENGTH digits" }
        require(maxFailures >= 1) { "maxFailures must be at least 1" }
    }

    private var failures = 0

    val failureCount: Int @Synchronized get() = failures

    val isLocked: Boolean @Synchronized get() = failures >= maxFailures

    @Synchronized
    fun check(presented: String?): Verdict {
        // Locked wins over everything, including a correct PIN: otherwise the guesses would just keep
        // coming and the lockout would only ever delay the attacker's eventual hit.
        if (failures >= maxFailures) return Verdict.LOCKED
        val candidate = presented?.trim()
        if (candidate.isNullOrEmpty()) return Verdict.MISSING
        if (constantTimeEquals(candidate, pin)) return Verdict.OK
        failures++
        return Verdict.WRONG
    }

    companion object {
        const val PIN_LENGTH = 6
        const val MAX_FAILURES = 5
        private val PIN_FORMAT = Regex("\\d{$PIN_LENGTH}")

        /** [random] is a seam for tests; production is [SecureRandom], never a seeded/predictable source. */
        fun generatePin(random: Random = SecureRandom()): String =
            // %06d keeps leading zeros ("000042"): dropping them would make ~10% of PINs shorter than
            // the companion's 6-digit field accepts.
            "%0${PIN_LENGTH}d".format(random.nextInt(1_000_000))

        /**
         * MessageDigest.isEqual compares every byte regardless of where the first difference is, unlike
         * String.equals/contentEquals, so response time does not reveal how many leading digits of a
         * guess were right. It does short-circuit on LENGTH, which leaks nothing here: the PIN length is
         * fixed and public.
         */
        internal fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
