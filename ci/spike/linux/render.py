# Replays a tui-linux.exp log through a VT emulator (pyte), resizing at the @@RESIZE RxC@@ markers.
# Prints the non-blank bounding box of the screen at the end of each segment, to show whether the TUI redrew at the new size.
import re, sys, pyte
for path in sys.argv[1:]:
    data = open(path, "rb").read().decode("utf-8", "replace")
    parts = re.split(r"\n@@(RESIZE (\d+)x(\d+)|QUIT)@@\n", data)
    screen = pyte.Screen(120, 40); stream = pyte.Stream(screen)
    size = "40x120"; out = []
    i = 0
    stream.feed(parts[0])
    segs = [(size, parts[0])]
    rest = parts[1:]
    def bbox():
        rows = [r for r, line in enumerate(screen.display) if line.strip()]
        cols = [len(line.rstrip()) for line in screen.display]
        return f"rows_used={rows[-1]+1 if rows else 0} max_col={max(cols) if cols else 0}"
    out.append(f"{size}: {bbox()}")
    while rest:
        tag, r, c, text = rest[0], rest[1], rest[2], rest[3]; rest = rest[4:]
        if tag.startswith("RESIZE"):
            screen.resize(int(r), int(c)); size = f"{r}x{c}"
            stream.feed(text); out.append(f"{size}: {bbox()} bytes={len(text)}")
    print(path.split("/")[-1], " | ".join(out))
    if "-v" in sys.argv[0:1]: pass
