// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AdFreeCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "AdFreeCore", targets: ["AdFreeCore"])],
    targets: [
        .target(name: "AdFreeCore"),
        .testTarget(name: "AdFreeCoreTests", dependencies: ["AdFreeCore"]),
    ]
)
