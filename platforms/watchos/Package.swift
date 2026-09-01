// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodJSWatch",
    platforms: [.watchOS(.v11)],
    products: [.library(name: "PodJSWatch", targets: ["PodJSWatch"])],
    targets: [
        .binaryTarget(
            name: "PodJSRuntime",
            url: "https://github.com/gfhdhytghd/PodJS/releases/download/v0.1.0-alpha.2/PodJSRuntime-watchOS.xcframework.zip",
            checksum: "30be5a476866c2673b3d280c89a313498148a1cd062e702ebb076075e7f33957"
        ),
        .systemLibrary(name: "CPodJS", path: "Sources/CPodJS"),
        .target(
            name: "PodJSWatch",
            dependencies: ["CPodJS", "PodJSRuntime"],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "PodJSWatchTests", dependencies: ["PodJSWatch"]),
    ]
)
