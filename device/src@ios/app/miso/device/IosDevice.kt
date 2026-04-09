package app.miso.device

import app.miso.audio.IosAudioDiagnosticsEngine

class IosDevice(
    callbacks: DeviceAudioCallbacks? = null,
    config: DeviceConfig = DeviceConfig(),
) : Device {
    private val engine = IosAudioDiagnosticsEngine(
        callbacks = callbacks,
        config = config.audio,
    )

    override val audio: DuplexDeviceAudio = EngineBackedDuplexDeviceAudio(engine)
}
