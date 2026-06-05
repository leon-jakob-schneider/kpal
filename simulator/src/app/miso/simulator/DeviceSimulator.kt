package app.miso.simulator

import app.miso.audio.Audio
import app.miso.audio.AudioDuplex
import app.miso.audio.AudioEngine
import app.miso.audio.AudioEngineRequest
import app.miso.audio.AudioDuplexException
import app.miso.audio.AudioException
import app.miso.audio.AudioInputException
import app.miso.audio.AudioRoute
import app.miso.audio.AudioSessionConfig
import app.miso.audio.AudioSessionObserver
import app.miso.audio.AudioSessionState
import app.miso.audio.Pcm16Buffer
import app.miso.device.Device
import app.miso.device.DeviceConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.sqrt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeviceSimulator(
    config: DeviceConfig = DeviceConfig(),
    audioObserver: AudioSessionObserver? = null,
) : Device {
    val simulatedAudio = SimulatedAudio(
        config = config.audio,
        observer = audioObserver,
    )

    override val audio: Audio = simulatedAudio

    fun setAudioInputPcm16(bytes: ByteArray) {
        simulatedAudio.setInputPcm16(bytes)
    }

    fun appendAudioInputPcm16(bytes: ByteArray) {
        simulatedAudio.appendInputPcm16(bytes)
    }

    fun takeNextAudioOutputPcm16(): ByteArray? = simulatedAudio.takeNextOutputPcm16()

    fun audioOutputPcm16(): ByteArray = simulatedAudio.outputPcm16()

    fun drainAudioOutputPcm16(): ByteArray = simulatedAudio.drainOutputPcm16()

    fun clearAudioInput() {
        simulatedAudio.clearInput()
    }

    fun clearAudioOutput() {
        simulatedAudio.clearOutput()
    }
}

class SimulatedAudio(
    private val config: AudioSessionConfig = AudioSessionConfig(),
    private val observer: AudioSessionObserver? = null,
) : Audio, AudioEngine, AudioDuplex {
    private val pendingInputChunks = ArrayDeque<ByteArray>()
    private val pendingInputContinuations = ArrayDeque<CancellableContinuation<ByteArray>>()
    private val capturedOutputChunks = ArrayDeque<ByteArray>()
    private val capturedOutputBuffer = Pcm16Buffer()

    private var running = false
    private var inputLevel = 0f
    private var capturedBytes = 0L
    private var playedBytes = 0L
    private var routeSequence = 0L

    override suspend fun requestEngine(): AudioEngineRequest = AudioEngineRequest(engine = this)

    override suspend fun useDuplex(block: (AudioDuplex) -> Unit) {
        start()
        try {
            block(this)
        } finally {
            stop()
        }
    }

    override fun currentState(): AudioSessionState = AudioSessionState(
        isRunning = running,
        inputLevel = inputLevel,
        capturedBytes = capturedBytes,
        playedBytes = playedBytes,
        route = routeSnapshot(),
    )

    override suspend fun restart() {
        currentCoroutineContext().ensureActive()
        stop()
        start()
    }

    override suspend fun playPcm16(bytes: ByteArray) {
        currentCoroutineContext().ensureActive()
        if (bytes.isEmpty()) {
            return
        }
        val chunk = bytes.copyOf()
        capturedOutputChunks.addLast(chunk)
        capturedOutputBuffer.append(chunk)
        playedBytes += chunk.size.toLong()
        emitState()
    }

    override suspend fun takeNextInputPcm16(): ByteArray {
        currentCoroutineContext().ensureActive()
        if (!config.enableInput) {
            throw AudioInputException("Simulated audio input is disabled.")
        }
        val chunk = pendingInputChunks.removeFirstOrNull()
        if (chunk != null) {
            capturedBytes += chunk.size.toLong()
            inputLevel = calculatePcm16Level(chunk)
            emitState()
            return chunk.copyOf()
        }
        if (!running) {
            throw AudioDuplexException("Simulated audio duplex is not running.")
        }
        return suspendCancellableCoroutine { continuation ->
            pendingInputContinuations.addLast(continuation)
            continuation.invokeOnCancellation {
                pendingInputContinuations.remove(continuation)
            }
        }
    }

    private fun deliverInputChunk(chunk: ByteArray) {
        val continuation = pendingInputContinuations.removeFirstOrNull()
        inputLevel = calculatePcm16Level(chunk)
        if (continuation != null) {
            capturedBytes += chunk.size.toLong()
            emitState()
            continuation.resume(chunk.copyOf())
        } else {
            pendingInputChunks.addLast(chunk.copyOf())
            emitState()
        }
    }

    fun setInputPcm16(bytes: ByteArray) {
        clearInput()
        appendInputPcm16(bytes)
    }

    fun appendInputPcm16(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        deliverInputChunk(bytes)
    }

    fun takeNextOutputPcm16(): ByteArray? = capturedOutputChunks.removeFirstOrNull()?.copyOf()

    fun outputPcm16(): ByteArray = capturedOutputBuffer.snapshot()

    fun drainOutputPcm16(): ByteArray = outputPcm16().also {
        clearOutput()
    }

    fun clearInput() {
        pendingInputChunks.clear()
        cancelInputReaders(AudioInputException("Simulated audio input was cleared."))
        inputLevel = 0f
        emitState()
    }

    fun clearOutput() {
        capturedOutputChunks.clear()
        capturedOutputBuffer.clear()
        playedBytes = 0
        emitState()
    }

    private fun start() {
        if (running) {
            return
        }
        running = true
        capturedBytes = 0
        playedBytes = 0
        inputLevel = pendingInputChunks.firstOrNull()?.let(::calculatePcm16Level) ?: 0f
        emitState()
    }

    private fun stop() {
        if (!running) {
            return
        }
        running = false
        cancelInputReaders(AudioDuplexException("Simulated audio duplex stopped."))
        inputLevel = 0f
        emitState()
    }

    private fun cancelInputReaders(error: AudioException) {
        while (true) {
            val continuation = pendingInputContinuations.removeFirstOrNull() ?: return
            continuation.resumeWithException(error)
        }
    }

    private fun routeSnapshot(): AudioRoute {
        return AudioRoute(
            input = "simulated input",
            output = "simulated output",
            category = "Simulator",
            mode = "SIMULATED_DUPLEX",
            sampleRate = config.sampleRate.toDouble(),
            ioBufferDurationMillis = config.ioBufferFrames.toDouble() / config.sampleRate.toDouble() * 1_000.0,
            hasBluetooth = false,
            hasBuiltInAudio = true,
            timestampMillis = routeSequence,
        )
    }

    private fun emitState() {
        routeSequence += 1
        observer?.onStateChanged(currentState())
    }

    private fun calculatePcm16Level(bytes: ByteArray): Float {
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
            val normalized = sample.toShort().toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
            samples += 1
            index += 2
        }
        return if (samples == 0) 0f else sqrt(sumSquares / samples).toFloat().coerceIn(0f, 1f)
    }
}
