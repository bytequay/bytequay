# PR Overview Timeline Implementation Checklist

Status: complete.

This checklist tracks the work required to match the GitHub Copilot pull-request
Overview and timeline shown in the supplied references. The live comparison
fixtures are:

- open: [`trinodb/trino#29883`](https://github.com/trinodb/trino/pull/29883)
- merged: [`trinodb/trino#30745`](https://github.com/trinodb/trino/pull/30745)

A box is checked only when the implementation and its focused verification
exist. Work proceeds in checkpoint order.

## Fixed rules

- Preserve ByteQuay's current font.
- Preserve the current Overview and Changes controls and their behavior.
- Reuse the existing GitHub client, PR detail endpoint, cache, avatar,
  React stack, and plain CSS. The audit found no Markdown renderer, so add one
  focused renderer without introducing a UI framework.
- Do not add a UI framework, state library, database, or parallel PR-detail
  API.
- Keep all GitHub writes behind existing direct user actions.
- Fetch every GitHub page required for a complete timeline and checks summary.
- Treat the two live PRs as smoke-test inputs; keep automated tests
  deterministic with recorded or constructed fixtures pinned to a head SHA.

## Checkpoint 1 — Audit the existing PR detail flow

Goal: identify what ByteQuay already fetches and renders before changing its
contracts.

- [x] Trace the backend PR-detail path from controller through service,
  GitHub client, mapper, cache, and SQLite detail store.
- [x] Trace the frontend detail path from preload bridge and model through the
  Overview, Changes, activity, checks, review, and comment components.
- [x] Inventory existing tests and reusable Markdown, avatar, diff, badge,
  timestamp, and activity helpers.
- [x] Map every reference-screen element to an existing field or a missing
  GitHub source.
- [x] Record the smallest contract and file set needed for the implementation.

Checkpoint gate:

- [x] The data-gap inventory is grounded in the current code, not assumptions.

Audit result:

- The existing path is
  `PullRequestController -> PullRequestService -> PullRequestDetailFetcher ->`
  `GitHubPullRequestReadClient/GitHubActionsClient -> StoredPrDetail ->`
  `SqlitePrDetailStore -> PullRequestDetailMapper -> preload bridge ->`
  `PullsScreen`. It already provides one cached detail endpoint and one live
  refresh path, so no second API or store is needed.
- The retained list-level `PullRequest` supplies title, number, author, URL,
  state, timestamps, labels/colors, requested reviewers, mergeability, head
  branch, totals, and comment count. The cached raw detail supplies body, head
  SHA, and the same numeric totals. Base branch, assignees, avatars, milestone,
  reactions, rich review data, commits, and review-thread state are absent.
- REST issue timeline pagination already exists, but the mapper discards label,
  rename, milestone, cross-reference, commit, and event-specific metadata and
  then reverses the result. Reviews and changed files are currently single-page.
  Review comments/threads, PR commits, legacy statuses, and GraphQL resolution
  state are not fetched. Check runs are paged to five pages and need a complete
  terminal condition.
- SQLite already normalizes raw detail, reviews, timeline rows, and check runs.
  The smallest persistence change is to enrich those current records/tables;
  a parallel overview database or opaque duplicate snapshot is unnecessary.
- The renderer is one component (`PullsScreen`) plus plain `pulls.css`. It has
  reusable `Avatar`, diff, status, merge, comment-action, and tab behavior, but
  no Markdown renderer, relative-time helper, rich timeline variants, reaction
  UI, or resolved-thread UI. Changes rendering is isolated in `PullChanges` and
  can remain untouched.
- GitHub source mapping: PR REST supplies header/body/branches/reviewer counts;
  issue REST supplies assignees/milestone; issue timeline supplies compact
  events and issue comments; paged reviews and review comments supply review
  cards and inline discussions; GraphQL supplies review-thread grouping plus
  resolved/outdated state; commits supply commit events; check-runs and commit
  statuses supply checks; embedded reaction summaries supply reaction chips.
- Minimal affected set: the current core PR detail records, existing GitHub DTO
  and read/action clients, the current fetcher/mapper/store and their entities,
  focused backend tests, `frontend/src/types.ts`, a new Overview-only component
  and Markdown helper, `PullsScreen`, `pulls.css`, and focused Vitest coverage.

## Checkpoint 2 — Normalize the backend overview contract

Goal: expose one coherent read model for the header and chronological timeline.

- [x] Extend the existing PR-detail contract with labels, assignees, reviewer
  totals, reactions, commits, review threads, and complete check groups.
- [x] Model the finite timeline item variants needed by the references:
  description, comment, review, review thread, commit, rename, label change,
  cross-reference, merge queue, milestone, close, and merge.
- [x] Include the fields needed for inline code discussions: path, diff hunk,
  original/current line range, outdated state, and resolved state.
- [x] Keep stored values immutable and JSON-compatible through the current PR
  detail store.
- [x] Add focused contract and serialization tests.

Checkpoint gate:

- [x] One backend response can describe both reference PRs without UI-specific
  inference.

## Checkpoint 3 — Fetch the missing GitHub data

Goal: obtain the same public GitHub facts used by the reference UI.

- [x] Fetch PR and issue metadata for header state, branches, counts, labels,
  assignees, and milestone.
- [x] Fetch every page of issue/PR timeline events.
- [x] Fetch every page of reviews, review comments, and PR commits.
- [x] Fetch review-thread resolved/outdated state through GitHub GraphQL where
  REST does not expose it.
- [x] Fetch every page of check runs plus legacy commit statuses for the head
  SHA.
- [x] Fetch displayed issue-comment and review-comment reactions without
  introducing avoidable duplicate calls.
- [x] Preserve existing authentication, rate-limit, and error handling.
- [x] Add HTTP/client tests for pagination and representative response shapes.

Checkpoint gate:

- [x] The backend can obtain all facts visible in both reference timelines.

## Checkpoint 4 — Assemble, cache, and serve the timeline

Goal: produce one stable timeline without duplicate or misordered items.

- [x] Normalize source payloads into the timeline variants from checkpoint 2.
- [x] Deduplicate comments and reviews returned by overlapping GitHub
  endpoints.
- [x] Sort by GitHub timestamps with a stable tie-breaker.
- [x] Calculate reviewer totals from requested and submitted reviews while
  excluding the PR author.
- [x] Group checks into failing, pending, successful, neutral, skipped, and
  cancelled categories with accurate totals.
- [x] Reuse the current PR-detail cache and invalidation path.
- [x] Return partial read data with a clear unavailable section when one
  optional GitHub source fails; do not hide the whole PR.
- [x] Add client, mapper, fetcher, service, and store tests for open and merged
  response shapes while retaining the existing controller endpoint.

Checkpoint gate:

- [x] The API response order and totals match GitHub for both live fixtures.

## Checkpoint 5 — Build the Overview component structure

Goal: reproduce the reference hierarchy while keeping the current page tabs.

- [x] Render the title, number, copy action, state badge, base/head branch
  pills, and diff totals.
- [x] Render reviewer, assignee, and label chips.
- [x] Render the description as the first full timeline card.
- [x] Render the timeline rail and compact activity rows.
- [x] Render comment cards, review-state cards, review summaries, reactions,
  and collapsed resolved-thread rows.
- [x] Render expanded inline discussions with diff hunks and replies; resolved
  rows can open the corresponding GitHub thread without issuing a hidden write.
- [x] Render commit, cross-reference, merge-queue, close, and merge events.
- [x] Render the grouped checks summary and the existing comment composer.
- [x] Add focused frontend tests for representative item variants and Markdown.

Checkpoint gate:

- [x] Both fixture states render from data without PR-specific conditions.

## Checkpoint 6 — Match the reference styling

Goal: match the supplied screenshots except for the explicitly retained font
and top Overview/Changes controls.

- [x] Match content width, horizontal alignment, vertical rhythm, card
  spacing, and timeline-rail placement.
- [x] Match borders, radii, header fills, badge colors, code backgrounds, and
  muted text colors.
- [x] Match open, merged, approved, changes-requested, resolved, passing, and
  failing visual states.
- [x] Match the sticky detail header and long-timeline scrolling behavior.
- [x] Add responsive behavior without changing the desktop reference layout.
- [x] Confirm the Changes surface and existing font are unchanged.

Checkpoint gate:

- [x] Reference-viewport screenshots have no material layout or state-style
  discrepancy.

## Checkpoint 7 — Exercise the two remote PR fixtures

Goal: verify real public data without making automated tests depend on mutable
network state.

- [x] Record deterministic backend fixtures alongside the observed head SHAs.
- [x] Cover more than 30 and more than 100 source records to prove pagination.
- [x] Verify `#29883` as an open PR with failing checks and a long active
  timeline.
- [x] Verify `#30745` as a merged PR with merge-queue history and successful
  checks.
- [x] Run a live read-only refresh against both PR URLs.
- [x] Compare initial, middle, and bottom timeline screenshots at the reference
  viewport.

Checkpoint gate:

- [x] Recorded tests are deterministic and live smoke results agree with the
  current GitHub pages.

## Checkpoint 8 — Full verification and reconciliation

- [x] Run `mvn verify` in `backend/`.
- [x] Run `npm run lint` in `frontend/`.
- [x] Run `npx tsc --noEmit` in `frontend/`.
- [x] Run `npm test` in `frontend/`.
- [x] Run `npm run package` in `frontend/`.
- [x] Run `git diff --check`.
- [x] Reconcile every checked box with the final code and tests.
- [x] Review the final diff for unrelated changes and accidental GitHub writes.

Final gate:

- [x] The open and merged Overview timelines match the references while the
  current font, Overview control, Changes control, and Changes behavior remain
  intact.

## Checkpoint 9 — Reconcile the direct #30653 comparison

The first pass was invalidated by a direct same-PR comparison. Complete these
items before restoring the final gate:

- [x] Invalidate pre-enrichment PR detail rows without deleting credentials.
- [x] Suppress empty `COMMENTED` review wrappers while retaining substantive
  reviews and verdict-only approval/change-request events.
- [x] Match the measured overview inset, state badge, card header, body rhythm,
  border color, font weight, and code-fence label.
- [x] Match compact-event spacing, resolved-thread rows, reaction rows, checks,
  composer controls, and SVG icon geometry.
- [x] Refresh and compare `trinodb/trino#30653`, `#29883`, and `#30745` after a
  `dev.sh` restart.
- [x] Re-run backend and frontend verification and reconcile this checklist.

## Verification record

- Open fixture `#29883` at head
  `e33be4ee9787a7dd5880fd7a9d33d64cb9cf0186`: open state, 3 labels,
  8 reviewers, 147 activity items, 11 review threads, and 100 checks
  (2 failing, 97 successful, 1 skipped). No unavailable sections.
- Merged fixture `#30745` at head
  `bdb213ec6d732fc3aef5437c74a1958b0b2a093a`: merged state, 1 label,
  2 reviewers, 38 activity items, 6 review threads, and 90 successful checks.
  No unavailable sections. This PR was refreshed through the retained detail
  endpoint because the local Done list intentionally contains only the
  signed-in user's closed PRs.
- Direct comparison fixture `#30653`: open state, 3 deduplicated commits,
  4 resolved review threads, 21 activity items, the substantive LGTM review
  with its `+1` reaction, and 43 successful checks. Empty `COMMENTED` review
  wrappers are absent and no section is unavailable.
- The desktop comparison covered the summary/description, middle review and
  resolved-thread timeline, checks, composer, and merged-state timeline.
- The non-credential PR detail cache was reset before the final live refresh.
  The credentials table retained its single existing row, and the enriched
  detail, timeline, review, and check rows were repopulated normally.
- Backend verification: 256 application tests passed under `mvn verify`; a
  focused 101-check pagination test also passed.
- Frontend verification: lint, TypeScript, 27 Vitest tests, and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 10 — Reconcile the direct #30828 comparison

- [x] Derive the queued state from the latest merge-queue timeline event.
- [x] Resolve bare GitHub issue and pull-request URLs to linked titles, icons,
  and issue numbers without PR-specific hardcoding.
- [x] Fetch and render description reactions with the reference sizing and
  reaction action.
- [x] Match the semantic `no-release-notes` and `cla-signed` label colors.
- [x] Match the measured 14 px overview inset while retaining the existing
  application font and Overview/Changes controls.
- [x] Reset only the cached #30828 detail rows, preserving credentials, then
  restart through `dev.sh` and refresh from GitHub.
- [x] Run the complete backend and frontend verification suite.

Checkpoint gate:

- [x] The live #30828 title, branch/status row, metadata chips, description,
  reactions, compact timeline, and label events have no material discrepancy
  from the supplied reference.

Verification:

- Live #30828: queued state, 5 reviewers, 2 semantic label dots, 8 enriched
  issue/PR references, `+1: 2`, `eyes: 1`, 6 commit events, 3 queue additions,
  2 queue removals, and 90 successful checks.
- The targeted cache reset removed only #30828 detail, timeline, review, and
  check rows. The single credential row remained intact and the detail cache
  repopulated from GitHub after the `dev.sh` restart.
- Backend: 257 tests passed under `mvn verify`.
- Frontend: ESLint, TypeScript, 28 Vitest tests, and Electron packaging passed.
  `git diff --check` passed.

## Checkpoint 11 — Reaction picker and commit-event glyph

- [x] Make the card-header and reaction-row smiley controls keyboard- and
  pointer-clickable.
- [x] Match the reference eight-item reaction picker order, hover/selected
  treatment, spacing, border, radius, and shadow.
- [x] Keep the picker above timeline cards instead of clipping it at the card
  boundary.
- [x] Replace the two-node commit-event symbol with GitHub's single-node commit
  glyph and retain the measured 24 px circular container.
- [x] Add regression coverage for the picker choices and commit SVG geometry.
- [x] Restart through `dev.sh` and exercise the picker on live PR #30828.

Verification:

- The live #30828 description reaction control opened all eight choices in the
  reference order, and selecting a choice closed the picker.
- Frontend: ESLint and TypeScript passed; all 28 Vitest tests and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 12 — Resizable list/detail split

- [x] Start divider drags from the detail pane's actual rendered width so the
  first drag movement is effective at every window size.
- [x] Increase the divider hit target and expose it as a vertical separator.
- [x] Disable horizontal overflow in the middle pull-request list while
  retaining its independent vertical scrolling.
- [x] Restart through `dev.sh` and verify both behaviors in the live app.

Verification:

- The live divider moved immediately from approximately 444 px to 560 px.
- A horizontal scroll gesture left the middle-column content anchored, while a
  vertical gesture continued to scroll its pull-request rows.
- Frontend: ESLint and TypeScript passed; all 28 Vitest tests and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 13 — Persistent reaction picker behavior

- [x] Keep a larger reaction action visible beneath the PR description even
  when the description has no existing reactions.
- [x] Animate the picker open and closed with a short opacity and scale
  transition.
- [x] Close the picker when the user clicks outside it or presses Escape.
- [x] Restart through `dev.sh` and verify the interaction in the live app.

Verification:

- [x] Frontend lint, TypeScript, Vitest, Electron packaging, and
  `git diff --check` pass.

- Live #30653: the no-reaction description retained its bottom action; the
  picker exposed all eight reactions; Escape and an outside click both closed
  it without removing the action.
- Frontend: ESLint and TypeScript passed; all 28 Vitest tests and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 14 — Reviewer identities for small groups

- [x] Show reviewer avatars and logins when there are one or two reviewers.
- [x] Keep the compact people count when there are three or more reviewers.
- [x] Add regression coverage for both rendering paths.
- [x] Restart through `dev.sh` and verify PR #30619 in the live app.

Verification:

- [x] Frontend lint, TypeScript, Vitest, Electron packaging, and
  `git diff --check` pass.

- Live #30619: the single-reviewer chip rendered ezra-b's avatar and login in
  place of the numeric people count.
- Frontend: ESLint and TypeScript passed; all 29 Vitest tests and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 15 — Readable fenced log text

- [x] Increase fenced markdown code text from its effective 10.8 px size to
  14 px without changing body copy or inline-code sizing.
- [x] Restart through `dev.sh` and verify the log block on live PR #30619.

Verification:

- [x] Frontend lint, TypeScript, Vitest, Electron packaging, and
  `git diff --check` pass.

- Live #30619: the fenced plain-text log renders at 14 px while the surrounding
  description and inline-code typography remain unchanged.
- Frontend: ESLint and TypeScript passed; all 29 Vitest tests and Electron
  packaging passed. `git diff --check` passed.

## Checkpoint 16 — Titled shorthand PR references

- [x] Resolve same-repository `#1234` references through the existing batched
  GitHub GraphQL enrichment request.
- [x] Render resolved PRs as clickable `PR title #1234` links with the existing
  PR glyph and in-app browser behavior.
- [x] Retain the original `#1234` text when GitHub cannot resolve a reference.
- [x] Add regression coverage and run backend/frontend verification.
- [x] Restart through `dev.sh` and verify a live shorthand reference.

Verification:

- [x] Backend tests, frontend checks, and `git diff --check` pass.

- Live #29883: `Fixes #23740` refreshed to the linked title
  `trino access Aliyun OSS got error: 'failed: No factory for location:
  oss://bucket-name/path' #23740`, and clicking it followed the enriched GitHub
  URL through the shared in-app-browser handler.
- Backend coverage resolves a same-repository shorthand to a `PullRequest` and
  verifies the `/pull/{number}` target. Frontend coverage verifies the title,
  number, PR glyph class, and link target.
- Backend `mvn verify`, frontend ESLint, TypeScript, all 29 Vitest tests,
  Electron packaging, and `git diff --check` passed.

## Checkpoint 17 — Narrow list-pane clipping

- [x] Keep all pull-request filters visible in the narrow middle pane.
- [x] Keep timestamps inside PR cards while truncating only long repository or
  author names.
- [x] Preserve the middle pane's no-horizontal-scroll behavior.
- [x] Restart through `dev.sh` and verify the reported pane width.

Verification:

- [x] Frontend lint, TypeScript, Vitest, Electron packaging, and
  `git diff --check` pass.

Notes:

- At the minimum pane width, `All`, `My PRs`, `Review`, `Done`, refresh, and the
  detail-pane toggle remain visible.
- Long repository and author names ellipsize while timestamps remain visible;
  the pane still has no horizontal scrolling.
- ESLint, TypeScript, all 29 Vitest tests, Electron packaging, and
  `git diff --check` passed. The app was killed and restarted with `dev.sh`.

## Checkpoint 18 — Titled references in timeline comments

- [x] Apply shorthand PR-reference enrichment to timeline comments, review
  bodies, and inline-review replies, not only the PR description.
- [x] Keep reference lookup batched and preserve unresolved `#1234` text.
- [x] Add regression coverage for a titled, clickable timeline reference.
- [x] Restart through `dev.sh` and verify a live comment reference.

Verification:

- [x] Backend tests, frontend checks, and `git diff --check` pass.

Notes:

- Reference enrichment now runs once after all timeline sources are merged, so
  issue comments, review bodies, and inline-review replies share the same
  batched lookup and fallback behavior as the PR description.
- Verified live on `trinodb/trino#30825`: both occurrences of `#26544` render
  as the clickable titled link `Update MongoDB image versions in tests #26544`.
- `mvn verify` passed 257 backend tests; frontend lint, TypeScript, 29 tests,
  and Electron packaging passed. The app was restarted through `dev.sh`.

## Checkpoint 19 — Username scale and mention suggestions

- [x] Increase timeline username text without changing the body font.
- [x] Show current PR participants when `@` is typed in the comment composer.
- [x] Support mouse selection plus arrow, Enter/Tab, and Escape keys.
- [x] Close the suggestion list when focus moves outside the composer.
- [x] Add focused mention parsing and insertion regression coverage.
- [x] Restart through `dev.sh` and verify the interaction live.

Verification:

- [x] Frontend lint, TypeScript, tests, packaging, and `git diff --check` pass.

Notes:

- Timeline card and compact-event usernames now render at `15px`; inline-thread
  usernames render at `14px`. Body typography is unchanged.
- The composer derives suggestions from the PR author, reviewers, assignees,
  timeline actors, and inline-review participants without another API call.
- Live verification opened the list for `@f`, selected `finn-ep` with Enter,
  inserted `@finn-ep `, closed the list, and cleared the draft without posting.
- Frontend lint, TypeScript, 30 tests, Electron packaging, and the final
  `dev.sh` restart passed.

## Checkpoint 20 — Check groups and composer text scale

- [x] Increase the Pending/Successful check-group labels to match timeline body text.
- [x] Increase comment textarea and placeholder text without changing global body typography.
- [x] Restart through `dev.sh` so the running app loads the larger text.

Notes:

- Check-group labels and comment textarea text now render at `15px`.
- Frontend lint, TypeScript, `git diff --check`, and the clean `dev.sh` restart passed.

## Checkpoint 21 — Pull-request link copy feedback

- [x] Swap the title copy glyph to a checkmark after a successful clipboard write.
- [x] Show a temporary `Copied` message above the button.
- [x] Restore the copy glyph and hide the message after 1.5 seconds.
- [x] Restart through `dev.sh` and verify the interaction in the running app.

Notes:

- Clipboard feedback uses the existing button and native clipboard API; no dependency was added.
- Live verification on `trinodb/trino#30653` showed the checkmark and `Copied`
  message immediately after clicking, then restored `Copy pull request link`
  after 1.5 seconds.
- Frontend lint, TypeScript, all 30 tests, and the clean `dev.sh` restart passed.

## Checkpoint 22 — Searchable reviewer and assignee pickers

- [x] Fetch the repository's assignable users through the existing authenticated GitHub client.
- [x] Open searchable people lists from the Reviewers and Assignees chips.
- [x] Pin current selections first with checkmarks, avatars, usernames, and Bot badges.
- [x] Close either list with Escape or an outside click without issuing a GitHub write.
- [x] Run backend and frontend verification.
- [x] Restart through `dev.sh` and verify both pickers in the running app.

Notes:

- Both chips open the same searchable repository-user list; existing reviewers or assignees are pinned first with checkmarks, avatars, usernames, and Bot badges.
- Selected people stay visually neutral. The grey row and blue rail are reserved for the currently focused person, matching the reference picker.
- The repository-user endpoint was verified live against `trinodb/trino` after the clean restart. The pickers remain read-only and do not issue GitHub assignment writes.
- Backend `mvn verify` passed 258 tests. Frontend lint, TypeScript, all 31 tests, Electron packaging, and the final `dev.sh` kill/start cycle passed.

## Checkpoint 23 — CodeRabbit rich markdown parity

- [x] Render linked-image badges such as `Review Change Stack` as the source image and destination link.
- [x] Preserve nested disclosure sections so closed `Recent review info` and `Walkthrough` blocks hide their contents correctly.
- [x] Render underscore emphasis used by the merge-risk label.
- [x] Run frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the corrected CodeRabbit card.

Notes:
- The exact CodeRabbit body from `trinodb/trino#30834` now renders its linked `Review Change Stack` image instead of exposing the markdown source.
- Nested `<details>` blocks remain inside their parent disclosure, and underscore emphasis renders the merge-risk label correctly.
- Frontend lint, TypeScript, all 32 tests, Electron packaging, and the final `dev.sh` restart passed.

## Checkpoint 24 — Merge queue action

- [x] Detect whether the pull request's base branch has a GitHub merge queue.
- [x] Enqueue directly when the user confirms `Merge when ready`; retain rebase-and-merge for branches without a queue.
- [x] Add the green header action and a confirmation sheet with the current checks and unresolved-review count.
- [x] Add focused backend and frontend regression coverage.
- [x] Run backend and frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the action in the running app.

Notes:
- Merge-queue support is detected for the pull request's exact base branch through GitHub's authenticated GraphQL API.
- The green header action is limited to open, writable, non-draft pull requests that are not already queued. Opening the sheet is read-only; the GitHub write occurs only after the user confirms the final action.
- Queue-enabled branches use `enqueuePullRequest`; other branches retain the existing rebase-and-merge endpoint.
- Backend `mvn verify` passed 260 tests. Frontend lint, TypeScript, all 33 tests, `git diff --check`, and Electron packaging passed.
- After the clean `dev.sh` restart, the live merge-mode endpoint returned `{"mergeQueue":true}` for `trinodb/trino`'s `master` branch. No GitHub write was issued during verification.

## Checkpoint 25 — Review-thread reply interaction

- [x] Render the review-thread `Reply` action at regular font weight with an adjacent reaction picker.
- [x] Open an inline reply editor and submit the reply to the selected GitHub review thread.
- [x] Refresh the pull-request detail after a successful reply and preserve clear cancel/error states.
- [x] Add focused backend and frontend regression coverage.
- [x] Run backend and frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the interaction in the running app.

Verification: `mvn -q verify`, `npm test -- --run` (34 tests), `npm run lint`, `npx tsc --noEmit`, and `npm run package` all pass. The reply path uses GitHub's review-comment reply API, invalidates cached PR detail, and refreshes the renderer after success.
Runtime verification: `dev.sh` was restarted in its persistent PTY, `/hello` returned `ByteQuay-backend ok`, and the Electron renderer process remains alive. No test reply was posted to GitHub during verification.

## Checkpoint 26 — Merged pull-request list reconciliation

- [x] Persist the canonical detail state back into the pull-request list row.
- [x] Reconcile the visible card immediately after a detail fetch or refresh.
- [x] Render the merged icon and keep merged/closed pull requests only in Done.
- [x] Add focused backend and frontend regression coverage.
- [x] Run backend/frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the corrected runtime state.

Verification: `mvn -q verify`, `npm test -- --run` (35 tests), `npm run lint`, `npx tsc --noEmit`, `npm run package`, and `git diff --check` all pass. The list model now classifies canonical merged/closed states exclusively under Done and updates the selected card as soon as detail data arrives.
Runtime verification: `dev.sh` was restarted in its persistent PTY, `/hello` returned `ByteQuay-backend ok`, and PR #30828 returned `state: merged` from both `/prs/detail` and `/prs` with the same merge timestamp. The Electron app remains running.

## Checkpoint 27 — Merge-status ring motion

- [x] Animate the merge-status arc so the syncing state is visibly active.
- [x] Respect reduced-motion preferences without changing the button layout.
- [x] Run frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the existing ring arc now rotates continuously with a native CSS animation and falls back to the original static angle when reduced motion is requested. `npm test -- --run` (35 tests), `npm run lint`, `npx tsc --noEmit`, `npm run package`, and `git diff --check` all pass.
Runtime verification: `dev.sh` was restarted in its persistent PTY, `/hello` returned `ByteQuay-backend ok`, and the Electron renderer remains alive.

## Checkpoint 28 — Review submission and complete merge header states

- [x] Expose canonical mergeability fields in pull-request detail data.
- [x] Add a generic backend review endpoint for comment, approval, and change requests.
- [x] Show `Submit review` for open pull requests authored by someone else.
- [x] Add the review sheet and submit its selected review outcome to GitHub.
- [x] Render loading, conflict, merge-queue, and rebase merge-action states.
- [x] Add focused backend and frontend regression coverage.
- [x] Run backend/frontend verification and package the Electron app.
- [x] Restart through `dev.sh` and verify the running app.

Verification: backend `mvn -q verify`; frontend 32 tests, lint, TypeScript, and Electron package; live `dev.sh` detail check for `trinodb/trino#30335` returned an external open PR with write access and `mergeableState: dirty`, while merge-mode detection returned `mergeQueue: true`.

## Checkpoint 29 — Review and merge overlay drawers

- [x] Keep the original pull-request page mounted beneath review and merge sheets.
- [x] Present sheets as right-side modal drawers with a dim, dismissible backdrop.
- [x] Add focused regression coverage and restart through `dev.sh`.

Verification: review drawer visually checked in the running Electron app; the PR remains mounted beneath the backdrop and is restored unchanged after `Escape` dismissal. Focused tests (15/15), lint, and TypeScript checks pass.

## Checkpoint 30 — Narrower action drawers

- [x] Reduce the review and merge drawer width while preserving small-window responsiveness.
- [x] Verify the narrower drawer in the running app.

Verification: review and merge drawers now use `min(56vw, 1120px)` with a responsive `680px` minimum, and the review drawer was visually checked in the running Electron app.

## Checkpoint 31 — 40% action drawers

- [x] Set review and merge drawers to 40% of the window width.
- [x] Preserve full-width behavior on windows narrower than the responsive minimum.

Verification: both drawers now use `40vw` with a responsive `480px` minimum.

## Checkpoint 32 — Regular-weight action drawers

- [x] Remove bold text from the review drawer.
- [x] Remove bold text from the merge drawer.

Verification: drawer headings, labels, status text, and buttons now use regular font weight.

## Checkpoint 33 — Grouped GitHub label events

- [x] Coalesce adjacent label additions or removals by the same actor into one timeline row.
- [x] Render one timeline tag marker, one actor/avatar, all label pills, and one timestamp.
- [x] Preserve separate rows across different actors, actions, or non-label events.
- [x] Add focused frontend regression coverage.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: focused and full Vitest suites pass (34 tests total), with ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 34 — Review submission/thread association

- [x] Inspect the live `trinodb/trino#30683` detail payload for review and thread identifiers.
- [x] Confirm the backend supplies a standalone review event and the thread-to-review relationship.
- [x] Group review threads under their matching standalone review event in the timeline.
- [x] Render the reviewer avatar and `reviewed` summary before the grouped thread.
- [x] Preserve suppression of unmatched empty comment-only review wrappers.
- [x] Add focused regression coverage and run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the live payload relates review `4925022844` to thread root `3773729721`; the frontend now performs that association instead of treating the thread as an unrelated row. Focused tests pass (17/17), the full Vitest suite passes (35/35), and ESLint, TypeScript, and `git diff --check` are clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 35 — GitHub Markdown tables

- [x] Parse GitHub pipe-table headers, alignment rows, and body rows.
- [x] Render description tables with GitHub-style borders, alignment, striping, and horizontal overflow.
- [x] Add regression coverage for the `CI impact` table shape.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: focused Markdown/overview tests pass (18/18), with the full frontend suite, ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 36 — Review-thread code and reactions

- [x] Increase the inline review file-path and diff code size.
- [x] Remove the plus sign from the shared reaction icon.
- [x] Remove the duplicate reaction picker beside the thread `Reply` action.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: focused overview tests pass (18/18), the full Vitest suite passes (44/44), and ESLint, TypeScript, and `git diff --check` are clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 37 — Review-thread reply and resolution controls

- [x] Show a reply action on both resolved and unresolved review threads.
- [x] Show `Resolve` for unresolved threads and `Unresolve` for resolved threads.
- [x] Post replies through the existing GitHub review-comment API and update
  thread resolution through GitHub GraphQL.
- [x] Refresh the PR detail after each successful action and surface failures
  without discarding the user's reply.
- [x] Add focused frontend and backend regression coverage.
- [x] Run frontend and backend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: focused backend tests pass (19/19), the full Vitest suite passes (45/45), and Maven Checkstyle, ESLint, TypeScript, and `git diff --check` are clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 38 — Markdown fence presentation

- [x] Remove the visible language label from fenced Markdown blocks.
- [x] Color Markdown heading lines separately inside fenced Markdown blocks.
- [x] Color backtick-delimited spans separately inside fenced Markdown blocks.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the full Vitest suite passes (45/45), with ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend health endpoint returned `ByteQuay-backend ok`.

## Checkpoint 39 — Left-aligned review verdicts

- [x] Keep review verdict badges beside the reviewer timestamp.
- [x] Preserve reaction and menu controls at the right edge.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the full Vitest suite passes (45/45), with ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend hello endpoint returned `ByteQuay-backend ok`.

## Checkpoint 40 — Completed merge-check status

- [x] Show a static green check after every reported check has passed.
- [x] Keep the animated progress ring only while checks are pending.
- [x] Show explicit failure and no-check states instead of a misleading spinner.
- [x] Add focused frontend regression coverage.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the full Vitest suite passes (46/46), with ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend hello endpoint returned `ByteQuay-backend ok`.

## Checkpoint 41 — Blocked merge status

- [x] Replace the progress ring with a failure icon after checks fail.
- [x] Show `Merge blocked` instead of a ready message when checks or review threads block merging.
- [x] Disable the dialog merge action until checks and review threads are clear.
- [x] Add focused frontend regression coverage.
- [x] Run frontend verification.
- [x] Restart through `dev.sh` and verify the running app.

Verification: the full Vitest suite passes (47/47), with ESLint, TypeScript, and `git diff --check` clean.

Runtime verification: a clean `dev.sh` restart launched Electron and the backend hello endpoint returned `ByteQuay-backend ok`.
