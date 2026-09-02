import XCTest
import ImageIO

final class CrownInputTests: XCTestCase {
    func testDigitalCrownChangesRenderedGallery() throws {
        let app = XCUIApplication()
        app.launch()
        sleep(2)
        let crownStatus = app.staticTexts["podjs-crown-status"]
        XCTAssertTrue(crownStatus.waitForExistence(timeout: 2))
        let before = try galleryPixels(in: XCUIScreen.main.screenshot())
        XCUIDevice.shared.rotateDigitalCrown(delta: 0.4)
        sleep(1)
        let after = try galleryPixels(in: XCUIScreen.main.screenshot())

        XCTAssertNotEqual(before, after, "Digital Crown changed the native label but not the PodJS gallery")
    }

    func testTouchSwipeChangesRenderedGallery() throws {
        let app = XCUIApplication()
        app.launch()
        sleep(2)

        let before = try galleryPixels(in: XCUIScreen.main.screenshot())
        app.swipeUp(velocity: .slow)
        sleep(1)
        let after = try galleryPixels(in: XCUIScreen.main.screenshot())

        XCTAssertNotEqual(before, after, "Touch scrolling did not reach the PodJS gallery")
    }

    private func galleryPixels(in screenshot: XCUIScreenshot) throws -> Data {
        let source = try XCTUnwrap(CGImageSourceCreateWithData(screenshot.pngRepresentation as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let crop = CGRect(
            x: 0,
            y: CGFloat(image.height) * 0.25,
            width: CGFloat(image.width),
            height: CGFloat(image.height) * 0.5
        )
        let gallery = try XCTUnwrap(image.cropping(to: crop))
        return try XCTUnwrap(gallery.dataProvider?.data) as Data
    }
}
