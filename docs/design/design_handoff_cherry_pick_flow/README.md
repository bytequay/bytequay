# Handoff: Workspaces & Cherry-pick Flow (ByteQuay)

## Overview
UI design for ByteQuay's workspace system and its cherry-pick feature: a workspaces list, a per-workspace shell (left nav with Home / Tasks / Pull requests / Issues / Commits / Memory / Settings), and — inside the **Commits** tab — a GitHub-Desktop-style commits browser with a full cherry-pick pipeline: browse a read-only linked upstream repo, select a contiguous commit range, review/confirm a cherry-pick plan (with PR title/description), and watch an agent-driven execution with live logs and conflict resolution.

## About the Design Files
The files in this bundle are **design references created in HTML** (Design Component prototypes) — they show intended look and behavior, not production code to copy directly. The task is to **recreate these designs in the target codebase's existing environment** (the user's macOS desktop app stack) using its established patterns and libraries. If no UI framework is settled yet, choose the most appropriate one for a macOS desktop app and implement the designs there. The prototypes use mock data throughout; wire real Git/GitHub data where noted.

## Fidelity
**High-fidelity.** Colors, spacing, and typography follow GitHub's visual language (Primer-like palette) for content areas, and a native macOS sidebar style for the shell. Recreate closely, but prefer the codebase's existing token/component system where one exists.

## Files
- `Workspaces.dc.html` — workspaces list + workspace shell (left nav, all tabs). The Commits tab embeds the second file.
- `Cherry Pick Flow.dc.html` — the whole commits/cherry-pick feature: History/Changes/Cherry-pick tabs, range confirm page, task progress page.
- `support.js` — prototype runtime only; ignore for implementation.

Open either `.dc.html` in a browser to interact. In `Cherry Pick Flow.dc.html`, demo knobs exist as tweakable props: `initialScreen` (commits/flow/progress), `progressState` (running/paused/finished), `embedded`.

## Screens / Views

### 1. Workspaces list (`Workspaces.dc.html`, initialView=workspaces)
- Purpose: entry point; each card = one local checkout.
- Layout: macOS sidebar (230px, #f7f7f8, traffic lights, "Workspaces" item selected, Settings pinned bottom) + main area (padding 30/36px).
- Header row: "Workspaces" (22px/700) · "N local checkouts" (13px #8c959f) · "+ Add workspace" button (right).
- Card grid: `repeat(auto-fill, minmax(320px,1fr))`, gap 16px. Card: 1px #e3e3e6 border, radius 12, padding 18px; name (15px/700) + optional upstream chip (`↞ trinodb/trino`, 10.5px/600, #9a6700 on #fff8c5, pill); mono path (11.5px #8c959f); stats row (open PRs / tasks / issues, 12.5px); footer: activity dot + last-activity text, separated by top border #f0f0f2. Hover: border #b6b6bc + shadow `0 4px 14px rgba(27,31,36,.08)`.
- Click card → workspace shell.

### 2. Workspace shell (initialView=workspace)
- Sidebar adds: "‹ Workspaces" back link (12px #8c959f), workspace name (14px/700), then nav items (icon 18px column + 14px label, selected row = #e4e4e8 bg, radius 8): Home, Tasks (badge 2), Pull requests (badge 4), Issues (badge 7), Commits, Memory; Settings pinned at bottom above border #ececef.
- Tabs Home/Tasks/PRs/Issues/Memory/Settings are standard list/detail pages (see prototype for exact content and styling: stat cards, activity feed, task list with state chips, grouped PR lists, issue list, memory note cards, settings form with linked-upstream section and danger zone).
- **Commits tab** renders the entire Cherry Pick Flow design full-bleed (no extra chrome).

### 3. Commits — History view (default; `Cherry Pick Flow.dc.html` screen=commits)
- Top tab bar (all commits screens share it, identical buttons — no size shifts between views):
  - `⑂ master ▾` chip — the **workspace branch**; active = #0969da bg/white. Click when active opens branch menu ("SWITCH BRANCH"; on upstream: "TARGET BRANCH — bytequay/trino-fork").
  - `↞ trinodb/trino · master [read-only] ▾` chip — the **linked upstream repo** (read-only source). Click switches source; click again opens branch dropdown with a **search box** (`⌕ Find an upstream branch…`) filtering the branch list live.
  - `⟳` sync icon button (28×28, 1px #d0d7de, radius 6) + "synced 2 min ago" label (11px #8c959f). Click: spinner (1s rotate) + "syncing…" → "synced just now". Fetches both the workspace branch and upstream.
  - Divider, then underline tabs: **Changes** (with count badge; disabled at 45% opacity on upstream), **History**, **Cherry pick**. Active: #1f2328 text + 2px #fd8c73 underline.
- Body: two panes.
  - Left (400–460px): RANGE from/to SHA inputs + "Select range" button (typed SHAs select the same contiguous range as checkboxes; invalid → "SHA not on this branch" in #cf222e); hint line; commit list grouped by day ("Commits on Aug 20, 2026" headers, 11px/700 on #eceff2). Row: checkbox (accent #0969da), title (13px/600), PR chip (`#412 …`, 11px #0969da on #ddf4ff, pill), author avatar (14px circle, initial) + "author committed X ago" + checks `✓ 39 / 43` (#1a7f37), right-aligned mono SHA (#0969da). Selected-range rows: #f1f8ff bg. Clicked (viewed) row: 3px #0969da left bar.
  - Right: viewed commit header (title 14px/700, mono meta, "N changed files · +A −D") above a file list (300px, status square M/A/D in #9a6700/#1a7f37/#cf222e, rtl-ellipsized mono path, +adds/−dels) and a unified diff (GitHub colors: add #dafbe1/#aceebb, del #ffebe9/#ffcecb, hunk #ddf4ff; mono 12px, line-height 20px, two 40px number gutters).
- Selection semantics (workspace repo): **check one** commit → inline editor expands under it (title input, body textarea, "Update message" green button — amends locally); **check two** → contiguous range; squash panel expands under the newest with combined-message textarea, "Squash N commits", range diff on the right.
- Upstream repo: checks select a range; a floating dark action bar (bottom center, #24292f, radius 10) shows "N commits selected · sha…sha → bytequay/trino-fork · master · [Cherry-pick to workspace →]" leading to the confirm page. Rows already on target show a `already on target` amber chip.

### 4. Commits — Changes view
- GitHub Desktop working-tree view: left pane (300–340px) uncommitted file list + commit box pinned at bottom (Summary input, Description textarea, green "Commit to master" full-width button); right pane same diff component. Only for the workspace repo.

### 5. Cherry pick — task list (screen=tasks; the "Cherry pick" tab)
- Header: "Cherry-pick tasks" (18px/700) · "2 running or paused · 12 finished" · right: green **+ New cherry-pick** button (jumps to upstream History to select a range) and a segmented filter **All | Not done | Done** (active #0969da/white).
- Task rows (bordered list, radius 8, hover #f6f8fa): status dot (running = amber pulse; paused = red; done = green; canceled = gray), mono task id (#0969da, 66px), **PR title** (13.5px/600) with route+status as small text under it ("12 commits · trinodb/trino → master · item 5 of 12 · resolving conflict on acdfbb7"), right: relative time, state chip (RUNNING #0969da/#ddf4ff · PAUSED #9a6700/#fff8c5 · DONE #1a7f37/#dafbe1 · CANCELED #57606a/#eceff2), `›`.
- Sorting/paging: running & paused always first; max 10 rows; **"Load more (N older)"** button reveals 10 more. Filter resets paging.
- Row click → task progress page.

### 6. Range confirm page (screen=flow; transient — reached only from "Cherry-pick to workspace →", not from tabs)
- Header: source→target line, "Cherry-pick 12 commits → `master`", frozen-preview note (preview id, source range, target SHA; stale if master moves).
- Count chips: "7 will apply" green · "3 skipped by you" gray · "2 already on target" amber.
- Controls row: title filter input (view-only — shows amber note "Showing X of Y — the filter changes the view only"); **Skip by pull request** dropdown (search input; checkbox list of PRs in range with commit counts; skipping a PR unchecks all its commits; footnote: skips travel as commit SHAs); right: **Dry run** + green **Start cherry-pick (N)**.
- **Pull request card**: title input + description textarea — stored with the task, used when the work branch is later pushed (nothing sent to GitHub at start). Defaults are generated from the plan.
- Application-order list (oldest first): checkbox (accent #1f883d; disabled for already-on-target), position number, title, mono SHA, PR chip, status chips (`already on target · reason`, `skipped via PR #…`, `skipped by you`, `⚠ conflict likely — files`), author/time right. Skipped rows: #f6f8fa bg, 55% opacity.
- **Dry run modal** (760px, radius 12, overlay rgba(27,31,36,.45)): three ordered sections — WILL CHERRY-PICK (in order, conflict flags), SKIPPED BY YOU (reasons), ALREADY ON TARGET (detection reasons); footer states the work branch + frozen target and repeats Start.

### 7. Task progress page (screen=progress)
- Three columns with **drag-resizable** dividers (5px, hover #54aeff): left item nav (default 260px, min 180 / max 420), center log (min 480px), right PR panel (default 380px, min 280 / max 560).
- Left nav: "Range items" + "N of 12 settled" + progress bar (#1f883d). Items: status dot (applied green, empty/user-skip gray, already amber, resolving amber pulse, paused red, pending light) + "pos · title" + status sub-line. **Folding**: when long, show previous + current + next 7; collapsed groups become "N earlier items — settled" / "N more pending" rows; expandable, collapsible back.
- Center: header ("‹ Tasks" back link, RUNNING/PAUSED/FINISHED pill, step label, mono work branch, Cancel execution); optional paused banner (amber, note input + "Send note & retry") or finished banner (green, totals + "View range-diff" / "Start another range").
- Log stream: dim mono meta lines (execution header; already-on-target and user-skip accounting) + **collapsible per-commit cards**: chevron, status dot, "pos · sha — title", right status ("Applied → newSha · 4s"). Finished cards collapsed by default; active card expanded. Card body: mono log lines ($ git cherry-pick -x …, CONFLICT lines in #bc4c00, verification lines in #1a7f37) and, for conflicts, an **agent turn** (Codex-style): left 2px border, "✦ Claude · resolving files" + duration, prose, tool-step lines (⌕ search, ▤ read, ✎ edit, ⚙ run), closing prose, spinner tail while waiting.
- Bottom: **message input to the agent** (rounded 12, send ↑ button in #24292f circle) — sent messages render right-aligned in a gray bubble + a meta line "note queued for the current agent turn — delivered as untrusted user context".
- Right panel: PR overview for the selected commit (reuses the existing PR page design): title + "Open on GitHub ↗", Overview/Changes tabs (#fd8c73 underline), Merged state pill (#8250df), branch chips, reviewers/labels, description card. Commits without a PR: empty state "No pull request is associated with this commit."

## Interactions & Behavior
- Range selection is always **one contiguous span**: checking two commits selects everything between; typed from/to SHAs do the same; per-commit exclusions happen later on the confirm page.
- Upstream is **read-only**: no message editing, no squash, no Changes tab; its only action is cherry-pick into the workspace's selected target branch.
- Title filter on the confirm page never changes the plan; PR-skips are resolved to commit SHAs before submission.
- Start creates a work branch at frozen target (`bytequay/cp-xk29` at `41ab9e2`); if the target moves first, the preview is stale and must be re-reviewed.
- Progress states per item: PENDING → (APPLIED | EMPTY_SKIPPED | ALREADY_ON_TARGET | USER_SKIPPED | RESOLVING → APPLIED/PAUSED). Pause preserves the worktree and asks for a user note; retry limit 3.
- Sync button spins ~1s; branch menus close on outside action; all dropdowns are single-open.

## State Management
Workspace shell: `view` (list/workspace), `tab`. Commits feature: `repo` (workspace/upstream), `branch`, `upBranch` (+ search query), `commitsView` (changes/history), `screen` (commits/flow/tasks/progress), checked SHAs per repo, viewed SHA, diff file index, message/squash drafts, flow filter + unchecked SHAs + skipped PR ids, PR title/body drafts, task filter + paging, selected task, per-card open state, panel widths, agent chat drafts/messages, sync status.
Data fetching (real impl): commit lists per repo/branch (paged by day), per-commit and range diffs, PR metadata per commit, PRs within a range, task list (paged, 10 at a time), task execution log stream (live), per-item states.

## Design Tokens
- Content palette (GitHub-like): text #1f2328, secondary #57606a/#656d76, muted #8c959f; borders #d0d7de (content) / #e3e3e6 #ececef #f0f0f2 (shell); canvas #ffffff, subtle #f6f8fa, shell sidebar #f7f7f8, selected sidebar row #e4e4e8.
- Accents: blue #0969da (+#ddf4ff/#f1f8ff), green #1a7f37/#1f883d (+#dafbe1/#aceebb), red #cf222e (+#ffebe9/#ffcecb), amber #9a6700/#bf8700/#d4a72c (+#fff8c5), orange (warnings) #bc4c00 (+#fff1e5), merged purple #8250df, tab underline #fd8c73, dark bars #24292f.
- Type: system stack (`-apple-system, "Segoe UI", …`); mono `ui-monospace, Menlo`. Sizes: page titles 18–22px/700, row titles 13–14px/600, body 13px, meta 11–12px, mono 11–12px. Diff line-height 20px.
- Radius: 6 (buttons/inputs), 8 (cards/rows), 10–12 (menus/cards/modals); pills 10–14.
- Shadows: menus `0 8px 24px rgba(140,149,159,.25)`; modal `0 16px 48px rgba(27,31,36,.35)`; action bar `0 8px 24px rgba(27,31,36,.35)`.

## Assets
None — all icons are text glyphs (⑂ ↞ ⟳ ⌂ ☑ ◉ ✦ ⌕ ▤ ✎ ⚙). Use the codebase's icon set (e.g. SF Symbols / Octicons) for: branch, upstream/arrow, sync, home, tasks, PR, issue, memory, settings, search, chevrons, status dots.
