package io.github.ddagunts.screencast.webrtc

internal class FakeRec(override val state: Int = 1) : PlaybackRecord {
    var releases = 0
    override var isRecording = false
    override fun release() { releases++ }
}

internal class FakeAdmPort : AdmPort {
    val events = mutableListOf<String>()
    var threadIdle = true
    var hasRecordAtStart = false
    var initResult: Boolean = true
    var initFails: Throwable? = null
    var buildFails: Throwable? = null
    var installFails: Throwable? = null
    var identityHolds = true
    internal var playback: PlaybackRecord = FakeRec()
    internal var installed: PlaybackRecord? = null
    private var depth = 0
    override fun <T> atomically(block: () -> T): T { events += "lock+"; depth++; try { return block() } finally { depth--; events += "lock-" } }
    override fun isCaptureThreadIdle() = threadIdle
    override fun hasRecord() = hasRecordAtStart
    override fun setRecordEnabled(enabled: Boolean) { events += "enable($enabled)"; check(depth > 0) { "setUseAudioRecord must be inside the lock" }; onEnable(enabled) }
    var onEnable: (Boolean) -> Unit = {}
    override fun initRecording(): Boolean { events += "init"; initFails?.let { throw it }; return initResult }
    override fun buildPlayback(): PlaybackRecord { events += "build"; buildFails?.let { throw it }; return playback }
    override fun install(record: PlaybackRecord?) { events += if (record == null) "uninstall" else "install"; installFails?.let { if (record != null) throw it }; installed = record }
    override fun isInstalled(record: PlaybackRecord) = identityHolds && installed === record
    override fun diagnosticSource(record: PlaybackRecord): Int? = 8
    override fun disableRecording() { events += "disable"; disabled = true }
    override fun disableRecordingNow() { events += "disable"; disabled = true }
    var disabled = false
    var quietAfterDisable = true
    override fun isQuiet(record: PlaybackRecord) = disabled && quietAfterDisable
}

