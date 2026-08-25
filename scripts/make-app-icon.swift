// Draws the Cloud Itonami app icon and writes it as PNG.
//
// The icon is generated rather than checked in as opaque pixels so the colours
// can be traced to their source: the fill is the jp-go-dds (デジタル庁) blue the
// application's own interface is built from, so the Dock and the window agree.
//
//   swift make-app-icon.swift <output.png> [size]
//
// The mark is い — the first character of いとなみ (営み). Two strokes stay
// legible at 16pt, which a denser glyph such as 営 does not.

import AppKit
import Foundation

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
  FileHandle.standardError.write("usage: make-app-icon.swift <output.png> [size]\n".data(using: .utf8)!)
  exit(2)
}
let outputPath = arguments[1]
let size = CGFloat(Int(arguments.count > 2 ? arguments[2] : "1024") ?? 1024)

// --color-primitive-blue-600 and --color-primitive-blue-1000 from the vendored
// jp-go-dds palette.
let top = NSColor(srgbRed: 0x34 / 255.0, green: 0x60 / 255.0, blue: 0xfb / 255.0, alpha: 1)
let bottom = NSColor(srgbRed: 0x00 / 255.0, green: 0x11 / 255.0, blue: 0x8f / 255.0, alpha: 1)

let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()

guard let context = NSGraphicsContext.current?.cgContext else {
  FileHandle.standardError.write("no graphics context\n".data(using: .utf8)!)
  exit(1)
}
context.setAllowsAntialiasing(true)
context.interpolationQuality = .high

// A macOS app icon does not fill its source canvas. The system's icon grid
// keeps the visible plate at roughly 82% so circles, squircles and irregular
// marks have the same optical weight in the Dock. Filling all 1024 units made
// Cloud Itonami look one size larger than Chrome, ChatGPT and Terminal even
// though the Dock assigned every app the same slot.
let plateSize = size * 0.82
let plateInset = (size - plateSize) / 2
let plateRect = NSRect(x: plateInset, y: plateInset,
                       width: plateSize, height: plateSize)

// macOS rounds app icon plates at roughly 22.4% of the plate edge.
let radius = plateSize * 0.2237
let plate = NSBezierPath(roundedRect: plateRect,
                         xRadius: radius, yRadius: radius)
plate.addClip()
NSGradient(starting: top, ending: bottom)?.draw(in: plateRect,
                                                angle: -90)

// A single soft highlight across the top third, so the plate reads as a surface
// rather than a flat swatch at large sizes.
if let sheen = NSGradient(colors: [NSColor(white: 1, alpha: 0.16), NSColor(white: 1, alpha: 0)]) {
  sheen.draw(in: NSRect(x: plateInset, y: plateInset + plateSize * 0.62,
                        width: plateSize, height: plateSize * 0.38), angle: -90)
}

let glyph = "い"
let fontSize = plateSize * 0.60
let font = NSFont(name: "HiraginoSans-W6", size: fontSize)
  ?? NSFont(name: "HiraKakuProN-W6", size: fontSize)
  ?? NSFont.systemFont(ofSize: fontSize, weight: .semibold)
let attributes: [NSAttributedString.Key: Any] = [
  .font: font,
  .foregroundColor: NSColor.white,
]
let text = NSAttributedString(string: glyph, attributes: attributes)
let textSize = text.size()
// Optical centring: the glyph's bounding box is not its visual centre, and a
// mathematically centred い sits low and left.
let origin = NSPoint(x: (size - textSize.width) / 2,
                     y: (size - textSize.height) / 2 - size * 0.012)
text.draw(at: origin)

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
  FileHandle.standardError.write("could not encode png\n".data(using: .utf8)!)
  exit(1)
}
do {
  try png.write(to: URL(fileURLWithPath: outputPath))
  print("wrote \(outputPath) at \(Int(size))×\(Int(size))")
} catch {
  FileHandle.standardError.write("write failed: \(error)\n".data(using: .utf8)!)
  exit(1)
}
