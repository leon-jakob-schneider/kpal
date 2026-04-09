package app.miso.device

import app.miso.audio.AudioDiagnosticsCallbacks
import app.miso.audio.AudioDiagnosticsConfig
import app.miso.audio.AudioDuplexEngine

data class DeviceConfig(
    val audio: AudioDiagnosticsConfig = AudioDiagnosticsConfig(),
)

interface Device {
    val audio: AudioDuplexEngine
}

expect class DeviceImpl(
    platformContext: Any? = null,
    callbacks: AudioDiagnosticsCallbacks? = null,
    config: DeviceConfig = DeviceConfig(),
) : Device {
    override val audio: AudioDuplexEngine
}
