// Focus-free desktop capability for macOS (ADR-0059).
//
// Every command here reads or drives another application WITHOUT activating
// it. Three calls are therefore absent by construction, and their absence is
// the contract:
//
//   NSRunningApplication.activate    would raise the target and take the key
//                                    window away from whatever the person is
//                                    typing into.
//   CGWarpMouseCursorPosition        would move the real cursor.
//   CGEvent.post / postToPid         synthesised events do not work here at
//                                    all — see below.
//
// What replaces them: the accessibility tree for reading, and AXUIElement-
// PerformAction / AXUIElementSetAttributeValue for acting.
//
// There is no synthesised-event path, and its absence was measured rather than
// assumed. On 2026-08-18, macOS 26.3.1, CGEvent.postToPid was tried twice
// against a background TextEdit: `cmd+s` left the document unsaved and its
// Save item disabled, and eighteen characters of unicode key events left the
// text area's value byte-identical. AppKit routes key events to the key
// window, and a background application does not have one, so the events are
// enqueued and dropped. Keeping that code would have shipped two tools that
// report success and change nothing — which is worse than not having them,
// because a caller cannot tell the difference.
//
// A keyboard shortcut is therefore expressed as the menu command it stands
// for: `menu` lists every command WITH its shortcut, and `menu-press` performs
// it. Measured the same day: pressing ファイル>新規 on a background TextEdit took
// its window count from 0 to 1 with the frontmost application and the cursor
// unmoved.
//
// Element references (`@a12`) are indices into a deterministic depth-first
// walk, the same shape the browser tool's `@e12` already uses. They are only
// meaningful against the tree they came from, so every acting command takes
// `--expect <digest>` and refuses when the tree has changed underneath it.
// That refusal is what replaced `require-frontmost!`: the old guard asked
// "is the same app still in front", which is both weaker (the window could
// have changed while staying frontmost) and only answerable by depending on
// focus in the first place.

import Cocoa
import ApplicationServices
import CryptoKit

// ── output ──────────────────────────────────────────────────────────────

func emit(_ value: [String: Any]) -> Never {
  let data = try! JSONSerialization.data(withJSONObject: value,
                                         options: [.sortedKeys])
  FileHandle.standardOutput.write(data)
  FileHandle.standardOutput.write("\n".data(using: .utf8)!)
  exit(0)
}

func fail(_ type: String, _ message: String, _ extra: [String: Any] = [:]) -> Never {
  var body: [String: Any] = ["ok": false, "type": type, "message": message]
  for (k, v) in extra { body[k] = v }
  let data = try! JSONSerialization.data(withJSONObject: body,
                                         options: [.sortedKeys])
  FileHandle.standardError.write(data)
  FileHandle.standardError.write("\n".data(using: .utf8)!)
  exit(2)
}

// ── arguments ───────────────────────────────────────────────────────────

var options: [String: String] = [:]
var positional: [String] = []
do {
  var rest = Array(CommandLine.arguments.dropFirst())
  while let head = rest.first {
    rest.removeFirst()
    if head.hasPrefix("--") {
      let name = String(head.dropFirst(2))
      guard let value = rest.first else { fail("bad-argument", "\(head) needs a value") }
      rest.removeFirst()
      options[name] = value
    } else {
      positional.append(head)
    }
  }
}

guard let command = positional.first else {
  fail("bad-argument", "usage: cloud-itonami-desktop-macos <command> [--flag value]")
}

func option(_ name: String) -> String? { options[name] }

func required(_ name: String) -> String {
  guard let value = options[name], !value.isEmpty else {
    fail("bad-argument", "--\(name) is required")
  }
  return value
}

// ── trust ───────────────────────────────────────────────────────────────

// Never with the prompt option: a helper invoked from a background server has
// no business raising a system dialog on somebody's screen. Reporting untrusted
// lets the application ask, once, in its own settings surface.
func accessibilityTrusted() -> Bool {
  AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): false] as CFDictionary)
}

func requireTrust() {
  if !accessibilityTrusted() {
    fail("accessibility-untrusted",
         "This process is not trusted for Accessibility. Grant it in System Settings › Privacy & Security › Accessibility.")
  }
}

// ── target application ──────────────────────────────────────────────────

struct Target {
  let pid: pid_t
  let name: String
  let bundleId: String?
}

func resolveTarget(_ wanted: String) -> Target {
  let running = NSWorkspace.shared.runningApplications.filter {
    $0.activationPolicy == .regular || $0.activationPolicy == .accessory
  }
  let exact = running.first { $0.localizedName == wanted }
  let byBundle = running.first { $0.bundleIdentifier == wanted }
  // Both fallbacks are case-insensitive, and the bundle one is not decoration:
  // the documentation tells callers to prefer a bundle id because a localized
  // machine answers to テキストエディット rather than TextEdit, and then
  // `com.apple.Finder` was reported as not running while `com.apple.finder`
  // worked. Reverse-DNS identifiers are conventionally lowercase but are
  // written both ways, and a capitalisation is not a different application.
  let insensitiveName = running.first {
    $0.localizedName?.compare(wanted, options: .caseInsensitive) == .orderedSame
  }
  let insensitiveBundle = running.first {
    $0.bundleIdentifier?.compare(wanted, options: .caseInsensitive) == .orderedSame
  }
  guard let app = exact ?? byBundle ?? insensitiveName ?? insensitiveBundle else {
    let names = running.compactMap { $0.localizedName }.sorted()
    fail("application-not-running", "No running application named \(wanted).",
         ["available": names])
  }
  return Target(pid: app.processIdentifier,
                name: app.localizedName ?? wanted,
                bundleId: app.bundleIdentifier)
}

// ── accessibility reading ───────────────────────────────────────────────

func copyAttribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
  var value: CFTypeRef?
  let status = AXUIElementCopyAttributeValue(element, name as CFString, &value)
  return status == .success ? value : nil
}

func stringAttribute(_ element: AXUIElement, _ name: String) -> String? {
  guard let value = copyAttribute(element, name) else { return nil }
  if let s = value as? String { return s }
  if let n = value as? NSNumber { return n.stringValue }
  return nil
}

func boolAttribute(_ element: AXUIElement, _ name: String) -> Bool? {
  guard let value = copyAttribute(element, name) as? NSNumber else { return nil }
  return value.boolValue
}

func pointAttribute(_ element: AXUIElement, _ name: String) -> CGPoint? {
  guard let value = copyAttribute(element, name) else { return nil }
  guard CFGetTypeID(value) == AXValueGetTypeID() else { return nil }
  var point = CGPoint.zero
  guard AXValueGetValue(value as! AXValue, .cgPoint, &point) else { return nil }
  return point
}

func sizeAttribute(_ element: AXUIElement, _ name: String) -> CGSize? {
  guard let value = copyAttribute(element, name) else { return nil }
  guard CFGetTypeID(value) == AXValueGetTypeID() else { return nil }
  var size = CGSize.zero
  guard AXValueGetValue(value as! AXValue, .cgSize, &size) else { return nil }
  return size
}

func children(_ element: AXUIElement) -> [AXUIElement] {
  guard let value = copyAttribute(element, kAXChildrenAttribute as String) else { return [] }
  return (value as? [AXUIElement]) ?? []
}

func actionNames(_ element: AXUIElement) -> [String] {
  var names: CFArray?
  guard AXUIElementCopyActionNames(element, &names) == .success else { return [] }
  return ((names as? [String]) ?? []).sorted()
}

let maxNodesDefault = 400
let maxDepth = 24
let maxTextLength = 200

struct Node {
  let ref: String
  let element: AXUIElement
  let role: String
  let subrole: String?
  let title: String?
  let value: String?
  let help: String?
  let enabled: Bool?
  let focused: Bool?
  let frame: CGRect?
  let actions: [String]
  let depth: Int
}

func shorten(_ value: String?) -> String? {
  guard let value = value else { return nil }
  let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
  if trimmed.isEmpty { return nil }
  if trimmed.count <= maxTextLength { return trimmed }
  return String(trimmed.prefix(maxTextLength)) + "…"
}

// One depth-first walk, in the order the accessibility API returns children.
// The order is what makes `@a12` mean the same element to the second call as
// it did to the first; nothing is sorted or filtered out of the middle.
func walk(_ target: Target, limit: Int, includeMenu: Bool) -> [Node] {
  let app = AXUIElementCreateApplication(target.pid)
  var nodes: [Node] = []
  var index = 0

  func visit(_ element: AXUIElement, depth: Int) {
    if nodes.count >= limit || depth > maxDepth { return }
    let role = stringAttribute(element, kAXRoleAttribute as String) ?? "AXUnknown"
    // The menu bar is every application's largest subtree and almost never the
    // thing being driven. Measured on this machine: walking it first spent all
    // 40 nodes of a default budget inside the Apple menu and never reached the
    // window. It stays reachable behind --include-menu.
    if role == "AXMenuBar" && !includeMenu { return }
    let position = pointAttribute(element, kAXPositionAttribute as String)
    let size = sizeAttribute(element, kAXSizeAttribute as String)
    let frame: CGRect? = (position != nil && size != nil)
      ? CGRect(origin: position!, size: size!) : nil
    nodes.append(Node(ref: "@a\(index)",
                      element: element,
                      role: role,
                      subrole: stringAttribute(element, kAXSubroleAttribute as String),
                      title: shorten(stringAttribute(element, kAXTitleAttribute as String)),
                      value: shorten(stringAttribute(element, kAXValueAttribute as String)),
                      help: shorten(stringAttribute(element, kAXDescriptionAttribute as String)),
                      enabled: boolAttribute(element, kAXEnabledAttribute as String),
                      focused: boolAttribute(element, kAXFocusedAttribute as String),
                      frame: frame,
                      actions: actionNames(element),
                      depth: depth))
    index += 1
    for child in children(element) { visit(child, depth: depth + 1) }
  }

  // Windows rather than the application element itself: the menu bar and the
  // rest of the application's chrome are not what an agent is looking at, and
  // walking them first pushes the visible window past the node limit.
  var windows = (copyAttribute(app, kAXWindowsAttribute as String) as? [AXUIElement]) ?? []
  if windows.isEmpty {
    // Some hosts publish only a main or focused window. Asking for those before
    // falling back to the application element is the difference between reading
    // the surface and reading the menu bar.
    for attribute in [kAXMainWindowAttribute as String, kAXFocusedWindowAttribute as String] {
      if let value = copyAttribute(app, attribute) {
        windows.append(value as! AXUIElement)
        break
      }
    }
  }
  if windows.isEmpty { visit(app, depth: 0) } else {
    for window in windows { visit(window, depth: 0) }
  }
  return nodes
}

func describe(_ node: Node) -> [String: Any] {
  var body: [String: Any] = ["ref": node.ref, "role": node.role, "depth": node.depth]
  if let v = node.subrole { body["subrole"] = v }
  if let v = node.title { body["title"] = v }
  if let v = node.value { body["value"] = v }
  if let v = node.help { body["description"] = v }
  if let v = node.enabled { body["enabled"] = v }
  if let v = node.focused { body["focused"] = v }
  if !node.actions.isEmpty { body["actions"] = node.actions }
  if let f = node.frame {
    body["frame"] = [Int(f.origin.x), Int(f.origin.y), Int(f.size.width), Int(f.size.height)]
  }
  return body
}

// The digest covers identity and geometry — ref, role, subrole, title, frame —
// and deliberately NOT `value`. A text field whose contents change while the
// agent is typing into it must not invalidate the tree the person approved;
// a button that moved, was relabelled or disappeared must.
func digest(_ nodes: [Node]) -> String {
  var hasher = SHA256()
  for node in nodes {
    let frame = node.frame.map {
      "\(Int($0.origin.x)),\(Int($0.origin.y)),\(Int($0.size.width)),\(Int($0.size.height))"
    } ?? "-"
    let line = "\(node.ref)\u{1}\(node.role)\u{1}\(node.subrole ?? "-")\u{1}\(node.title ?? "-")\u{1}\(frame)\n"
    hasher.update(data: Data(line.utf8))
  }
  return "sha256:" + hasher.finalize().map { String(format: "%02x", $0) }.joined()
}

func requireUnchanged(_ nodes: [Node]) {
  guard let expected = option("expect") else { return }
  let actual = digest(nodes)
  if expected != actual {
    fail("tree-changed",
         "The application's accessibility tree changed after the approved snapshot.",
         ["expected": expected, "actual": actual])
  }
}

func node(_ nodes: [Node], ref: String) -> Node {
  guard let found = nodes.first(where: { $0.ref == ref }) else {
    fail("element-not-found", "No element \(ref) in this application's tree.")
  }
  return found
}

// ── window identity, for a window-scoped screenshot ─────────────────────

// A whole-screen capture is focus-free already, but it hands the model every
// other window on the display — bank tabs, someone's messages, another agent's
// terminal. The window id lets the caller capture exactly the target.
//
// This enumerates ALL windows and reports `onscreen` per window rather than
// asking CoreGraphics for on-screen ones only. The filter version could not
// tell "this application has no window" from "this application's window is not
// being composited right now", and answered the first for both. Measured
// 2026-08-19: with the display asleep, EVERY application on this machine —
// Terminal in front included — reported zero on-screen windows, so the earlier
// filter made `screenshot` fail with "no window" for a machine full of them. A
// window on another Space reads the same way, and so does a minimized one.
func windowIds(_ target: Target) -> [[String: Any]] {
  let info = (CGWindowListCopyWindowInfo([.optionAll, .excludeDesktopElements],
                                         kCGNullWindowID) as? [[String: Any]]) ?? []
  return info.compactMap { entry in
    guard let owner = entry[kCGWindowOwnerPID as String] as? pid_t, owner == target.pid,
          let number = entry[kCGWindowNumber as String] as? Int else { return nil }
    let layer = entry[kCGWindowLayer as String] as? Int ?? 0
    guard layer == 0 else { return nil }
    var body: [String: Any] = [
      "window-id": number,
      "layer": layer,
      "onscreen": (entry[kCGWindowIsOnscreen as String] as? Bool) ?? false,
    ]
    if let name = entry[kCGWindowName as String] as? String, !name.isEmpty {
      body["title"] = name
    }
    if let bounds = entry[kCGWindowBounds as String] as? [String: Any] {
      body["bounds"] = bounds
    }
    return body
  }
}

// ── menu items ──────────────────────────────────────────────────────────

// The public, focus-free replacement for a keyboard shortcut.
//
// Measured on this machine 2026-08-18: a chord posted with CGEvent.postToPid
// reaches the process but does nothing, because AppKit routes key events to the
// key window and a background application has none. `cmd+s` on a background
// TextEdit left the file zero bytes. Pressing the menu item that the shortcut
// stands for performs the same command, through the accessibility API, with no
// key window involved — which is why this exists and why `key` is documented as
// unverifiable rather than dressed up as working.
struct MenuEntry {
  let path: String
  let element: AXUIElement
  let enabled: Bool
  let shortcut: String?
}

func menuEntries(_ target: Target, limit: Int) -> [MenuEntry] {
  let app = AXUIElementCreateApplication(target.pid)
  guard let bar = copyAttribute(app, kAXMenuBarAttribute as String) else { return [] }
  var entries: [MenuEntry] = []

  func visit(_ element: AXUIElement, prefix: [String], depth: Int) {
    if entries.count >= limit || depth > 8 { return }
    for child in children(element) {
      if entries.count >= limit { return }
      let role = stringAttribute(child, kAXRoleAttribute as String) ?? ""
      if role == "AXMenu" { visit(child, prefix: prefix, depth: depth + 1); continue }
      guard role == "AXMenuItem" || role == "AXMenuBarItem" else { continue }
      guard let title = stringAttribute(child, kAXTitleAttribute as String),
            !title.isEmpty else { continue }
      let path = prefix + [title]
      let sub = children(child).filter {
        (stringAttribute($0, kAXRoleAttribute as String) ?? "") == "AXMenu"
      }
      if sub.isEmpty {
        var shortcut = stringAttribute(child, "AXMenuItemCmdChar")
        if let modifiers = copyAttribute(child, "AXMenuItemCmdModifiers") as? NSNumber,
           shortcut != nil {
          shortcut = "mod\(modifiers.intValue)+" + shortcut!
        }
        entries.append(MenuEntry(path: path.joined(separator: ">"),
                                 element: child,
                                 enabled: boolAttribute(child, kAXEnabledAttribute as String) ?? true,
                                 shortcut: shortcut))
      } else {
        for menu in sub { visit(menu, prefix: path, depth: depth + 1) }
      }
    }
  }

  visit(bar as! AXUIElement, prefix: [], depth: 0)
  return entries
}

// ── the overlay marker ──────────────────────────────────────────────────

// What the person sees when the agent acts.
//
// The reason this is worth having is the same reason the rest of this helper
// exists: acting without taking the cursor means acting INVISIBLY. The old
// tools were at least honest by accident — the pointer jumped, so you knew.
// Now nothing moves, and a person watching their own screen has no way to tell
// which of their windows an agent just pressed a button in. Hermes solves this
// with a tinted overlay pointer; this is that, on public API.
//
// It is a marker, not a cursor: it appears over the element being acted on and
// stays for the requested milliseconds. There is no animation path from a
// previous position, because there is no previous position — nothing is
// travelling anywhere.
//
// Three properties are load-bearing and each has a line below enforcing it:
// the panel is `.nonactivatingPanel` so ordering it in cannot make this process
// key; it is ordered with `orderFrontRegardless` and never made key, so no
// window loses focus; and it `ignoresMouseEvents`, so a click that lands while
// it is up goes to the window underneath rather than to a decoration.
final class OverlayView: NSView {
  var label: String = ""
  override func draw(_ dirtyRect: NSRect) {
    guard let context = NSGraphicsContext.current?.cgContext else { return }
    let inset = bounds.insetBy(dx: 2, dy: 2)
    let path = NSBezierPath(roundedRect: inset, xRadius: 6, yRadius: 6)
    context.setFillColor(NSColor.systemBlue.withAlphaComponent(0.18).cgColor)
    path.fill()
    context.setStrokeColor(NSColor.systemBlue.withAlphaComponent(0.95).cgColor)
    path.lineWidth = 3
    path.stroke()

    // A pointer glyph at the centre, so a screen recording shows WHERE as well
    // as WHICH. Drawn rather than composed from a system cursor image: the
    // system cursor is the person's, and borrowing its appearance would make a
    // still frame ambiguous about whose pointer moved.
    let centre = NSPoint(x: bounds.midX, y: bounds.midY)
    let arrow = NSBezierPath()
    arrow.move(to: centre)
    arrow.line(to: NSPoint(x: centre.x, y: centre.y - 22))
    arrow.line(to: NSPoint(x: centre.x + 6, y: centre.y - 16))
    arrow.line(to: NSPoint(x: centre.x + 13, y: centre.y - 25))
    arrow.line(to: NSPoint(x: centre.x + 17, y: centre.y - 22))
    arrow.line(to: NSPoint(x: centre.x + 10, y: centre.y - 13))
    arrow.line(to: NSPoint(x: centre.x + 18, y: centre.y - 11))
    arrow.close()
    NSColor.white.setFill()
    arrow.fill()
    NSColor.systemBlue.setStroke()
    arrow.lineWidth = 1.5
    arrow.stroke()

    guard !label.isEmpty else { return }
    let attributes: [NSAttributedString.Key: Any] = [
      .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
      .foregroundColor: NSColor.white,
    ]
    let text = label as NSString
    let size = text.size(withAttributes: attributes)
    let box = NSRect(x: inset.minX, y: inset.maxY - size.height - 6,
                     width: min(size.width + 10, inset.width), height: size.height + 4)
    NSColor.systemBlue.withAlphaComponent(0.92).setFill()
    NSBezierPath(roundedRect: box, xRadius: 3, yRadius: 3).fill()
    text.draw(at: NSPoint(x: box.minX + 5, y: box.minY + 2), withAttributes: attributes)
  }
}

// Accessibility reports a frame whose origin is the top-left of the primary
// display with y growing DOWN; AppKit windows are placed from the bottom-left
// with y growing UP. Getting this backwards puts the marker on the wrong half
// of the screen, and on a single-display machine it is wrong by exactly the
// amount that looks plausible.
func screenFrame(fromAccessibility frame: CGRect) -> NSRect {
  guard let primary = NSScreen.screens.first else { return frame }
  return NSRect(x: frame.origin.x,
                y: primary.frame.maxY - frame.origin.y - frame.size.height,
                width: frame.size.width, height: frame.size.height)
}

var overlayPanel: NSPanel?

func showOverlay(_ frame: CGRect, label: String) {
  guard frame.size.width > 0, frame.size.height > 0 else { return }
  let app = NSApplication.shared
  // Accessory, so this helper never gets a Dock tile or a menu bar of its own.
  app.setActivationPolicy(.accessory)
  let panel = NSPanel(contentRect: screenFrame(fromAccessibility: frame),
                      styleMask: [.borderless, .nonactivatingPanel],
                      backing: .buffered, defer: false)
  panel.isOpaque = false
  panel.backgroundColor = .clear
  panel.hasShadow = false
  panel.ignoresMouseEvents = true
  panel.level = .statusBar
  panel.collectionBehavior = [.canJoinAllSpaces, .ignoresCycle, .fullScreenAuxiliary,
                              .stationary]
  let view = OverlayView(frame: NSRect(origin: .zero, size: frame.size))
  view.label = label
  panel.contentView = view
  // orderFrontRegardless, never makeKeyAndOrderFront: the second would take the
  // key window from whoever has it, which is the entire thing this helper does
  // not do.
  panel.orderFrontRegardless()
  overlayPanel = panel
}

func holdOverlay(_ milliseconds: Int) {
  guard overlayPanel != nil else { return }
  let deadline = Date().addingTimeInterval(Double(max(0, milliseconds)) / 1000.0)
  // A run loop rather than sleep: the panel has to draw, and a sleeping process
  // never services the display.
  RunLoop.current.run(until: deadline)
  overlayPanel?.orderOut(nil)
  overlayPanel = nil
}

func overlayMilliseconds() -> Int? {
  guard let raw = option("overlay") else { return nil }
  return Int(raw) ?? 900
}

// ── what to capture ─────────────────────────────────────────────────────

// Finding the target window turned out to need BOTH APIs, because neither one
// answers on its own.
//
// CGWindowList knows window ids, which is what `screencapture -l` takes, but
// measured 2026-08-19 the only layer-0 entries this application owns are eight
// menu-bar strips (1470x33 and 1280x30) — its actual 430x860 window is not
// among them, while accessibility reports it without difficulty.
//
// Accessibility knows the window and its frame but has no window id; the call
// that would give one, _AXUIElementGetWindow, is undocumented SPI, and this
// helper exists precisely because the documented path was worth the work.
//
// So: take the frame from accessibility, and look for a CGWindowList entry that
// matches it. A match gives `screencapture -l`, which captures the window even
// when something overlaps it. No match still gives `screencapture -R` on the
// frame, which captures the rectangle — including anything on top of it. The
// caller is told which one it got, because those are different pictures.
func captureTarget(_ target: Target) -> [String: Any] {
  let app = AXUIElementCreateApplication(target.pid)
  var window: AXUIElement?
  for attribute in [kAXMainWindowAttribute as String, kAXFocusedWindowAttribute as String] {
    if let value = copyAttribute(app, attribute) { window = (value as! AXUIElement); break }
  }
  if window == nil {
    window = (copyAttribute(app, kAXWindowsAttribute as String) as? [AXUIElement])?.first
  }
  guard let window = window,
        let origin = pointAttribute(window, kAXPositionAttribute as String),
        let size = sizeAttribute(window, kAXSizeAttribute as String) else {
    fail("no-window", "\(target.name) publishes no window to capture.")
  }
  let frame = CGRect(origin: origin, size: size)
  var body: [String: Any] = [
    "ok": true,
    "application": target.name,
    "frame": [Int(frame.origin.x), Int(frame.origin.y),
              Int(frame.size.width), Int(frame.size.height)],
    "match": "region",
    "onscreen": false,
  ]
  if let title = stringAttribute(window, kAXTitleAttribute as String), !title.isEmpty {
    body["title"] = title
  }
  for entry in windowIds(target) {
    guard let bounds = entry["bounds"] as? [String: Any],
          let x = (bounds["X"] as? NSNumber)?.doubleValue,
          let y = (bounds["Y"] as? NSNumber)?.doubleValue,
          let w = (bounds["Width"] as? NSNumber)?.doubleValue,
          let h = (bounds["Height"] as? NSNumber)?.doubleValue else { continue }
    // Two points of slack: window shadows and the AX frame disagree by a pixel
    // on some hosts, and an exact comparison would silently fall back to the
    // weaker capture for a window that matched perfectly well.
    if abs(x - frame.origin.x) <= 2, abs(y - frame.origin.y) <= 2,
       abs(w - frame.size.width) <= 2, abs(h - frame.size.height) <= 2 {
      body["window-id"] = entry["window-id"]
      body["match"] = "window-id"
      body["onscreen"] = entry["onscreen"] ?? false
      break
    }
  }
  return body
}

// ── commands ────────────────────────────────────────────────────────────

switch command {

case "permissions":
  // Reported, never prompted for. The caller decides whether to ask a person.
  emit(["ok": true,
        "accessibility": accessibilityTrusted(),
        "screen-recording": CGPreflightScreenCaptureAccess()])

case "apps":
  let running = NSWorkspace.shared.runningApplications
    .filter { $0.activationPolicy == .regular }
    .compactMap { app -> [String: Any]? in
      guard let name = app.localizedName else { return nil }
      var body: [String: Any] = ["name": name, "pid": app.processIdentifier]
      if let bundle = app.bundleIdentifier { body["bundle-id"] = bundle }
      return body
    }
  emit(["ok": true, "applications": running])

case "tree":
  requireTrust()
  let target = resolveTarget(required("app"))
  let limit = Int(option("max") ?? "") ?? maxNodesDefault
  let includeMenu = option("include-menu") == "true"
  let nodes = walk(target, limit: max(1, min(limit, 2000)), includeMenu: includeMenu)
  emit(["ok": true,
        "application": target.name,
        "pid": target.pid,
        "digest": digest(nodes),
        "truncated": nodes.count >= max(1, min(limit, 2000)),
        "windows": windowIds(target),
        "nodes": nodes.map(describe)])

case "press":
  requireTrust()
  let target = resolveTarget(required("app"))
  let includeMenu = option("include-menu") == "true"
  let nodes = walk(target, limit: maxNodesDefault, includeMenu: includeMenu)
  requireUnchanged(nodes)
  let chosen = node(nodes, ref: required("ref"))
  // AXPress is the focus-free click: the element performs its action where it
  // stands. No cursor, no raise, and no coordinate that could land on whatever
  // moved into that spot.
  let wanted = option("action") ?? (kAXPressAction as String)
  guard chosen.actions.contains(wanted) else {
    fail("action-unavailable",
         "\(chosen.ref) (\(chosen.role)) does not offer \(wanted).",
         ["actions": chosen.actions])
  }
  // Shown BEFORE the action, so a screen recording has a frame with the marker
  // over the element that is about to change rather than one that already has.
  if let ms = overlayMilliseconds(), let frame = chosen.frame {
    showOverlay(frame, label: "\(wanted) \(chosen.ref)")
    let status = AXUIElementPerformAction(chosen.element, wanted as CFString)
    holdOverlay(ms)
    if status != .success {
      fail("action-failed", "\(wanted) on \(chosen.ref) failed.",
           ["status": status.rawValue])
    }
    emit(["ok": true, "application": target.name, "ref": chosen.ref,
          "action": wanted, "role": chosen.role, "overlay": ms])
  }
  let status = AXUIElementPerformAction(chosen.element, wanted as CFString)
  if status != .success {
    fail("action-failed", "\(wanted) on \(chosen.ref) failed.",
         ["status": status.rawValue])
  }
  emit(["ok": true, "application": target.name, "ref": chosen.ref,
        "action": wanted, "role": chosen.role])

case "set-value":
  requireTrust()
  let target = resolveTarget(required("app"))
  let includeMenu = option("include-menu") == "true"
  let text = required("text")
  let nodes = walk(target, limit: maxNodesDefault, includeMenu: includeMenu)
  requireUnchanged(nodes)
  let chosen = node(nodes, ref: required("ref"))
  var settable: DarwinBoolean = false
  AXUIElementIsAttributeSettable(chosen.element, kAXValueAttribute as CFString, &settable)
  guard settable.boolValue else {
    // Fail closed rather than falling back. The only fallback available was
    // synthesised keys, and those are measured not to work from the background;
    // offering it would turn "this element cannot be written" into a silent
    // no-op reported as success. A caller that needs a contenteditable or a
    // terminal has to use the isolated browser, or ask a person.
    fail("value-not-settable",
         "\(chosen.ref) (\(chosen.role)) does not accept a value. There is no focus-free fallback for this element.",
         ["actions": chosen.actions])
  }
  let status = AXUIElementSetAttributeValue(chosen.element,
                                            kAXValueAttribute as CFString,
                                            text as CFTypeRef)
  if status != .success {
    fail("set-value-failed", "Writing \(chosen.ref) failed.", ["status": status.rawValue])
  }
  // Read back. Reporting the observed value rather than "ok" is what lets a
  // caller see the one case this API does not cover: several document apps
  // accept the write into the widget WITHOUT marking the document edited, so
  // the text is on screen and Save stays disabled. Measured on TextEdit.
  let observed = stringAttribute(chosen.element, kAXValueAttribute as String)
  emit(["ok": true, "application": target.name, "ref": chosen.ref,
        "written": text.count,
        "observed": observed ?? NSNull(),
        "verified": observed == text])

case "scroll":
  requireTrust()
  let target = resolveTarget(required("app"))
  let includeMenu = option("include-menu") == "true"
  let nodes = walk(target, limit: maxNodesDefault, includeMenu: includeMenu)
  requireUnchanged(nodes)
  let chosen = node(nodes, ref: required("ref"))
  let direction = required("direction")
  guard direction == "up" || direction == "down" else {
    fail("bad-argument", "--direction must be up or down")
  }
  // An accessibility action, like every other write here — not a scroll-wheel
  // event, which would be dropped for the same reason a keystroke is.
  let action = direction == "up" ? "AXScrollUpByPage" : "AXScrollDownByPage"
  guard chosen.actions.contains(action) else {
    fail("action-unavailable",
         "\(chosen.ref) (\(chosen.role)) does not offer \(action). Scroll the AXScrollArea, not its contents.",
         ["actions": chosen.actions])
  }
  let status = AXUIElementPerformAction(chosen.element, action as CFString)
  if status != .success {
    fail("action-failed", "\(action) on \(chosen.ref) failed.", ["status": status.rawValue])
  }
  emit(["ok": true, "application": target.name, "ref": chosen.ref, "action": action])

case "windows":
  let target = resolveTarget(required("app"))
  emit(["ok": true, "application": target.name, "pid": target.pid,
        "windows": windowIds(target)])

case "capture-target":
  requireTrust()
  emit(captureTarget(resolveTarget(required("app"))))

case "overlay":
  // Marking without acting. Useful on its own: "this is what I am about to
  // touch" is a thing a person can be shown before an approval, and it is also
  // how the marker itself is tested without changing anybody's application.
  requireTrust()
  let target = resolveTarget(required("app"))
  let includeMenu = option("include-menu") == "true"
  let nodes = walk(target, limit: maxNodesDefault, includeMenu: includeMenu)
  let chosen = node(nodes, ref: required("ref"))
  guard let frame = chosen.frame else {
    fail("no-frame", "\(chosen.ref) (\(chosen.role)) reports no frame to mark.")
  }
  let ms = Int(option("ms") ?? "") ?? 900
  showOverlay(frame, label: option("label") ?? chosen.ref)
  holdOverlay(ms)
  emit(["ok": true, "application": target.name, "ref": chosen.ref,
        "frame": [Int(frame.origin.x), Int(frame.origin.y),
                  Int(frame.size.width), Int(frame.size.height)],
        "milliseconds": ms])

case "menu":
  requireTrust()
  let target = resolveTarget(required("app"))
  let limit = Int(option("max") ?? "") ?? 600
  let entries = menuEntries(target, limit: max(1, min(limit, 4000)))
  let filter = option("contains")
  let shown = entries.filter { filter == nil || $0.path.localizedCaseInsensitiveContains(filter!) }
  emit(["ok": true, "application": target.name,
        "items": shown.map { entry -> [String: Any] in
          var body: [String: Any] = ["path": entry.path, "enabled": entry.enabled]
          if let s = entry.shortcut { body["shortcut"] = s }
          return body
        }])

case "menu-press":
  requireTrust()
  let target = resolveTarget(required("app"))
  let wanted = required("path")
  let entries = menuEntries(target, limit: 4000)
  guard let entry = entries.first(where: { $0.path == wanted }) else {
    let near = entries.map { $0.path }
      .filter { $0.localizedCaseInsensitiveContains(wanted.split(separator: ">").last.map(String.init) ?? wanted) }
    fail("menu-item-not-found", "No menu item at \(wanted).", ["near": Array(near.prefix(20))])
  }
  guard entry.enabled else {
    fail("menu-item-disabled", "\(wanted) is disabled right now.")
  }
  // A closed menu's items report a zero frame, so there is nothing to outline
  // where the command lives. The window it acts on is the honest thing to mark.
  var overlayShown = false
  if let ms = overlayMilliseconds() {
    let body = captureTarget(target)
    if let f = body["frame"] as? [Int], f.count == 4 {
      showOverlay(CGRect(x: f[0], y: f[1], width: f[2], height: f[3]),
                  label: entry.path)
      overlayShown = true
      let status = AXUIElementPerformAction(entry.element, kAXPressAction as CFString)
      holdOverlay(ms)
      if status != .success {
        fail("action-failed", "Pressing \(wanted) failed.", ["status": status.rawValue])
      }
      emit(["ok": true, "application": target.name, "path": entry.path, "overlay": ms])
    }
  }
  _ = overlayShown
  let status = AXUIElementPerformAction(entry.element, kAXPressAction as CFString)
  if status != .success {
    fail("action-failed", "Pressing \(wanted) failed.", ["status": status.rawValue])
  }
  emit(["ok": true, "application": target.name, "path": entry.path])

default:
  fail("unknown-command", "Unknown command \(command).")
}
