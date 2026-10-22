// Fork modification by bearyjd: open the shared content URI ONCE at accept time; later work uses dup'd fds only.
package io.github.ddagunts.screencast.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.ddagunts.screencast.media.FileChannelMediaSource
import io.github.ddagunts.screencast.media.LocalMediaHandle
import io.github.ddagunts.screencast.media.LocalMediaPolicy
import io.github.ddagunts.screencast.media.LocalMediaSource
import java.io.IOException

interface LocalUriBackend {
    fun type(uri: Uri): String?
    fun displayName(uri: Uri): String?
    fun openReadOnly(uri: Uri): ParcelFileDescriptor?
}

class ContentResolverBackend(private val resolver: ContentResolver) : LocalUriBackend {
    override fun type(uri: Uri): String? = try { resolver.getType(uri) } catch (_: RuntimeException) { null }
    override fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null
        }
    } catch (_: RuntimeException) { null }
    override fun openReadOnly(uri: Uri): ParcelFileDescriptor? = resolver.openFileDescriptor(uri, "r")
}

/** Owns the one fd obtained under the share's URI grant; [openSource] hands out independent dup'd, verified sources. */
class PfdMediaHandle(private val pfd: ParcelFileDescriptor) : LocalMediaHandle {
    /** statSize is -1 for anything that is not a regular file (pipe, socket, device): those cannot be served positionally. */
    private fun isRegular(): Boolean = try { pfd.statSize >= 0 } catch (_: RuntimeException) { false }

    override fun openSource(): LocalMediaSource? {
        if (!isRegular()) return null
        val dup = try { pfd.dup() } catch (_: IOException) { return null } catch (_: RuntimeException) { return null }
        val source = try { FileChannelMediaSource(ParcelFileDescriptor.AutoCloseInputStream(dup).channel) }
            catch (e: RuntimeException) { try { dup.close() } catch (_: IOException) { }; return null }
        val ok = try { LocalMediaPolicy.isSeekable(source) } catch (_: RuntimeException) { false }
        if (ok) return source
        source.close()
        return null
    }
    /** True only for a regular, readable file of length 0 (a pipe also reports size 0 but cannot be read positionally). */
    fun isEmptyRegularFile(): Boolean = try {
        // Reads through the original fd without taking ownership: the stream/channel wrapper is deliberately not closed.
        isRegular() && pfd.statSize == 0L && java.io.FileInputStream(pfd.fileDescriptor).channel.read(java.nio.ByteBuffer.allocate(1), 0) == -1
    } catch (_: IOException) { false } catch (_: RuntimeException) { false }
    override fun close() { try { pfd.close() } catch (_: IOException) { } }
}

data class LocalOpenResult(
    val handle: LocalMediaHandle? = null, val contentType: String? = null,
    val displayName: String? = null, val error: String? = null,
)

object LocalMediaOpener {
    const val NOT_ON_PHONE = "This item isn't stored on the phone yet. Download it first."
    const val BAD_TYPE = "Only MP4 video and MP3 or M4A audio files can be cast from this phone."
    const val UNREADABLE = "Could not read the shared file."
    const val EMPTY = "This file is empty."
    const val NOT_MEDIA = "This file doesn't look like the MP4 or MP3 it claims to be."

    fun open(backend: LocalUriBackend, request: LocalShareRequest): LocalOpenResult {
        val type = LocalMediaPolicy.resolveType(backend.type(request.uri), request.intentType)
            ?: return LocalOpenResult(error = BAD_TYPE)
        val pfd = try { backend.openReadOnly(request.uri) }
            catch (_: SecurityException) { null } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
            ?: return LocalOpenResult(error = UNREADABLE)
        val handle = PfdMediaHandle(pfd)
        if (handle.isEmptyRegularFile()) { handle.close(); return LocalOpenResult(error = EMPTY) }
        val probe = handle.openSource()
        if (probe == null) { handle.close(); return LocalOpenResult(error = NOT_ON_PHONE) }
        val head = ByteArray(12)
        val read = try { probe.readAt(java.nio.ByteBuffer.wrap(head), 0) } catch (_: IOException) { -1 } finally { probe.close() }
        if (read < 3 || !LocalMediaPolicy.matchesMagic(type, head.copyOf(read))) { handle.close(); return LocalOpenResult(error = NOT_MEDIA) }
        return LocalOpenResult(handle, type, LocalMediaPolicy.sanitizeDisplayName(backend.displayName(request.uri), type))
    }
}
