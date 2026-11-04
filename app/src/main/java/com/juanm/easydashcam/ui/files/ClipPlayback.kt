package com.juanm.easydashcam.ui.files

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.juanm.easydashcam.core.media.AviMjpegPlayer
import com.juanm.easydashcam.core.net.GpsocketClient.DownloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays one AVI/MJPEG clip: a local file, or a streamed one whose bytes
 * [fetch] writes into the file [player] reads while it grows.
 *
 * The video loop decodes one frame per [AviMjpegPlayer.frameMs]; PCM audio
 * feeds a self-clocking AudioTrack and follows the video position on seeks.
 * The UI observes [state] and calls [seekTo], [skip] and [togglePause].
 */
class ClipPlayback(
    private val player: AviMjpegPlayer,
    private val isImage: Boolean,
    private val fetch: (suspend (onProgress: (Long, Long) -> Unit) -> DownloadResult)?
) {
    sealed interface Failure {
        data class Transfer(val result: DownloadResult) : Failure
        data object NoFrames : Failure
        data class Crash(val message: String) : Failure
    }

    data class State(
        val frame: Bitmap? = null,
        val failure: Failure? = null,
        val pos: Int = 0,
        val paused: Boolean = false,
        val indexed: Boolean = false,
        val buffering: Boolean = false,
        val streamGot: Long = 0,
        val streamTotal: Long = 0,
        val streamDone: Boolean = false,
        /** Outcome of a streamed clip's transfer, set together with [streamDone]. */
        val transfer: DownloadResult? = null,
        val frameCount: Int = 0
    )

    private val _state = MutableStateFlow(State(streamDone = fetch == null))
    val state: StateFlow<State> = _state

    private val lastFrame get() = (_state.value.frameCount - 1).coerceAtLeast(0)

    /** Clip time of the audio actually played, or -1 while no audio plays.
     *  Video follows it: it is the only clock that runs at real speed. */
    private val audioClockMs = java.util.concurrent.atomic.AtomicLong(-1)

    fun seekTo(frame: Int) = _state.update { it.copy(pos = frame.coerceIn(0, lastFrame)) }

    fun skip(frames: Int) = _state.update { it.copy(pos = (it.pos + frames).coerceIn(0, lastFrame), paused = false) }

    fun togglePause() = _state.update {
        val paused = !it.paused
        it.copy(paused = paused, pos = if (!paused && it.pos >= it.frameCount) 0 else it.pos)
    }

    /** Runs until cancelled. Failures end up in [State.failure]. */
    // A corrupt or truncated clip can throw from any parser or decoder
    // call; it must show an error in the player, not crash the app.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run() = coroutineScope {
        try {
            if (fetch != null) launch(Dispatchers.IO) { fetchClip(fetch) }
            if (!awaitPlayable()) return@coroutineScope
            _state.update { it.copy(frameCount = player.frames.size, indexed = true) }
            val audioTrack = java.util.concurrent.atomic.AtomicReference<AudioTrack?>()
            // IO, not the caller's Main: AudioTrack.write blocks for up to a
            // buffer's worth of audio.
            val audioJob = launch(Dispatchers.IO) { playAudio { audioTrack.set(it) } }
            try {
                playVideo()
            } finally {
                audioJob.cancel()
                // Unblock a write() stuck on a full buffer — it doesn't
                // observe cancellation, so it would delay the release.
                audioTrack.get()?.let { runCatching { it.stop() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = Failure.Crash(e.message ?: e.javaClass.simpleName)
            _state.update { it.copy(failure = failure) }
        }
    }

    private suspend fun fetchClip(fetch: suspend (onProgress: (Long, Long) -> Unit) -> DownloadResult) {
        var lastMs = 0L
        var result: DownloadResult? = null
        try {
            result = fetchOrFail(fetch) { got, total ->
                val now = System.currentTimeMillis()
                // Throttle: the callback fires per 32 KiB chunk.
                if (now - lastMs >= 300 || got >= total) {
                    lastMs = now
                    _state.update { it.copy(streamGot = got, streamTotal = total) }
                }
            }
        } finally {
            // One update: whoever sees streamDone also sees the outcome.
            _state.update { it.copy(streamDone = true, transfer = result) }
        }
    }

    /** Index what is playable; false (with [State.failure] set) when
     *  playback cannot start. */
    private suspend fun awaitPlayable(): Boolean {
        indexAvailable()
        val failure = transferFailure(_state.value.transfer) ?: Failure.NoFrames.takeIf { player.frames.isEmpty() }
        if (failure != null) _state.update { it.copy(failure = failure) }
        return failure == null
    }

    // Local clips index once. A photo is one JPEG, so it waits for the whole
    // transfer. A streamed video indexes as soon as its first frame arrives.
    private suspend fun indexAvailable() {
        when {
            fetch == null -> player.index()
            isImage -> {
                while (!_state.value.streamDone) delay(150)
                if (transferFailure(_state.value.transfer) == null) player.index()
            }
            else -> {
                while (player.frames.isEmpty() && !_state.value.streamDone) {
                    player.extendIndex()
                    if (player.frames.isEmpty()) delay(150)
                }
                player.extendIndex()
            }
        }
    }

    private suspend fun playVideo() {
        var lastDecoded = -1
        while (true) {
            // Streaming: index whatever arrived since the last pass — the
            // scan resumes at the last complete chunk, so this is ~one header
            // read per iteration when idle.
            if (fetch != null) {
                player.extendIndex()
                _state.update { it.copy(frameCount = player.frames.size) }
            }
            val s = _state.value
            val atEnd = s.pos >= player.frames.size
            val waitMs = when {
                atEnd && !s.streamDone -> {
                    _state.update { it.copy(buffering = true) }
                    120L
                }
                atEnd -> {
                    _state.update { it.copy(paused = true) } // end of the clip
                    80L
                }
                // Paused: decode once after the slider moved, then idle.
                s.paused && s.pos == lastDecoded -> 80L
                else -> {
                    val decodeStart = System.currentTimeMillis()
                    val bmp = player.frameAt(s.pos)
                    lastDecoded = s.pos
                    _state.update { it.copy(frame = bmp, buffering = false) }
                    if (s.paused) {
                        80L
                    } else {
                        val (next, wait) = advanceVideo(
                            s.pos,
                            player.frameMs,
                            audioClockMs.get(),
                            System.currentTimeMillis() - decodeStart
                        )
                        // A seek during the decode wins over the advance.
                        _state.update { if (it.pos == s.pos) it.copy(pos = minOf(next, player.frames.size)) else it }
                        wait
                    }
                }
            }
            delay(waitMs)
        }
    }

    private suspend fun CoroutineScope.playAudio(onTrack: (AudioTrack) -> Unit) {
        // A streamed clip can deliver its first audio chunk after its first frame.
        while (!player.hasAudio) {
            if (fetch == null || (_state.value.streamDone && player.fullyIndexed)) return
            delay(250)
        }
        val track = newAudioTrack(player.audioRate, player.audioChannels)
        onTrack(track)
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }
        try {
            feedAudio(track)
        } finally {
            audioClockMs.set(-1)
            runCatching { track.stop() }
            track.release()
        }
    }

    // PCM chunks feed a self-clocking AudioTrack. Audio is re-seeked to the
    // video time only when the two drift apart: one 6 KiB write is ~190 ms
    // of this camera's audio, during which video moves ~6 frames, so a
    // "next frame" check fired on almost every write and flushed the sound.
    private suspend fun CoroutineScope.feedAudio(track: AudioTrack) {
        val buf = ByteArray(8192)
        var bytePos = 0L
        var syncedAtMs = 0L
        var synced = false
        var streaming = false
        while (isActive) {
            val s = _state.value
            // Wait for the first decoded frame: starting audio during that
            // slow first decode put it ahead and forced re-seeks.
            if (s.paused || s.frame == null || s.pos >= player.frames.size) {
                audioClockMs.set(-1)
                if (streaming) track.pause()
                streaming = false
                delay(60)
            } else {
                if (!streaming) track.play()
                streaming = true
                val videoMs = s.pos * player.frameMs
                // Frames actually played since the last flush — bytes written
                // run ahead of what is audible by the track's buffer.
                val audioMs = syncedAtMs + track.playbackHeadPosition * 1000L / player.audioRate.coerceAtLeast(1)
                if (!synced || audioOutOfSync(audioMs, videoMs)) {
                    bytePos = player.audioPosForMs(videoMs)
                    syncedAtMs = videoMs
                    synced = true
                    track.pause()
                    track.flush()
                    track.play()
                }
                audioClockMs.set(syncedAtMs + track.playbackHeadPosition * 1000L / player.audioRate.coerceAtLeast(1))
                val n = player.audioRead(bytePos, buf)
                if (n > 0) {
                    bytePos += n
                    track.write(buf, 0, n) // blocks — paces the playback
                } else {
                    delay(60)
                }
            }
        }
    }
}

private fun newAudioTrack(rate: Int, channels: Int): AudioTrack = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(
                if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            )
            .build()
    )
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()

private fun transferFailure(result: DownloadResult?): ClipPlayback.Failure? =
    result?.takeIf { it !is DownloadResult.Success }?.let { ClipPlayback.Failure.Transfer(it) }

/** Audio and video may differ by this much before audio is re-seeked. */
private const val AUDIO_SYNC_TOLERANCE_MS = 200L

/** True when played audio and shown video are far enough apart to re-seek
 *  the audio: after a user seek, or once decode time has let video drift. */
internal fun audioOutOfSync(audioMs: Long, videoMs: Long): Boolean =
    kotlin.math.abs(audioMs - videoMs) > AUDIO_SYNC_TOLERANCE_MS

private suspend fun fetchOrFail(
    fetch: suspend (onProgress: (Long, Long) -> Unit) -> DownloadResult,
    onProgress: (Long, Long) -> Unit
): DownloadResult = try {
    fetch(onProgress)
} catch (e: java.io.IOException) {
    DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
}

/**
 * The frame to show next and how long to wait for it. With audio playing
 * ([audioClockMs] >= 0) video jumps to the frame matching the audio, so a
 * slow decode drops frames instead of falling behind and forcing an audible
 * audio re-seek. Without audio it advances one frame and counts the decode
 * against the frame interval.
 */
internal fun advanceVideo(pos: Int, frameMs: Long, audioClockMs: Long, decodeMs: Long): Pair<Int, Long> {
    if (audioClockMs < 0) return pos + 1 to (frameMs - decodeMs).coerceAtLeast(0)
    val next = maxOf(pos + 1, (audioClockMs / frameMs).toInt())
    return next to (next * frameMs - audioClockMs).coerceIn(0, frameMs)
}
