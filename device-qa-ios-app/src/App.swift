import Foundation
@preconcurrency import KotlinModules
import SwiftUI
import UIKit

struct AudioDiagnosticState {
    var isRunning = false
    var inputLevel: Float = 0
    var capturedBytes: Int64 = 0
    var playedBytes: Int64 = 0
    var captureCallbacks: Int64 = 0
    var convertedChunks: Int64 = 0
    var playbackRequests: Int64 = 0
    var inputRoute = "none"
    var outputRoute = "none"
    var category = "unknown"
    var mode = "unknown"
    var sampleRate: Double = 0
    var ioBufferMillis: Double = 0
    var bluetoothActive = false
    var builtInAudioActive = false
    var lastMessage = "Idle"

    var routeSummary: String {
        """
        inputs: \(inputRoute)
        outputs: \(outputRoute)
        bluetooth: \(bluetoothActive)
        builtInAudio: \(builtInAudioActive)
        """
    }

    var sessionSummary: String {
        """
        category: \(category)
        mode: \(mode)
        sampleRate: \(Int(sampleRate)) Hz
        ioBuffer: \(String(format: "%.2f", ioBufferMillis)) ms
        """
    }
}

enum AudioQATestKind: String {
    case tonePlayback = "Tone Playback"
    case capturedLoopback = "Captured Loopback"
}

enum AudioQATestOutcome: String {
    case passed = "Passed"
    case failed = "Failed"
    case skipped = "Skipped"
}

struct AudioQATestDefinition: Identifiable, Hashable {
    let id: String
    let title: String
    let summary: String
    let kind: AudioQATestKind
    let instructions: [String]
    let actionTitle: String
    let passTitle: String
    let failTitle: String
}

struct AudioQATestResult {
    let testId: String
    let title: String
    let outcome: AudioQATestOutcome
    let recordedAt: Date
    let stateSummary: String
    let recentLogs: [String]
}

enum AudioQAScreen {
    case landing
    case test(index: Int, suite: Bool)
    case results(suite: Bool)
}

struct SharePayload: Identifiable {
    let id = UUID()
    let url: URL
}

struct AlertMessage: Identifiable {
    let id = UUID()
    let text: String
}

@MainActor
final class AudioQAViewModel: ObservableObject {
    @Published private(set) var state = AudioDiagnosticState()
    @Published private(set) var logLines: [String] = []
    @Published private(set) var activeTests: [AudioQATestDefinition] = []
    @Published private(set) var results: [String: AudioQATestResult] = [:]
    @Published private(set) var isPreparingSession = false

    let tests: [AudioQATestDefinition] = [
        AudioQATestDefinition(
            id: "iphone-speaker-playback",
            title: "iPhone Speaker Playback",
            summary: "Verify that the test tone comes from the built-in iPhone speaker.",
            kind: .tonePlayback,
            instructions: [
                "Disconnect AirPods or any other external audio device.",
                "Set the iPhone volume to at least 50 percent.",
                "Hold the phone away from your ear. This test expects loudspeaker output.",
                "Tap the playback button and listen for the 440 Hz test tone."
            ],
            actionTitle: "Play 440 Hz Tone",
            passTitle: "I heard the tone",
            failTitle: "I did not hear the tone"
        ),
        AudioQATestDefinition(
            id: "iphone-mic-loopback",
            title: "iPhone Mic Loopback",
            summary: "Verify that built-in microphone capture can be played back over the phone speaker.",
            kind: .capturedLoopback,
            instructions: [
                "Keep AirPods disconnected.",
                "Prepare the audio session and clear the capture buffer.",
                "Speak a short sentence for three to five seconds.",
                "Tap the playback button and verify that your recorded voice is audible."
            ],
            actionTitle: "Play Recorded Voice",
            passTitle: "I heard my recorded voice",
            failTitle: "I did not hear my recorded voice"
        ),
        AudioQATestDefinition(
            id: "airpods-playback",
            title: "AirPods Playback",
            summary: "Verify that the test tone is routed to connected AirPods.",
            kind: .tonePlayback,
            instructions: [
                "Connect AirPods and confirm they are the active output device in Control Center if needed.",
                "Wear both AirPods before starting playback.",
                "Tap the playback button and listen for the 440 Hz test tone in the AirPods."
            ],
            actionTitle: "Play 440 Hz Tone",
            passTitle: "I heard the tone in AirPods",
            failTitle: "I did not hear the tone in AirPods"
        ),
        AudioQATestDefinition(
            id: "airpods-mic-loopback",
            title: "AirPods Mic Loopback",
            summary: "Verify that AirPods microphone capture can be played back to AirPods.",
            kind: .capturedLoopback,
            instructions: [
                "Keep AirPods connected and in your ears.",
                "Prepare the audio session and clear the capture buffer.",
                "Speak a short sentence for three to five seconds.",
                "Tap the playback button and verify that your recorded voice is audible in the AirPods."
            ],
            actionTitle: "Play Recorded Voice",
            passTitle: "I heard my recorded voice in AirPods",
            failTitle: "I did not hear my recorded voice in AirPods"
        ),
    ]

    private var device: IosDevice
    private var currentPreferSpeaker = true
    private let logDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()
    private let fileDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        return formatter
    }()
    private var pollTask: Task<Void, Never>?

    init() {
        device = Self.makeDevice(preferSpeaker: true)
        appendLog("QA app ready. Use the suite to compare built-in iPhone audio with AirPods and export a report at the end.")
        startPolling()
    }

    deinit {
        pollTask?.cancel()
        device.audio.stop()
    }

    func beginSuite() {
        resetRunState()
        activeTests = tests
        appendLog("Started full QA suite with \(tests.count) tests.")
    }

    func beginSingleTest(_ test: AudioQATestDefinition) {
        resetRunState()
        activeTests = [test]
        appendLog("Started single QA test: \(test.title).")
    }

    func prepareForTest(_ test: AudioQATestDefinition) async -> Bool {
        let preferSpeaker = shouldPreferSpeaker(for: test)
        if preferSpeaker != currentPreferSpeaker {
            replaceEngine(preferSpeaker: preferSpeaker, reason: "Switching route policy for \(test.title)")
        } else if state.isRunning {
            stopSession()
        }
        let ready = await ensureSessionReady()
        guard ready else {
            return false
        }
        if test.kind == .capturedLoopback {
            clearCapture()
            appendLog("Cleared capture buffer for \(test.title).")
        }
        return true
    }

    func ensureSessionReady() async -> Bool {
        if state.isRunning {
            return true
        }

        isPreparingSession = true
        defer {
            isPreparingSession = false
        }

        let granted = await withCheckedContinuation { continuation in
            device.audio.requestRecordPermission { allowed in
                continuation.resume(returning: allowed.boolValue)
            }
        }
        guard granted else {
            appendLog("Microphone permission denied.")
            refreshState()
            return false
        }

        device.audio.start()
        drainLogs()
        refreshState()
        return state.isRunning
    }

    func stopSession() {
        device.audio.stop()
        drainLogs()
        refreshState()
    }

    func clearCapture() {
        device.audio.clearCapture()
        drainLogs()
        refreshState()
    }

    func playTestTone() {
        device.audio.playTestTone()
        drainLogs()
        refreshState()
    }

    func playCapturedAudio() {
        device.audio.playCapturedAudio()
        drainLogs()
        refreshState()
    }

    func result(for test: AudioQATestDefinition) -> AudioQATestResult? {
        results[test.id]
    }

    func recordResult(for test: AudioQATestDefinition, outcome: AudioQATestOutcome) {
        refreshState()
        let result = AudioQATestResult(
            testId: test.id,
            title: test.title,
            outcome: outcome,
            recordedAt: Date(),
            stateSummary: diagnosticSnapshotText(),
            recentLogs: Array(logLines.prefix(40).reversed())
        )
        results[test.id] = result
        appendLog("Recorded QA result for \(test.title): \(outcome.rawValue).")
    }

    func reportURL() throws -> URL {
        refreshState()
        let filename = "device-audio-qa-\(fileDateFormatter.string(from: Date())).txt"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        try buildReport().write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    var completedCount: Int {
        activeTests.filter { results[$0.id] != nil }.count
    }

    var passedCount: Int {
        results.values.filter { $0.outcome == .passed }.count
    }

    var failedCount: Int {
        results.values.filter { $0.outcome == .failed }.count
    }

    var skippedCount: Int {
        results.values.filter { $0.outcome == .skipped }.count
    }

    private func startPolling() {
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 200_000_000)
                await MainActor.run {
                    self?.drainLogs()
                    self?.refreshState()
                }
            }
        }
    }

    private func resetRunState() {
        stopSession()
        activeTests = []
        results = [:]
    }

    private func shouldPreferSpeaker(for test: AudioQATestDefinition) -> Bool {
        !test.id.contains("airpods")
    }

    private func replaceEngine(preferSpeaker: Bool, reason: String) {
        device.audio.stop()
        currentPreferSpeaker = preferSpeaker
        device = Self.makeDevice(preferSpeaker: preferSpeaker)
        appendLog("\(reason). preferSpeaker=\(preferSpeaker)")
        refreshState()
    }

    private static func makeDevice(preferSpeaker: Bool) -> IosDevice {
        IosDevice(
            callbacks: nil,
            config: DeviceConfig(
                audio: AudioDiagnosticsConfig(
                    sampleRate: 24_000,
                    ioBufferFrames: 1_024,
                    preferSpeaker: preferSpeaker,
                    voiceProcessing: true
                )
            )
        )
    }

    private func refreshState() {
        let stateValue = device.audio.currentState()
        let route = stateValue.route
        state = AudioDiagnosticState(
            isRunning: stateValue.isRunning,
            inputLevel: stateValue.inputLevel,
            capturedBytes: stateValue.capturedBytes,
            playedBytes: stateValue.playedBytes,
            captureCallbacks: stateValue.captureCallbacks,
            convertedChunks: stateValue.convertedChunks,
            playbackRequests: stateValue.playbackRequests,
            inputRoute: route?.input ?? "none",
            outputRoute: route?.output ?? "none",
            category: route?.category ?? "unknown",
            mode: route?.mode ?? "unknown",
            sampleRate: route?.sampleRate ?? 0,
            ioBufferMillis: route?.ioBufferDurationMillis ?? 0,
            bluetoothActive: route?.bluetoothActive ?? false,
            builtInAudioActive: route?.builtInAudioActive ?? false,
            lastMessage: stateValue.lastMessage
        )
    }

    private func drainLogs() {
        while let line = device.audio.takeNextLogMessage() {
            appendLog(line)
        }
    }

    private func appendLog(_ message: String) {
        logLines.insert("\(logDateFormatter.string(from: Date())) \(message)", at: 0)
        if logLines.count > 200 {
            logLines.removeLast(logLines.count - 200)
        }
    }

    private func diagnosticSnapshotText() -> String {
        """
        running: \(state.isRunning)
        last: \(state.lastMessage)
        capturedBytes: \(state.capturedBytes)
        playedBytes: \(state.playedBytes)
        captureCallbacks: \(state.captureCallbacks)
        convertedChunks: \(state.convertedChunks)
        playbackRequests: \(state.playbackRequests)
        inputLevel: \(String(format: "%.3f", state.inputLevel))
        route:
        \(state.routeSummary)
        session:
        \(state.sessionSummary)
        """
    }

    private func buildReport() -> String {
        let orderedResults = activeTests.compactMap { results[$0.id] }
        let resultText = orderedResults.map { result in
            """
            Test: \(result.title)
            Outcome: \(result.outcome.rawValue)
            Recorded At: \(result.recordedAt.formatted(date: .numeric, time: .standard))
            Snapshot:
            \(result.stateSummary)
            Recent Logs:
            \(result.recentLogs.joined(separator: "\n"))
            """
        }.joined(separator: "\n\n")

        let summary = """
        Device Audio QA Report
        Generated: \(Date().formatted(date: .numeric, time: .standard))

        Suite Size: \(activeTests.count)
        Completed: \(completedCount)
        Passed: \(passedCount)
        Failed: \(failedCount)
        Skipped: \(skippedCount)

        Final Diagnostics:
        \(diagnosticSnapshotText())
        """

        let allLogs = logLines.reversed().joined(separator: "\n")
        return """
        \(summary)

        Results:
        \(resultText)

        Full Diagnostics Log:
        \(allLogs)
        """
    }
}

struct AudioQAAppView: View {
    @StateObject private var viewModel = AudioQAViewModel()
    @State private var screen: AudioQAScreen = .landing
    @State private var sharePayload: SharePayload?
    @State private var alertMessage: AlertMessage?

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.94, green: 0.92, blue: 0.86), Color(red: 0.84, green: 0.90, blue: 0.88)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            switch screen {
            case .landing:
                AudioQALandingView(
                    viewModel: viewModel,
                    startSuite: {
                        viewModel.beginSuite()
                        screen = .test(index: 0, suite: true)
                    },
                    startSingle: { test in
                        viewModel.beginSingleTest(test)
                        screen = .test(index: 0, suite: false)
                    }
                )

            case .test(let index, let suite):
                if index < viewModel.activeTests.count {
                    AudioQATestView(
                        viewModel: viewModel,
                        test: viewModel.activeTests[index],
                        stepText: suite ? "Test \(index + 1) of \(viewModel.activeTests.count)" : "Single Test",
                        suite: suite,
                        exitRun: {
                            viewModel.stopSession()
                            screen = .landing
                        },
                        advance: {
                            let nextIndex = index + 1
                            if suite && nextIndex < viewModel.activeTests.count {
                                screen = .test(index: nextIndex, suite: true)
                            } else {
                                viewModel.stopSession()
                                screen = .results(suite: suite)
                            }
                        }
                    )
                }

            case .results(let suite):
                AudioQAResultsView(
                    viewModel: viewModel,
                    suite: suite,
                    backToHome: {
                        screen = .landing
                    },
                    shareReport: {
                        do {
                            sharePayload = SharePayload(url: try viewModel.reportURL())
                        } catch {
                            alertMessage = AlertMessage(text: error.localizedDescription)
                        }
                    }
                )
            }
        }
        .sheet(item: $sharePayload) { payload in
            ActivityView(activityItems: [payload.url])
        }
        .alert(item: $alertMessage) { item in
            Alert(title: Text("Report Error"), message: Text(item.text), dismissButton: .default(Text("OK")))
        }
    }
}

struct AudioQALandingView: View {
    @ObservedObject var viewModel: AudioQAViewModel
    let startSuite: () -> Void
    let startSingle: (AudioQATestDefinition) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Device Audio QA")
                    .font(.system(.largeTitle, design: .rounded).weight(.bold))
                Text("Choose a full QA suite or run one test in isolation. Each test opens as its own screen and records the user verdict together with diagnostics.")
                    .font(.callout)
                    .foregroundStyle(.secondary)

                qaCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Full Suite")
                            .font(.title3.weight(.semibold))
                        Text("Run all built-in iPhone and AirPods playback and loopback tests in sequence, then export one combined report.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Button("Start Full Test Suite", action: startSuite)
                            .buttonStyle(.borderedProminent)
                    }
                }

                Text("Single Test")
                    .font(.title3.weight(.semibold))

                ForEach(viewModel.tests) { test in
                    qaCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(test.title)
                                .font(.headline)
                            Text(test.summary)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                            Button("Run This Test") {
                                startSingle(test)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }

                qaCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Live Diagnostics")
                            .font(.headline)
                        Text(verbatim: """
        running: \(viewModel.state.isRunning)
        last: \(viewModel.state.lastMessage)
        inputLevel: \(String(format: "%.3f", viewModel.state.inputLevel))
        captureCallbacks: \(viewModel.state.captureCallbacks)
        convertedChunks: \(viewModel.state.convertedChunks)
        playbackRequests: \(viewModel.state.playbackRequests)
        """)
                        .font(.system(.caption, design: .monospaced))
                        Text(viewModel.state.routeSummary)
                            .font(.system(.caption, design: .monospaced))
                    }
                }
            }
            .padding(20)
        }
    }
}

struct AudioQATestView: View {
    @ObservedObject var viewModel: AudioQAViewModel
    let test: AudioQATestDefinition
    let stepText: String
    let suite: Bool
    let exitRun: () -> Void
    let advance: () -> Void

    @State private var didPerformPrimaryAction = false
    @State private var alertMessage: AlertMessage?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                instructionsCard
                controlsCard
                diagnosticsCard("Status", text: statusText)
                levelCard
                diagnosticsCard("Route", text: viewModel.state.routeSummary)
                diagnosticsCard("Session", text: viewModel.state.sessionSummary)
                verdictCard
                diagnosticsCard("Recent Log", text: recentLogText)
            }
            .padding(20)
        }
        .task(id: test.id) {
            didPerformPrimaryAction = false
            let ready = await viewModel.prepareForTest(test)
            if !ready {
                alertMessage = AlertMessage(text: "Microphone access or audio session setup failed. Check permission state and try again.")
            }
        }
        .alert(item: $alertMessage) { item in
            Alert(title: Text("Audio Setup"), message: Text(item.text), dismissButton: .default(Text("OK")))
        }
    }

    private var header: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(stepText.uppercased())
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(test.title)
                    .font(.system(.title, design: .rounded).weight(.bold))
                Text(test.summary)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                HStack {
                    Button("End Run") {
                        exitRun()
                    }
                    .buttonStyle(.bordered)
                    Spacer()
                    Text(test.kind.rawValue)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Color.black.opacity(0.06)))
                }
            }
        }
    }

    private var instructionsCard: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("Instructions")
                    .font(.headline)
                ForEach(Array(test.instructions.enumerated()), id: \.offset) { offset, instruction in
                    Text("\(offset + 1). \(instruction)")
                        .font(.callout)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var controlsCard: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Actions")
                    .font(.headline)
                Button(viewModel.state.isRunning ? "Audio Session Ready" : "Prepare Audio Session") {
                    Task {
                        let ready = await viewModel.prepareForTest(test)
                        if !ready {
                            alertMessage = AlertMessage(text: "Microphone access or audio session setup failed. Check permission state and try again.")
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isPreparingSession || viewModel.state.isRunning)

                if test.kind == .capturedLoopback {
                    Button("Clear Capture Buffer") {
                        viewModel.clearCapture()
                    }
                    .buttonStyle(.bordered)
                }

                Button(test.actionTitle) {
                    runPrimaryAction()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!viewModel.state.isRunning)

                if test.kind == .capturedLoopback {
                    Text("After clearing the capture buffer, speak for a few seconds before tapping playback.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var verdictCard: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Reaction")
                    .font(.headline)
                if let result = viewModel.result(for: test) {
                    Text("Result: \(result.outcome.rawValue)")
                        .font(.title3.weight(.semibold))
                    Text("Recorded at \(result.recordedAt.formatted(date: .omitted, time: .standard))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(suite ? nextButtonTitle : "View Report") {
                        advance()
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Text("Run the audio action first, then capture the user verdict.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Button(test.passTitle) {
                        viewModel.recordResult(for: test, outcome: .passed)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!didPerformPrimaryAction)
                    Button(test.failTitle) {
                        viewModel.recordResult(for: test, outcome: .failed)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .disabled(!didPerformPrimaryAction)
                    Button("Skip This Test") {
                        viewModel.recordResult(for: test, outcome: .skipped)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var levelCard: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("Live Mic Level")
                    .font(.headline)
                ProgressView(value: Double(viewModel.state.inputLevel))
                    .progressViewStyle(.linear)
                Text("If this stays near 0 while you are speaking, input capture or route selection is likely broken.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var statusText: String {
        """
        running: \(viewModel.state.isRunning)
        last: \(viewModel.state.lastMessage)
        capturedBytes: \(viewModel.state.capturedBytes)
        playedBytes: \(viewModel.state.playedBytes)
        captureCallbacks: \(viewModel.state.captureCallbacks)
        convertedChunks: \(viewModel.state.convertedChunks)
        playbackRequests: \(viewModel.state.playbackRequests)
        inputLevel: \(String(format: "%.3f", viewModel.state.inputLevel))
        """
    }

    private var recentLogText: String {
        let lines = Array(viewModel.logLines.prefix(16))
        return lines.isEmpty ? "No log lines yet." : lines.joined(separator: "\n")
    }

    private var nextButtonTitle: String {
        let currentIndex = viewModel.activeTests.firstIndex(of: test) ?? 0
        let hasNext = currentIndex + 1 < viewModel.activeTests.count
        return hasNext ? "Continue to Next Test" : "Finish Suite"
    }

    private func runPrimaryAction() {
        switch test.kind {
        case .tonePlayback:
            viewModel.playTestTone()
        case .capturedLoopback:
            viewModel.playCapturedAudio()
        }
        didPerformPrimaryAction = true
    }
}

struct AudioQAResultsView: View {
    @ObservedObject var viewModel: AudioQAViewModel
    let suite: Bool
    let backToHome: () -> Void
    let shareReport: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                qaCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(suite ? "QA Suite Complete" : "QA Test Complete")
                            .font(.system(.title, design: .rounded).weight(.bold))
                        Text("Export a report that includes all recorded outcomes and the combined diagnostics log.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Text("""
                        completed: \(viewModel.completedCount) / \(viewModel.activeTests.count)
                        passed: \(viewModel.passedCount)
                        failed: \(viewModel.failedCount)
                        skipped: \(viewModel.skippedCount)
                        """)
                        .font(.system(.body, design: .monospaced))
                    }
                }

                ForEach(viewModel.activeTests) { test in
                    qaCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(test.title)
                                .font(.headline)
                            if let result = viewModel.result(for: test) {
                                Text("Outcome: \(result.outcome.rawValue)")
                                    .font(.subheadline.weight(.semibold))
                                Text(result.stateSummary)
                                    .font(.system(.caption, design: .monospaced))
                                    .textSelection(.enabled)
                            } else {
                                Text("No result recorded.")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                qaCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Final Diagnostics")
                            .font(.headline)
                        Text(viewModel.state.routeSummary)
                            .font(.system(.caption, design: .monospaced))
                        Text(viewModel.state.sessionSummary)
                            .font(.system(.caption, design: .monospaced))
                        Text("last: \(viewModel.state.lastMessage)")
                            .font(.system(.caption, design: .monospaced))
                    }
                }

                Button("Share QA Report", action: shareReport)
                    .buttonStyle(.borderedProminent)

                Button("Back to Home", action: backToHome)
                    .buttonStyle(.bordered)
            }
            .padding(20)
        }
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private func qaCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 0, content: content)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.white.opacity(0.84))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color.black.opacity(0.08), lineWidth: 1)
                )
        )
}

private func diagnosticsCard(_ title: String, text: String) -> some View {
    qaCard {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

@main
struct DeviceQaIosApp: App {
    var body: some Scene {
        WindowGroup {
            AudioQAAppView()
        }
    }
}
