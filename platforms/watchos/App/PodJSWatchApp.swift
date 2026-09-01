import SwiftUI
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
        VStack(spacing: 8) {
            Text("PodJS")
                .font(.headline)
            Text(status)
                .font(.caption2)
                .multilineTextAlignment(.center)
        }
        .task {
            guard host == nil else { return }
            do {
                host = try PodWatchHost(bundle: .main)
                status = "Runtime ready"
                print("PodJSWatch: runtime ready")
            } catch {
                status = "Runtime error"
                print("PodJSWatch: runtime error: \(error)")
            }
        }
    }
}
