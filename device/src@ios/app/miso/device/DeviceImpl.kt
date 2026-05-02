package app.miso.device

import app.miso.audio.Audio
import app.miso.audio.AudioSessionObserver
import app.miso.audio.IosAudioEngine

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    audioObserver: AudioSessionObserver?,
    config: DeviceConfig,
) : Device {
    actual override val audio: Audio = IosAudioEngine(
        observer = audioObserver,
        config = config.audio,
    )
}
