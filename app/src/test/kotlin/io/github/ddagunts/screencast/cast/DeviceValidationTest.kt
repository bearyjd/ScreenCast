package io.github.ddagunts.screencast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DeviceValidationTest {
    private fun ok(ip: String) = isAllowedAddress(InetAddress.getByName(ip))

    @Test fun siteLocalAndLinkLocalAllowed() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.9", "169.254.1.1", "fe80::1", "fd12:3456::1", "fc00::1")
            .forEach { assertTrue(it, ok(it)) }
    }

    @Test fun publicLoopbackAnyMulticastRejected() {
        listOf("8.8.8.8", "172.32.0.1", "127.0.0.1", "::1", "0.0.0.0", "::", "224.0.0.251", "ff02::fb", "2001:db8::1")
            .forEach { assertFalse(it, ok(it)) }
    }

    @Test fun portRange() {
        assertFalse(isValidPort(0)); assertFalse(isValidPort(-1)); assertFalse(isValidPort(65536))
        assertTrue(isValidPort(1)); assertTrue(isValidPort(8009)); assertTrue(isValidPort(65535))
    }

    @Test fun nameTrimmedStrippedCapped() {
        assertEquals("Den", sanitizeDeviceName("  Den  ", "svc"))
        assertEquals("AB", sanitizeDeviceName("A\u0000\u202E\u2066B", "svc"))
        assertEquals(MAX_DEVICE_NAME_LENGTH, sanitizeDeviceName("x".repeat(500), "svc").length)
    }

    @Test fun blankFallsBackToServiceThenDefault() {
        assertEquals("svc", sanitizeDeviceName(" \u202E ", "svc"))
        assertEquals("svc", sanitizeDeviceName(null, "svc"))
        assertEquals("Chromecast", sanitizeDeviceName(null, "  "))
    }
}
