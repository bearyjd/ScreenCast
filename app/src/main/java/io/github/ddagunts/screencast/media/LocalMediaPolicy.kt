// Fork modification by bearyjd: allow-list, limits and address guards for casting a local file.
package io.github.ddagunts.screencast.media

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

object LocalMediaPolicy {
    private val extensions = mapOf("video/mp4" to "mp4", "audio/mpeg" to "mp3", "audio/mp4" to "m4a")
    const val BUFFER_BYTES = 128 * 1024
    const val MAX_CONCURRENT = 4
    const val IDLE_TIMEOUT_MS = 30L * 60_000
    const val MAX_LIFETIME_MS = 6L * 60 * 60_000
    const val REAP_INTERVAL_MS = 30_000L
    const val MAX_NAME_CHARS = 80
    const val MAX_URI_CHARS = 2048
    const val WRITE_TIMEOUT_MS = 30_000L
    /** Origins that may read the file cross-origin. Exact DMR origin still to be confirmed from a device run. */
    val ALLOWED_ORIGINS: Set<String> = setOf("https://www.gstatic.com")

    fun extensionFor(contentType: String): String? = extensions[contentType]

    /** The ContentResolver's answer wins; the intent's type is only a fallback and must itself be allowed. */
    fun resolveType(resolverType: String?, intentType: String?): String? {
        val raw = resolverType ?: intentType ?: return null
        val type = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
        return type.takeIf { it in extensions }
    }

    /** Display name is UI-only: strip control/format/bidi characters and bound the length. */
    fun sanitizeDisplayName(raw: String?, contentType: String): String =
        sanitizeLabel(raw, if (contentType.startsWith("audio/")) "Shared audio" else "Shared video")

    /** Strips control/format/separator characters (incl. bidi overrides) and bounds the length; for any UI string we did not author. */
    fun sanitizeLabel(raw: String?, fallback: String, max: Int = MAX_NAME_CHARS): String {
        val cleaned = raw.orEmpty().filterNot {
            Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                Character.getType(it) == Character.LINE_SEPARATOR.toInt() || Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt()
        }.trim().let { if (it.length > max) it.take(max).let { t -> if (t.last().isHighSurrogate()) t.dropLast(1) else t } else it }.trim()
        return cleaned.ifEmpty { fallback }
    }

    /** An RFC 1918 private IPv4 literal (10/8, 172.16/12, 192.168/16): never public, CGNAT, wildcard, loopback or link-local. */
    fun isLanBindAddress(host: String): Boolean {
        val ip = parseIpv4Literal(host) ?: return false
        return ip.isSiteLocalAddress && !(ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isMulticastAddress)
    }

    /** The receiver must be a private LAN address and never the phone itself (a local app could impersonate a receiver there). */
    fun isAcceptableReceiver(receiver: InetAddress, phoneAddress: String): Boolean =
        receiver is Inet4Address && receiver.isSiteLocalAddress && !receiver.isLoopbackAddress &&
            !receiver.isAnyLocalAddress && receiver.hostAddress != phoneAddress

    /** True if [receiver] is inside the [prefixLength]-bit subnet of the phone's own address [local]. */
    fun inSubnet(receiver: InetAddress, local: String, prefixLength: Int): Boolean {
        val mine = parseIpv4Literal(local) ?: return false
        if (receiver !is Inet4Address || prefixLength !in 8..32) return false
        val mask = if (prefixLength == 32) -1 else -1 shl (32 - prefixLength)
        fun bits(a: InetAddress) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        return (bits(mine) and mask) == (bits(receiver) and mask)
    }

    /** Cheap container sniff so a mislabelled file is refused instead of served: MP4 'ftyp' at 4, MP3 'ID3' or frame sync. */
    fun matchesMagic(contentType: String, head: ByteArray): Boolean = when (contentType) {
        "video/mp4", "audio/mp4" -> head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        "audio/mpeg" -> head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" ||
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
        else -> false
    }

    /** Receiver must be a plain dotted-quad: it is compared to peer addresses and never resolved via DNS. */
    fun parseReceiverAddress(host: String): InetAddress? = parseIpv4Literal(host)

    private fun parseIpv4Literal(host: String): Inet4Address? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { p ->
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            p.toInt().takeIf { it in 0..255 } ?: return null
        }
        return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }) as? Inet4Address
    }

    /** Positional reads must work and the size must be known: rules out pipes/sockets/cloud streams. */
    fun isSeekable(source: LocalMediaSource): Boolean {
        val length = source.length
        if (length <= 0) return false
        return try {
            val one = java.nio.ByteBuffer.allocate(1)
            if (source.readAt(one, 0) != 1) return false
            one.clear()
            source.readAt(one, length - 1) == 1
        } catch (_: java.io.IOException) { false }
    }
}
