// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodJSWatch",
    defaultLocalization: "en",
    platforms: [.watchOS(.v11)],
    products: [.library(name: "PodJSWatch", targets: ["PodJSWatch"])],
    targets: [
        .binaryTarget(
            name: "PodJSRuntime",
            path: "Artifacts/PodJSRuntime.xcframework"
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
