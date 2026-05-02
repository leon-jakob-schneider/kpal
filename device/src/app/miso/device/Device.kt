package app.miso.device

import app.miso.audio.AudioEngine
import app.miso.audio.AudioSessionConfig
import app.miso.audio.AudioSessionObserver

data class DeviceConfig(
    val audio: AudioSessionConfig = AudioSessionConfig(),
)

interface Device {
    val audio: AudioEngine
}

expect class DeviceImpl(
    platformContext: Any? = null,
    audioObserver: AudioSessionObserver? = null,
    config: DeviceConfig = DeviceConfig(),
) : Device {
    override val audio: AudioEngine
}
