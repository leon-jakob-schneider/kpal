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
    let timeline: [String]
    let scopedLogs: [String]
    let recentLogs: [String]
}

private struct AudioQATestContext {
    let testId: String
    let logStartIndex: Int
    var timeline: [String]
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

private enum AudioQAStepKind {
    case confirmBuiltInRoute
    case confirmAirPodsRoute
    case captureSpeech
    case playTone
    case playCapturedAudio
    case verdict
}

private struct AudioQAStepDefinition: Identifiable {
    let id: String
    let title: String
    let detail: String
    let actionTitle: String?
    let kind: AudioQAStepKind
}

private struct AudioEnginePolicy: Equatable {
    let preferSpeaker: Bool
    let enableInput: Bool
    let preferBluetoothA2dpOutput: Bool
    let voiceProcessing: Bool

    static let builtIn = AudioEnginePolicy(
        preferSpeaker: true,
        enableInput: true,
        preferBluetoothA2dpOutput: false,
        voiceProcessing: true
    )
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

    private var device: DeviceImpl
    private var currentPolicy = AudioEnginePolicy.builtIn
    private let logDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()
    private let eventDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()
    private let fileDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        return formatter
    }()
    private var pollTask: Task<Void, Never>?
    private var reportLogLines: [String] = []
    private var currentTestContext: AudioQATestContext?
    private var peakInputLevelSinceCaptureReset: Float = 0

    init() {
        device = Self.makeDevice(policy: .builtIn)
        appendLog("kpal ready. Use the suite to compare built-in iPhone audio with AirPods and export a report at the end.")
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
        beginTestContextIfNeeded(for: test)
        recordTestEvent(
            for: test,
            "Preparing audio session. kind=\(test.kind.rawValue) preferSpeaker=\(shouldPreferSpeaker(for: test))"
        )
        let policy = policy(for: test)
        if policy != currentPolicy {
            replaceEngine(policy: policy, reason: "Switching audio policy for \(test.title)")
        } else if state.isRunning {
            recordTestEvent(for: test, "Audio session already running. Restarting before test.")
            stopSession()
        }
        let ready = await ensureSessionReady()
        guard ready else {
            recordTestEvent(for: test, "Audio session failed to start.", includeSnapshot: true)
            return false
        }
        recordTestEvent(for: test, "Audio session ready.", includeSnapshot: true)
        if test.kind == .capturedLoopback {
            clearCapture(for: test)
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

        appendLog("Microphone permission granted.")
        device.audio.start()
        drainLogs()
        refreshState()
        return state.isRunning
    }

    func stopSession() {
        device.audio.stop()
        drainLogs()
        refreshState()
        peakInputLevelSinceCaptureReset = 0
    }

    func clearCapture(for test: AudioQATestDefinition? = nil) {
        device.audio.clearCapture()
        drainLogs()
        refreshState()
        peakInputLevelSinceCaptureReset = 0
        if let test {
            recordTestEvent(for: test, "Cleared capture buffer.", includeSnapshot: true)
        }
    }

    func playTestTone(for test: AudioQATestDefinition? = nil) {
        if let test {
            recordTestEvent(for: test, "Requested 440 Hz playback.", includeSnapshot: true)
        }
        device.audio.playTestTone()
        drainLogs()
        refreshState()
        if let test {
            recordTestEvent(for: test, "Playback request finished.", includeSnapshot: true)
        }
    }

    func playCapturedAudio(for test: AudioQATestDefinition? = nil) {
        if let test {
            recordTestEvent(for: test, "Requested captured audio playback.", includeSnapshot: true)
        }
        device.audio.playCapturedAudio()
        drainLogs()
        refreshState()
        if let test {
            recordTestEvent(for: test, "Captured audio playback request finished.", includeSnapshot: true)
        }
    }

    func result(for test: AudioQATestDefinition) -> AudioQATestResult? {
        results[test.id]
    }

    func verifyEnvironment(for test: AudioQATestDefinition) async -> String? {
        let ready = await prepareForTest(test)
        guard ready else {
            return "Audio setup failed. Check microphone permission and try again."
        }
        let routeMatched = await waitForExpectedRoute(for: test)
        guard routeMatched else {
            if shouldPreferSpeaker(for: test) {
                return "The phone is still not using its built-in audio route. Disconnect external audio devices and try again."
            }
            return "AirPods are not active yet. Connect them, wear them, and select them as the audio route, then try again."
        }
        return nil
    }

    func verifySpeechCapture(for test: AudioQATestDefinition) async -> String? {
        let routeMatched = await waitForExpectedRoute(for: test)
        guard routeMatched else {
            if shouldPreferSpeaker(for: test) {
                return "The phone audio route changed away from the built-in mic or speaker. Go back and prepare the test again."
            }
            return "AirPods are no longer the active route. Reconnect them and try again."
        }

        if capturedSpeechLooksValid {
            return nil
        }

        return "We could not detect a clear voice recording. Speak for a few seconds and try again."
    }

    func verifyActiveRoute(for test: AudioQATestDefinition) async -> String? {
        let routeMatched = await waitForExpectedRoute(for: test)
        guard routeMatched else {
            if shouldPreferSpeaker(for: test) {
                return "The phone is no longer using its built-in audio route. Go back and prepare the test again."
            }
            return "AirPods are no longer the active audio route. Reconnect them and try again."
        }
        return nil
    }

    func recordResult(for test: AudioQATestDefinition, outcome: AudioQATestOutcome) {
        refreshState()
        let timeline = finalizedTimeline(for: test, outcome: outcome)
        let scopedLogs = scopedLogs(for: test)
        let result = AudioQATestResult(
            testId: test.id,
            title: test.title,
            outcome: outcome,
            recordedAt: Date(),
            stateSummary: diagnosticSnapshotText(),
            timeline: timeline,
            scopedLogs: scopedLogs,
            recentLogs: Array(logLines.prefix(40).reversed())
        )
        results[test.id] = result
        appendLog("Recorded QA result for \(test.title): \(outcome.rawValue).")
        currentTestContext = nil
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
        currentTestContext = nil
        reportLogLines = []
        peakInputLevelSinceCaptureReset = 0
    }

    private func shouldPreferSpeaker(for test: AudioQATestDefinition) -> Bool {
        !test.id.contains("airpods")
    }

    private func policy(for test: AudioQATestDefinition) -> AudioEnginePolicy {
        if test.id == "airpods-playback" {
            return AudioEnginePolicy(
                preferSpeaker: false,
                enableInput: false,
                preferBluetoothA2dpOutput: true,
                voiceProcessing: false
            )
        }
        return AudioEnginePolicy(
            preferSpeaker: shouldPreferSpeaker(for: test),
            enableInput: true,
            preferBluetoothA2dpOutput: false,
            voiceProcessing: true
        )
    }

    private func replaceEngine(policy: AudioEnginePolicy, reason: String) {
        device.audio.stop()
        currentPolicy = policy
        device = Self.makeDevice(policy: policy)
        appendLog(
            "\(reason). preferSpeaker=\(policy.preferSpeaker) enableInput=\(policy.enableInput) preferBluetoothA2dpOutput=\(policy.preferBluetoothA2dpOutput)"
        )
        refreshState()
    }

    private static func makeDevice(policy: AudioEnginePolicy) -> DeviceImpl {
        DeviceImpl(
            platformContext: nil,
            callbacks: nil,
            config: DeviceConfig(
                audio: AudioDiagnosticsConfig(
                    sampleRate: 24_000,
                    ioBufferFrames: 1_024,
                    preferSpeaker: policy.preferSpeaker,
                    voiceProcessing: policy.voiceProcessing,
                    enableInput: policy.enableInput,
                    preferBluetoothA2dpOutput: policy.preferBluetoothA2dpOutput
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
        peakInputLevelSinceCaptureReset = max(peakInputLevelSinceCaptureReset, state.inputLevel)
    }

    private func drainLogs() {
        while let line = device.audio.takeNextLogMessage() {
            appendLog(line)
        }
    }

    private func appendLog(_ message: String) {
        let line = "\(logDateFormatter.string(from: Date())) \(message)"
        logLines.insert(line, at: 0)
        if logLines.count > 200 {
            logLines.removeLast(logLines.count - 200)
        }
        reportLogLines.append(line)
    }

    private func beginTestContextIfNeeded(for test: AudioQATestDefinition) {
        guard currentTestContext?.testId != test.id else {
            return
        }
        currentTestContext = AudioQATestContext(
            testId: test.id,
            logStartIndex: reportLogLines.count,
            timeline: []
        )
        recordTestEvent(for: test, "Entered test screen.", includeSnapshot: true)
    }

    private func recordTestEvent(
        for test: AudioQATestDefinition,
        _ message: String,
        includeSnapshot: Bool = false
    ) {
        beginTestContextIfNeeded(for: test)
        guard var context = currentTestContext, context.testId == test.id else {
            return
        }
        let snapshotSuffix = includeSnapshot ? " | \(compactDiagnosticSummary())" : ""
        context.timeline.append("[\(eventDateFormatter.string(from: Date()))] \(message)\(snapshotSuffix)")
        currentTestContext = context
    }

    private func compactDiagnosticSummary() -> String {
        "running=\(state.isRunning) input=\(state.inputRoute) output=\(state.outputRoute) sampleRate=\(Int(state.sampleRate))Hz ioBuffer=\(String(format: "%.2f", state.ioBufferMillis))ms inputLevel=\(String(format: "%.3f", state.inputLevel)) capturedBytes=\(state.capturedBytes) playedBytes=\(state.playedBytes) playbackRequests=\(state.playbackRequests)"
    }

    private func finalizedTimeline(for test: AudioQATestDefinition, outcome: AudioQATestOutcome) -> [String] {
        beginTestContextIfNeeded(for: test)
        recordTestEvent(for: test, "Recorded verdict: \(outcome.rawValue).", includeSnapshot: true)
        return currentTestContext?.timeline ?? []
    }

    private func scopedLogs(for test: AudioQATestDefinition) -> [String] {
        guard let context = currentTestContext, context.testId == test.id else {
            return []
        }
        guard context.logStartIndex < reportLogLines.count else {
            return []
        }
        return Array(reportLogLines[context.logStartIndex...])
    }

    private func waitForExpectedRoute(for test: AudioQATestDefinition) async -> Bool {
        for _ in 0..<12 {
            drainLogs()
            refreshState()
            if routeMatchesExpectation(for: test) {
                return true
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        drainLogs()
        refreshState()
        return routeMatchesExpectation(for: test)
    }

    private func routeMatchesExpectation(for test: AudioQATestDefinition) -> Bool {
        if shouldPreferSpeaker(for: test) {
            return state.inputRoute.contains("MicrophoneBuiltIn") &&
                (state.outputRoute.contains("Speaker") || state.outputRoute.contains("BuiltInReceiver"))
        }
        if test.kind == .capturedLoopback {
            return state.inputRoute.contains("BluetoothHFP") && state.outputRoute.contains("BluetoothHFP")
        }
        return state.outputRoute.contains("BluetoothA2DPOutput")
    }

    private var capturedSpeechLooksValid: Bool {
        state.capturedBytes >= 48_000 && peakInputLevelSinceCaptureReset >= 0.015
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
            Timeline:
            \(result.timeline.isEmpty ? "No test timeline events recorded." : result.timeline.joined(separator: "\n"))
            Logs During Test:
            \(result.scopedLogs.isEmpty ? "No scoped logs recorded." : result.scopedLogs.joined(separator: "\n"))
            Recent Logs:
            \(result.recentLogs.joined(separator: "\n"))
            """
        }.joined(separator: "\n\n")

        let summary = """
        kpal Report
        Generated: \(Date().formatted(date: .numeric, time: .standard))

        Suite Size: \(activeTests.count)
        Completed: \(completedCount)
        Passed: \(passedCount)
        Failed: \(failedCount)
        Skipped: \(skippedCount)

        Final Diagnostics:
        \(diagnosticSnapshotText())
        """

        let allLogs = reportLogLines.joined(separator: "\n")
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
                colors: QATheme.backgroundGradient,
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            switch screen {
            case .landing:
                AudioQALandingView(
                    tests: viewModel.tests,
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
    let tests: [AudioQATestDefinition]
    let startSuite: () -> Void
    let startSingle: (AudioQATestDefinition) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("kpal")
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

                ForEach(tests) { test in
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
            }
            .padding(20)
        }
    }
}

struct AudioQATestView: View {
    @ObservedObject var viewModel: AudioQAViewModel
    let test: AudioQATestDefinition
    let stepText: String
    let exitRun: () -> Void
    let advance: () -> Void

    @State private var currentStepIndex = 0
    @State private var isWorking = false
    @State private var stepMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                currentStepCard
            }
            .padding(20)
        }
        .task(id: test.id) {
            currentStepIndex = 0
            isWorking = false
            stepMessage = nil
            await enterCurrentStep()
        }
        .onChange(of: currentStepIndex) { _, newValue in
            let clamped = clampedStepIndex(for: newValue)
            if clamped != newValue {
                currentStepIndex = clamped
                return
            }
            Task {
                await enterCurrentStep()
            }
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
                    Text("Step \(displayStepIndex) of \(steps.count)")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(QATheme.badgeBackground))
                }
            }
        }
    }

    private var currentStepCard: some View {
        qaCard {
            VStack(alignment: .leading, spacing: 14) {
                Text(currentStep.title)
                    .font(.title3.weight(.semibold))
                Text(currentStep.detail)
                    .font(.callout)
                    .foregroundStyle(.secondary)

                if let stepMessage {
                    Text(stepMessage)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.red)
                }

                if isWorking {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                switch currentStep.kind {
                case .verdict:
                    verdictButtons
                default:
                    Button(currentStep.actionTitle ?? "Continue") {
                        runCurrentStepAction()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isWorking)

                    Button("Abort Test") {
                        skipTest()
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var verdictButtons: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Did this step work as expected?")
                .font(.callout)
                .foregroundStyle(.secondary)
            Button(test.passTitle) {
                viewModel.recordResult(for: test, outcome: .passed)
                advance()
            }
            .buttonStyle(.borderedProminent)
            Button(test.failTitle) {
                viewModel.recordResult(for: test, outcome: .failed)
                advance()
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            Button("Abort Test") {
                skipTest()
            }
            .buttonStyle(.bordered)
        }
    }

    private var steps: [AudioQAStepDefinition] {
        let routeStep = expectsAirPods
            ? AudioQAStepDefinition(
                id: "confirm-airpods",
                title: "Connect AirPods",
                detail: "Connect your AirPods, put them in your ears, then confirm. The app will verify that AirPods are really available before continuing.",
                actionTitle: "AirPods Are Connected",
                kind: .confirmAirPodsRoute
            )
            : AudioQAStepDefinition(
                id: "confirm-built-in",
                title: "Use iPhone Audio",
                detail: "Disconnect AirPods and any other external audio devices, then confirm. The app will verify that the phone is using its own audio route.",
                actionTitle: "Use iPhone Audio",
                kind: .confirmBuiltInRoute
            )

        switch test.kind {
        case .tonePlayback:
            return [
                routeStep,
                AudioQAStepDefinition(
                    id: "play-tone",
                    title: "Play the Test Tone",
                    detail: "Tap the button below and listen for the 440 Hz tone on the expected output device.",
                    actionTitle: "Play 440 Hz Tone",
                    kind: .playTone
                ),
                AudioQAStepDefinition(
                    id: "verdict",
                    title: "Confirm the Result",
                    detail: "Record whether you heard the tone on the expected output device.",
                    actionTitle: nil,
                    kind: .verdict
                )
            ]
        case .capturedLoopback:
            return [
                routeStep,
                AudioQAStepDefinition(
                    id: "capture-speech",
                    title: "Record Your Voice",
                    detail: "Speak a short sentence for three to five seconds, then confirm. The app will check that it captured a usable recording before continuing.",
                    actionTitle: "I Finished Speaking",
                    kind: .captureSpeech
                ),
                AudioQAStepDefinition(
                    id: "play-capture",
                    title: "Play the Recording",
                    detail: "Tap the button below to play the captured voice on the expected output device.",
                    actionTitle: "Play Recorded Voice",
                    kind: .playCapturedAudio
                ),
                AudioQAStepDefinition(
                    id: "verdict",
                    title: "Confirm the Result",
                    detail: "Record whether you heard the recorded voice on the expected output device.",
                    actionTitle: nil,
                    kind: .verdict
                )
            ]
        }
    }

    private var expectsAirPods: Bool {
        test.id.contains("airpods")
    }

    private var displayStepIndex: Int {
        min(clampedStepIndex + 1, steps.count)
    }

    private var clampedStepIndex: Int {
        clampedStepIndex(for: currentStepIndex)
    }

    private func clampedStepIndex(for index: Int) -> Int {
        guard !steps.isEmpty else {
            return 0
        }
        return min(max(index, 0), steps.count - 1)
    }

    private var currentStep: AudioQAStepDefinition {
        steps[clampedStepIndex]
    }

    private func enterCurrentStep() async {
        stepMessage = nil
        if currentStep.kind == .captureSpeech {
            viewModel.clearCapture(for: test)
        }
    }

    private func runCurrentStepAction() {
        let currentStep = currentStep
        Task {
            isWorking = true
            stepMessage = nil
            defer {
                isWorking = false
            }

            switch currentStep.kind {
            case .confirmBuiltInRoute, .confirmAirPodsRoute:
                if let message = await viewModel.verifyEnvironment(for: test) {
                    stepMessage = message
                } else {
                    advanceToNextStep()
                }
            case .captureSpeech:
                if let message = await viewModel.verifySpeechCapture(for: test) {
                    stepMessage = message
                } else {
                    advanceToNextStep()
                }
            case .playTone:
                if let message = await viewModel.verifyActiveRoute(for: test) {
                    stepMessage = message
                    return
                }
                viewModel.playTestTone(for: test)
                advanceToNextStep()
            case .playCapturedAudio:
                if let message = await viewModel.verifyActiveRoute(for: test) {
                    stepMessage = message
                    return
                }
                viewModel.playCapturedAudio(for: test)
                advanceToNextStep()
            case .verdict:
                break
            }
        }
    }

    private func advanceToNextStep() {
        guard currentStepIndex + 1 < steps.count else {
            return
        }
        currentStepIndex += 1
    }

    private func skipTest() {
        viewModel.recordResult(for: test, outcome: .skipped)
        advance()
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
                            } else {
                                Text("No result recorded.")
                                    .foregroundStyle(.secondary)
                            }
                        }
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
                .fill(QATheme.cardBackground)
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(QATheme.cardBorder, lineWidth: 1)
                )
        )
}

private enum QATheme {
    static var backgroundGradient: [Color] {
        [
            Color(uiColor: .systemGroupedBackground),
            Color(uiColor: .secondarySystemGroupedBackground),
        ]
    }

    static var cardBackground: Color {
        Color(uiColor: .tertiarySystemGroupedBackground)
    }

    static var cardBorder: Color {
        Color(uiColor: .separator).opacity(0.7)
    }

    static var badgeBackground: Color {
        Color(uiColor: .secondarySystemFill)
    }
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
