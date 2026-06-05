package app.miso.audio

import java.lang.System.currentTimeMillis
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

class JvmAudioEngine(
    private val observer: AudioSessionObserver? = null,
    private val config: AudioSessionConfig = AudioSessionConfig(),
) : Audio, AudioEngine, AudioDuplex {
    @Volatile
    private var running = false

    @Volatile
    private var inputLevel = 0f

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var playedBytes = 0L

    @Volatile
    private var lastRoute: AudioRoute? = null

    @Volatile
    private var lastMessage = "Idle"

    private val audioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        config.sampleRate.toFloat(),
        16,
        1,
        2,
        config.sampleRate.toFloat(),
        false,
    )
    private val captureLock = Any()
    private val pendingCapturedChunks = ArrayDeque<ByteArray>()
    private val pendingInputContinuations = ArrayDeque<CancellableContinuation<ByteArray>>()
    private var readySignal: CompletableDeferred<Unit>? = null
    private var failureSignal: CompletableDeferred<AudioException>? = null
    private var duplexError: AudioException? = null

    private var targetLine: TargetDataLine? = null
    private var sourceLine: SourceDataLine? = null
    private var captureThread: Thread? = null

    override suspend fun requestEngine(): AudioEngineRequest {
        return if (isOutputAvailable() && (!config.enableInput || isInputAvailable())) {
            AudioEngineRequest(engine = this)
        } else {
            val missing = when {
                !isOutputAvailable() -> "No JVM desktop PCM output line is available."
                else -> "No JVM desktop PCM input line is available."
            }
            emitError(missing)
            AudioEngineRequest(engine = null)
        }
    }

    override suspend fun useDuplex(block: (AudioDuplex) -> Unit) {
        start()
        try {
            awaitReadyOrFailure()
            block(this)
            duplexError?.let { throw it }
        } finally {
            stop()
        }
    }

    override fun currentState(): AudioSessionState = AudioSessionState(
        isRunning = running,
        inputLevel = inputLevel,
        capturedBytes = capturedBytes,
        playedBytes = playedBytes,
        route = routeSnapshot().also { lastRoute = it },
    )

    override suspend fun restart() {
        currentCoroutineContext().ensureActive()
        stop()
        start()
        awaitReadyOrFailure()
    }

    override suspend fun playPcm16(bytes: ByteArray) {
        currentCoroutineContext().ensureActive()
        if (bytes.isEmpty()) {
            return
        }

        val line = sourceLine ?: throw AudioDuplexException("JVM desktop audio output is not running.")

        val written = line.write(bytes, 0, bytes.size)
        currentCoroutineContext().ensureActive()
        if (written > 0) {
            playedBytes += written.toLong()
        } else {
            val error = AudioDuplexException("JVM desktop audio output write failed: $written")
            failDuplex(error)
            throw error
        }
        emitState("Played PCM16 buffer")
    }

    override suspend fun takeNextInputPcm16(): ByteArray {
        currentCoroutineContext().ensureActive()
        if (!config.enableInput) {
            throw AudioInputException("JVM desktop audio input is disabled.")
        }
        synchronized(captureLock) {
            duplexError?.let { throw it }
            pendingCapturedChunks.removeFirstOrNull()?.let { return it }
            if (!running) {
                throw AudioDuplexException("JVM desktop audio duplex is not running.")
            }
        }
        return suspendCancellableCoroutine { continuation ->
            synchronized(captureLock) {
                duplexError?.let {
                    continuation.resumeWithException(it)
                    return@synchronized
                }
                pendingCapturedChunks.removeFirstOrNull()?.let {
                    continuation.resume(it)
                    return@synchronized
                }
                if (!running) {
                    continuation.resumeWithException(AudioDuplexException("JVM desktop audio duplex is not running."))
                    return@synchronized
                }
                pendingInputContinuations.addLast(continuation)
                continuation.invokeOnCancellation {
                    synchronized(captureLock) {
                        pendingInputContinuations.remove(continuation)
                    }
                }
            }
        }
    }

    private fun start() {
        if (running) {
            emitLog("Start ignored because the engine is already running.")
            return
        }
        readySignal = CompletableDeferred()
        failureSignal = CompletableDeferred()
        duplexError = null

        synchronized(captureLock) {
            pendingCapturedChunks.clear()
            pendingInputContinuations.clear()
            capturedBytes = 0
            playedBytes = 0
        }

        runCatching {
            val playback = createSourceLine()
            val capture = if (config.enableInput) createTargetLine() else null

            sourceLine = playback
            targetLine = capture
            running = true
            playback.start()
            capture?.start()
            updateRoute("Started JVM desktop audio session")
            if (capture != null) {
                startCaptureLoop(capture)
            } else {
                readySignal?.complete(Unit)
            }
        }.onFailure { error ->
            val audioError = AudioStartupException(error.message ?: "JVM desktop audio session start failed.", error)
            running = false
            cleanupAudio()
            emitError(audioError.message ?: "JVM desktop audio session start failed.")
            failDuplex(audioError)
            throw audioError
        }
    }

    private fun stop() {
        if (!running && targetLine == null && sourceLine == null) {
            return
        }

        running = false
        captureThread?.interrupt()
        captureThread = null
        cancelInputReaders(AudioDuplexException("JVM desktop audio duplex stopped."))
        cleanupAudio()
        inputLevel = 0f
        updateRoute("Stopped")
    }

    private fun createTargetLine(): TargetDataLine {
        val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
        if (!AudioSystem.isLineSupported(info)) {
            throw LineUnavailableException("JVM desktop PCM input line is unavailable for ${formatSummary()}.")
        }
        return (AudioSystem.getLine(info) as TargetDataLine).also { line ->
            line.open(audioFormat, config.ioBufferFrames * audioFormat.frameSize * 2)
        }
    }

    private fun createSourceLine(): SourceDataLine {
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
        if (!AudioSystem.isLineSupported(info)) {
            throw LineUnavailableException("JVM desktop PCM output line is unavailable for ${formatSummary()}.")
        }
        return (AudioSystem.getLine(info) as SourceDataLine).also { line ->
            line.open(audioFormat, config.ioBufferFrames * audioFormat.frameSize * 2)
        }
    }

    private fun startCaptureLoop(line: TargetDataLine) {
        captureThread = thread(name = "jvm-desktop-audio-capture") {
            val buffer = ByteArray(config.ioBufferFrames * audioFormat.frameSize)
            while (running && !Thread.currentThread().isInterrupted) {
                val read = line.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    inputLevel = calculatePcm16Level(chunk, read)
                    enqueueCapturedChunk(chunk)
                    emitState("Capturing PCM input")
                } else if (read < 0) {
                    failDuplex(AudioInputException("JVM desktop audio input read failed: $read"))
                }
            }
        }
    }

    private suspend fun awaitReadyOrFailure() {
        val ready = readySignal ?: return
        val failure = failureSignal ?: CompletableDeferred()
        select {
            ready.onAwait { }
            failure.onAwait { error -> throw error }
        }
    }

    private fun enqueueCapturedChunk(chunk: ByteArray) {
        val continuation = synchronized(captureLock) {
            if (!running || duplexError != null) {
                return
            }
            capturedBytes += chunk.size.toLong()
            pendingInputContinuations.removeFirstOrNull().also { continuation ->
                if (continuation == null) {
                    pendingCapturedChunks.addLast(chunk)
                }
            }
        }
        readySignal?.complete(Unit)
        continuation?.resume(chunk)
    }

    private fun failDuplex(error: AudioException) {
        duplexError = error
        running = false
        failureSignal?.complete(error)
        cancelInputReaders(error)
    }

    private fun cancelInputReaders(error: AudioException) {
        val continuations = synchronized(captureLock) {
            buildList {
                while (true) {
                    add(pendingInputContinuations.removeFirstOrNull() ?: break)
                }
            }
        }
        continuations.forEach { it.resumeWithException(error) }
    }

    private fun cleanupAudio() {
        targetLine?.let { line ->
            runCatching { line.stop() }
            line.close()
        }
        targetLine = null

        sourceLine?.let { line ->
            runCatching { line.drain() }
            runCatching { line.stop() }
            line.close()
        }
        sourceLine = null
    }

    private fun routeSnapshot(): AudioRoute {
        val inputMixers = mixerNames(TargetDataLine::class.java)
        val outputMixers = mixerNames(SourceDataLine::class.java)
        val active = listOf(inputMixers, outputMixers).joinToString(" ")
        return AudioRoute(
            input = inputMixers.ifBlank { "none" },
            output = outputMixers.ifBlank { "none" },
            category = "Java Sound",
            mode = if (config.enableInput) "PCM16_DUPLEX" else "PCM16_PLAYBACK",
            sampleRate = config.sampleRate.toDouble(),
            ioBufferDurationMillis = config.ioBufferFrames.toDouble() / config.sampleRate.toDouble() * 1_000.0,
            hasBluetooth = active.contains("bluetooth", ignoreCase = true),
            hasBuiltInAudio = active.contains("built-in", ignoreCase = true) ||
                active.contains("speaker", ignoreCase = true) ||
                active.contains("microphone", ignoreCase = true),
            timestampMillis = currentTimeMillis(),
        )
    }

    private fun mixerNames(lineClass: Class<*>): String {
        val info = DataLine.Info(lineClass, audioFormat)
        return AudioSystem.getMixerInfo()
            .mapNotNull { mixerInfo ->
                runCatching {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (mixer.isLineSupported(info)) {
                        mixerInfo.name
                    } else {
                        null
                    }
                }.getOrNull()
            }
            .distinct()
            .joinToString()
    }

    private fun isInputAvailable(): Boolean = AudioSystem.isLineSupported(
        DataLine.Info(TargetDataLine::class.java, audioFormat),
    )

    private fun isOutputAvailable(): Boolean = AudioSystem.isLineSupported(
        DataLine.Info(SourceDataLine::class.java, audioFormat),
    )

    private fun calculatePcm16Level(bytes: ByteArray, byteCount: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < byteCount) {
            val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
            val normalized = sample.toShort().toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            samples += 1
            index += 2
        }
        return if (samples == 0) 0f else sqrt(sumSquares / samples).toFloat().coerceIn(0f, 1f)
    }

    private fun updateRoute(message: String) {
        lastRoute = routeSnapshot()
        emitState(message)
        emitLog("Route: input=${lastRoute?.input} output=${lastRoute?.output}")
    }

    private fun formatSummary(): String {
        return "${config.sampleRate} Hz mono signed PCM16 little-endian"
    }

    private fun emitLog(message: String) {
        lastMessage = message
    }

    private fun emitError(message: String) {
        lastMessage = message
        observer?.onError(AudioError(message))
        observer?.onStateChanged(currentState())
    }

    private fun emitState(message: String) {
        lastMessage = message
        observer?.onStateChanged(currentState())
    }
}
