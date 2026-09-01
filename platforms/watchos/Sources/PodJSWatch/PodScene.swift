#if os(watchOS)
import SpriteKit
import CoreGraphics

/// SpriteKit backend. Unsupported DrawList operations are surfaced as errors;
/// production packaging runs the same parser and refuses an incompatible app.
public final class PodScene: SKScene {
    private var generation: UInt64 = 0
    public func commit(
        rgba: [UInt8],
        pixelWidth: Int,
        pixelHeight: Int,
        generation next: UInt64
    ) throws {
        guard next != generation else { return }
        guard rgba.count == pixelWidth * pixelHeight * 4,
              let provider = CGDataProvider(data: Data(rgba) as CFData),
              let image = CGImage(
                width: pixelWidth,
                height: pixelHeight,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: pixelWidth * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
              ) else { throw PodDrawError.invalidRGBAFrame }
        let sprite = SKSpriteNode(texture: SKTexture(cgImage: image))
        sprite.anchorPoint = CGPoint(x: 0, y: 0)
        sprite.position = .zero
        sprite.size = size
        removeAllChildren()
        addChild(sprite)
        generation = next
    }

    public func commit(words: [UInt32], generation next: UInt64) throws {
        guard next != generation else { return }
        let commands = try PodDrawListParser.parse(words)
        removeAllChildren()
        for command in commands {
            switch command {
            case let .rect(xy, packedSize, color):
                let x = CGFloat(Int16(bitPattern: UInt16(xy & 0xffff)))
                let yy = CGFloat(Int16(bitPattern: UInt16(xy >> 16)))
                let w = CGFloat(packedSize & 0xffff), h = CGFloat(packedSize >> 16)
                let node = SKShapeNode(rect: CGRect(x: x, y: 240 - yy - h, width: w, height: h))
                node.fillColor = SKColor(red: CGFloat(color & 255)/255, green: CGFloat((color>>8)&255)/255, blue: CGFloat((color>>16)&255)/255, alpha: CGFloat(color>>24)/255)
                node.strokeColor = .clear; addChild(node)
            case .scissor, .scissorPop: break
            case let .unsupported(op): throw PodDrawError.spriteKitUnsupported(op)
            }
        }
        generation = next
    }
}
#endif
