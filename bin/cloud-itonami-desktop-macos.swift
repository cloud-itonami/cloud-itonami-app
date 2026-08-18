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
  let insensitive = running.first {
    $0.localizedName?.compare(wanted, options: .caseInsensitive) == .orderedSame
  }
  guard let app = exact ?? byBundle ?? insensitive else {
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
func windowIds(_ target: Target) -> [[String: Any]] {
  let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
  let info = (CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]]) ?? []
  return info.compactMap { entry in
    guard let owner = entry[kCGWindowOwnerPID as String] as? pid_t, owner == target.pid,
          let number = entry[kCGWindowNumber as String] as? Int else { return nil }
    let layer = entry[kCGWindowLayer as String] as? Int ?? 0
    guard layer == 0 else { return nil }
    var body: [String: Any] = ["window-id": number, "layer": layer]
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
  let status = AXUIElementPerformAction(entry.element, kAXPressAction as CFString)
  if status != .success {
    fail("action-failed", "Pressing \(wanted) failed.", ["status": status.rawValue])
  }
  emit(["ok": true, "application": target.name, "path": entry.path])

default:
  fail("unknown-command", "Unknown command \(command).")
}
