# UI design prompt — Upstream sync run view

Paste everything below the line into the ByteQuay design-companion chat. Attach alongside it:

- `docs/intermediate/ci-autofix-design.md` — the integration decisions. **Read "The
  upstream sync run" first**: the program/agent split was inverted 2026-08-05 and this
  surface renders the new one.
- `docs/intermediate/ci-autofix-harness.md` — the generic engine design, canonical except
  where the note above overrides it

---

I want to design a new ByteQuay surface: the **upstream sync run view** — the live cockpit
for one upstream cherry-pick job, from the first pick through to a green PR parked for my
review.

Read the attached engine design first. Don't re-open engine or architecture decisions; this
session is about the surface. One thing to know up front: the flow below is **being built
now**, so nothing here is constrained by an existing screen. The only UI that exists today
is a small modal that shows a status line and a resume button — assume it's replaced.

## What the feature does, in one story

My fork tracks an OSS upstream and has drifted. I select a range of upstream commits, the
app tells me which ones are already in my fork and drops them, and I confirm. From there it
runs unattended for a long time — often hours — and this surface is where I watch it.

**Phase 1 — picking.** It cherry-picks into an isolated worktree, one commit at a time.
Conflicts are the *expected* case, not the exception. On a conflict it commits git's own
conflicted resolution, then an **agent proposes edits** which the program applies as a
`fixup!` for that pick. It compiles — scoped to the module the commit touched, tests
skipped, using a build command learned from my CI config. Red → the agent tries again,
bounded attempts. Still red → **the job parks and waits for me; nothing is pushed.** Green
→ next commit. When the range finishes it pushes once, opens a draft PR, and starts a CI
harness watch on it.

*Fallback:* if the local compile can't run **at all** — no toolchain, internal repo
unreachable, credentials — that's not a red gate. The job switches to remote mode: pushes,
opens the PR early, and takes the compile verdict from CI logs instead for the rest of the
range. The switch is sticky. **This mode change is user-visible and matters** — my
expectations about speed, cost, and what "verified" means all change with it.

**Phase 2 — driving CI green.** Once the last commit has landed, the harness loops on the
PR: fetch failed-job logs, parse, classify, diagnose root cause, propose a fix, verify it,
commit it. Each verified fix becomes a `fixup!` positioned **directly after the cherry-pick
that owns it — never absorbed into it**, so every pick stays independently reviewable. One
fixup per target: a second failure owned by the same pick squashes into that pick's existing
fixup. It pushes, CI re-runs, and it loops until green. Then it parks and waits for my
review.

## What I asked for, explicitly

I want to see, at a glance and continuously:

1. **What the agent is doing right now** — not a spinner, an actual current activity.
2. **Its main conversation window** — the agent's reasoning as it works, readable as a
   running narrative, not raw JSON.
3. **Every command and tool it executes** — the literal `git cherry-pick …`, the compile
   invocation, the log fetch — with exit status and enough output to judge it.
4. **PR status on the right side** — the draft PR, its CI run state, what's red.
5. **Which commits it is processing**, and **the waiting list of commits queued to be
   picked next**.

My instinct is three regions: commit queue on the left (done / current / waiting), agent
conversation plus command log in the centre, PR status on the right. **Challenge that if
you have something better** — in particular, whether the command log belongs interleaved
in the conversation or in its own column, and what happens to the PR column during phase 1
when there is no PR yet.

## Surfaces and states to design (please render, not just describe)

1. **Run view, phase 1, mid-range.** Some commits applied, some skipped as already-present,
   one in flight, a long queue below. Show the conversation and command log during a normal
   clean pick — most picks have no conflict and the surface shouldn't look dramatic.
2. **Run view, phase 1, agent repairing a conflict.** The most information-dense moment:
   conflicted files, the agent's proposed edits, the fixup it produced, the compile
   command and its red output, attempt N of M. This is the state I'll stare at most.
3. **Parked on an unrepairable conflict.** Bounded attempts exhausted. What I need to take
   over by hand, where the worktree is, and how I hand it back.
4. **Fallback to remote mode.** The local compile couldn't run. Make the reason and the
   consequence legible — this is a mode change, not an error.
5. **Phase 1 → phase 2 handover.** Range complete, pushed, draft PR opened, watch created.
   A satisfying transition; the surface's centre of gravity moves to the right column.
6. **Run view, phase 2.** Harness looping on CI failures. This overlaps the existing
   harness dashboard — **read the sibling prompt and decide the boundary**: does this view
   embed a compact form of the failure table, or hand off to that surface entirely? Propose
   an answer rather than duplicating it.
7. **Green and parked for review.** The terminal state. What did it do across hours — how
   many picks, how many conflicts repaired, how many CI failures fixed, what history it
   wrote — and what I'm being asked to approve.
8. **Long-run / away-from-desk.** I will not watch this for three hours. What does coming
   back to it look like — is there a digest, a jump-to-what-changed, an unread marker?

## Constraints the UI must reflect

- **The agent proposes; the program applies, verifies, and commits.** Never render an edit
  as "the agent changed your files". Verification state is first-class, not a footnote.
- **Conflicts are normal.** A conflicted pick is routine progress, not a red error state.
  Reserve alarm styling for the park.
- **Nothing is pushed while a commit is parked unrepaired** — and the UI must never imply
  otherwise.
- **History mutations are guarded** by backups and a net-neutrality proof. This tool
  rewrites history on my real checkout; make that visibly safe, because it is.
- **The fixup history model is a promise to the reviewer**: fixups sit next to their target
  pick, never inside it. If you can show that shape — the commit list with fixups adjacent
  to their owners — it's worth screen space.
- A range can be **hundreds of commits**. The queue needs to work at that length: no
  unbounded list, and "done" shouldn't crowd out "next".
- Cost and elapsed time are real (agent turns have a budget). Surface them without making
  them the headline.

## Naming

"Upstream sync run" is my working label, not a decision. The surface sits near the existing
Commits page and the CI Harness surface — propose naming that makes the three legible as a
family.

## Process + deliverables

Work the way we usually do: start with the phase-1 mid-range view, iterate as rendered HTML
mockups, ask me questions where the flow is ambiguous (conversation density, how much
command output to show inline vs collapse, whether the queue is scannable or navigable).
When we converge:

- Rendered PNGs per surface/state above → I'll save them to
  `docs/mockups/design/upstream-sync/` with predictable kebab-case names
  (`run-phase1-midrange.png`, `run-phase1-conflict-repair.png`, `run-parked.png`,
  `run-fallback-remote.png`, `run-handover.png`, `run-phase2.png`, `run-green-parked.png`,
  `run-digest.png`).
- Keep the HTML sources → `docs/mockups/design/upstream-sync/_src/`, so the build prompt
  can reference copy, hover states, and inline styles the PNGs can't carry.
