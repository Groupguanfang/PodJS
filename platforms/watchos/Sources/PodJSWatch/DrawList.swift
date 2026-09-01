import Foundation

public enum PodDrawCommand: Equatable {
    case rect(xy: UInt32, size: UInt32, color: UInt32)
    case scissor(xy: UInt32, size: UInt32)
    case scissorPop
    case unsupported(UInt32)
}

/// Bounds-checked parser shared by SpriteKit rendering and build validation.
public enum PodDrawListParser {
    public static func parse(_ words: [UInt32]) throws -> [PodDrawCommand] {
        var result: [PodDrawCommand] = []; var i = 0
        while i < words.count {
            let op = words[i]
            let length: Int
            switch op {
            case 1:
                length = 4
                guard i + length <= words.count else { throw PodDrawError.truncated }
                result.append(.rect(xy: words[i+1], size: words[i+2], color: words[i+3]))
            case 2: length = 6; result.append(.unsupported(op))
            case 3:
                guard i + 3 <= words.count else { throw PodDrawError.truncated }
                length = 3 + 2 * Int(words[i+1] >> 16); result.append(.unsupported(op))
            case 4: length = 9; result.append(.unsupported(op))
            case 5: length = 3; result.append(.scissor(xy: words[i+1], size: words[i+2]))
            case 6: length = 1; result.append(.scissorPop)
            case 7: length = 7; result.append(.unsupported(op))
            case 8: length = 12; result.append(.unsupported(op))
            case 9:
                guard i + 8 <= words.count else { throw PodDrawError.truncated }
                length = 8 + (Int(words[i+7]) + 3) / 4; result.append(.unsupported(op))
            case 10: length = 9; result.append(.unsupported(op))
            default: throw PodDrawError.unknownOpcode(op)
            }
            guard length > 0, i + length <= words.count else { throw PodDrawError.truncated }
            i += length
        }
        return result
    }
}

public enum PodDrawError: Error, Equatable { case truncated; case unknownOpcode(UInt32); case spriteKitUnsupported(UInt32); case invalidRGBAFrame }
