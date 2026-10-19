// Fork modification by bearyjd: classify direct receiver media without fetching shared URLs.
package io.github.ddagunts.screencast.media

import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class SharedMedia(val url: String, val contentType: String)
data class SharedMediaResult(val media: SharedMedia? = null, val error: String? = null)

object SharedMediaParser {
    private val extensions = mapOf("mp4" to "video/mp4", "mp3" to "audio/mpeg")
    fun parse(text: String?, mime: String?): SharedMediaResult {
        fun reject() = SharedMediaResult(error = "Share one direct HTTP(S) MP4 or MP3 URL. Webpages, private app sessions and local files are unsupported.")
        val url = text?.trim() ?: return reject()
        if (url.isEmpty() || url.length > 16384 || url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return reject()
        val uri = runCatching { URI(url) }.getOrNull() ?: return reject()
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.isOpaque ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) return reject()
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: return reject()
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host == "0" ||
            host.all { it.isDigit() } || host.startsWith("0x")) return reject()
        if (host.matches(Regex("[0-9.]+"))) {
            val octets = host.split('.')
            if (octets.any { it.length > 1 && it.startsWith('0') }) return reject()
            val parts = octets.mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 } || parts[0] in setOf(0, 127) || parts[0] >= 224 ||
                (parts[0] == 169 && parts[1] == 254)) return reject()
        }
        if (':' in host) {
            // Numeric IPv6 only: this never resolves an attacker-provided DNS name.
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return reject()
            if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isMulticastAddress || '%' in host) return reject()
        }
        if (listOf("youtube.com", "youtu.be", "spotify.com").any { host == it || host.endsWith(".$it") }) return reject()
        val inferred = extensions[uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)]
        val declared = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val contentType = when (declared) {
            "video/mp4", "audio/mpeg" -> declared
            "text/plain", "audio/*", "video/*", null -> inferred
            else -> null
        } ?: return reject()
        if (declared == "audio/*" && !contentType.startsWith("audio/")) return reject()
        if (declared == "video/*" && !contentType.startsWith("video/")) return reject()
        return SharedMediaResult(SharedMedia(url, contentType))
    }
}
