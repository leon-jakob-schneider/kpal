package app.miso.device

import app.miso.audio.AudioDiagnosticsCallbacks
import app.miso.audio.AudioDuplexEngine
import app.miso.audio.IosAudioDiagnosticsEngine

actual class DeviceImpl actual constructor(
    platformContext: Any?,
    callbacks: AudioDiagnosticsCallbacks?,
    config: DeviceConfig,
) : Device {
    actual override val audio: AudioDuplexEngine = IosAudioDiagnosticsEngine(
        callbacks = callbacks,
        config = config.audio,
    )
}
