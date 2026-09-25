# #31 final verification update

## Coordinator follow-up, pending publication approval

The three coordinator findings are addressed locally. Confirmed discard resets
the entire draft and reopening setup is fresh, while cancelled discard preserves
the draft. Blank, root-equal and root-escaping relative row paths are rejected
by both Validate and Save. Archive roots must be absolute when supplied.

`HomeLightAppTest` covers each invalid condition through validation and save,
then correction and successful first save, with configuration and relocation
paths unchanged by failed attempts. The targeted PTY driver passed discard →
reopen and invalid-input correction → save at 80×24 and 120×30, with resize in
both directions. Java 25.0.3 `mvn -o clean test` passed 165 tests with zero
failures, errors or skips; `git diff --check` passed.

Do not post this update until the coordinator grants publication approval.

Final table verification passed. The real-JLine PTY driver covers default and
explicit missing configurations, `init`, Locations → Relocations → Row details,
root editing with rows preserved, row addition/removal, detail-path access,
validation reset after edits, discard confirmation/cancellation, and save to
Workspace without relocation execution.

It passed at 80×24 and 120×30 with resize in both directions. Each session also
verified exit 0, alternate-screen and cursor restoration, and supported termios
restoration. The clean Java 25.0.3 suite passed 163 tests with zero failures,
errors or skips. `git diff --check` passed.

Evidence: [PTY driver](pty-check.py), [PTY transcript](pty-check.txt), and
[clean-suite log](full-suite.txt).

Stop here for coordinator review and human walkthrough. The proposed design
wording remains only in `session-d4-implementation-handoff.md`; do not move it
to `docs/tui-design.md` before human acceptance. No #32 work, commit or push.
