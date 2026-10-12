package com.example.parkinson.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    @Test
    fun typedAddressesBecomeHttpsUrls() {
        assertEquals("https://panel.example.ir", ServerAddress.normalize("panel.example.ir"))
        assertEquals("https://panel.example.ir", ServerAddress.normalize("  https://panel.example.ir/ "))
        assertEquals("https://panel.example.ir:8443/clinic", ServerAddress.normalize("HTTPS://panel.example.ir:8443/clinic"))
        assertEquals("https://192.168.1.20:8443", ServerAddress.normalize("192.168.1.20:8443"))
    }

    @Test
    fun plainHttpAndNonsenseAreRefused() {
        assertNull(ServerAddress.normalize("http://panel.example.ir"))
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("panel"))
        assertNull(ServerAddress.normalize("panel example.ir"))
        assertNull(ServerAddress.normalize("https://"))
    }
}
