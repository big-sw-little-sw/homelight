import re, sys
S = "/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad/"
def text(name):
    raw = open(S + name, "rb").read().decode("utf-8", "replace")
    raw = raw.replace(S, "S/").replace("tui-jvm-", "tui-X-").replace("tui-native-", "tui-X-").replace("tui-agent-", "tui-X-")
    return raw
for mode in sys.argv[1:]:
    j, n = text(f"tui-jvm-{mode}.log"), text(f"tui-native-{mode}.log")
    strip = lambda s: re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", s)
    words = lambda s: sorted(set(re.findall(r"[A-Za-z][A-Za-z:\-]{3,}", strip(s))))
    wj, wn = words(j), words(n)
    print(mode, "jvm bytes", len(j), "native bytes", len(n))
    print("  only jvm:", [w for w in wj if w not in wn][:30])
    print("  only native:", [w for w in wn if w not in wj][:30])
    print("  native first 200:", repr(n[:200]))
