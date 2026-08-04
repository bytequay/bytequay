# UI design prompt — CI Autofix Harness surface

Paste everything below the line into the ByteQuay design-companion chat. Attach alongside it:

- `docs/intermediate/ci-autofix-harness.md` — the engine design (canonical; read it first)
- `docs/intermediate/ci-autofix-design.md` — the ByteQuay integration decisions
- optionally `docs/intermediate/ci-autofix-harness-component-specs.html` — component detail

---

I want to design the UI for a new ByteQuay workspace surface: the **CI Autofix Harness**.
The engine design is in the attached `ci-autofix-harness.md` — read it before proposing
anything; the integration decisions (already locked) are in `ci-autofix-design.md`. Don't
re-open engine or architecture decisions; this session is about the surface.

## What the feature does, in one story

I have a long version-bump PR on my internal fork (dozens of cherry-picks onto a fork that
tracks an OSS upstream; CI keeps going red at the seams). I register that PR as
a **watch** in the workspace that maps to the repo. The harness then loops: it polls the
PR's GitHub Actions run, downloads failed-job logs, parses them into typed failures,
classifies each against a per-repo learned knowledge base, and routes each one —
deterministic recipe, LLM diagnosis (advisory only), defer (infra/flake), or escalate to
me. Every applied fix is verified with the project's own CI commands, committed as a
**path-scoped `fixup!` targeting the cherry-pick that owns it**, then rebased into place
with a proof that the rebase changed nothing but attribution. The harness **never pushes**
— it ends each cycle with a handoff: "here's what I fixed, here's what needs you, here's
the push command." I review in my editor, push, and the next CI run starts the next cycle.

Over time the KB accretes: first occurrence of a failure is explored by the agent and
recorded as a **candidate** rule; I promote candidates to **active** (or K repeat hits do);
second occurrence is deterministic. So the surface is also where I supervise that learning.

## Where it lives

A new entry in the workspace nav (sibling of the existing workspace surfaces). It should
feel native to the app — same visual language as the pulls/tasks surfaces. Naming is open:
"CI Harness" / "Autofix" / something better — propose options.

## Surfaces to design

1. **Harness dashboard** (the home). The watched-PR card (PR title/number, CI run state,
   current loop phase, elapsed), a live failure table (normalized signature, module,
   bucket chip, route — recipe / agent / defer / escalated — and per-failure status:
   fixing → verifying → fixed / escalated / deferred), a compact loop-activity timeline
   (probe → parse → classify → fix → verify → commit → rebase events as they happen), and
   the **handoff banner** when a cycle ends: N fixups created, M escalations waiting, the
   copyable push hint, and the backup ref that guards the rewrite. Also the idle/empty
   state ("watch a PR" picker) and the all-green terminal state.

2. **Failure detail** (drawer or pane off the table). The log excerpt (~40 lines,
   monospace), what classified it (the matched rule, or UNKNOWN → agent), and — for
   agent-routed failures — the **diagnosis card**: root cause (one sentence), culprit
   cherry-pick, proposed edits as a diff preview, the fixup target's commit subject,
   confidence with its rubric breakdown, rationale, and the verify result. Actions where
   they apply: approve / send back / take over (dismiss to manual).

3. **Knowledge Base browser.** The per-repo rules table: matcher pattern, bucket, binding
   (recipe / agent / defer), status (candidate / active / retired), origin (bootstrap /
   agent / human), hit count, evidence. The **promotion gate** is the key interaction:
   reviewing a candidate rule (what it matched, the fix that worked) and promoting or
   retiring it. Recipes list alongside.

4. **Escalation queue.** Everything waiting on me across the watch, each with its reason
   (verify failed / low confidence / agent asked for human / divergence detected) and
   enough context to decide without leaving the app.

5. **Bootstrap view** ("what the harness derived"). Job topology with the aggregator
   jobs it will skip, secret/cloud-gated jobs it will defer, the VerifyProfile commands
   it extracted from CI, the module map, the upstream-link convention. This is a
   trust-building, mostly-read-only surface — I want to see that it read my repo right.

## States that matter (please render, not just describe)

- Dashboard mid-cycle: several failures in different states at once (one fixed, one
  verifying, one at the agent, one deferred INFRA, one escalated).
- Dashboard at handoff: fixups ready, net-neutral proof shown, push hint, backup ref.
- Failure detail with a full diagnosis card (high confidence) — and the low-confidence
  variant that landed in the escalation queue.
- KB with a candidate rule pending promotion next to seasoned active rules.
- First-run: bootstrap just completed, empty KB, everything routes UNKNOWN → agent.

## Constraints the UI must reflect (from the engine doc — non-negotiable)

- The harness **never pushes**; pushing is mine. The UI offers a copyable command, never
  a push button.
- The agent **proposes only** — its edits are always shown as proposals that the program
  applied/verified, never as "the agent changed your files".
- Every fix is verified before commit; verification state is first-class, not a footnote.
- History mutations are guarded: backups and the net-neutral proof deserve visible,
  reassuring presence (this tool rewrites history on my real checkout — the UI's job is
  to make that feel safe because it is).
- Buckets are the minimal universal core (style / build / test / resource / infra / flake
  / unknown) with learned sub-tags — chips should accommodate `resource:plan_mismatch`
  style refinements.

## Process + deliverables

Work the way we usually do: start with the dashboard, iterate as rendered HTML mockups,
ask me questions where the flow is ambiguous (e.g. how much diagnosis JSON to surface vs
collapse, timeline density, whether escalations belong inline or only in the queue).
When we converge:

- Rendered PNGs for each surface/state above → I'll save them to
  `docs/mockups/design/ci-autofix/` with predictable kebab-case names
  (`dashboard-mid-cycle.png`, `dashboard-handoff.png`, `failure-detail-diagnosis.png`,
  `kb-promotion.png`, `bootstrap-view.png`, `first-run.png`, …).
- Keep the HTML sources — I'll save them to `docs/mockups/design/ci-autofix/_src/` so the
  build prompt can reference copy, hover states, and inline styles the PNGs can't show.
