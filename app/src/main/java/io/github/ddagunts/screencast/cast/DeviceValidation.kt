package io.github.ddagunts.screencast.cast

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

// mDNS answers are attacker-controlled on a hostile LAN, so everything that
// reaches the picker (and later a socket connect) is validated first.

const val MAX_DEVICE_NAME_LENGTH = 64
private const val DEFAULT_DEVICE_NAME = "Chromecast"

private val STRIPPED_TYPES = setOf(
    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
).map { it.toInt() }.toSet()

private fun clean(s: String?): String {
    val stripped = s.orEmpty().filterNot { Character.getType(it) in STRIPPED_TYPES }.trim()
    val capped = stripped.take(MAX_DEVICE_NAME_LENGTH)
    // Don't leave half of a surrogate pair at the cut.
    return (if (capped.lastOrNull()?.isHighSurrogate() == true) capped.dropLast(1) else capped).trim()
}

/** Friendly name -> service name -> "Chromecast", whichever is first non-blank after cleaning. */
fun sanitizeDeviceName(raw: String?, serviceName: String): String =
    clean(raw).ifEmpty { clean(serviceName) }.ifEmpty { DEFAULT_DEVICE_NAME }

/** Only LAN addresses: RFC1918, IPv4/IPv6 link-local, IPv6 ULA. No loopback/any/multicast/public. */
fun isAllowedAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return false
    return when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress
        is Inet6Address -> address.isLinkLocalAddress || (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }
}

fun isValidPort(port: Int): Boolean = port in 1..65535
