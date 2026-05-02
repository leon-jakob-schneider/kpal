package app.miso.device

import app.miso.audio.Audio
import app.miso.audio.AudioSessionConfig
import app.miso.audio.AudioSessionObserver

data class DeviceConfig(
    val audio: AudioSessionConfig = AudioSessionConfig(),
)

interface Device {
    val audio: Audio
}

expect class DeviceImpl(
    platformContext: Any? = null,
    audioObserver: AudioSessionObserver? = null,
    config: DeviceConfig = DeviceConfig(),
) : Device {
    override val audio: Audio
}
