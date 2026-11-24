package com.danieldk.splatevpn.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerListOrderTest {
    @Test fun choosingPersonalOrSubscriptionNodeDoesNotReorderList() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.serverDao()
            val nodes = (0 until 40).map { index ->
                ServerNode(id = "node-%02d".format(index), name = if (index < 20) "Same subscription name" else "My server",
                    protocol = "vless", address = "example.com", port = 443,
                    subId = if (index < 20) "subscription" else "custom", isSelected = index == 0)
            }
            dao.insertServers(nodes.reversed())
            val initialOrder = dao.getAllServers().first().map { it.id }
            assertEquals(initialOrder, dao.getAllServersSync().map { it.id })
            for (index in listOf(12, 35, 8, 27)) {
                dao.setActiveServer(nodes[index].id)
                val emitted = dao.getAllServers().first()
                assertEquals(initialOrder, emitted.map { it.id })
                assertEquals(initialOrder, dao.getAllServersSync().map { it.id })
                assertEquals(nodes[index].id, emitted.single { it.isSelected }.id)
                assertEquals(nodes[index].id, dao.getSelectedServerSync()?.id)
            }
        } finally { db.close() }
    }
}
