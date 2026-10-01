"""Replays a tui.exp log through a VT emulator (pyte) and prints the screen at each size.

    python3 ci/native/render.py <logfile> [ROWSxCOLS ...]

The log starts at 120x40; each "@@RESIZE <rows>x<cols>@@" marker resizes the emulated screen.
For each requested size (default: all), prints the screen as it stood at the end of that segment.
Exits 1 when a segment leaves the screen blank or drawn past its bounds, i.e. the TUI did not
redraw at the new size.
"""
import re
import sys

import pyte


def segments(data):
    """Yields (rows, cols, text) for the initial size and each resize segment."""
    parts = re.split(r"\n@@(?:RESIZE (\d+)x(\d+)|QUIT)@@\n", data)
    yield 40, 120, parts[0]
    for i in range(1, len(parts) - 2, 3):
        rows, cols, text = parts[i], parts[i + 1], parts[i + 2]
        if rows is None:  # QUIT: output after the last resize belongs to shutdown
            return
        yield int(rows), int(cols), text


def main(path, wanted):
    data = open(path, "rb").read().decode("utf-8", "replace")
    screen = pyte.Screen(120, 40)
    stream = pyte.Stream(screen)
    ok = True
    for rows, cols, text in segments(data):
        screen.resize(rows, cols)
        stream.feed(text)
        lines = [line.rstrip() for line in screen.display]
        used = [i for i, line in enumerate(lines) if line]
        # A full-screen TUI that redrew at this size reaches the last row.
        if not used or used[-1] != rows - 1:
            ok = False
            print(f"{rows}x{cols}: not redrawn (last used row {used[-1] + 1 if used else 0} of {rows})")
        size = f"{cols}x{rows}"
        if not wanted or size in wanted:
            print(f"--- {size}")
            print("\n".join(lines))
    return ok


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1], set(sys.argv[2:])) else 1)
