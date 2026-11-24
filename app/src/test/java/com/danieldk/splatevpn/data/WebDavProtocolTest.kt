package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class WebDavProtocolTest {
    @Test fun appendsOneStableFilenameToAnExistingFolder() {
        assertEquals("https://cloud.example.com/dav/folder/splate-settings.v1.json", WebDavProtocol.documentUrl("https://cloud.example.com/dav/folder"))
        assertEquals("https://cloud.example.com/dav/folder/splate-settings.v1.json", WebDavProtocol.documentUrl("https://cloud.example.com/dav/folder/"))
        assertEquals("https://cloud.example.com/splate-settings.v1.json", WebDavProtocol.documentUrl("https://cloud.example.com/"))
    }
    @Test fun rejectsCleartextAndCredentialsInUrls() {
        listOf("http://cloud.example.com/dav", "https://user:password@cloud.example.com/dav", "https://cloud.example.com/dav?secret=value", "https://cloud.example.com/dav#secret").forEach {
            assertThrows(Exception::class.java) { WebDavProtocol.documentUrl(it) }
        }
    }
}
