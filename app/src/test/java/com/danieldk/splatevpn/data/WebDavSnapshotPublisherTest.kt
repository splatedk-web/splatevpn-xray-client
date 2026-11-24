package com.danieldk.splatevpn.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WebDavSnapshotPublisherTest {
    @Test fun publicationTimeoutLeavesArchiveTrackedAndDoesNotAdvanceRevision() = runBlocking {
        var disk = WebDavPublicationState("old")
        var uploaded = false
        val failure = runCatching { WebDavSnapshotPublisher.publish(disk, "new", { disk = it }, {
            assertEquals(listOf("new"), disk.archives); uploaded = true; WebDavReply(201)
        }, { assertEquals("old", it); throw IOException("timeout") }, { true }) }
        assertTrue(failure.isFailure); assertTrue(uploaded)
        assertEquals(WebDavPublicationState("old", listOf("new")), disk)
    }
    @Test fun remoteConflictKeepsPreviousAcknowledgedRevision() = runBlocking {
        var disk = WebDavPublicationState("acknowledged")
        val failure = runCatching { WebDavSnapshotPublisher.publish(disk, "new", { disk = it }, { WebDavReply(201) }, {
            assertEquals("acknowledged", it); WebDavReply(412, "other-device")
        }, { true }) }
        assertTrue(failure.isFailure)
        assertEquals("acknowledged", disk.etag)
        assertEquals(listOf("new"), disk.archives)
    }
    @Test fun unacknowledgedRemoteUsesCreateOnlyAndSuccessAcknowledgesStrongEtag() = runBlocking {
        var disk = WebDavPublicationState()
        WebDavSnapshotPublisher.publish(disk, "new", { disk = it }, { WebDavReply(201) }, {
            assertNull(it); WebDavReply(201, "revision-1")
        }, { true })
        assertEquals("revision-1", disk.etag)
    }
    @Test fun diskFailurePreventsAnyNetworkMutation() = runBlocking {
        var network = false
        assertTrue(runCatching { WebDavSnapshotPublisher.publish(WebDavPublicationState(), "new", { throw IOException("disk") }, {
            network = true; WebDavReply(201)
        }, { network = true; WebDavReply(201) }, { network = true; true }) }.isFailure)
        assertFalse(network)
    }
    @Test fun cancellationAfterArchivePreventsPublicationAndDeletion() = runBlocking {
        var disk = WebDavPublicationState()
        var published = false
        var deleted = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = scope.launch {
            WebDavSnapshotPublisher.publish(disk, "new", { disk = it }, {
                currentCoroutineContext().cancel()
                WebDavReply(201)
            }, { published = true; WebDavReply(201) }, { deleted = true; true })
        }
        job.join(); scope.cancel()
        assertTrue(job.isCancelled); assertFalse(published); assertFalse(deleted)
        assertEquals(listOf("new"), disk.archives)
    }
    @Test fun onlySuccessfullyDeletedArchivesLeaveHistory() = runBlocking {
        var disk = WebDavPublicationState("old", (1..11).map { "archive-$it" })
        val deleted = mutableListOf<String>()
        WebDavSnapshotPublisher.publish(disk, "new", { disk = it }, { WebDavReply(201) }, { WebDavReply(200, "new-etag") }, {
            deleted += it; it == "archive-1"
        })
        assertEquals(listOf("archive-1", "archive-2"), deleted)
        assertFalse(disk.archives.contains("archive-1"))
        assertTrue(disk.archives.contains("archive-2"))
        assertTrue(disk.archives.contains("new"))
    }
}
