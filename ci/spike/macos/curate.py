import json
S = '/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad/'
d = json.load(open(S + 'agent-full-metadata.json'))
keep_types = ('io.github.bigswlittlesw.homelight.config.', 'io.smallrye.config._private.', 'dev.tamboui.',
              'sun.misc.Signal', 'java.lang.System', 'java.lang.Boolean')
refl = [e for e in d['reflection'] if isinstance(e.get('type'), str) and e['type'].startswith(keep_types)]
refl += [e for e in d['reflection'] if isinstance(e.get('type'), dict)]
res = [r for r in d['resources'] if r.get('glob') == 'META-INF/services/dev.tamboui.terminal.BackendProvider']
# One glob so future binding sets are covered too.
res.append({'glob': 'dev/tamboui/tui/bindings/*.properties'})
out = {'reflection': refl, 'resources': res}
p = '/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca/src/main/resources/META-INF/native-image/io.github.bigswlittlesw/homelight/reachability-metadata.json'
json.dump(out, open(p, 'w'), indent=2)
print(len(refl), 'reflection', len(res), 'resources')
for e in refl: print(' ', e['type'])
for r in res: print(' ', r)
