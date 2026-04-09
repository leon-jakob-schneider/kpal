package app.miso.device

import android.content.Context
import app.miso.audio.AndroidAudioDiagnosticsEngine

class AndroidDevice(
    context: Context,
    callbacks: DeviceAudioCallbacks? = null,
    config: DeviceConfig = DeviceConfig(),
) : Device {
    private val engine = AndroidAudioDiagnosticsEngine(
        context = context,
        callbacks = callbacks,
        config = config.audio,
    )

    override val audio: DuplexDeviceAudio = EngineBackedDuplexDeviceAudio(engine)
}
