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

    var body: some View {
        Group {
            if let host {
                SpriteView(scene: host.scene, preferredFramesPerSecond: 60)
                    .ignoresSafeArea()
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
