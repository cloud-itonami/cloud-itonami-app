// Store art for the iOS / Android app, from the same source as the desktop
// icon (scripts/make-app-icon.swift): the jp-go-dds blue gradient and い, the
// first character of いとなみ (営み).
//
// The desktop icon cannot be reused as-is. It draws a macOS plate inset to
// 82% with rounded, transparent corners; iOS and Google Play want a
// full-bleed OPAQUE square and apply their own mask, so the desktop art would
// ship as a small rounded plate inside a second mask, with black corners where
// the transparency was flattened.
//
//   swift mobile/scripts/make-store-art.swift mobile/store/art
//
// Writes, at exact pixel sizes (drawn into a bitmap, not an NSImage, so a
// Retina screen does not double them):
//   ios-appstore-1024.png               1024x1024, opaque — App Store icon
//   play-icon-512.png                    512x512, opaque — Play listing icon
//   android-adaptive-foreground-432.png  432x432, transparent — adaptive icon
//                                        foreground (glyph inside the 66% safe zone)
//   android-adaptive-background-432.png  432x432, opaque — adaptive background
//   play-feature-graphic-1024x500.png    1024x500, opaque — Play feature graphic

import AppKit
import Foundation

let args = CommandLine.arguments
guard args.count >= 2 else {
  FileHandle.standardError.write("usage: make-store-art.swift <output-dir>\n".data(using: .utf8)!)
  exit(2)
}
let outDir = URL(fileURLWithPath: args[1])
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

// --color-primitive-blue-600 / -1000, as in make-app-icon.swift.
let top = CGColor(srgbRed: 0x34 / 255.0, green: 0x60 / 255.0, blue: 0xfb / 255.0, alpha: 1)
let bottom = CGColor(srgbRed: 0x00 / 255.0, green: 0x11 / 255.0, blue: 0x8f / 255.0, alpha: 1)

func font(_ size: CGFloat, _ names: [String]) -> NSFont {
  for n in names { if let f = NSFont(name: n, size: size) { return f } }
  return NSFont.systemFont(ofSize: size, weight: .semibold)
}

func render(_ w: Int, _ h: Int, opaque: Bool, _ draw: (CGContext) -> Void) -> NSBitmapImageRep {
  let space = CGColorSpace(name: CGColorSpace.sRGB)!
  let info = opaque ? CGImageAlphaInfo.noneSkipLast.rawValue : CGImageAlphaInfo.premultipliedLast.rawValue
  let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                      space: space, bitmapInfo: info)!
  ctx.setAllowsAntialiasing(true)
  ctx.interpolationQuality = .high
  let ns = NSGraphicsContext(cgContext: ctx, flipped: false)
  NSGraphicsContext.saveGraphicsState()
  NSGraphicsContext.current = ns
  draw(ctx)
  NSGraphicsContext.restoreGraphicsState()
  return NSBitmapImageRep(cgImage: ctx.makeImage()!)
}

func gradient(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
  let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                     colors: [top, bottom] as CFArray, locations: [0, 1])!
  ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: h), end: CGPoint(x: 0, y: 0), options: [])
  // the same soft top highlight as the desktop plate
  let sheen = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                         colors: [CGColor(gray: 1, alpha: 0.16), CGColor(gray: 1, alpha: 0)] as CFArray,
                         locations: [0, 1])!
  ctx.drawLinearGradient(sheen, start: CGPoint(x: 0, y: h), end: CGPoint(x: 0, y: h * 0.62), options: [])
}

func glyph(_ text: String, size: CGFloat, centerX: CGFloat, centerY: CGFloat, nudge: CGFloat,
           fontNames: [String] = ["HiraginoSans-W6", "HiraKakuProN-W6"]) {
  let attrs: [NSAttributedString.Key: Any] = [.font: font(size, fontNames), .foregroundColor: NSColor.white]
  let s = NSAttributedString(string: text, attributes: attrs)
  let sz = s.size()
  s.draw(at: NSPoint(x: centerX - sz.width / 2, y: centerY - sz.height / 2 - nudge))
}

func write(_ rep: NSBitmapImageRep, _ name: String) {
  let png = rep.representation(using: .png, properties: [:])!
  try! png.write(to: outDir.appendingPathComponent(name))
  print("wrote \(name) \(rep.pixelsWide)x\(rep.pixelsHigh) alpha=\(rep.hasAlpha)")
}

// The glyph takes 60% of the desktop's 82% plate there; full-bleed it keeps
// the same proportion of the visible icon after the platform mask.
func icon(_ px: Int) -> NSBitmapImageRep {
  let s = CGFloat(px)
  return render(px, px, opaque: true) { ctx in
    gradient(ctx, s, s)
    glyph("い", size: s * 0.60, centerX: s / 2, centerY: s / 2, nudge: s * 0.012)
  }
}

write(icon(1024), "ios-appstore-1024.png")
write(icon(512), "play-icon-512.png")

// Adaptive icon: 108dp canvas, 72dp visible, 66dp safe zone. At 432px the
// safe circle is 264px across; the glyph stays well inside it.
write(render(432, 432, opaque: false) { _ in
  glyph("い", size: 432 * 0.40, centerX: 216, centerY: 216, nudge: 432 * 0.008)
}, "android-adaptive-foreground-432.png")
write(render(432, 432, opaque: true) { ctx in gradient(ctx, 432, 432) },
      "android-adaptive-background-432.png")

write(render(1024, 500, opaque: true) { ctx in
  gradient(ctx, 1024, 500)
  glyph("い", size: 300, centerX: 230, centerY: 250, nudge: 4)
  let title = NSAttributedString(string: "Cloud Itonami", attributes: [
    .font: font(76, ["HiraginoSans-W7", "HiraginoSans-W6"]), .foregroundColor: NSColor.white])
  title.draw(at: NSPoint(x: 420, y: 262))
  let sub = NSAttributedString(string: "営みの actor 目録を、手のひらで。", attributes: [
    .font: font(36, ["HiraginoSans-W4", "HiraKakuProN-W3"]),
    .foregroundColor: NSColor(white: 1, alpha: 0.9)])
  sub.draw(at: NSPoint(x: 424, y: 188))
}, "play-feature-graphic-1024x500.png")
