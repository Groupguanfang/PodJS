import XCTest
@testable import PodJSWatch
final class DrawListTests: XCTestCase {
  func testRejectsUnknownOpcode() { XCTAssertThrowsError(try PodDrawListParser.parse([99])) }
  func testRejectsTruncatedRect() { XCTAssertThrowsError(try PodDrawListParser.parse([1, 2])) }
  func testRejectsMalformedRGBAFrame() {
    let scene = PodScene(size: CGSize(width: 240, height: 240))
    XCTAssertThrowsError(
      try scene.commit(rgba: [0, 0, 0, 255], pixelWidth: 2, pixelHeight: 2, generation: 1)
    )
  }
}
