package com.danieldk.splatevpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FragmentationDiagnosisTest {
    private val aggressive = FragmentationProfile(length = "50-100", interval = "20-40", packets = "1-3")

    @Test fun requiresTwoMatchedFailuresAndTwoStandardSuccesses() = runBlocking {
        val profiles = mutableListOf<FragmentationProfile>()
        val results = ArrayDeque(listOf(-1L, 120L, -1L, 130L))
        assertTrue(confirmFragmentationMismatch(aggressive, { true }) { profiles += it; results.removeFirst() })
        assertEquals(listOf(aggressive, FragmentationProfile(), aggressive, FragmentationProfile()), profiles)
    }

    @Test fun ordinaryOutageAndIntermittentRecoveryAreNotBlamedOnFragmentation() = runBlocking {
        for (samples in listOf(listOf(120L), listOf(-1L, -1L), listOf(-1L, 120L, 125L), listOf(-1L, 120L, -1L, -1L))) {
            val results = ArrayDeque(samples)
            assertFalse(confirmFragmentationMismatch(aggressive, { true }) { results.removeFirst() })
        }
    }

    @Test fun changedConnectionDiscardsSuccessfulComparison() = runBlocking {
        var current = true
        var calls = 0
        assertFalse(confirmFragmentationMismatch(aggressive, { current }) {
            calls++
            if (calls == 2) current = false
            if (it == aggressive) -1L else 100L
        })
        assertEquals(2, calls)
    }

    @Test fun standardAndDisabledSettingsDoNotStartProbes() = runBlocking {
        for (profile in listOf(FragmentationProfile(), aggressive.copy(enabled = false))) {
            assertFalse(confirmFragmentationMismatch(profile, { true }) { error("Unexpected probe") })
        }
    }

    @Test fun cancellationIsNotClassifiedAsAConnectionFailure() = runBlocking {
        try {
            confirmFragmentationMismatch(aggressive, { true }) { throw CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
