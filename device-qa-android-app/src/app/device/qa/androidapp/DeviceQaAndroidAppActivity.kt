package app.device.qa.androidapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.miso.audio.AudioCaptureBuffer
import app.miso.audio.AudioError
import app.miso.audio.AudioSessionConfig
import app.miso.audio.AudioSessionObserver
import app.miso.audio.AudioSessionState
import app.miso.audio.Pcm16ToneGenerator
import app.miso.device.DeviceConfig
import app.miso.device.DeviceImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceQaAndroidAppActivity : Activity(), AudioSessionObserver {
    private lateinit var device: DeviceImpl
    private lateinit var statusView: TextView
    private lateinit var routeView: TextView
    private lateinit var counterView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var logView: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logs = ArrayDeque<String>()
    private val captureBuffer = AudioCaptureBuffer(maxBytes = 24_000 * 2 * 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = DeviceImpl(
            platformContext = applicationContext,
            audioObserver = this,
            config = DeviceConfig(
                audio = AudioSessionConfig(
                    sampleRate = 24_000,
                    ioBufferFrames = 1_024,
                    preferSpeaker = true,
                    voiceProcessing = true,
                ),
            ),
        )
        setContentView(createContent())
        renderState(device.audio.currentState())
        appendLog("Open this on a real Android device. Speak, play the tone, then compare route and level behavior.")
    }

    override fun onDestroy() {
        device.audio.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                device.audio.start()
            } else {
                appendLog("Microphone permission denied.")
            }
        }
    }

    override fun onStateChanged(state: AudioSessionState) {
        runOnUiThread { renderState(state) }
    }

    override fun onError(error: AudioError) {
        runOnUiThread { appendLog("ERROR: ${error.message}") }
    }

    private fun createContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#F2EFE7"))
        }

        content.addView(title("kpal"))
        content.addView(body("Use this app on a real device to validate mic input, speaker output, route changes, and AirPods/Bluetooth behavior before touching the main app."))

        statusView = panelText()
        routeView = panelText()
        counterView = panelText()
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1_000
            progress = 0
        }
        content.addView(statusView)
        content.addView(routeView)
        content.addView(counterView)
        content.addView(levelLabel())
        content.addView(levelBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        content.addView(button("Start Capture") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                device.audio.start()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        })
        content.addView(button("Stop") { device.audio.stop() })
        content.addView(button("Play 440 Hz Tone") {
            device.audio.playPcm16(Pcm16ToneGenerator.sine())
            appendLog("Requested 440 Hz tone playback.")
        })
        content.addView(button("Play Captured Audio") {
            drainCapture()
            if (captureBuffer.play(device.audio)) {
                appendLog("Requested captured PCM playback.")
            } else {
                appendLog("No captured PCM to play.")
            }
        })
        content.addView(button("Clear Capture") {
            captureBuffer.clear()
            appendLog("Cleared QA capture buffer.")
        })

        content.addView(title("Log"))
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#27312D"))
            setBackgroundColor(Color.parseColor("#FFFCF5"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 12
            movementMethod = ScrollingMovementMethod()
        }
        content.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun renderState(state: AudioSessionState) {
        drainCapture()
        statusView.text = """
            running: ${state.isRunning}
            qaCapturedBytes: ${captureBuffer.sizeBytes}
        """.trimIndent()
        val route = state.route
        routeView.text = if (route == null) {
            "route: not available yet"
        } else {
            """
            input: ${route.input}
            output: ${route.output}
            mode: ${route.mode}
            sampleRate: ${route.sampleRate.toInt()} Hz
            ioBuffer: ${"%.2f".format(route.ioBufferDurationMillis)} ms
            bluetooth: ${route.hasBluetooth}
            builtInAudio: ${route.hasBuiltInAudio}
            """.trimIndent()
        }
        counterView.text = """
            inputLevel: ${"%.3f".format(state.inputLevel)}
            capturedBytes: ${state.capturedBytes}
            playedBytes: ${state.playedBytes}
        """.trimIndent()
        levelBar.progress = (state.inputLevel * 1_000).toInt().coerceIn(0, 1_000)
    }

    private fun appendLog(message: String) {
        logs.addFirst("${timeFormat.format(Date())} $message")
        while (logs.size > 120) {
            logs.removeLast()
        }
        logView.text = logs.joinToString(separator = "\n")
    }

    private fun drainCapture() {
        captureBuffer.drainFrom(device.audio)
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#1F2A24"))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#5E6D64"))
        setPadding(0, 0, 0, dp(12))
    }

    private fun panelText(): TextView = TextView(this).apply {
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.parseColor("#26312C"))
        setBackgroundColor(Color.parseColor("#FFFCF5"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val margin = dp(8)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = margin
        }
    }

    private fun levelLabel(): TextView = TextView(this).apply {
        text = "Live Mic Level"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#1F2A24"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_RECORD_AUDIO = 9001
    }
}
