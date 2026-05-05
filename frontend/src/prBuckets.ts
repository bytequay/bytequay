/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { HandledAction, PullRequestDto } from './types';
import { getCached, setCached } from './dataCache';

// Resurface window. A PR in the Inbox jumps back to Needs-attention when
// upstream `updatedAt` advances past `reviewedAt + RESURFACE_GRACE_MS`.
// See docs/mockups/v2/pr-state-definitions.md §"Did something change?"
export const RESURFACE_GRACE_MS = 60 * 60 * 1000;

const PRS_CACHE_KEY = 'prs:list';
const repoPullsKey = (owner: string, repo: string) => `repo:${owner}/${repo}:pulls`;

/**
 * Propagate a PR patch into every data-cache entry that could hold the PR,
 * so other pages/tabs see the latest state on their next mount.
 */
export function syncCachesAfterPrChange(
  prId: number,
  patch: Partial<PullRequestDto>,
  repo?: string,
): void {
  const main = getCached<PullRequestDto[]>(PRS_CACHE_KEY);
  if (main) setCached(PRS_CACHE_KEY, main.map(p => (p.id === prId ? { ...p, ...patch } : p)));

  if (repo && repo.includes('/')) {
    const [owner, name] = repo.split('/');
    const key = repoPullsKey(owner, name);
    const repoCached = getCached<PullRequestDto[]>(key);
    if (repoCached) setCached(key, repoCached.map(p => (p.id === prId ? { ...p, ...patch } : p)));
  }
}

export type Bucket = 'inbox' | 'snoozed' | 'handled';

/**
 * Terminal Handled states per docs/mockups/v2/pr-state-definitions.md:
 *   - MERGED     — the PR was merged (by anyone)
 *   - DISMISSED  — the user closed the PR without merging
 *   - MANUAL     — the user clicked "Mark as handled" to dismiss from Inbox
 *
 * Note that APPROVED is NOT handled — approved PRs live in the Inbox's
 * Cleared zone until they're merged. That way the user can track what's
 * almost shipped without flipping between views.
 */
export function isHandled(pr: PullRequestDto): boolean {
  const a = pr.handledAction;
  return a === 'MERGED' || a === 'DISMISSED' || a === 'MANUAL';
}

/**
 * A PR is snoozed if `snoozedUntil` is set and still in the future. Past
 * the deadline the backend's auto-wake job clears the field on its next
 * sync, but the UI shouldn't wait — we treat expired snoozes as inbox.
 */
export function isSnoozed(pr: PullRequestDto, now: number = Date.now()): boolean {
  if (!pr.snoozedUntil) return false;
  return new Date(pr.snoozedUntil).getTime() > now;
}

/** Snooze takes precedence over Handled — a parked merged PR still hides. */
export function bucketize(pr: PullRequestDto, now: number = Date.now()): Bucket {
  if (isSnoozed(pr, now)) return 'snoozed';
  return isHandled(pr) ? 'handled' : 'inbox';
}

/**
 * A PR "resurfaces" from a quiet zone (Awaiting author / In progress after
 * review) back to Needs attention when upstream activity appears after the
 * user last reviewed it. Cleared (APPROVED) and Handled states don't
 * auto-resurface — the doc calls out only rare CI-regression / force-push
 * cases for Cleared, and those aren't detectable from list-level data.
 */
export function isResurfaced(pr: PullRequestDto): boolean {
  if (pr.reviewedAt === null) return false;
  if (isHandled(pr)) return false;
  if (pr.handledAction === 'APPROVED') return false;
  const reviewedMs = new Date(pr.reviewedAt).getTime();
  const updatedMs = new Date(pr.updatedAt).getTime();
  return updatedMs > reviewedMs + RESURFACE_GRACE_MS;
}

// ── Kanban zone (aka Category) — see pr-state-definitions.md ────────────────

/**
 * The four Inbox zones:
 *
 *   needs_attention — "Your turn, now."
 *   in_progress     — "You touched it, you own the next step."
 *   awaiting_author — "Not your turn."
 *   cleared         — "Resolved, but not merged."
 *
 * Merged / Dismissed / Manually-handled PRs are *not* a zone — they leave
 * the Inbox and land in the Handled tab (see `isHandled`).
 */
export type Category = 'needs_attention' | 'in_progress' | 'awaiting_author' | 'cleared';

export const CATEGORIES: Category[] = [
  'needs_attention',
  'in_progress',
  'awaiting_author',
  'cleared',
];

export const CATEGORY_LABEL: Record<Category, string> = {
  needs_attention: 'Needs attention',
  in_progress: 'In progress',
  awaiting_author: 'Awaiting author',
  cleared: 'Cleared',
};

export const CATEGORY_ONELINER: Record<Category, string> = {
  needs_attention: 'Your turn, now.',
  in_progress: 'You touched it, you own the next step.',
  awaiting_author: 'Not your turn.',
  cleared: 'Resolved, but not merged.',
};

/**
 * Assigns an Inbox PR to one of the four zones. Callers should pre-filter
 * out handled PRs (see `isHandled`) — invoking `categorize` on a handled PR
 * returns Cleared as a safe fallback, but the PR really belongs in the
 * Handled tab.
 */
export function categorize(pr: PullRequestDto): Category {
  // Resurfaced PRs always jump back to Needs attention.
  if (isResurfaced(pr)) return 'needs_attention';

  // Terminal-state PRs go to Cleared first. Without these guards a
  // merged-on-GitHub PR whose local handledAction is still null
  // (the user merged via the web, never via our app) falls through
  // to the viewedAt / draft branches and lands in In Progress —
  // which is wrong: there's nothing to do, the PR is done.
  if (pr.mergedAt !== null) return 'cleared';
  if (pr.state === 'closed') return 'cleared';

  const action = pr.handledAction;

  // User approved (Awaiting Review side) — Cleared zone: visible but quiet.
  if (action === 'APPROVED') return 'cleared';

  // User left a review without approving — ball is with the author.
  if (action === 'CHANGES_REQUESTED' || action === 'COMMENTED') return 'awaiting_author';

  // Drafts park in Cleared too — they're not yet an actionable review
  // request, so they don't belong in any active zone. The kanban's
  // dedicated DRAFTING column is where drafts live as first-class
  // citizens; the sidebar list keeps them out of the way.
  if (pr.draft) return 'cleared';

  // User has peeked at the PR but hasn't reviewed yet → In progress.
  if (pr.viewedAt !== null && pr.reviewedAt === null) return 'in_progress';

  // Handled fallback (shouldn't usually reach this point for Inbox PRs).
  if (isHandled(pr)) return 'cleared';

  // Otherwise: brand new / unopened → Needs attention.
  return 'needs_attention';
}

/**
 * Group a list of PRs into the four Inbox zones, preserving input order.
 * Handled PRs are filtered out — callers who want them should use
 * `splitInboxAndHandled`.
 */
export function groupByCategory(prs: PullRequestDto[], now: number = Date.now()): Record<Category, PullRequestDto[]> {
  const out: Record<Category, PullRequestDto[]> = {
    needs_attention: [],
    in_progress: [],
    awaiting_author: [],
    cleared: [],
  };
  for (const pr of prs) {
    if (isSnoozed(pr, now)) continue;
    if (isHandled(pr)) continue;
    out[categorize(pr)].push(pr);
  }
  return out;
}

/** Split a PR list into Inbox (active), Snoozed (parked), and Handled
 *  (terminal action) sets. Snoozed wins over Handled — a merged-but-
 *  snoozed PR stays in Snoozed until its wake time. */
export function splitByBucket(prs: PullRequestDto[], now: number = Date.now()): {
  inbox: PullRequestDto[];
  snoozed: PullRequestDto[];
  handled: PullRequestDto[];
} {
  const inbox: PullRequestDto[] = [];
  const snoozed: PullRequestDto[] = [];
  const handled: PullRequestDto[] = [];
  for (const pr of prs) {
    const bucket = bucketize(pr, now);
    if (bucket === 'snoozed') snoozed.push(pr);
    else if (bucket === 'handled') handled.push(pr);
    else inbox.push(pr);
  }
  return { inbox, snoozed, handled };
}

/** Sort snoozed PRs by wake time ascending — soonest waking first. */
export function sortSnoozed(prs: PullRequestDto[]): PullRequestDto[] {
  return [...prs].sort((a, b) => {
    const am = a.snoozedUntil ? new Date(a.snoozedUntil).getTime() : 0;
    const bm = b.snoozedUntil ? new Date(b.snoozedUntil).getTime() : 0;
    return am - bm;
  });
}

export type HandledBadge = { label: string; cls: string; icon: string };

export function handledBadge(action: HandledAction | null): HandledBadge {
  switch (action) {
    case 'APPROVED': return { label: 'Approved', cls: 'handled-badge--approved', icon: '✓' };
    case 'MERGED': return { label: 'Merged', cls: 'handled-badge--merged', icon: '✓' };
    case 'COMMENTED': return { label: 'Commented', cls: 'handled-badge--commented', icon: '💬' };
    case 'CHANGES_REQUESTED': return { label: 'Requested changes', cls: 'handled-badge--changes', icon: '✗' };
    case 'DISMISSED': return { label: 'Dismissed', cls: 'handled-badge--dismissed', icon: '⊘' };
    case 'MANUAL':
    default:
      return { label: 'Handled', cls: 'handled-badge--dismissed', icon: '✓' };
  }
}

export function formatRelative(iso: string | null, now: number = Date.now()): string {
  if (!iso) return '';
  const diffMs = now - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 5) return `${hrs}h ago`;
  if (hrs < 12) return 'this morning';
  if (hrs < 24) return 'today';
  if (hrs < 48) return 'yesterday';
  const days = Math.round(hrs / 24);
  if (days < 7) {
    return new Date(iso).toLocaleDateString(undefined, { weekday: 'long' });
  }
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export type HandledGroups = {
  today: PullRequestDto[];
  thisWeek: PullRequestDto[];
  older: PullRequestDto[];
};

/** Splits handled PRs into Today / This week / Older based on reviewedAt. */
export function groupHandledByTime(prs: PullRequestDto[], now: number = Date.now()): HandledGroups {
  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const todayMs = startOfToday.getTime();
  const weekAgoMs = now - 7 * 24 * 60 * 60 * 1000;

  const today: PullRequestDto[] = [];
  const thisWeek: PullRequestDto[] = [];
  const older: PullRequestDto[] = [];
  for (const pr of prs) {
    const ms = pr.reviewedAt ? new Date(pr.reviewedAt).getTime() : 0;
    if (ms >= todayMs) today.push(pr);
    else if (ms >= weekAgoMs) thisWeek.push(pr);
    else older.push(pr);
  }
  return { today, thisWeek, older };
}

/** Sort handled PRs newest first by reviewedAt. */
export function sortHandled(prs: PullRequestDto[]): PullRequestDto[] {
  return [...prs].sort((a, b) => {
    const am = a.reviewedAt ? new Date(a.reviewedAt).getTime() : 0;
    const bm = b.reviewedAt ? new Date(b.reviewedAt).getTime() : 0;
    return bm - am;
  });
}

/** Returns a new list with one PR replaced by applying the patch. No-op if not found. */
export function patchPr(
  prs: PullRequestDto[],
  prId: number,
  patch: Partial<PullRequestDto>,
): PullRequestDto[] {
  return prs.map(pr => (pr.id === prId ? { ...pr, ...patch } : pr));
}

/** Optimistic patch when the user clicks "Handled" on a card. */
export function markHandledPatch(action: HandledAction, now: string = new Date().toISOString()): Partial<PullRequestDto> {
  return { reviewedAt: now, handledAction: action };
}

/** Optimistic patch when the user reopens a handled PR. */
export function reopenPatch(): Partial<PullRequestDto> {
  return { reviewedAt: null, handledAction: null };
}

/** Optimistic patch when a merge succeeds — same fields as
 *  markHandledPatch('MERGED') plus state + mergedAt so the OPEN pill
 *  flips to MERGED and the merge bar gates off (`!pr.mergedAt`)
 *  without waiting for the next sync. */
export function mergedPatch(now: string = new Date().toISOString()): Partial<PullRequestDto> {
  return {
    reviewedAt: now,
    handledAction: 'MERGED',
    state: 'merged',
    mergedAt: now,
  };
}

/** Rollback for {@link mergedPatch} when the merge call fails — restores
 *  the snapshot taken before the optimistic patch was applied. */
export function unmergedPatch(previousState: string | null, previousMergedAt: string | null): Partial<PullRequestDto> {
  return {
    reviewedAt: null,
    handledAction: null,
    state: previousState,
    mergedAt: previousMergedAt,
  };
}

// ── Phase 3 kanban refactor ────────────────────────────────────────────────
// Two parallel column models for the new kanban (docs/design/kanban-refactor.md).
// "My PRs" lane (origin = AUTHORED) has 5 columns; "To review" lane
// (origin = REVIEW_REQUESTED) has 4. Both reuse the v26 list-DTO fields —
// see types.ts. Existing 4-column `Category` above stays for back-compat
// with non-kanban callers (HomePage, PrBucketViews, PullRequestList sidebar).

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

export type MyPrColumn =
  | 'drafting'
  | 'waiting_on_review'
  | 'needs_changes'
  | 'ready_to_merge'
  | 'recently_merged'
  | 'handled';

export type ToReviewColumn =
  | 'needs_attention'
  | 'in_progress'
  | 'awaiting_author'
  | 'cleared_today';

export const MY_PR_COLUMNS: MyPrColumn[] = [
  'drafting',
  'waiting_on_review',
  'needs_changes',
  'ready_to_merge',
  'recently_merged',
];

/** Team kanban renders this superset — adds the trailing "Handled"
 *  column for PRs the user dismissed via mark-handled. The inbox
 *  kanban skips it because the inbox/handled split happens at the
 *  page level. */
export const MY_PR_COLUMNS_TEAM: MyPrColumn[] = [...MY_PR_COLUMNS, 'handled'];

export const TO_REVIEW_COLUMNS: ToReviewColumn[] = [
  'needs_attention',
  'in_progress',
  'awaiting_author',
  'cleared_today',
];

export const MY_PR_COLUMN_LABEL: Record<MyPrColumn, string> = {
  drafting: 'Drafting',
  waiting_on_review: 'Waiting on review',
  needs_changes: 'Needs changes',
  ready_to_merge: 'Ready to merge',
  recently_merged: 'Recently merged',
  handled: 'Handled',
};

export const TO_REVIEW_COLUMN_LABEL: Record<ToReviewColumn, string> = {
  needs_attention: 'Needs attention',
  in_progress: 'In progress',
  awaiting_author: 'Awaiting author',
  cleared_today: 'Cleared today',
};

/**
 * Returns the My-PR column for an authored PR, or null when the PR
 * doesn't belong to any column (e.g. closed > 7 days, or a PR that
 * isn't authored by the current user).
 */
export function categorizeMyPr(pr: PullRequestDto, now: number = Date.now()): MyPrColumn | null {
  if (pr.origin !== 'AUTHORED') return null;

  const merged = pr.mergedAt ? new Date(pr.mergedAt).getTime() : null;
  const closed = pr.closedAt ? new Date(pr.closedAt).getTime() : null;
  // Recently-merged window covers anything closed/merged within 7 days.
  // Older than that = drops out of the kanban.
  if (merged !== null && now - merged <= SEVEN_DAYS_MS) return 'recently_merged';
  if (closed !== null && now - closed <= SEVEN_DAYS_MS && pr.state !== 'open') return 'recently_merged';
  if (pr.state === 'closed' || pr.state === 'merged') return null;

  if (pr.draft) return 'drafting';

  const verdicts = pr.reviewerVerdicts ?? {};
  const verdictValues = Object.values(verdicts);
  const hasApproval = verdictValues.includes('APPROVED');
  const hasChangesRequested = verdictValues.includes('CHANGES_REQUESTED');

  // Ready to merge = green path: ≥1 approval, no outstanding change
  // request, CI passing, and GitHub doesn't actively say "no" to the
  // merge. We tolerate `mergeable === null` (GitHub is still computing
  // for ~30s after a push, and our cached row often catches it in that
  // window) — only `mergeable === false` blocks the column. Without
  // this tolerance the column stays empty for PRs that are objectively
  // ready, just because the cache hasn't refreshed yet.
  if (hasApproval && !hasChangesRequested && pr.ciStatus === 'PASSING' && pr.mergeable !== false) {
    return 'ready_to_merge';
  }

  // Any explicit change request is blocking — author has work to do.
  // Without unresolvedThreadCount (GraphQL-only) we don't try to detect
  // "unanswered review feedback" beyond an explicit CHANGES_REQUESTED.
  if (hasChangesRequested) return 'needs_changes';

  // No reviewer has weighed in yet → still waiting on review.
  return 'waiting_on_review';
}

/**
 * Returns the To-review column for a review-requested PR, or null when
 * it doesn't belong (wrong origin, or cleared > today).
 */
export function categorizeToReview(pr: PullRequestDto, now: number = Date.now()): ToReviewColumn | null {
  if (pr.origin !== 'REVIEW_REQUESTED') return null;

  // Cleared today: handled action with reviewedAt today. Anything older
  // drops out of the kanban (lives in the Handled tab via splitInboxAndHandled).
  if (isHandled(pr) || pr.handledAction === 'APPROVED') {
    if (!pr.reviewedAt) return null;
    const reviewedMs = new Date(pr.reviewedAt).getTime();
    const startOfToday = new Date(now);
    startOfToday.setHours(0, 0, 0, 0);
    return reviewedMs >= startOfToday.getTime() ? 'cleared_today' : null;
  }

  // Resurfaced or attention-flagged → top priority.
  if (isResurfaced(pr) || (pr.attentionReason ?? null) !== null) return 'needs_attention';

  // User left feedback that the author hasn't addressed yet.
  if (pr.handledAction === 'CHANGES_REQUESTED' || pr.handledAction === 'COMMENTED') {
    return 'awaiting_author';
  }

  // User has peeked but not reviewed → still in progress for them.
  if (pr.viewedAt !== null && pr.reviewedAt === null) return 'in_progress';

  // Brand-new review request → Needs attention.
  return 'needs_attention';
}

export function groupMyPrs(prs: PullRequestDto[], now: number = Date.now()): Record<MyPrColumn, PullRequestDto[]> {
  const out: Record<MyPrColumn, PullRequestDto[]> = {
    drafting: [],
    waiting_on_review: [],
    needs_changes: [],
    ready_to_merge: [],
    recently_merged: [],
    handled: [],
  };
  for (const pr of prs) {
    const col = categorizeMyPr(pr, now);
    if (col) out[col].push(pr);
  }
  // Per-column sort so the most actionable card sits at the top.
  out.drafting.sort(byUpdatedAtAsc);              // oldest first (user prefs)
  out.waiting_on_review.sort(byCreatedAtAsc);     // oldest = most stale
  out.needs_changes.sort(byUpdatedAtAsc);         // oldest first (user prefs)
  out.ready_to_merge.sort(byUpdatedAtAsc);        // oldest first (user prefs)
  out.recently_merged.sort(byMergedAtDesc);       // newest merges first
  out.handled.sort(byReviewedAtDesc);             // most recently dismissed first
  return out;
}

export function groupToReview(prs: PullRequestDto[], now: number = Date.now()): Record<ToReviewColumn, PullRequestDto[]> {
  const out: Record<ToReviewColumn, PullRequestDto[]> = {
    needs_attention: [],
    in_progress: [],
    awaiting_author: [],
    cleared_today: [],
  };
  for (const pr of prs) {
    const col = categorizeToReview(pr, now);
    if (col) out[col].push(pr);
  }
  // Per-column sort. needs_attention uses an attention-severity rank as
  // primary key so a CI_FAILING / MENTIONED PR always sits above a plain
  // unviewed one — that's the "second PR becomes top 1 once you've handled
  // the first" behaviour the user wants. Older PRs win ties (stale longer
  // = more deserving of attention).
  out.needs_attention.sort((a, b) => {
    const sa = attentionRank(a);
    const sb = attentionRank(b);
    if (sa !== sb) return sa - sb;
    return byCreatedAtAsc(a, b);
  });
  out.in_progress.sort(byUpdatedAtAsc);           // oldest first (user prefs)
  out.awaiting_author.sort(byUpdatedAtAsc);       // oldest first (user prefs)
  out.cleared_today.sort(byReviewedAtDesc);       // most recently cleared first
  return out;
}

// Lower rank = more urgent. Anything you'd want pushed to the top of the
// "Needs attention" column. CI_FAILING + MERGE_CONFLICT are blocking and
// time-sensitive; MENTIONED is a personal call-out; the rest fall through
// to the brand-new bucket. Tweak this table if priorities shift.
const ATTENTION_RANK: Record<string, number> = {
  CI_FAILING: 0,
  MERGE_CONFLICT: 1,
  MENTIONED: 2,
  NEW_COMMENT: 3,
  BLOCKING: 4,
  STALE: 5,
  MINE: 6,
};
function attentionRank(pr: PullRequestDto): number {
  if (pr.attentionReason && ATTENTION_RANK[pr.attentionReason] !== undefined) {
    return ATTENTION_RANK[pr.attentionReason];
  }
  // No attentionReason but landed in needs_attention = brand-new request.
  // Sit below explicit reasons but above resolved ones.
  return 7;
}

function byCreatedAtAsc(a: PullRequestDto, b: PullRequestDto): number {
  const ta = a.createdAt ? new Date(a.createdAt).getTime() : new Date(a.updatedAt).getTime();
  const tb = b.createdAt ? new Date(b.createdAt).getTime() : new Date(b.updatedAt).getTime();
  return ta - tb;
}
function byUpdatedAtAsc(a: PullRequestDto, b: PullRequestDto): number {
  return new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime();
}
function byMergedAtDesc(a: PullRequestDto, b: PullRequestDto): number {
  const ta = a.mergedAt ? new Date(a.mergedAt).getTime() : 0;
  const tb = b.mergedAt ? new Date(b.mergedAt).getTime() : 0;
  return tb - ta;
}
function byReviewedAtDesc(a: PullRequestDto, b: PullRequestDto): number {
  const ta = a.reviewedAt ? new Date(a.reviewedAt).getTime() : 0;
  const tb = b.reviewedAt ? new Date(b.reviewedAt).getTime() : 0;
  return tb - ta;
}

// ── Briefing — at-a-glance counters used by the page header / banner ──────

/** Roll-up of the kanban columns the user reads "at a glance". The
 *  My PRs summary banner pulls from `mine*`; the To Review summary
 *  banner from `toReview*`. The page-header red-dot alert on the
 *  My PRs scope tab fires when `mineNeedsAction > 0`. */
export type Briefing = {
  mineTotal: number;
  mineNeedsAction: number;        // needs_changes + ready_to_merge
  mineReadyToMerge: number;
  mineNeedsChanges: number;
  toReviewTotal: number;
  toReviewNeedsAttention: number;
  toReviewInProgress: number;
};

export function buildBriefing(prs: PullRequestDto[]): Briefing {
  const myPrs = prs.filter(p => p.origin === 'AUTHORED');
  const toReview = prs.filter(p => p.origin === 'REVIEW_REQUESTED');
  const myGroups = groupMyPrs(myPrs);
  const trGroups = groupToReview(toReview);
  return {
    mineTotal: Object.values(myGroups).reduce((s, l) => s + l.length, 0),
    mineNeedsAction: myGroups.needs_changes.length + myGroups.ready_to_merge.length,
    mineReadyToMerge: myGroups.ready_to_merge.length,
    mineNeedsChanges: myGroups.needs_changes.length,
    toReviewTotal: Object.values(trGroups).reduce((s, l) => s + l.length, 0),
    toReviewNeedsAttention: trGroups.needs_attention.length,
    toReviewInProgress: trGroups.in_progress.length,
  };
}
