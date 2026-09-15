package com.synclisten.desktop.audio

import com.synclisten.shared.playback.PlayerEngine
import com.synclisten.shared.playback.PlayerEngineEvent
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock

interface AudioOutput : AutoCloseable {
    val playedFrames: Long
    fun start()
    fun stop()
    fun write(bytes: ByteArray, length: Int)
    fun drain()
}

private class JavaSoundOutput(format: AudioFormat) : AudioOutput {
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply { open(format, 8192) }
    override val playedFrames: Long get() = line.longFramePosition
    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun write(bytes: ByteArray, length: Int) { line.write(bytes, 0, length) }
    override fun drain() = line.drain()
    override fun close() { line.stop(); line.flush(); line.close() }
}

/** A session owns its decoder, output and thread. Loading only prepares; play is explicit. */
class DesktopPlayerEngine(
    private val outputFactory: (AudioFormat) -> AudioOutput = ::JavaSoundOutput,
) : PlayerEngine {
    private class Session(val file: File, val startMs: Long, initiallyPlaying: Boolean) {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        @Volatile var playing = initiallyPlaying
        @Volatile var stopped = false
        @Volatile var ended = false
        @Volatile var output: AudioOutput? = null
        @Volatile var stream: AudioInputStream? = null
        var thread: Thread? = null
    }

    @Volatile private var session: Session? = null
    @Volatile private var listener: (PlayerEngineEvent) -> Unit = {}
    override fun setEventListener(listener: (PlayerEngineEvent) -> Unit) { this.listener = listener }

    override fun load(localPath: String) = startSession(File(localPath), 0, false)

    override fun play() {
        val current = session ?: return
        if (current.ended) { startSession(current.file, 0, true); return }
        current.lock.withLock { current.playing = true; current.output?.start(); current.changed.signalAll() }
    }

    override fun pause() {
        val current = session ?: return
        current.lock.withLock { current.playing = false; current.output?.stop() }
    }

    override fun seekTo(positionMs: Long) {
        val current = session ?: return
        startSession(current.file, positionMs.coerceAtLeast(0), current.playing)
    }

    override fun setPlaybackSpeed(speed: Float) = Unit // The desktop sync manager uses seeks.
    override fun release() {
        val old = session
        session = null
        stop(old)
    }

    @Synchronized
    private fun startSession(file: File, startMs: Long, playing: Boolean) {
        val next = Session(file, startMs, playing)
        val old = session
        session = next
        stop(old)
        next.thread = Thread({ decode(next) }, "sync-listen-audio").apply { isDaemon = true; start() }
    }

    private fun stop(current: Session?) {
        current ?: return
        current.stopped = true
        current.lock.withLock { current.changed.signalAll() }
        runCatching { current.output?.close() }
        current.thread?.interrupt()
    }

    private fun emit(current: Session, event: PlayerEngineEvent) {
        if (session === current && !current.stopped) listener(event)
    }

    private fun decode(current: Session) {
        try {
            AudioSystem.getAudioInputStream(current.file).use { raw ->
                val base = raw.format
                val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false)
                AudioSystem.getAudioInputStream(pcm, raw).use { input ->
                    current.stream = input
                    val wanted = (current.startMs * pcm.frameRate / 1000).toLong() * pcm.frameSize
                    var skipped = 0L
                    val buffer = ByteArray(8192 - 8192 % pcm.frameSize)
                    while (skipped < wanted && !current.stopped) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), wanted - skipped).toInt())
                        if (count < 0) break
                        skipped += count
                    }
                    if (current.stopped) return
                    val offsetMs = (skipped / pcm.frameSize * 1000 / pcm.frameRate).toLong()
                    val duration = if (input.frameLength > 0) (input.frameLength * 1000 / pcm.frameRate).toLong() else 0L
                    outputFactory(pcm).use { output ->
                        current.output = output
                        emit(current, PlayerEngineEvent.Ready(duration))
                        while (!current.stopped) {
                            current.lock.withLock {
                                while (!current.playing && !current.stopped) current.changed.await()
                            }
                            if (current.stopped) break
                            output.start()
                            val count = input.read(buffer)
                            if (count < 0) {
                                output.drain()
                                current.ended = true
                                current.playing = false
                                emit(current, PlayerEngineEvent.Ended)
                                break
                            }
                            output.write(buffer, count)
                            val position = offsetMs + (output.playedFrames * 1000 / pcm.frameRate).toLong()
                            emit(current, PlayerEngineEvent.Position(position, duration))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (!current.stopped && error !is InterruptedException) {
                emit(current, PlayerEngineEvent.Error(error.message ?: "无法解码音频，桌面端建议使用 MP3 或 WAV"))
            }
        } finally {
            current.output = null
            current.stream = null
        }
    }
}
