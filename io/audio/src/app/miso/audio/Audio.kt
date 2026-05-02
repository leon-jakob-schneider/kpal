package app.miso.audio

import kotlin.math.PI
import kotlin.math.sin

data class AudioSessionConfig(
    val sampleRate: Int = 24_000,
    val ioBufferFrames: Int = 1_024,
    val preferSpeaker: Boolean = true,
    val voiceProcessing: Boolean = true,
    val enableInput: Boolean = true,
    val preferBluetoothA2dpOutput: Boolean = false,
)

data class AudioRoute(
    val input: String,
    val output: String,
    val category: String,
    val mode: String,
    val sampleRate: Double,
    val ioBufferDurationMillis: Double,
    val hasBluetooth: Boolean,
    val hasBuiltInAudio: Boolean,
    val timestampMillis: Long,
)

data class AudioSessionState(
    val isRunning: Boolean = false,
    val inputLevel: Float = 0f,
    val capturedBytes: Long = 0,
    val playedBytes: Long = 0,
    val route: AudioRoute? = null,
)

interface AudioSessionObserver {
    fun onStateChanged(state: AudioSessionState)

    fun onError(error: AudioError)
}

data class AudioError(
    val message: String,
)

interface AudioEngine {
    fun requestInputPermission(onResult: (Boolean) -> Unit)

    fun start()

    fun stop()

    fun currentState(): AudioSessionState

    fun playPcm16(bytes: ByteArray)

    fun takeNextInputPcm16(): ByteArray?
}

class Pcm16Buffer(private val maxBytes: Int = Int.MAX_VALUE) {
    private val chunks = ArrayDeque<ByteArray>()

    var sizeBytes: Int = 0
        private set

    fun clear() {
        chunks.clear()
        sizeBytes = 0
    }

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            return
        }
        chunks.addLast(chunk)
        sizeBytes += chunk.size
        trimToMaxBytes()
    }

    fun snapshot(): ByteArray {
        val snapshot = ByteArray(sizeBytes)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(snapshot, offset, 0, chunk.size)
            offset += chunk.size
        }
        return snapshot
    }

    private fun trimToMaxBytes() {
        if (sizeBytes <= maxBytes) {
            return
        }
        var bytesToDrop = sizeBytes - maxBytes
        while (bytesToDrop > 0 && chunks.isNotEmpty()) {
            val first = chunks.removeFirst()
            if (first.size <= bytesToDrop) {
                sizeBytes -= first.size
                bytesToDrop -= first.size
            } else {
                val retained = first.copyOfRange(bytesToDrop, first.size)
                chunks.addFirst(retained)
                sizeBytes -= bytesToDrop
                bytesToDrop = 0
            }
        }
    }
}

object Pcm16ToneGenerator {
    fun sine(
        frequencyHz: Double = 440.0,
        durationMillis: Int = 1_500,
        sampleRate: Int = 24_000,
        amplitude: Float = 0.22f,
    ): ByteArray {
        val sampleCount = (sampleRate.toLong() * durationMillis / 1_000L).toInt().coerceAtLeast(0)
        val bytes = ByteArray(sampleCount * 2)
        for (sampleIndex in 0 until sampleCount) {
            val sample = (
                sin(2.0 * PI * frequencyHz * sampleIndex / sampleRate) *
                    Short.MAX_VALUE *
                    amplitude.coerceIn(0f, 1f)
                ).toInt()
            bytes[sampleIndex * 2] = (sample and 0xff).toByte()
            bytes[sampleIndex * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return bytes
    }
}

class AudioCaptureBuffer(maxBytes: Int = Int.MAX_VALUE) {
    private val buffer = Pcm16Buffer(maxBytes)

    val sizeBytes: Int
        get() = buffer.sizeBytes

    fun clear() {
        buffer.clear()
    }

    fun drainFrom(audio: AudioEngine) {
        while (true) {
            val chunk = audio.takeNextInputPcm16() ?: return
            buffer.append(chunk)
        }
    }

    fun play(audio: AudioEngine): Boolean {
        val bytes = buffer.snapshot()
        if (bytes.isEmpty()) {
            return false
        }
        audio.playPcm16(bytes)
        return true
    }
}
