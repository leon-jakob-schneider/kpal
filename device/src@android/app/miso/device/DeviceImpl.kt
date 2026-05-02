package app.miso.device

import android.content.Context
import app.miso.audio.AndroidAudioEngine
import app.miso.audio.AudioEngine
import app.miso.audio.AudioSessionObserver

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    audioObserver: AudioSessionObserver?,
    config: DeviceConfig,
) : Device {
    private val context = platformContext as? Context
        ?: error("Android DeviceImpl requires an Android Context as platformContext.")

    actual override val audio: AudioEngine = AndroidAudioEngine(
        context = context,
        observer = audioObserver,
        config = config.audio,
    )
}
