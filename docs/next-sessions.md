# Sessions

The step sequence and status now live in [the roadmap](roadmap.md). GitHub issues
own ticket scope. The earlier session log (A–F, coordinator dispositions) is in
git history before the commit that introduced `roadmap.md`.

## Starting a session

Read `docs/roadmap.md`, pick the current step, and read any issue it names with
`gh issue view <n> --comments`. Work only that step's scope. Update the step's
status line before finishing.

## Handoff

End with: baseline and result commits, checks run, ticket changes, open questions,
and the next step. A human gate is a product or UX decision, not a request for the
human to write code. Merges to `main` are the user's.
