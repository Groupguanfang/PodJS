// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PodJSWatch",
    platforms: [.watchOS(.v11)],
    products: [.library(name: "PodJSWatch", targets: ["PodJSWatch"])],
    targets: [
        .binaryTarget(
            name: "PodJSRuntime",
            url: "https://github.com/gfhdhytghd/PodJS/releases/download/v0.1.0-alpha.1/PodJSRuntime-watchOS.xcframework.zip",
            checksum: "61b159a4808f9eb066c335879b23a70dfc09e734cdc9a6a3047c27afdf4395ee"
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
