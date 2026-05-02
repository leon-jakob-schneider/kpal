package app.miso.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AndroidAudioEngine(
    private val context: Context,
    private val observer: AudioSessionObserver? = null,
    private val config: AudioSessionConfig = AudioSessionConfig(),
) : AudioEngine {
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

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    private val captureLock = Any()
    private val pendingCapturedChunks = ArrayDeque<ByteArray>()

    override fun requestInputPermission(onResult: (Boolean) -> Unit) {
        onResult(true)
    }

    override fun start() {
        if (running) {
            emitLog("Start ignored because the engine is already running.")
            return
        }
        synchronized(captureLock) {
            pendingCapturedChunks.clear()
            capturedBytes = 0
            playedBytes = 0
        }

        runCatching {
            configureAudioRoute()
            val record = createAudioRecord()
            val track = createAudioTrack()
            audioRecord = record
            audioTrack = track
            running = true
            record.startRecording()
            track.play()
            updateRoute("Started full-duplex audio session")
            startCaptureLoop(record)
        }.onFailure { error ->
            running = false
            cleanupAudio()
            emitError(error.message ?: "Android audio session start failed.")
        }
    }

    override fun stop() {
        if (!running && audioRecord == null && audioTrack == null) {
            return
        }
        running = false
        captureThread?.interrupt()
        captureThread = null
        cleanupAudio()
        restoreAudioRoute()
        inputLevel = 0f
        updateRoute("Stopped")
    }

    override fun playPcm16(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        val track = audioTrack ?: createAudioTrack().also { audioTrack = it; it.play() }
        val written = track.write(bytes, 0, bytes.size)
        if (written > 0) {
            playedBytes += written.toLong()
        }
        emitState("Played PCM16 buffer")
    }

    override fun currentState(): AudioSessionState = AudioSessionState(
        isRunning = running,
        inputLevel = inputLevel,
        capturedBytes = capturedBytes,
        playedBytes = playedBytes,
        route = routeSnapshot().also { lastRoute = it },
    )

    override fun takeNextInputPcm16(): ByteArray? = synchronized(captureLock) {
        pendingCapturedChunks.removeFirstOrNull()
    }

    private fun createAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "AudioRecord minimum buffer is invalid: $minBufferSize" }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize * 2, config.ioBufferFrames * 2),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialize." }
        return record
    }

    private fun createAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "AudioTrack minimum buffer is invalid: $minBufferSize" }

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBufferSize * 2, config.ioBufferFrames * 2))
            .build()
    }

    private fun startCaptureLoop(record: AudioRecord) {
        captureThread = thread(name = "audio-diagnostic-capture") {
            val buffer = ByteArray(config.ioBufferFrames * 2)
            while (running && !Thread.currentThread().isInterrupted) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    synchronized(captureLock) {
                        pendingCapturedChunks.addLast(chunk)
                        capturedBytes += read.toLong()
                    }
                    inputLevel = calculatePcm16Level(chunk, read)
                    emitState("Capturing PCM input")
                } else if (read < 0) {
                    emitLog("AudioRecord read returned $read")
                }
            }
        }
    }

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

    private fun configureAudioRoute() {
        val manager = audioManager()
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = config.preferSpeaker
        emitLog("Configured AudioManager mode=MODE_IN_COMMUNICATION speaker=${config.preferSpeaker}")
    }

    private fun restoreAudioRoute() {
        val manager = audioManager()
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = false
        manager.mode = AudioManager.MODE_NORMAL
        emitLog("Restored AudioManager mode=MODE_NORMAL speaker=false")
    }

    private fun cleanupAudio() {
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null

        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
        audioTrack = null
    }

    private fun updateRoute(message: String) {
        lastRoute = routeSnapshot()
        emitState(message)
        emitLog("Route: input=${lastRoute?.input} output=${lastRoute?.output}")
    }

    private fun routeSnapshot(): AudioRoute {
        val manager = audioManager()
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { it.diagnosticName() }
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { it.diagnosticName() }
        val active = listOf(inputs, outputs).joinToString(" ")
        val bluetooth = active.contains("Bluetooth", ignoreCase = true)
        val builtIn = active.contains("Built-in", ignoreCase = true) || active.contains("Speaker", ignoreCase = true)
        return AudioRoute(
            input = inputs.ifBlank { "none" },
            output = outputs.ifBlank { "none" },
            category = "AudioManager",
            mode = audioModeName(manager.mode),
            sampleRate = config.sampleRate.toDouble(),
            ioBufferDurationMillis = config.ioBufferFrames.toDouble() / config.sampleRate.toDouble() * 1_000.0,
            hasBluetooth = bluetooth,
            hasBuiltInAudio = builtIn,
            timestampMillis = SystemClock.elapsedRealtime(),
        )
    }

    private fun AudioDeviceInfo.diagnosticName(): String {
        return "${typeName()} ${productName ?: "unknown"} id=$id channels=${channelCounts.joinToString()}"
    }

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> "type=$type"
    }

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "mode=$mode"
    }

    private fun audioManager(): AudioManager = context.getSystemService(AudioManager::class.java)
        ?: error("AudioManager is unavailable.")

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
