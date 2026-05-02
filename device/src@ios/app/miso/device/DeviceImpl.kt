package app.miso.device

import app.miso.audio.AudioEngine
import app.miso.audio.AudioSessionObserver
import app.miso.audio.IosAudioEngine

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    audioObserver: AudioSessionObserver?,
    config: DeviceConfig,
) : Device {
    actual override val audio: AudioEngine = IosAudioEngine(
        observer = audioObserver,
        config = config.audio,
    )
}
