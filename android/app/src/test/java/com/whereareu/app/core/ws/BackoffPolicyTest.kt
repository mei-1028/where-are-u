package com.whereareu.app.core.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {

    @Test
    fun `first attempt stays within initial delay jitter window`() {
        val policy = BackoffPolicy(
            initialDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            multiplier = 2.0,
            jitterFraction = 0.2,
            random = Random(42),
        )
        val delay = policy.nextDelay(0)
        assertTrue("delay=$delay", delay in 800L..1_200L)
    }

    @Test
    fun `delay grows exponentially and never exceeds the cap`() {
        val policy = BackoffPolicy(
            initialDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            multiplier = 2.0,
            jitterFraction = 0.2,
            random = Random(7),
        )
        val delays = (0..14).map { attempt -> policy.nextDelay(attempt) }

        assertTrue("attempt 0 and 1 should increase: $delays", delays[1] > delays[0])
        assertTrue("all delays must respect the cap: $delays", delays.all { it <= 60_000L })
        assertTrue(
            "late attempts should be near the cap: $delays",
            delays.last() >= 48_000L,
        )
    }

    @Test
    fun `zero jitter produces exact expontential values`() {
        val policy = BackoffPolicy(
            initialDelayMs = 500L,
            maxDelayMs = 4_000L,
            multiplier = 2.0,
            jitterFraction = 0.0,
        )
        assertEquals(500L, policy.nextDelay(0))
        assertEquals(1_000L, policy.nextDelay(1))
        assertEquals(2_000L, policy.nextDelay(2))
        assertEquals(4_000L, policy.nextDelay(3))
        assertEquals(4_000L, policy.nextDelay(10))
    }

    @Test
    fun `negative attempt is treated as the first attempt`() {
        val policy = BackoffPolicy(jitterFraction = 0.0)
        assertEquals(policy.nextDelay(0), policy.nextDelay(-3))
    }
}
