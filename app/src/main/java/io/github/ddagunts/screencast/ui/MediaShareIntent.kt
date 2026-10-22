// Fork modification by bearyjd: adapt untrusted Android shares into preview input only.
package io.github.ddagunts.screencast.ui

import android.content.Intent
import android.net.Uri
import io.github.ddagunts.screencast.media.SharedMediaParser
import io.github.ddagunts.screencast.media.SharedMediaResult

object MediaShareIntent {
    fun parse(intent: Intent): SharedMediaResult {
        fun reject() = SharedMediaResult(error = "Share one direct HTTP(S) MP4 or MP3 URL. Local files and multiple items are unsupported.")
        return try {
            if (intent.action != Intent.ACTION_SEND || intent.data != null) return reject()
            val candidates = mutableListOf<String>()
            @Suppress("DEPRECATION")
            val text = intent.extras?.get(Intent.EXTRA_TEXT)
            if (text != null && text !is CharSequence) return reject()
            if (text is CharSequence) candidates += text.toString()
            @Suppress("DEPRECATION")
            val stream = intent.extras?.get(Intent.EXTRA_STREAM)
            if (stream != null && stream !is Uri) return reject()
            if (stream is Uri) candidates += stream.toString()
            intent.clipData?.let { clip ->
                if (clip.itemCount != 1) return reject()
                val item = clip.getItemAt(0)
                if (item.intent != null || item.htmlText != null) return reject()
                item.uri?.let { candidates += it.toString() }
                item.text?.let { candidates += it.toString() }
            }
            val unique = candidates.map { it.trim() }.distinct()
            if (unique.size != 1) reject() else SharedMediaParser.parse(unique.single(), intent.type)
        } catch (_: RuntimeException) { reject() }
    }

    /** Classifies a share carrying a file (EXTRA_STREAM). Only a single content:// item is accepted. */
    fun local(intent: Intent): LocalShareResult {
        fun reject() = LocalShareResult(error = "Share a single video or audio file (content from another app).")
        return try {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) return if (intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) reject() else LocalShareResult()
            if (intent.action != Intent.ACTION_SEND) return LocalShareResult()
            @Suppress("DEPRECATION")
            val stream = intent.extras?.get(Intent.EXTRA_STREAM) ?: return LocalShareResult()
            if (stream !is Uri) return reject()
            val scheme = stream.scheme?.lowercase(java.util.Locale.ROOT)
            if (scheme == "http" || scheme == "https") return LocalShareResult()
            val text = intent.extras?.containsKey(Intent.EXTRA_TEXT) == true
            val clipOk = intent.clipData?.let { clip ->
                val item = if (clip.itemCount == 1) clip.getItemAt(0) else null
                item != null && item.intent == null && item.htmlText == null && item.text == null && (item.uri == null || item.uri == stream)
            } ?: true
            val acceptable = scheme == "content" && !stream.authority.isNullOrBlank() && intent.data == null && !text && clipOk &&
                stream.toString().length <= io.github.ddagunts.screencast.media.LocalMediaPolicy.MAX_URI_CHARS
            if (acceptable) LocalShareResult(LocalShareRequest(stream, intent.type)) else reject()
        } catch (_: RuntimeException) { reject() }
    }
}

data class LocalShareRequest(val uri: Uri, val intentType: String?)
data class LocalShareResult(val request: LocalShareRequest? = null, val error: String? = null)
