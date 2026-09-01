// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodJSWatch",
    platforms: [.watchOS(.v11)],
    products: [.library(name: "PodJSWatch", targets: ["PodJSWatch"])],
    targets: [
        .systemLibrary(name: "CPodJS", path: "Sources/CPodJS"),
        .target(name: "PodJSWatch", dependencies: ["CPodJS"], resources: [.process("Resources")]),
        .testTarget(name: "PodJSWatchTests", dependencies: ["PodJSWatch"]),
    ]
)
