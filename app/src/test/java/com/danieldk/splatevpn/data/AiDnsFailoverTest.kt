package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AiDnsFailoverTest {
    private val old = AiDnsChoice("old", false)
    private val next = AiDnsChoice("next", false)
    private val ok = AiDnsOutcome.ACCESSIBLE
    private val failed = AiDnsOutcome.FAILED
    private val limited = AiDnsOutcome.LIMITED
    private val geo = AiDnsOutcome.GEO_BLOCKED

    private fun result(choice: AiDnsChoice, vararg outcomes: AiDnsOutcome) = AiDnsCandidateResult(choice,
        outcomes.mapIndexed { index, outcome -> AiDnsTargetResult(listOf("Gemini", "ChatGPT", "Claude")[index], outcome) })

    private suspend fun choose(oldSamples: List<AiDnsCandidateResult>, newSamples: List<AiDnsCandidateResult>,
        visited: MutableList<AiDnsChoice> = mutableListOf()): AiDnsCandidateResult? {
        var oldIndex = 0
        var newIndex = 0
        return findAiDnsReplacement(old, listOf(old, next), confirmDelay = {}) { choice ->
            visited.add(choice)
            if (choice == old) oldSamples[minOf(oldIndex++, oldSamples.lastIndex)]
            else newSamples[minOf(newIndex++, newSamples.lastIndex)]
        }
    }

    @Test fun healthyOrChallengedProviderNeverStartsRotation() = runBlocking {
        for (outcome in listOf(ok, limited)) {
            val visited = mutableListOf<AiDnsChoice>()
            assertNull(choose(listOf(result(old, outcome, outcome, outcome)), emptyList(), visited))
            assertEquals(listOf(old), visited)
        }
    }

    @Test fun isolatedFailureAndDifferentFailedServicesDoNotSwitch() = runBlocking {
        val visited = mutableListOf<AiDnsChoice>()
        assertNull(choose(listOf(result(old, failed, ok), result(old, ok, failed)), emptyList(), visited))
        assertEquals(listOf(old, old), visited)
    }

    @Test fun persistentFailureNeedsTwoReplacementSuccessesAndFinalCurrentCheck() = runBlocking {
        val visited = mutableListOf<AiDnsChoice>()
        val replacement = result(next, ok, ok, limited)
        assertEquals(replacement, choose(listOf(result(old, failed, ok, limited)), listOf(replacement), visited))
        assertEquals(listOf(old, old, next, next, old), visited)
    }

    @Test fun persistentGeoBlockCanBeReplaced() = runBlocking {
        assertNotNull(choose(listOf(result(old, geo, limited)), listOf(result(next, ok, limited))))
    }

    @Test fun candidateMustKeepEveryPreviouslyWorkingService() = runBlocking {
        assertNull(choose(listOf(result(old, failed, ok)), listOf(result(next, ok, limited))))
        assertNull(choose(listOf(result(old, failed, limited)), listOf(result(next, ok, failed))))
        // Protect an accessible service even if it failed in the second observation.
        assertNull(choose(listOf(result(old, failed, ok), result(old, failed, failed)), listOf(result(next, ok, failed))))
    }

    @Test fun challengeOrOneOffCandidateSuccessCannotWin() = runBlocking {
        assertNull(choose(listOf(result(old, failed)), listOf(result(next, limited))))
        assertNull(choose(listOf(result(old, failed)), listOf(result(next, ok), result(next, failed))))
    }

    @Test fun sameRecoveredServiceMustPassBothCandidateChecks() = runBlocking {
        assertNull(choose(listOf(result(old, failed, failed)), listOf(result(next, ok, failed), result(next, failed, ok))))
    }

    @Test fun recoveryOfCurrentProviderAvoidsUnnecessaryReconnect() = runBlocking {
        assertNull(choose(listOf(result(old, failed), result(old, failed), result(old, ok)), listOf(result(next, ok))))
    }

    @Test fun finalCurrentCheckProtectsNewlyWorkingServices() = runBlocking {
        assertNull(choose(listOf(result(old, failed, failed), result(old, failed, failed), result(old, failed, ok)),
            listOf(result(next, ok, failed))))
    }

    @Test fun missingServicesOrMismatchedChoiceAreRejected() = runBlocking {
        assertNull(choose(listOf(result(old, failed, ok)), listOf(result(next, ok))))
        assertNull(choose(listOf(result(old, failed)), listOf(result(old, ok))))
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        try {
            findAiDnsReplacement(old, confirmDelay = {}) { throw CancellationException("Stopped") }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun firstCheckRunsAfterFifteenSecondsThenEveryFiveMinutesAndIsInactiveWhenDisconnected() = runBlocking {
        withTimeout(3000) {
            val states = MutableStateFlow(config().copy(connected = false))
            val waits = Channel<Long>(Channel.UNLIMITED)
            val ticks = Channel<Unit>(Channel.UNLIMITED)
            val checked = Channel<AiDnsMonitorConfig>(Channel.UNLIMITED)
            val job = launch {
                monitorAiDns(states, check = { checked.send(it); null }, apply = { _, _ -> fail("Unexpected switch") },
                    wait = { waits.send(it); ticks.receive() })
            }
            try {
                yield()
                assertTrue(waits.tryReceive().isFailure)
                states.value = config()
                assertEquals(15_000L, waits.receive())
                assertTrue(checked.tryReceive().isFailure)
                ticks.send(Unit)
                assertEquals(config(), checked.receive())
                assertEquals(300_000L, waits.receive())
                states.value = config().copy(connected = false)
                yield()
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun disconnectDisableModeProviderAndNetworkEditsCancelInFlightCheck() = runBlocking {
        for (changed in listOf(config().copy(connected = false), config().copy(enabled = false),
            config().copy(directSmartDns = false), config().copy(choice = next), config().copy(network = "mobile"))) {
            withTimeout(3000) {
                val states = MutableStateFlow(config())
                val waits = Channel<Unit>(Channel.UNLIMITED)
                val ticks = Channel<Unit>(Channel.UNLIMITED)
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                var applications = 0
                val job = launch {
                    monitorAiDns(states, check = {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }, apply = { _, _ -> applications++ }, wait = { waits.send(Unit); ticks.receive() })
                }
                try {
                    waits.receive(); ticks.send(Unit); entered.await()
                    states.value = changed
                    cancelled.await()
                    assertEquals(0, applications)
                } finally { job.cancelAndJoin() }
            }
        }
    }

    @Test fun successfulSwitchSchedulesAnotherIntervalInsteadOfSpinning() = runBlocking {
        withTimeout(3000) {
            val states = MutableStateFlow(config())
            val waits = Channel<Long>(Channel.UNLIMITED)
            val ticks = Channel<Unit>(Channel.UNLIMITED)
            val switched = CompletableDeferred<Unit>()
            val job = launch {
                monitorAiDns(states, check = { result(next, ok) }, apply = { expected, replacement ->
                    assertEquals(old, expected.choice)
                    states.value = expected.copy(choice = replacement.choice)
                    switched.complete(Unit)
                }, wait = { waits.send(it); ticks.receive() })
            }
            try {
                assertEquals(15_000L, waits.receive())
                ticks.send(Unit); switched.await()
                assertEquals(300_000L, waits.receive())
            } finally { job.cancelAndJoin() }
        }
    }

    private fun config() = AiDnsMonitorConfig(true, true, true, old, "wifi")
}
