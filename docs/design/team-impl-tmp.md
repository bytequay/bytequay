# Private circles implementation checklist

This temporary checklist tracks the approved private-circle feature. Membership
is local to ByteQuay, has no product member cap, and never changes GitHub access
or sends invitations. Existing unrelated checkout changes are preserved.

## Design and contracts

- [x] Read product/contribution guidance and inspect existing attention, activity,
  persistence, refresh, and notification paths.
- [x] Record the final design and shared API contracts.

## Private circles and priority

- [x] Persist account-scoped circles and stable GitHub member identities, with
  optional repository scope and duplicate membership prevention.
- [x] Add a Teams entrance and local circle/member management.
- [x] Apply preference after eligibility, handling, and source filtering, before
  the four-card limit in both Home views.
- [x] Preserve non-team visibility, acknowledgment, snooze, pins, and native
  notification semantics; expose a Team-only filter and priority reasons.

## Activity and statistics

- [x] Collect member activity in bounded batches with shared rate-limit and
  quiet-hours behavior; preserve stable source/actor identities.
- [x] Include accessible repository PR/issue activity and public discovery,
  deduplicate before display limits, and report coverage/freshness honestly.
- [x] Retain observed facts for circle/person contribution and interaction
  statistics; separate direct interactions from shared work.
- [x] Add Team activity, People, and Insights views with period/member filters
  and links to the underlying work.

## Verification

- [x] Focused backend checks for membership, scope, collection/deduplication,
  account isolation, statistics, and failure/partial coverage behavior.
- [x] Focused frontend checks for selection beyond the previous cap, overlapping
  circles, non-team visibility, handled state, and Team interactions.
- [x] Backend `mvn verify` passes without skipped checks.
- [x] Frontend `npm run lint`, `npx tsc --noEmit`, and `npm test`.
- [x] Frontend `npm run package`.
- [x] Inspect the final UI and diff; record remaining limitations.

## Verification progress

- [x] Complete the full backend gate without skipped checks after resolving the
  shared-checkout build failures.
- [x] Recheck the current frontend and package the newly verified backend JAR.
- [x] Confirm the packaged backend matches the freshly built backend.

- [x] Home priority and directory cache tests pass, including stale-account and
  stale-mutation responses.
- [x] Seven desktop scenarios pass for circle/member management, validation,
  person insights, grouped actor identity, refresh failure retention, navigation,
  and account switching during an outstanding refresh.
- [x] Inspect Activity, Insights, and the circle dialog at wide and narrow sizes.
- [x] Fix account-change invalidation across Settings and the main window; verify
  successful token changes broadcast and failed changes retain existing caches.
- [x] Recheck private review-request history and exact statistic drilldowns after review fixes.
- [x] Full backend verification: all seven reactor modules passed, with 3,484
  tests, zero failures, zero errors, and zero skipped tests. Normal license,
  Checkstyle, compilation, Spring context, test, and packaging gates ran.
- [x] Final test cleanup uses Guava immutable collections. A concurrent context
  constructor change required updating its test fixture; the affected Team and
  context tests then passed through normal Maven verification (10 tests).
- [x] Current frontend verification: lint and TypeScript passed; all 131 test
  files and 1,367 tests passed with four workers.
- [x] Desktop packaging passed after rebuilding the backend. The bundled JAR
  matches the freshly built JAR byte-for-byte and contains the Team service,
  activity controller/service, and GitHub collector classes.
- [x] Final diff passes whitespace checks. No verification blocker remains for
  the approved Team scope.

Verification logs are in `/private/tmp/bytequay-teams-final-backend.log`,
`/private/tmp/bytequay-teams-final-followup.log`,
`/private/tmp/bytequay-teams-final-lint.log`,
`/private/tmp/bytequay-teams-final-tsc.log`,
`/private/tmp/bytequay-teams-final-tests.log`, and
`/private/tmp/bytequay-teams-final-package.log`.

## Notes

- Concurrent repository-context and agent-workflow changes were preserved.
  The earlier build blockers cleared; the final context test fixture adjustment
  only supplies its newly required build-store dependency.
- Existing user-event previews are not a complete statistics database. The new
  history must identify collection gaps and must not imply complete historical
  or real-time coverage.
- Collection retains 90 days of observations and rotates through bounded member
  and watched-repository batches. A manual refresh advances that shared batch.
  Private repository activity requires watching the accessible repository.
- Home's existing attention projection exposes logins. Re-add a renamed person
  to refresh their saved login; membership and activity use stable numeric IDs.

## Team usability fixes

- [x] Trace reported cards against local PR authors and inspect shared matching.
- [x] Name the matching member and reason on attention cards without implying
  that an unrelated latest commenter belongs to a circle.
- [x] Persist circle descriptions and atomically create circles with selected people.
- [x] Reuse the assignee-style picker with local contributor suggestions across repositories.
- [x] Preserve and verify the selected GitHub ID when saving a cached contributor.
- [x] Unify the top window drag area for Teams, Jira, Workspaces, and other pages.
- [x] Verify targeted regressions, full backend/frontend checks, and packaging.

Focused checks cover contributor search without remote typing requests,
creation with selected people, description preservation, stale contributor ID
rejection without partial writes, precise attention reasons, and shared window
drag regions with interactive and modal exclusions. Wide and narrow creation
and People layouts were inspected.

The final backend gate passed 3,493 tests with no failures, errors, or skips.
Frontend lint, TypeScript, and all 1,370 tests across 132 files passed.
Desktop packaging passed; its backend matches the verified JAR byte-for-byte
and includes the selected-member identity contract. Packaging log:
`/private/tmp/bytequay-team-usability-package.log`.
Logs: `/private/tmp/bytequay-team-usability-backend-final.log`,
`/private/tmp/bytequay-team-usability-tests-final.log`,
`/private/tmp/bytequay-team-usability-lint.log`, and
`/private/tmp/bytequay-team-usability-tsc.log`.

## Home activity sidebar cleanup

- [x] Remove the "From your teams" sidebar section and unused preview code/styles.
- [x] Keep Team activity accessible through the Teams sidebar entrance.
- [x] Update the existing navigation scenario and design contract.
- [x] Verify frontend lint, TypeScript, all 1,370 tests across 132 files, and desktop packaging.

Verification logs: `/private/tmp/bytequay-home-team-removal-lint.log`,
`/private/tmp/bytequay-home-team-removal-tsc.log`,
`/private/tmp/bytequay-home-team-removal-tests.log`, and
`/private/tmp/bytequay-home-team-removal-package.log`.

## Circles redesign

Source: Claude Design project `58646e95-9ad5-46f5-b9bf-2185d65c1988`,
`Teams Redesign.dc.html` (prototype 1a and its states).

- [x] Rename user-facing Teams copy to Circles, including Home's filter.
- [x] Backend: drill-down filters for PRs opened, merges performed, issues
  closed, comments and shared work; per-person last activity; scope totals
  with shared work counted once; per-day counts with collected days; partial
  status from the latest pass; the unverified person named on save.
- [x] Circle list, header, tabs, filter bar, status popover and refresh banner.
- [x] Needs you rows with the shared handled state, Undo and snooze menu.
- [x] Activity grouped by work item with direct/participant verbs and load more.
- [x] Members table with removal Undo; Insights tiles, per-day strip, by-person table.
- [x] Person panel with circle menu, metrics, and latest activity.
- [x] Create/edit dialogs with inline picker, repository scope and save errors.
- [x] Empty, loading, narrow-window and keyboard behavior; remembered view.
- [x] Gates: backend `mvn verify` passed 3,535 tests with no failures, errors
  or skips; frontend lint and TypeScript are clean; 1,389 of 1,390 tests passed
  in the full run, and the one Chromium suite that failed under load passed
  alone and with all 28 Chromium suites run one file at a time; desktop
  packaging passed.

Deliberate deviations: no lock icon on private repositories, because watched
repositories store no visibility; the Snooze action opens the existing snooze
menu instead of snoozing until tomorrow directly; a collected-days window runs
from the first pass's 30-day look-back to the latest pass, so an app closed for
longer than the look-back is not shown as a gap.
