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
  func testMapsAspectFitTouchIntoLogicalCanvas() throws {
    let point = try XCTUnwrap(PodWatchHost.logicalTouchPoint(
      CGPoint(x: 88, y: 107.5),
      in: CGSize(width: 176, height: 215)
    ))
    XCTAssertEqual(point.x, 120, accuracy: 0.001)
    XCTAssertEqual(point.y, 120, accuracy: 0.001)
    XCTAssertNil(
      PodWatchHost.logicalTouchPoint(
        CGPoint(x: 88, y: 10),
        in: CGSize(width: 176, height: 215)
      )
    )
  }
}
