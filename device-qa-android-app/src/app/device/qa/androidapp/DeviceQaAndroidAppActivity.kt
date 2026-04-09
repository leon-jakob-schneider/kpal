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
import app.miso.audio.AudioDiagnosticsCallbacks
import app.miso.audio.AudioDiagnosticsConfig
import app.miso.audio.AudioDiagnosticsState
import app.miso.device.DeviceConfig
import app.miso.device.DeviceImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceQaAndroidAppActivity : Activity(), AudioDiagnosticsCallbacks {
    private lateinit var device: DeviceImpl
    private lateinit var statusView: TextView
    private lateinit var routeView: TextView
    private lateinit var counterView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var logView: TextView

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logs = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = DeviceImpl(
            platformContext = applicationContext,
            callbacks = this,
            config = DeviceConfig(
                audio = AudioDiagnosticsConfig(
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

    override fun onStateChanged(state: AudioDiagnosticsState) {
        runOnUiThread { renderState(state) }
    }

    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    override fun onError(message: String) {
        runOnUiThread { appendLog("ERROR: $message") }
    }

    private fun createContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#F2EFE7"))
        }

        content.addView(title("Device Audio QA"))
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
        content.addView(button("Play 440 Hz Tone") { device.audio.playTestTone() })
        content.addView(button("Play Captured Audio") { device.audio.playCapturedAudio() })
        content.addView(button("Clear Capture") { device.audio.clearCapture() })

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

    private fun renderState(state: AudioDiagnosticsState) {
        statusView.text = """
            running: ${state.isRunning}
            last: ${state.lastMessage}
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
            bluetooth: ${route.bluetoothActive}
            builtInAudio: ${route.builtInAudioActive}
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
