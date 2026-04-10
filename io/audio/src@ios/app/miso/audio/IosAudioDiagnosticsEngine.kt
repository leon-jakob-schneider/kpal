package app.miso.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothHFP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.IOBufferDuration
import platform.AVFAudio.availableInputs
import platform.AVFAudio.currentRoute
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSLock
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalForeignApi::class)
class IosAudioDiagnosticsEngine(
    private val callbacks: AudioDiagnosticsCallbacks? = null,
    private val config: AudioDiagnosticsConfig = AudioDiagnosticsConfig(),
) : AudioDuplexEngine {
    private val playbackFormat = AVAudioFormat(
        commonFormat = platform.AVFAudio.AVAudioPCMFormatFloat32,
        sampleRate = config.sampleRate.toDouble(),
        channels = 1u,
        interleaved = false,
    )
    private val captureOutputFormat = AVAudioFormat(
        commonFormat = platform.AVFAudio.AVAudioPCMFormatInt16,
        sampleRate = config.sampleRate.toDouble(),
        channels = 1u,
        interleaved = false,
    )
    private val lock = NSLock()

    private var audioEngine: AVAudioEngine? = null
    private var playerNode: AVAudioPlayerNode? = null
    private var captureFormat: AVAudioFormat? = null
    private var running = false
    private var inputLevel = 0f
    private var capturedBytes = 0L
    private var playedBytes = 0L
    private var captureCallbacks = 0L
    private var convertedChunks = 0L
    private var playbackRequests = 0L
    private var lastRoute: AudioRouteSnapshot? = null
    private var lastMessage = "Idle"
    private var capturedAudio = ByteArray(0)
    private val pendingCapturedChunks = ArrayDeque<ByteArray>()
    private val pendingLogs = ArrayDeque<String>()
    private val notificationTokens = mutableListOf<Any>()
    private var reconfiguringRoute = false

    override fun requestRecordPermission(onResult: (Boolean) -> Unit) {
        val session = AVAudioSession.sharedInstance()
        session.requestRecordPermission { granted ->
            onResult(granted)
        }
    }

    override fun start() {
        if (running) {
            emitLog("Start ignored because the iOS engine is already running.")
            return
        }

        runCatching {
            val session = AVAudioSession.sharedInstance()
            registerSessionObservers()
            configureAudioSession(session)

            val engine = AVAudioEngine()
            val player = AVAudioPlayerNode()
            val inputNode = engine.inputNode

            if (config.voiceProcessing && !inputNode.isVoiceProcessingEnabled()) {
                check(inputNode.setVoiceProcessingEnabled(true, error = null)) {
                    "setVoiceProcessingEnabled failed."
                }
                emitLog("Enabled AVAudioInputNode voice processing.")
            }

            engine.attachNode(player)
            engine.connect(player, to = engine.mainMixerNode, format = playbackFormat)

            engine.prepare()
            audioEngine = engine
            playerNode = player
            installInputTap(inputNode, session, reason = "initial start")
            startAudioGraph(reason = "initial start")
            running = true
            updateRoute("Started AVAudioEngine full-duplex diagnostics")
        }.onFailure { error ->
            running = false
            cleanupAudioGraph()
            unregisterSessionObservers()
            emitError(error.message ?: "iOS audio diagnostic start failed.")
        }
    }

    override fun stop() {
        running = false
        cleanupAudioGraph()
        unregisterSessionObservers()
        val session = AVAudioSession.sharedInstance()
        runCatching { session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, error = null) }
        runCatching { session.setActive(false, error = null) }
        inputLevel = 0f
        updateRoute("Stopped")
        emitLog("Stopped and deactivated AVAudioSession.")
    }

    override fun clearCapture() {
        withLock {
            capturedAudio = ByteArray(0)
            pendingCapturedChunks.clear()
            capturedBytes = 0
            playedBytes = 0
            captureCallbacks = 0
            convertedChunks = 0
            playbackRequests = 0
        }
        emitState("Cleared captured PCM buffer")
    }

    override fun playCapturedAudio() {
        val bytes = withLock { capturedAudio }
        if (bytes.isEmpty()) {
            emitLog("No captured PCM to play.")
            return
        }
        playPcm16(bytes)
    }

    override fun playTestTone() {
        val sampleCount = (config.sampleRate * 1.5).toInt()
        val bytes = ByteArray(sampleCount * 2)
        for (sampleIndex in 0 until sampleCount) {
            val sample = (sin(2.0 * PI * 440.0 * sampleIndex / config.sampleRate) * Short.MAX_VALUE * 0.22).toInt()
            bytes[sampleIndex * 2] = (sample and 0xff).toByte()
            bytes[sampleIndex * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        playPcm16(bytes)
        emitLog("Played 440 Hz test tone.")
    }

    override fun playPcm16(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            emitLog("Ignored empty PCM16 playback request.")
            return
        }
        val player = playerNode ?: return
        val sampleCount = bytes.size / 2
        if (sampleCount <= 0) {
            return
        }
        playbackRequests += 1
        val buffer = AVAudioPCMBuffer(playbackFormat, sampleCount.toUInt())
        buffer.frameLength = sampleCount.toUInt()
        val output = buffer.floatChannelData?.get(0) ?: return
        var byteIndex = 0
        for (sampleIndex in 0 until sampleCount) {
            val low = bytes[byteIndex].toInt() and 0xff
            val high = bytes[byteIndex + 1].toInt() shl 8
            output[sampleIndex] = ((low or high).toShort().toFloat() / Short.MAX_VALUE.toFloat())
            byteIndex += 2
        }
        if (!player.isPlaying()) {
            player.play()
        }
        player.scheduleBuffer(buffer, completionHandler = null)
        playedBytes += bytes.size.toLong()
        emitLog(
            "Scheduled playback request #$playbackRequests bytes=${bytes.size} samples=$sampleCount route=${routeSnapshot().output}"
        )
        emitState("Played PCM16 buffer")
    }

    override fun currentState(): AudioDiagnosticsState = AudioDiagnosticsState(
        isRunning = running,
        inputLevel = inputLevel,
        capturedBytes = capturedBytes,
        playedBytes = playedBytes,
        captureCallbacks = captureCallbacks,
        convertedChunks = convertedChunks,
        playbackRequests = playbackRequests,
        route = routeSnapshot().also { lastRoute = it },
        lastMessage = lastMessage,
    )

    override fun takeNextCapturedChunk(): ByteArray? = withLock {
        pendingCapturedChunks.removeFirstOrNull()
    }

    override fun takeNextLogMessage(): String? = withLock {
        pendingLogs.removeFirstOrNull()
    }

    private fun configureAudioSession(session: AVAudioSession) {
        val options = if (config.preferSpeaker) {
            AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothHFP
        } else {
            AVAudioSessionCategoryOptionAllowBluetoothHFP
        }
        check(
            session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeVoiceChat,
                options = options,
                error = null,
            )
        ) { "setCategory failed." }
        check(session.setPreferredSampleRate(config.sampleRate.toDouble(), error = null)) {
            "setPreferredSampleRate failed."
        }
        check(session.setPreferredIOBufferDuration(config.ioBufferFrames.toDouble() / config.sampleRate.toDouble(), error = null)) {
            "setPreferredIOBufferDuration failed."
        }
        check(session.setActive(true, error = null)) { "setActive(true) failed." }
        configurePreferredRoute(session)
        emitLog("Configured AVAudioSession category=playAndRecord mode=voiceChat.")
    }

    private fun configurePreferredRoute(session: AVAudioSession) {
        val outputs = session.currentRoute.outputs.mapNotNull { it as? AVAudioSessionPortDescription }
        val usesBuiltInAudio = outputs.isEmpty() || outputs.all {
            it.portType == AVAudioSessionPortBuiltInReceiver || it.portType == AVAudioSessionPortBuiltInSpeaker
        }
        val bluetoothInput = session.availableInputs?.firstOrNull {
            (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortBluetoothHFP
        } as? AVAudioSessionPortDescription
        if (!config.preferSpeaker && bluetoothInput != null) {
            session.setPreferredInput(bluetoothInput, error = null)
            session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, error = null)
            emitLog("Preferred input set to Bluetooth HFP: ${bluetoothInput.portName}")
            emitLog("Output override cleared to keep external route active.")
        } else if (usesBuiltInAudio) {
            val builtInMic = session.availableInputs?.firstOrNull {
                (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortBuiltInMic
            } as? AVAudioSessionPortDescription
            if (builtInMic != null) {
                session.setPreferredInput(builtInMic, error = null)
                emitLog("Preferred input set to built-in mic: ${builtInMic.portName}")
            }
            if (config.preferSpeaker) {
                session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, error = null)
                emitLog("Output overridden to speaker for built-in route.")
            } else {
                session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, error = null)
                emitLog("Output override cleared for built-in route.")
            }
        } else {
            session.overrideOutputAudioPort(AVAudioSessionPortOverrideNone, error = null)
            emitLog("Output override cleared for external route.")
        }
        emitLog("Available inputs after route config: ${availableInputsSummary(session)}")
    }

    private fun installInputTap(inputNode: platform.AVFAudio.AVAudioInputNode, session: AVAudioSession, reason: String) {
        val inputFormat = inputNode.outputFormatForBus(0u)
        val hardwareInputFormat = inputNode.inputFormatForBus(0u)
        captureFormat = inputFormat
        inputNode.removeTapOnBus(0u)
        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = config.ioBufferFrames.toUInt(),
            format = inputFormat,
        ) { buffer, _ ->
            if (buffer != null) {
                handleCapturedAudioBuffer(buffer)
            }
        }
        emitLog(
            "Installed input tap for $reason format=${inputFormat.sampleRate.toInt()}Hz channels=${inputFormat.channelCount}."
        )
        emitLog(
            "Input node hardware format=${hardwareInputFormat.sampleRate.toInt()}Hz channels=${hardwareInputFormat.channelCount}."
        )
        emitLog(
            "Available inputs before engine start: ${availableInputsSummary(session)}"
        )
    }

    private fun startAudioGraph(reason: String) {
        val engine = audioEngine ?: return
        val player = playerNode ?: return
        check(engine.startAndReturnError(null)) { "AVAudioEngine start failed during $reason." }
        if (!player.isPlaying()) {
            player.play()
        }
        emitLog("Started AVAudioEngine for $reason.")
    }

    private fun restartAudioGraphAfterRouteChange(reason: String) {
        val engine = audioEngine ?: return
        val player = playerNode ?: return
        val session = AVAudioSession.sharedInstance()
        if (reconfiguringRoute) {
            emitLog("Skipped nested route reconfiguration for $reason.")
            return
        }
        reconfiguringRoute = true
        runCatching {
            emitLog("Restarting audio graph after route change: $reason")
            engine.inputNode.removeTapOnBus(0u)
            player.stop()
            player.reset()
            engine.stop()
            configurePreferredRoute(session)
            installInputTap(engine.inputNode, session, reason)
            engine.prepare()
            startAudioGraph(reason)
            updateRoute("Restarted audio graph")
        }.onFailure { error ->
            emitError(error.message ?: "Route change recovery failed.")
        }
        reconfiguringRoute = false
    }

    private fun handleCapturedAudioBuffer(buffer: AVAudioPCMBuffer) {
        captureCallbacks += 1
        inputLevel = calculateLevel(buffer)
        if (captureCallbacks == 1L) {
            emitLog(
                "First capture callback frameLength=${buffer.frameLength} format=${buffer.format.sampleRate.toInt()}Hz/${buffer.format.channelCount}ch."
            )
        }
        val inputFormat = captureFormat ?: return
        val bytes = convertCapturedBufferToPcm16(buffer, inputFormat)
        if (bytes == null || bytes.isEmpty()) {
            if (captureCallbacks <= 3 || captureCallbacks % 50L == 0L) {
                emitLog(
                    "Capture conversion produced no output callback=$captureCallbacks frameLength=${buffer.frameLength}."
                )
            }
            return
        }
        convertedChunks += 1
        withLock {
            pendingCapturedChunks.addLast(bytes)
            capturedAudio = appendLimited(capturedAudio, bytes, config.sampleRate * 2 * 20)
            capturedBytes = capturedAudio.size.toLong()
        }
        if (convertedChunks == 1L) {
            emitLog("First converted capture chunk bytes=${bytes.size} inputLevel=$inputLevel.")
        } else if (convertedChunks % 50L == 0L) {
            emitLog(
                "Capture health callbacks=$captureCallbacks converted=$convertedChunks capturedBytes=$capturedBytes inputLevel=$inputLevel."
            )
        }
    }

    private fun appendLimited(existing: ByteArray, chunk: ByteArray, maxBytes: Int): ByteArray {
        val combined = ByteArray((existing.size + chunk.size).coerceAtMost(maxBytes))
        val retainedPrefix = (maxBytes - chunk.size).coerceAtLeast(0)
        val prefix = if (retainedPrefix == 0) ByteArray(0) else existing.takeLast(retainedPrefix).toByteArray()
        prefix.copyInto(combined, 0, 0, prefix.size)
        chunk.copyInto(combined, prefix.size, 0, chunk.size.coerceAtMost(combined.size - prefix.size))
        return combined
    }

    private fun calculateLevel(buffer: AVAudioPCMBuffer): Float {
        val channel = buffer.floatChannelData?.get(0) ?: return 0f
        val frameLength = buffer.frameLength.toInt()
        if (frameLength <= 0) {
            return 0f
        }
        var sumSquares = 0.0
        for (index in 0 until frameLength) {
            val sample = channel[index]
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / frameLength.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun convertCapturedBufferToPcm16(buffer: AVAudioPCMBuffer, inputFormat: AVAudioFormat): ByteArray? {
        val inputFrames = buffer.frameLength.toInt()
        if (inputFrames <= 0) {
            return null
        }
        val outputFrames = ceil(
            inputFrames.toDouble() * captureOutputFormat.sampleRate / inputFormat.sampleRate
        ).toInt().coerceAtLeast(1)
        val bytes = ByteArray(outputFrames * 2)
        val floatSamples = buffer.floatChannelData?.get(0)
        if (floatSamples != null) {
            var byteIndex = 0
            for (outputIndex in 0 until outputFrames) {
                val inputIndex = ((outputIndex.toDouble() * inputFormat.sampleRate) / captureOutputFormat.sampleRate)
                    .toInt()
                    .coerceIn(0, inputFrames - 1)
                val sample = (floatSamples[inputIndex].coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat()).toInt()
                bytes[byteIndex] = (sample and 0xff).toByte()
                bytes[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
                byteIndex += 2
            }
            return bytes
        }
        val int16Samples = buffer.int16ChannelData?.get(0)
        if (int16Samples != null) {
            var byteIndex = 0
            for (outputIndex in 0 until outputFrames) {
                val inputIndex = ((outputIndex.toDouble() * inputFormat.sampleRate) / captureOutputFormat.sampleRate)
                    .toInt()
                    .coerceIn(0, inputFrames - 1)
                val sample = int16Samples[inputIndex].toInt()
                bytes[byteIndex] = (sample and 0xff).toByte()
                bytes[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
                byteIndex += 2
            }
            return bytes
        }
        if (captureCallbacks <= 3 || captureCallbacks % 50L == 0L) {
            emitLog(
                "Unsupported capture buffer commonFormat=${buffer.format.commonFormat} sampleRate=${inputFormat.sampleRate.toInt()} channels=${inputFormat.channelCount}."
            )
        }
        return null
    }

    private fun cleanupAudioGraph() {
        audioEngine?.inputNode?.removeTapOnBus(0u)
        playerNode?.stop()
        playerNode?.reset()
        audioEngine?.stop()
        audioEngine?.reset()
        audioEngine = null
        playerNode = null
        captureFormat = null
    }

    private fun registerSessionObservers() {
        if (notificationTokens.isNotEmpty()) {
            return
        }
        val center = NSNotificationCenter.defaultCenter
        val routeToken = center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            handleRouteChange(notification)
        }
        val interruptionToken = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            handleInterruption(notification)
        }
        notificationTokens += routeToken
        notificationTokens += interruptionToken
        emitLog("Registered AVAudioSession route and interruption observers.")
    }

    private fun unregisterSessionObservers() {
        if (notificationTokens.isEmpty()) {
            return
        }
        val center = NSNotificationCenter.defaultCenter
        notificationTokens.forEach { token ->
            center.removeObserver(token)
        }
        notificationTokens.clear()
        emitLog("Unregistered AVAudioSession observers.")
    }

    private fun handleRouteChange(notification: NSNotification?) {
        val userInfoText = notification?.userInfo?.toString() ?: "none"
        emitLog("AVAudioSession route change notification userInfo=$userInfoText")
        if (running) {
            restartAudioGraphAfterRouteChange(userInfoText)
        } else {
            updateRoute("Route changed")
        }
    }

    private fun handleInterruption(notification: NSNotification?) {
        val userInfoText = notification?.userInfo?.toString() ?: "none"
        emitLog("AVAudioSession interruption notification userInfo=$userInfoText")
        updateRoute("Session interrupted")
    }

    private fun routeSnapshot(): AudioRouteSnapshot {
        val session = AVAudioSession.sharedInstance()
        val inputs = session.currentRoute.inputs.joinToString { value ->
            val port = value as? AVAudioSessionPortDescription
            "${port?.portType ?: "unknown"}=${port?.portName ?: "unknown"}"
        }
        val outputs = session.currentRoute.outputs.joinToString { value ->
            val port = value as? AVAudioSessionPortDescription
            "${port?.portType ?: "unknown"}=${port?.portName ?: "unknown"}"
        }
        val routeText = "$inputs $outputs"
        return AudioRouteSnapshot(
            input = inputs.ifEmpty { "none" },
            output = outputs.ifEmpty { "none" },
            category = session.category ?: "unknown",
            mode = session.mode ?: "unknown",
            sampleRate = session.sampleRate,
            ioBufferDurationMillis = session.IOBufferDuration * 1_000.0,
            bluetoothActive = routeText.contains(AVAudioSessionPortBluetoothHFP.orEmpty()) ||
                routeText.contains(AVAudioSessionPortBluetoothA2DP.orEmpty()),
            builtInAudioActive = routeText.contains(AVAudioSessionPortBuiltInMic.orEmpty()) ||
                routeText.contains(AVAudioSessionPortBuiltInReceiver.orEmpty()) ||
                routeText.contains(AVAudioSessionPortBuiltInSpeaker.orEmpty()),
            timestampMillis = (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong(),
        )
    }

    private fun emitLog(message: String) {
        withLock {
            pendingLogs.addLast(message)
            while (pendingLogs.size > 200) {
                pendingLogs.removeFirst()
            }
        }
        callbacks?.onLog(message)
    }

    private fun emitError(message: String) {
        lastMessage = message
        callbacks?.onError(message)
        callbacks?.onStateChanged(currentState())
    }

    private fun emitState(message: String) {
        lastMessage = message
        callbacks?.onStateChanged(currentState())
    }

    private fun updateRoute(message: String) {
        lastRoute = routeSnapshot()
        emitState(message)
        emitLog(
            "Route: input=${lastRoute?.input} output=${lastRoute?.output} availableInputs=${availableInputsSummary(AVAudioSession.sharedInstance())}"
        )
    }

    private fun availableInputsSummary(session: AVAudioSession): String {
        val inputs = session.availableInputs?.mapNotNull { it as? AVAudioSessionPortDescription }.orEmpty()
        if (inputs.isEmpty()) {
            return "none"
        }
        return inputs.joinToString { "${it.portType}=${it.portName}" }
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
