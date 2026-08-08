#!/usr/bin/env bash
# build-icons.sh — regenerate the macOS iconset and icns file from
# assets/logo.svg using a tiny Cocoa-based renderer that preserves
# alpha (qlmanage flattens transparency to opaque white, which broke
# the dock display — see commit history). Run from the repo root.
#
# Re-run this whenever assets/logo.svg changes.
#
# Requires macOS (Swift + iconutil are built in).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVG="$ROOT/assets/logo.svg"
ICONSET_DIR="$ROOT/build/icon.iconset"
RENDERER="$(mktemp -t svg2png).swift"

if [[ ! -f "$SVG" ]]; then
  echo "[build-icons] missing $SVG"; exit 1
fi

cat > "$RENDERER" <<'SWIFT'
import Cocoa

guard CommandLine.arguments.count == 4,
      let size = Int(CommandLine.arguments[3]) else {
    print("usage: svg2png input.svg output.png size"); exit(1)
}
let inURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outURL = URL(fileURLWithPath: CommandLine.arguments[2])
guard let data = try? Data(contentsOf: inURL),
      let img = NSImage(data: data) else {
    print("Cannot load \(inURL.path)"); exit(1)
}
let target = NSSize(width: size, height: size)
guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil,
                                  pixelsWide: size, pixelsHigh: size,
                                  bitsPerSample: 8, samplesPerPixel: 4,
                                  hasAlpha: true, isPlanar: false,
                                  colorSpaceName: .deviceRGB,
                                  bytesPerRow: 0, bitsPerPixel: 32) else { exit(1) }
rep.size = target
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.clear.set()
NSRect(origin: .zero, size: target).fill()
img.draw(in: NSRect(origin: .zero, size: target),
         from: .zero, operation: .sourceOver, fraction: 1.0)
NSGraphicsContext.restoreGraphicsState()
guard let pngData = rep.representation(using: .png, properties: [:]) else { exit(1) }
try pngData.write(to: outURL)
SWIFT

mkdir -p "$ICONSET_DIR"

echo "[build-icons] populating icon.iconset/"
for s in 16 32 128 256 512; do
  swift "$RENDERER" "$SVG" "$ICONSET_DIR/icon_${s}x${s}.png" "$s"
  swift "$RENDERER" "$SVG" "$ICONSET_DIR/icon_${s}x${s}@2x.png" "$((s*2))"
done

echo "[build-icons] bundling icon.icns"
iconutil -c icns "$ICONSET_DIR" -o "$ROOT/build/icon.icns"

rm "$RENDERER"
echo "[build-icons] done — restart the app to see the new icon"
