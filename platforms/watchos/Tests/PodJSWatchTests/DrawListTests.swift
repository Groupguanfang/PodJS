import XCTest
@testable import PodJSWatch
final class DrawListTests: XCTestCase {
  func testRejectsUnknownOpcode() { XCTAssertThrowsError(try PodDrawListParser.parse([99])) }
  func testRejectsTruncatedRect() { XCTAssertThrowsError(try PodDrawListParser.parse([1, 2])) }
}
