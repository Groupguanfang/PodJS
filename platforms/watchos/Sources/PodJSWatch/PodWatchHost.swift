#if os(watchOS)
import Foundation
import SpriteKit
import WatchKit
import CPodJS

public final class PodWatchHost {
    public let scene = PodScene(size: CGSize(width: 240, height: 240))
    private var runtime: OpaquePointer?
    private var crownRemainder: Double = 0

    public init(bundle: Bundle = .main) throws {
        guard pod_runtime_abi_version() == PODJS_RUNTIME_ABI_VERSION else { throw HostError.abi }
        let data = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].path
        runtime = "watchos-watch".withCString { target in data.withCString { path in
          let caps = "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"net.http\",\"data.fs\"]"
          return caps.withCString { capabilities in
            var c = PodRuntimeConfig(struct_size: UInt32(MemoryLayout<PodRuntimeConfig>.size), target_id: target,
              host_abi: PODJS_RUNTIME_ABI_VERSION, raster_density: 2, physical_width: 416, physical_height: 496,
              display_density: 2, display_shape: UInt32(POD_DISPLAY_ROUND.rawValue), safe_top: 0, safe_right: 0, safe_bottom: 0, safe_left: 0, data_dir: path, capabilities_json: capabilities)
            return pod_runtime_create(&c)
          }
        }}
        guard let runtime else { throw HostError.runtime(String(cString: pod_runtime_last_error())) }
        guard let pak = bundle.url(forResource: "main", withExtension: "pak"), let js = bundle.url(forResource: "main", withExtension: "js"), let manifest = bundle.url(forResource: "pod.manifest", withExtension: "json") else { throw HostError.assets }
        let pb = try Data(contentsOf: pak), jb = try Data(contentsOf: js), mb = try String(contentsOf: manifest, encoding: .utf8)
        try checked(pb.withUnsafeBytes { pod_runtime_load_pak(runtime, $0.bindMemory(to: UInt8.self).baseAddress, $0.count) })
        try checked(mb.withCString { pod_runtime_validate_package(runtime, $0) })
        try checked(jb.withUnsafeBytes { ptr in "app:///main.js".withCString { pod_runtime_eval_bundle(runtime, ptr.bindMemory(to: UInt8.self).baseAddress, ptr.count, $0) } })
    }
    deinit { if let runtime { pod_runtime_destroy(runtime) } }
    public func addCrownDegrees(_ degrees: Double) { crownRemainder += degrees * 1000 }
    public func setLifecycle(_ state: UInt32) throws { try checked(pod_runtime_set_lifecycle(runtime, state)) }
    private func checked(_ code: Int32) throws { if code < 0 { throw HostError.runtime(String(cString: pod_runtime_last_error())) } }
    public enum HostError: Error { case abi, assets, runtime(String) }
}
#endif
