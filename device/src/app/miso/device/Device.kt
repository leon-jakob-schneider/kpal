package app.miso.device

import app.miso.audio.AudioDiagnosticsCallbacks
import app.miso.audio.AudioDiagnosticsConfig
import app.miso.audio.AudioDiagnosticsEngine
import app.miso.audio.AudioDiagnosticsState
import app.miso.audio.AudioDuplexEngine

typealias DeviceAudioCallbacks = AudioDiagnosticsCallbacks
typealias DeviceAudioConfig = AudioDiagnosticsConfig
typealias DeviceAudioState = AudioDiagnosticsState

data class DeviceConfig(
    val audio: DeviceAudioConfig = DeviceAudioConfig(),
)

interface DeviceAudio {
    fun requestRecordPermission(onResult: (Boolean) -> Unit)

    fun start()

    fun stop()

    fun clearCapture()

    fun playCapturedAudio()

    fun playTestTone()

    fun currentState(): DeviceAudioState
}

interface DuplexDeviceAudio : DeviceAudio {
    fun playPcm16(bytes: ByteArray)

    fun takeNextCapturedChunk(): ByteArray?

    fun takeNextLogMessage(): String?
}

interface Device {
    val audio: DeviceAudio
}

internal class EngineBackedDeviceAudio(
    private val engine: AudioDiagnosticsEngine,
) : DeviceAudio {
    override fun requestRecordPermission(onResult: (Boolean) -> Unit) = engine.requestRecordPermission(onResult)

    override fun start() = engine.start()

    override fun stop() = engine.stop()

    override fun clearCapture() = engine.clearCapture()

    override fun playCapturedAudio() = engine.playCapturedAudio()

    override fun playTestTone() = engine.playTestTone()

    override fun currentState(): DeviceAudioState = engine.currentState()
}

internal class EngineBackedDuplexDeviceAudio(
    private val engine: AudioDuplexEngine,
) : DuplexDeviceAudio,
    DeviceAudio by EngineBackedDeviceAudio(engine) {
    override fun playPcm16(bytes: ByteArray) = engine.playPcm16(bytes)

    override fun takeNextCapturedChunk(): ByteArray? = engine.takeNextCapturedChunk()

    override fun takeNextLogMessage(): String? = engine.takeNextLogMessage()
}
