package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class LogWriteQueueTest {
    @Test fun clearDiscardsOldLinesEvenWhenTheQueueIsFull() {
        val queue = LogWriteQueue<String>(2)
        assertTrue(queue.append("old-1"))
        assertTrue(queue.append("old-2"))
        assertFalse(queue.append("overflow"))
        queue.clear()
        assertTrue(queue.append("new"))
        assertEquals(LogWrite.Clear, queue.poll())
        assertEquals(LogWrite.Append("new"), queue.poll())
        assertNull(queue.poll())
    }

    @Test fun anInFlightOldAppendFinishesBeforeClearAndCannotRestoreOldDiskLogs() {
        val queue = LogWriteQueue<String>(2)
        val disk = mutableListOf<String>()
        queue.append("old")
        val inFlight = queue.poll() as LogWrite.Append<String>
        queue.clear()
        queue.append("new")
        disk += inFlight.entry
        while (true) when (val write = queue.poll() ?: break) {
            LogWrite.Clear -> disk.clear()
            is LogWrite.Append -> disk += write.entry
        }
        assertEquals(listOf("new"), disk)
    }

    @Test fun repeatedClearKeepsOnlyLinesAfterTheLatestRequest() {
        val queue = LogWriteQueue<String>(2)
        queue.append("old")
        queue.clear()
        queue.append("between-clears")
        queue.clear()
        queue.append("latest")
        assertEquals(LogWrite.Clear, queue.poll())
        assertEquals(LogWrite.Append("latest"), queue.poll())
        assertNull(queue.poll())
    }

    @Test fun aSecondClearStillRunsWhenTheFirstDeletionIsAlreadyInFlight() {
        val queue = LogWriteQueue<String>(2)
        queue.clear()
        assertEquals(LogWrite.Clear, queue.poll())
        queue.append("between-clears")
        queue.clear()
        queue.append("latest")
        assertEquals(LogWrite.Clear, queue.poll())
        assertEquals(LogWrite.Append("latest"), queue.poll())
        assertNull(queue.poll())
    }
}
