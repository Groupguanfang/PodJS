#if os(watchOS)
import Foundation
import SwiftUI

public struct PodSemanticNode: Decodable, Identifiable, Equatable {
    public struct Bounds: Decodable, Equatable {
        public let left: Int, top: Int, right: Int, bottom: Int
    }
    public let id: Int32
    public let parentId: Int32
    public let role: String
    public let label: String
    public let value: String?
    public let hint: String?
    public let state: UInt16
    public let actions: UInt8
    public let bounds: Bounds

    public var disabled: Bool { state & 1 != 0 }
    public func permits(_ action: UInt8) -> Bool {
        !disabled && [UInt8(1), 2, 4].contains(action) && actions & action != 0
    }
    public func frame(in size: CGSize, logicalSize: CGSize) -> CGRect {
        guard logicalSize.width > 0, logicalSize.height > 0 else { return .zero }
        let scale = min(size.width / logicalSize.width, size.height / logicalSize.height)
        return CGRect(x: (size.width - logicalSize.width * scale) / 2 + CGFloat(bounds.left) * scale,
                      y: (size.height - logicalSize.height * scale) / 2 + CGFloat(bounds.top) * scale,
                      width: CGFloat(max(0, bounds.right - bounds.left)) * scale,
                      height: CGFloat(max(0, bounds.bottom - bounds.top)) * scale)
    }
    var traits: AccessibilityTraits {
        var result: AccessibilityTraits = []
        switch role {
        case "button", "checkbox", "switch": result.insert(.isButton)
        case "link": result.insert(.isLink)
        case "header": result.insert(.isHeader)
        case "image": result.insert(.isImage)
        case "text": result.insert(.isStaticText)
        default: break
        }
        if state & 2 != 0 { result.insert(.isSelected) }
        return result
    }
    var spokenValue: String {
        var parts = value.map { [$0] } ?? []
        if state & 64 != 0 {
            parts.append(Self.stateText(state & 8 != 0 ? "mixed" : state & 4 != 0 ? "checked" : "unchecked"))
        }
        if state & 128 != 0 { parts.append(Self.stateText(state & 16 != 0 ? "expanded" : "collapsed")) }
        if state & 32 != 0 { parts.append(Self.stateText("busy")) }
        return parts.filter { !$0.isEmpty }.joined(separator: ", ")
    }
    static func stateText(_ key: String) -> String {
        Bundle.module.localizedString(forKey: key, value: nil, table: "Accessibility")
    }
}

struct PodSemanticSnapshot: Decodable {
    let schema: Int
    let nodes: [PodSemanticNode]
}

/// Non-visual SwiftUI elements sharing the SpriteView's aspect-fit geometry.
/// Stable native IDs retain identity; ordering comes from the semantic tree.
public struct PodAccessibilityOverlay: View {
    @ObservedObject private var host: PodWatchHost
    public init(host: PodWatchHost) { self.host = host }
    public var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topLeading) {
                ForEach(Array(host.semanticNodes.enumerated()), id: \.element.id) { index, node in
                    let rect = node.frame(in: geometry.size, logicalSize: host.scene.size)
                    PodSemanticElement(node: node) { action in host.performAccessibilityAction(node.id, action: action) }
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                        .accessibilitySortPriority(Double(host.semanticNodes.count - index))
                }
            }
            .accessibilityElement(children: .contain)
        }
        .allowsHitTesting(false)
    }
}

private struct PodSemanticElement: View {
    let node: PodSemanticNode
    let perform: (UInt8) -> Void
    private var base: some View {
        Color.clear
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(node.label))
            .accessibilityValue(Text(node.spokenValue))
            .accessibilityHint(Text(node.hint ?? ""))
            .accessibilityAddTraits(node.traits)
            .accessibilityIdentifier("podjs-semantic-\(node.id)")
            .disabled(node.disabled)
    }
    @ViewBuilder private var activated: some View {
        if node.permits(1) { base.accessibilityAction(.default) { perform(1) } }
        else { base }
    }
    @ViewBuilder var body: some View {
        if node.permits(2) || node.permits(4) {
            activated.accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: if node.permits(2) { perform(2) }
                case .decrement: if node.permits(4) { perform(4) }
                @unknown default: break
                }
            }
        } else { activated }
    }
}
#endif
