package app.miso.device

import app.miso.audio.Audio
import app.miso.audio.AudioSessionObserver
import app.miso.audio.JvmAudioEngine

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    audioObserver: AudioSessionObserver?,
    config: DeviceConfig,
) : Device {
    actual override val audio: Audio = JvmAudioEngine(
        observer = audioObserver,
        config = config.audio,
    )
}
