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
import type { AttentionReason, CiStatus, HandledAction, PullRequestDto } from './types';
import type { DashboardPR } from './types/dashboardPr';
import { getCached, setCached } from './dataCache';

/**
 * The subset of PR fields every categorization/sort/bucket function in this
 * file actually reads. Both `PullRequestDto` (repo/team-scoped, GitHub-live,
 * numeric id) and `DashboardPR` (the personal dashboard, unified-`pr`-backed,
 * string id) satisfy this shape — every function below is generic over it so
 * one implementation serves both callers without duplicating the
 * categorization rules. `id` is deliberately excluded: the two concrete
 * types disagree on its type, and only `patchPr` needs it (handled there via
 * its own generic bound).
 */
export type PrLike = {
  repo: string;
  number: number;
  title: string;
  author: string | null;
  labels: string[];
  origin: 'AUTHORED' | 'REVIEW_REQUESTED' | null;
  handledAction: HandledAction | null;
  snoozedUntil: string | null;
  snoozeWakeReason: string | null;
  viewedAt: string | null;
  reviewedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  mergedAt: string | null;
  closedAt: string | null;
  state: string | null;
  draft: boolean;
  attentionReason: AttentionReason | null;
  ciStatus: CiStatus | null;
  mergeable: boolean | null;
  mergeableState: string | null;
  reviewerVerdicts: Record<string, string> | null;
  requestedReviewers: string[];
  reviewState?: 'none' | 'running' | 'done' | 'stale';
};

/** {@link PrLike} plus an id — the bound every card/list component in the
 *  kanban + bucket-view layer uses, since (unlike the pure categorization
 *  functions above) they render `#{number}`/key by id and — for draggable
 *  cards — carry it in the drag payload. */
export type PrLikeWithId = PrLike & { id: number | string };

// Resurface window. A PR in the Inbox jumps back to Needs-attention when
// upstream `updatedAt` advances past `reviewedAt + RESURFACE_GRACE_MS`.
// See docs/mockups/v2/pr-state-definitions.md §"Did something change?"
export const RESURFACE_GRACE_MS = 60 * 60 * 1000;

const PRS_CACHE_KEY = 'prs:list';
const repoPullsKey = (owner: string, repo: string) => `repo:${owner}/${repo}:pulls`;

/**
 * Propagate a PR patch into every data-cache entry that could hold the PR,
 * so other pages/tabs see the latest state on their next mount. Repo/team
 * scope only (numeric GitHub id, live `PullRequestDto` rows) — the personal
 * dashboard's unified-PR cache has its own patch helper, {@link
 * patchDashboardCache}, since the two caches hold structurally different
 * rows for the same conceptual PR.
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

/** Same idea as {@link syncCachesAfterPrChange}, for the personal
 *  dashboard's unified-PR cache (string id, `DashboardPR` rows). */
export function patchDashboardCache(prId: string, patch: Partial<DashboardPR>): void {
  const main = getCached<DashboardPR[]>(PRS_CACHE_KEY);
  if (main) setCached(PRS_CACHE_KEY, main.map(p => (p.id === prId ? { ...p, ...patch } : p)));
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
export function isHandled(pr: PrLike): boolean {
  const a = pr.handledAction;
  return a === 'MERGED' || a === 'DISMISSED' || a === 'MANUAL';
}

/**
 * A PR is snoozed if `snoozedUntil` is set and still in the future. Past
 * the deadline the backend's auto-wake job clears the field on its next
 * sync, but the UI shouldn't wait — we treat expired snoozes as inbox.
 */
export function isSnoozed(pr: PrLike, now: number = Date.now()): boolean {
  if (!pr.snoozedUntil) return false;
  return new Date(pr.snoozedUntil).getTime() > now;
}

/** Snooze takes precedence over Handled — a parked merged PR still hides. */
export function bucketize(pr: PrLike, now: number = Date.now()): Bucket {
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
export function isResurfaced(pr: PrLike): boolean {
  if (pr.reviewedAt === null) return false;
  if (isHandled(pr)) return false;
  if (pr.handledAction === 'APPROVED') return false;
  const reviewedMs = new Date(pr.reviewedAt).getTime();
  const updatedMs = pr.updatedAt ? new Date(pr.updatedAt).getTime() : 0;
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
export function categorize(pr: PrLike): Category {
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
 * `splitByBucket`.
 */
export function groupByCategory<T extends PrLike>(prs: T[], now: number = Date.now()): Record<Category, T[]> {
  const out: Record<Category, T[]> = {
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
  for (const cat of CATEGORIES) out[cat].sort(byUpdatedAtDesc);
  return out;
}

/** Split a PR list into Inbox (active), Snoozed (parked), and Handled
 *  (terminal action) sets. Snoozed wins over Handled — a merged-but-
 *  snoozed PR stays in Snoozed until its wake time. */
export function splitByBucket<T extends PrLike>(prs: T[], now: number = Date.now()): {
  inbox: T[];
  snoozed: T[];
  handled: T[];
} {
  const inbox: T[] = [];
  const snoozed: T[] = [];
  const handled: T[] = [];
  for (const pr of prs) {
    const bucket = bucketize(pr, now);
    if (bucket === 'snoozed') snoozed.push(pr);
    else if (bucket === 'handled') handled.push(pr);
    else inbox.push(pr);
  }
  return { inbox, snoozed, handled };
}

/** Sort snoozed PRs by wake time ascending — soonest waking first. */
export function sortSnoozed<T extends PrLike>(prs: T[]): T[] {
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

export type HandledGroups<T> = {
  today: T[];
  thisWeek: T[];
  older: T[];
};

/** Splits handled PRs into Today / This week / Older based on reviewedAt. */
export function groupHandledByTime<T extends PrLike>(prs: T[], now: number = Date.now()): HandledGroups<T> {
  const startOfToday = new Date(now);
  startOfToday.setHours(0, 0, 0, 0);
  const todayMs = startOfToday.getTime();
  const weekAgoMs = now - 7 * 24 * 60 * 60 * 1000;

  const today: T[] = [];
  const thisWeek: T[] = [];
  const older: T[] = [];
  for (const pr of prs) {
    const ms = pr.reviewedAt ? new Date(pr.reviewedAt).getTime() : 0;
    if (ms >= todayMs) today.push(pr);
    else if (ms >= weekAgoMs) thisWeek.push(pr);
    else older.push(pr);
  }
  return { today, thisWeek, older };
}

/** Sort handled PRs newest first by reviewedAt. */
export function sortHandled<T extends PrLike>(prs: T[]): T[] {
  return [...prs].sort((a, b) => {
    const am = a.reviewedAt ? new Date(a.reviewedAt).getTime() : 0;
    const bm = b.reviewedAt ? new Date(b.reviewedAt).getTime() : 0;
    return bm - am;
  });
}

/** Returns a new list with one PR replaced by applying the patch. No-op if
 *  not found. `prId` is deliberately `number | string` rather than `T['id']`
 *  — an indexed-access parameter type blocks TS from inferring `T` from the
 *  `prs`/`patch` arguments at call sites that pass a `Partial<PrLike>` patch
 *  (markHandledPatch/reopenPatch's return type). */
export function patchPr<T extends { id: number | string }>(
  prs: T[],
  prId: number | string,
  patch: Partial<T>,
): T[] {
  return prs.map(pr => (pr.id === prId ? { ...pr, ...patch } : pr));
}

/** Optimistic patch when the user clicks "Handled" on a card. */
export function markHandledPatch(action: HandledAction, now: string = new Date().toISOString()): Partial<PrLike> {
  return { reviewedAt: now, handledAction: action };
}

/** Optimistic patch when the user reopens a handled PR. */
export function reopenPatch(): Partial<PrLike> {
  return { reviewedAt: null, handledAction: null };
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

/** Inbox MY-PRs lane — three active columns + recently merged. The
 *  former "Ready to merge" column was folded into "Waiting on review"
 *  (approved-and-green PRs are still waiting on the author to click
 *  merge); needs_changes sits left of waiting_on_review so the
 *  scariest signal hits the eye first. */
export const MY_PR_COLUMNS: MyPrColumn[] = [
  'drafting',
  'needs_changes',
  'waiting_on_review',
  'recently_merged',
];

/** Team kanban keeps the wider 5-column layout because the backend's
 *  per-team categorizer still emits ready_to_merge as a distinct
 *  bucket. Defined independently of MY_PR_COLUMNS so changes to one
 *  don't bleed into the other. */
export const MY_PR_COLUMNS_TEAM: MyPrColumn[] = [
  'drafting',
  'waiting_on_review',
  'needs_changes',
  'ready_to_merge',
  'recently_merged',
  'handled',
];

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

/** Inbox MY-PRs lane uses second-person labels — these PRs are the
 *  user's own, so "Needs your changes" / "Waiting reviewers" reads
 *  more directly than the generic team labels above. The team kanban
 *  keeps the third-person versions because the viewer isn't the
 *  author there. */
export const MY_PR_COLUMN_LABEL_INBOX: Record<MyPrColumn, string> = {
  ...MY_PR_COLUMN_LABEL,
  waiting_on_review: 'Waiting reviewers',
  needs_changes: 'Needs your changes',
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
export function categorizeMyPr(pr: PrLike, now: number = Date.now()): MyPrColumn | null {
  if (pr.origin !== 'AUTHORED') return null;

  const merged = pr.mergedAt ? new Date(pr.mergedAt).getTime() : null;
  const closed = pr.closedAt ? new Date(pr.closedAt).getTime() : null;
  // Recently-merged window covers anything closed/merged within 7 days.
  // Older than that = drops out of the kanban.
  if (merged !== null && now - merged <= SEVEN_DAYS_MS) return 'recently_merged';
  if (closed !== null && now - closed <= SEVEN_DAYS_MS && pr.state !== 'open') return 'recently_merged';
  if (pr.state === 'closed' || pr.state === 'merged') return null;

  if (pr.draft) return 'drafting';

  // Auto-woke PRs always land in Needs your changes — the wake fires
  // when CI fails, a reviewer requests changes, or a merge conflict
  // appears, all of which require the author's eye. The wake banner
  // on the card explains the specific reason. Acknowledging (opening
  // the PR) clears snoozeWakeReason so it falls back to its
  // verdict / CI-derived column on the next render.
  if (pr.snoozeWakeReason) return 'needs_changes';

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

  // Failing CI is the author's problem too — same column. Sits below
  // hasChangesRequested only because the order doesn't matter (both
  // routes return the same value); keeping CHANGES_REQUESTED first is
  // a readability signal that human feedback is the primary trigger.
  if (pr.ciStatus === 'FAILING') return 'needs_changes';

  // File-conflicts state — mergeableState === 'dirty' is GitHub's
  // canonical "head conflicts with base" signal. The author has to
  // rebase / merge / resolve before anyone can merge this PR, so it
  // belongs in Needs your changes alongside CI failures and
  // CHANGES_REQUESTED. We deliberately don't trip on `mergeable ===
  // false` more broadly — that also fires on 'blocked' (branch-
  // protection rules aren't satisfied) and 'behind' (head is behind
  // base but otherwise clean), neither of which is a file conflict.
  if (pr.mergeableState === 'dirty') return 'needs_changes';

  // No reviewer has weighed in yet → still waiting on review.
  return 'waiting_on_review';
}

/**
 * "Is this review actually my turn?" — true when the PR has my review
 * requested right now, or I've already participated (left a GitHub
 * verdict, or opened / reviewed it locally). Gates the To-review
 * "Needs attention" column: a review-requested PR I'm neither asked to
 * review nor have touched isn't really on my plate.
 *
 * `me` is the current GitHub login. When it's unknown (null — the
 * profile hasn't loaded yet) we can't check the GitHub-side signals
 * (`requestedReviewers` / `reviewerVerdicts`), so we fall back to local
 * participation only but treat the result as "yes" — better to show a
 * PR than to hide it because the login wasn't ready.
 */
export function isMyReviewTurn(pr: PrLike, me: string | null): boolean {
  // Local engagement is mine regardless of login — the app only records
  // viewedAt / reviewedAt for the signed-in user.
  if (pr.viewedAt !== null || pr.reviewedAt !== null) return true;
  // Login unknown → can't evaluate the GitHub-side signals; don't hide.
  if (!me) return true;
  if (pr.requestedReviewers.includes(me)) return true;
  if (pr.reviewerVerdicts && Object.prototype.hasOwnProperty.call(pr.reviewerVerdicts, me)) return true;
  return false;
}

/**
 * Returns the To-review column for a review-requested PR, or null when
 * it doesn't belong (wrong origin, or cleared > today).
 *
 * A REVIEW_REQUESTED origin is trusted as "my turn" on its own: the
 * backend only tags it when the PR came back from the
 * user-review-requested search, which already means my review is asked
 * for — directly or through a team I'm on. We deliberately don't
 * re-derive "my turn" from the individual requestedReviewers list, which
 * omits the user on a team request and used to drop those PRs. `me` is
 * retained for callers but no longer gates needs_attention.
 */
export function categorizeToReview(pr: PrLike, now: number = Date.now(), _me: string | null = null): ToReviewColumn | null {
  if (pr.origin !== 'REVIEW_REQUESTED') return null;
  if (pr.mergedAt !== null || pr.state === 'closed' || pr.state === 'merged') return null;

  // Cleared today: handled action with reviewedAt today. Anything older
  // drops out of the kanban (lives in the Handled tab via splitInboxAndHandled).
  if (isHandled(pr) || pr.handledAction === 'APPROVED') {
    if (!pr.reviewedAt) return null;
    const reviewedMs = new Date(pr.reviewedAt).getTime();
    const startOfToday = new Date(now);
    startOfToday.setHours(0, 0, 0, 0);
    return reviewedMs >= startOfToday.getTime() ? 'cleared_today' : null;
  }

  // Resurfaced → top priority. Resurfacing requires reviewedAt, so this
  // is always my turn (I reviewed it; the author just pushed back).
  if (isResurfaced(pr)) return 'needs_attention';

  // Attention-flagged → needs_attention. The origin already means the
  // review is asked of me (or my team), so any flag on it is mine to chase.
  if ((pr.attentionReason ?? null) !== null) {
    return 'needs_attention';
  }

  // User left feedback that the author hasn't addressed yet.
  if (pr.handledAction === 'CHANGES_REQUESTED' || pr.handledAction === 'COMMENTED') {
    return 'awaiting_author';
  }

  // Failing CI flips the ball to the author too — even when the user
  // hasn't reviewed yet. Sits after the attention/handled checks so
  // explicit signals (mention, ping, prior feedback) still win.
  if (pr.ciStatus === 'FAILING') return 'awaiting_author';

  // User has peeked but not reviewed → still in progress for them.
  if (pr.viewedAt !== null && pr.reviewedAt === null) return 'in_progress';

  // Brand-new review request → Needs attention. The origin is the proof
  // it's my turn (direct or team request), so it lands here regardless of
  // whether my login appears in the individual requestedReviewers list.
  return 'needs_attention';
}

export function groupMyPrs<T extends PrLike>(prs: T[], now: number = Date.now()): Record<MyPrColumn, T[]> {
  const out: Record<MyPrColumn, T[]> = {
    drafting: [],
    waiting_on_review: [],
    needs_changes: [],
    ready_to_merge: [],
    recently_merged: [],
    handled: [],
  };
  for (const pr of prs) {
    // Snoozed PRs are parked — they reappear when the snooze expires
    // (or the user wakes them). Until then keep them out of every
    // column so the inbox kanban stays focused on actionable work.
    if (isSnoozed(pr, now)) continue;
    // No handledAction filter here. The inbox MY-PRs lane has no
    // Handled column to fall into, so dropping a PR because the user
    // (or a sync) tagged it MANUAL/DISMISSED makes it invisible —
    // the user reported losing track of an active authored PR
    // because of this. AUTHORED PRs always belong somewhere
    // actionable until the PR itself is closed/merged; that's what
    // categorizeMyPr decides.
    const col = categorizeMyPr(pr, now);
    if (col) out[col].push(pr);
  }
  // The inbox MY-PRs lane no longer renders a Ready-to-merge column —
  // those PRs collapse into Waiting on review (the author still owes
  // a merge click). categorizeMyPr keeps returning the precise bucket
  // so callers like the briefing can still ask, but the kanban view
  // sees them merged.
  out.waiting_on_review.push(...out.ready_to_merge);
  out.ready_to_merge = [];
  // Per-column sort so the most actionable card sits at the top.
  out.drafting.sort(byUpdatedAtDesc);             // newest first (latest activity at top)
  out.waiting_on_review.sort(byUpdatedAtDesc);    // newest first (latest activity at top)
  out.needs_changes.sort(byUpdatedAtDesc);        // newest first (latest activity at top)
  out.recently_merged.sort(byMergedAtDesc);       // newest merges first
  out.handled.sort(byReviewedAtDesc);             // most recently dismissed first
  return out;
}

export function groupToReview<T extends PrLike>(prs: T[], now: number = Date.now(), me: string | null = null): Record<ToReviewColumn, T[]> {
  const out: Record<ToReviewColumn, T[]> = {
    needs_attention: [],
    in_progress: [],
    awaiting_author: [],
    cleared_today: [],
  };
  for (const pr of prs) {
    // See groupMyPrs — snoozed PRs are parked and don't belong in
    // the active kanban until they wake.
    if (isSnoozed(pr, now)) continue;
    const col = categorizeToReview(pr, now, me);
    if (col) out[col].push(pr);
  }
  // needs_attention is ordered purely by most-recent activity — the PR
  // that moved last sits on top, regardless of why it's flagged. The
  // attention reason still colours the card; it no longer reorders the
  // column.
  out.needs_attention.sort(byUpdatedAtDesc);      // newest first (latest activity at top)
  out.in_progress.sort(byUpdatedAtDesc);          // newest first (latest activity at top)
  out.awaiting_author.sort(byUpdatedAtDesc);      // newest first (latest activity at top)
  out.cleared_today.sort(byReviewedAtDesc);       // most recently cleared first
  return out;
}

/** Newest first by updatedAt — the kanban / repo / team list default.
 *  Most-recently-touched PR sits at the top of each group so the user
 *  sees fresh activity without scrolling. */
export function byUpdatedAtDesc(a: PrLike, b: PrLike): number {
  const ta = a.updatedAt ? new Date(a.updatedAt).getTime() : 0;
  const tb = b.updatedAt ? new Date(b.updatedAt).getTime() : 0;
  return tb - ta;
}
function byMergedAtDesc(a: PrLike, b: PrLike): number {
  const ta = a.mergedAt ? new Date(a.mergedAt).getTime() : 0;
  const tb = b.mergedAt ? new Date(b.mergedAt).getTime() : 0;
  return tb - ta;
}
function byReviewedAtDesc(a: PrLike, b: PrLike): number {
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

export function buildBriefing<T extends PrLike>(prs: T[]): Briefing {
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
