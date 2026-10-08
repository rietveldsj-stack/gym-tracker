"""Writes the app icons, a dumbbell in each theme's colours, as PNGs. Run: python3 tools/make_icons.py

Blue is the default and the manifest's icon. Pink and Red & black only get a home-screen icon: the app offers it while
that theme is on, so adding the app to the Home Screen then gives the matching icon."""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/static/icons"
WHITE = (255, 255, 255)
# theme: (background, dumbbell, file suffix, sizes)
THEMES = {
    "blue": ((42, 120, 214), WHITE, "", [("icon-192.png", 192), ("icon-512.png", 512), ("apple-touch-icon.png", 180)]),  # #2a78d6
    "pink": ((194, 24, 91), WHITE, "-pink", [("apple-touch-icon.png", 180)]),  # #c2185b
    "redblack": ((214, 31, 44), (11, 11, 12), "-redblack", [("apple-touch-icon.png", 180)]),  # #d61f2c, #0b0b0c
}
# (left, top, right, bottom) as fractions of the icon size
SHAPES = [
    (0.20, 0.47, 0.80, 0.53),  # bar
    (0.26, 0.30, 0.34, 0.70), (0.36, 0.36, 0.42, 0.64),  # left plates
    (0.66, 0.30, 0.74, 0.70), (0.58, 0.36, 0.64, 0.64),  # right plates
]


def png(size, rows):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(row) for row in rows)
    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def icon(size, background, foreground):
    rows = []
    for y in range(size):
        row = bytearray()
        fy = (y + 0.5) / size
        for x in range(size):
            fx = (x + 0.5) / size
            inside = any(left <= fx <= right and top <= fy <= bottom for left, top, right, bottom in SHAPES)
            row += bytes(foreground if inside else background)
        rows.append(row)
    return png(size, rows)


OUT.mkdir(parents=True, exist_ok=True)
for background, foreground, suffix, files in THEMES.values():
    for name, size in files:
        path = OUT / name.replace(".png", suffix + ".png")
        path.write_bytes(icon(size, background, foreground))
        print("wrote", path)
