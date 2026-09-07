# Prompt: implement the Agent review tab

Implement ByteQuay's **Agent review** PR tab. Behaviour is locked in `docs/design/agent-review.md`; delivery is tracked in `docs/design/agent-review-impl-tmp.md`. Read `ByteQuay.md`, `CONTRIBUTING.md`, both design docs and the approved UI reference before writing code.

## UI is the source of truth

The approved prototype is `Agent Review Prototype.dc.html` + `AgentReviewPane.dc.html` (plus `screenshots/`). Match it: layout, copy, chips, states, action placement, private-vs-outgoing treatment. Where the prototype and the design doc disagree on *behaviour*, the design doc wins; where they disagree on *appearance or wording*, the prototype wins. If a detail is genuinely ambiguous or impossible with the real components, edit the prototype first (keep it consistent), note the change in the PR description, then implement it — don't silently invent UI.

## Scope: touch only what Agent review needs

Add, don't rewrite. Reuse the existing components as they are; a change to a shared file is allowed only when Agent review cannot work without it, and then it must be additive (a new prop, a new export, a new optional slot) with existing callers unchanged.

Do NOT modify the appearance or behaviour of:
- left navigation rail;
- PR list and its cards/filters/tabs;
- PR details header, title/branch/meta pills, description card, GitHub timeline, reviews, checks, merge UI;
- the Changes diff renderer (except the additive navigate-to-range hook and mounting the finding card at its line — see below);
- the agent task transcript renderer, tool/command rows, approval card, queue rows and the task composer/input box (reuse them as-is from `AgentTaskScreen`; Agent review passes data and callbacks in);
- shared CSS tokens, button variants, Markdown rendering, icons.

Required additive changes to existing code (and only these):
1. `PullsScreen`: a third tab **Agent review** beside PR # and Changes, with the optional derived status chip (spinner while running). Same tab component, same styles.
2. `PullChanges`: (a) an exported/injectable "navigate to range" target (open file, unfold, scroll, highlight `R118–R131`-style ranges, or the recorded snapshot for outdated findings); (b) a mount point rendering the finding card at a validated end line, using the existing inline-comment card shell.
3. Review-comment card, composer (Write/Preview), and transcript pieces: extract a *presentational* piece only when both the PR page and Agent review render it; pass handlers in. Never wire private actions (Ask agent, Resolve locally, Reopen, Add to my review) to the GitHub Reply/Resolve/Submit callbacks.
4. Submit review: count and include promoted finding items from the durable draft; keep manual comments working; new drafts default to COMMENT.

Everything else is new code under the review feature (e.g. `frontend/src/pulls/agent-review/…`, backend review service/storage per the design's module direction).

## What to build (from the prototype)

- **Tab + status strip**: Summary & comments (default) / Activity segmented control; status text + detail; Stop / Resume / Update review / End review by state.
- **States**: not started (Start review, private-availability note), running (provisional summary, `not yet read` commits, provisional finding, publishing blocked), queued question, approval needed (banner in Summary mirrors the Activity approval card), ready with findings, explicit no findings, incomplete/error (no summary, rejected candidate visible in Activity), resolved locally (reason + Reopen), draft added (Pending · in your review, outgoing text shown inside the card — no second pending clone), published (remote link, thread stays private), new commits / outdated (banner + Outdated pill, not publishable), stopped.
- **Summary card**: same card shell as the PR description; Private + Provisional chips; purpose, commits (sha · message · explanation), combined result, critical issues linking to findings, limitations; private discussion fold with Ask agent composer.
- **Finding card**: chevron fold, ellipsized path + range, severity, id · rev, body, evidence fold (source/provenance/excerpt/why), private thread with Queued chip, composer; actions row: Ask agent · Resolve locally · View in Changes | Add to my review · Post comment now (green = GitHub-bound only). In **Changes**, the same finding shows the same thread and the same actions (Ask agent, Resolve locally, Add to my review, Post comment now, Open in Agent review). One finding ID, one discussion, two projections.
- **Activity**: existing transcript components; first bubble labelled *ByteQuay · Review request*; **no per-row durations on log/tool rows**; Stop, Steer now (only when the provider lane supports it, otherwise "Steer unavailable"), Run next; Ask agent queues visibly while busy.
- Private content is never published automatically; Post/Submit go through the existing GitHub write path with re-validation.

## Verification before you claim done

- Screenshots side by side with `screenshots/normal-854-*.jpg` and `screenshots/narrow-600-*.jpg` at the same pane widths; keyboard-only pass (tabs, folds, composer Esc/⌘Enter, focus rings); long paths/Markdown; every state above.
- `git diff --stat` shows no changes to the excluded components beyond the additive hooks listed. Existing PR/Changes/Tasks tests still pass; add tests for tab navigation, shared finding state across the two projections, private actions with a GitHub write-client spy asserting zero calls, and publication gating.
- Run the checks in `CONTRIBUTING.md` (`npm run lint`, `npx tsc --noEmit`, `npm test`; `mvn verify` for backend changes). Update the checkpoint tracker with evidence; do not check a box without a passing test.
