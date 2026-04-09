package app.miso.device

import android.content.Context
import app.miso.audio.AndroidAudioDiagnosticsEngine
import app.miso.audio.AudioDiagnosticsCallbacks
import app.miso.audio.AudioDuplexEngine

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    callbacks: AudioDiagnosticsCallbacks?,
    config: DeviceConfig,
) : Device {
    private val context = platformContext as? Context
        ?: error("Android DeviceImpl requires an Android Context as platformContext.")

    actual override val audio: AudioDuplexEngine = AndroidAudioDiagnosticsEngine(
        context = context,
        callbacks = callbacks,
        config = config.audio,
    )
}
