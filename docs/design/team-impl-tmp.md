# Private teams implementation checklist

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
