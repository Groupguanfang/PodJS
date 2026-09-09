import XCTest
@testable import PodJSWatch
final class DrawListTests: XCTestCase {
  func testSemanticDecodeBoundsAndActionRevocation() throws {
    let source = #"{"schema":1,"nodes":[{"id":1048578,"parentId":0,"role":"adjustable","label":"音量😀","value":"50%","hint":null,"state":2,"actions":6,"bounds":{"left":10,"top":20,"right":70,"bottom":60}}]}"#
    let snapshot = try JSONDecoder().decode(PodSemanticSnapshot.self, from: Data(source.utf8))
    XCTAssertEqual(snapshot.schema, 1)
    let node = try XCTUnwrap(snapshot.nodes.first)
    XCTAssertEqual(node.id, 1048578)
    XCTAssertEqual(node.label, "音量😀")
    XCTAssertEqual(node.spokenValue, "50%")
    XCTAssertTrue(node.permits(2))
    XCTAssertTrue(node.permits(4))
    XCTAssertFalse(node.permits(1))
    XCTAssertFalse(node.permits(6))
    // 2x aspect-fit canvas has a 50 point top letterbox.
    XCTAssertEqual(node.frame(in: CGSize(width: 400, height: 300), logicalSize: CGSize(width: 200, height: 100)),
                   CGRect(x: 20, y: 90, width: 120, height: 80))
    let disabled = source.replacingOccurrences(of: "\"state\":2", with: "\"state\":3")
    let revoked = try JSONDecoder().decode(PodSemanticSnapshot.self, from: Data(disabled.utf8)).nodes[0]
    XCTAssertFalse(revoked.permits(2))
    let mixed = source.replacingOccurrences(of: "\"state\":2", with: "\"state\":248")
    let described = try JSONDecoder().decode(PodSemanticSnapshot.self, from: Data(mixed.utf8)).nodes[0]
    XCTAssertEqual(described.spokenValue, ["50%", PodSemanticNode.stateText("mixed"), PodSemanticNode.stateText("expanded"), PodSemanticNode.stateText("busy")].joined(separator: ", "))
  }
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
