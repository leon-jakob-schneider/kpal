package app.miso.audio

data class AudioDiagnosticsConfig(
    val sampleRate: Int = 24_000,
    val ioBufferFrames: Int = 1_024,
    val preferSpeaker: Boolean = true,
    val voiceProcessing: Boolean = true,
    val enableInput: Boolean = true,
    val preferBluetoothA2dpOutput: Boolean = false,
)

data class AudioRouteSnapshot(
    val input: String,
    val output: String,
    val category: String,
    val mode: String,
    val sampleRate: Double,
    val ioBufferDurationMillis: Double,
    val bluetoothActive: Boolean,
    val builtInAudioActive: Boolean,
    val timestampMillis: Long,
)

data class AudioDiagnosticsState(
    val isRunning: Boolean = false,
    val inputLevel: Float = 0f,
    val capturedBytes: Long = 0,
    val playedBytes: Long = 0,
    val captureCallbacks: Long = 0,
    val convertedChunks: Long = 0,
    val playbackRequests: Long = 0,
    val route: AudioRouteSnapshot? = null,
    val lastMessage: String = "Idle",
)

interface AudioDiagnosticsCallbacks {
    fun onStateChanged(state: AudioDiagnosticsState)

    fun onLog(message: String)

    fun onError(message: String)
}

interface AudioDiagnosticsEngine {
    fun requestRecordPermission(onResult: (Boolean) -> Unit)

    fun start()

    fun stop()

    fun clearCapture()

    fun playCapturedAudio()

    fun playTestTone()

    fun currentState(): AudioDiagnosticsState
}

interface AudioDuplexEngine : AudioDiagnosticsEngine {
    fun playPcm16(bytes: ByteArray)

    fun takeNextCapturedChunk(): ByteArray?

    fun takeNextLogMessage(): String?
}
