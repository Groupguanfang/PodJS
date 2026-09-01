import SwiftUI
import SpriteKit
import PodJSWatch

@main
struct PodJSWatchGalleryApp: App {
    var body: some Scene {
        WindowGroup {
            PodJSWatchRootView()
        }
    }
}

private struct PodJSWatchRootView: View {
    @State private var host: PodWatchHost?
    @State private var status = "Starting PodJS…"
    @State private var crownValue = 0.0
    @FocusState private var crownFocused: Bool

    var body: some View {
        Group {
            if let host {
                GeometryReader { geometry in
                    SpriteView(scene: host.scene, preferredFramesPerSecond: 60)
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0, coordinateSpace: .local)
                                .onChanged { value in
                                    host.updatePrimaryTouch(
                                        location: value.location,
                                        in: geometry.size
                                    )
                                }
                                .onEnded { _ in host.clearTouches() }
                        )
                }
                .ignoresSafeArea()
                .focusable()
                .focused($crownFocused)
                .digitalCrownRotation(
                    $crownValue,
                    from: -100_000,
                    through: 100_000,
                    by: 1,
                    sensitivity: .high,
                    isContinuous: true,
                    isHapticFeedbackEnabled: false
                )
                .onChange(of: crownValue) { oldValue, newValue in
                    // One SwiftUI detent selects one gallery row. The public
                    // PodJS contract remains physical integer millidegrees.
                    host.addCrownDegrees((newValue - oldValue) * 12)
                }
                .onAppear { crownFocused = true }
            } else {
                VStack(spacing: 8) {
                    Text("PodJS")
                        .font(.headline)
                    ScrollView {
                        Text(status)
                            .font(.system(size: 9))
                            .multilineTextAlignment(.center)
                    }
                }
            }
        }
        .task {
            guard host == nil else { return }
            do {
                let created = try PodWatchHost(bundle: .main)
                try created.frame()
                host = created
                status = "Runtime ready"
                recordDiagnostic("runtime ready")
                print("PodJSWatch: runtime ready")
                while !Task.isCancelled {
                    try await Task.sleep(for: .milliseconds(16))
                    try created.frame()
                }
            } catch {
                let message = String(describing: error)
                status = message
                recordDiagnostic(message)
                print("PodJSWatch: runtime error: \(message)")
            }
        }
    }

    private func recordDiagnostic(_ message: String) {
        guard let directory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { return }
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        try? message.write(
            to: directory.appendingPathComponent("podjs-last-error.txt"),
            atomically: true,
            encoding: .utf8
        )
    }
}
