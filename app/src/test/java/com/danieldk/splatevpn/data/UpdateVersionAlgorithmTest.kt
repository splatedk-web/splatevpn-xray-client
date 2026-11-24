package com.danieldk.splatevpn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionAlgorithmTest {

    @Test
    fun version1281IsRecognizedAsNewerThan128() {
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8", "1.2.8.1"))
        assertTrue(UpdateFilePolicy.isNewerVersion("v1.2.8", "v1.2.8.1"))
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8", "v1.2.8.1"))
        assertTrue(UpdateFilePolicy.isNewerVersion("V1.2.8", "v1.2.8.1"))
    }

    @Test
    fun version129IsRecognizedAsNewerThan1281() {
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8.1", "1.2.9"))
        assertTrue(UpdateFilePolicy.isNewerVersion("v1.2.8.1", "v1.2.9"))
        assertTrue(UpdateFilePolicy.isNewerVersion("V1.2.8.1", "1.2.9"))
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8.1", "v1.2.9.0"))
    }

    @Test
    fun olderOrEqualVersionsDoNotTriggerUpdate() {
        assertFalse(UpdateFilePolicy.isNewerVersion("1.2.8.1", "1.2.8"))
        assertFalse(UpdateFilePolicy.isNewerVersion("1.2.8.1", "1.2.8.1"))
        assertFalse(UpdateFilePolicy.isNewerVersion("v1.2.8.1", "1.2.8.1"))
        assertFalse(UpdateFilePolicy.isNewerVersion("V1.2.8.1", "v1.2.8.1"))
        assertFalse(UpdateFilePolicy.isNewerVersion("1.2.9", "1.2.8.1"))
    }

    @Test
    fun preReleaseAndSuffixTagsAreParsedCorrectly() {
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8-hotfix", "1.2.8.1"))
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8", "1.2.8.1-rc1"))
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8.1", "1.3.0-preview"))
    }

    @Test
    fun longTimestampAndMetadataAreHandledSafely() {
        // Timestamps exceeding Int.MAX_VALUE
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.202611081530", "1.2.202611081531"))
        assertFalse(UpdateFilePolicy.isNewerVersion("1.2.202611081531", "1.2.202611081530"))
        assertTrue(UpdateFilePolicy.isNewerVersion("1.2.8+build100", "1.2.8.1+build200"))
    }
}
