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
import { describe, expect, it } from 'vitest';
import type { HandledAction, PullRequestDto } from './types';
import {
  RESURFACE_GRACE_MS,
  bucketize,
  categorize,
  categorizeMyPr,
  categorizeToReview,
  formatRelative,
  groupByCategory,
  groupHandledByTime,
  groupMyPrs,
  groupToReview,
  handledBadge,
  isHandled,
  isResurfaced,
  markHandledPatch,
  patchPr,
  reopenPatch,
  sortHandled,
  splitByBucket,
} from './prBuckets';

function pr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'owner/repo',
    number: 1,
    title: 'Test',
    author: 'alice',
    htmlUrl: '',
    createdAt: '2026-04-23T09:00:00Z',
    updatedAt: '2026-04-23T10:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: 0,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: null,
    mergeableState: null,
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

describe('isHandled', () => {
  it('MERGED, DISMISSED, MANUAL are handled (terminal)', () => {
    expect(isHandled(pr({ handledAction: 'MERGED' }))).toBe(true);
    expect(isHandled(pr({ handledAction: 'DISMISSED' }))).toBe(true);
    expect(isHandled(pr({ handledAction: 'MANUAL' }))).toBe(true);
  });

  it('APPROVED is NOT handled — it stays in the Inbox Cleared zone', () => {
    expect(isHandled(pr({ handledAction: 'APPROVED' }))).toBe(false);
  });

  it('COMMENTED and CHANGES_REQUESTED are not handled', () => {
    expect(isHandled(pr({ handledAction: 'COMMENTED' }))).toBe(false);
    expect(isHandled(pr({ handledAction: 'CHANGES_REQUESTED' }))).toBe(false);
  });

  it('null handledAction is not handled', () => {
    expect(isHandled(pr({ handledAction: null }))).toBe(false);
  });
});

describe('bucketize', () => {
  it('returns inbox when the PR has never been reviewed', () => {
    expect(bucketize(pr({ reviewedAt: null }))).toBe('inbox');
  });

  it('returns handled for MERGED regardless of activity', () => {
    expect(bucketize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'MERGED',
    }))).toBe('handled');
  });

  it('returns handled for MANUAL (user dismissed from Inbox)', () => {
    expect(bucketize(pr({ handledAction: 'MANUAL' }))).toBe('handled');
  });

  it('returns handled for DISMISSED (user closed the PR)', () => {
    expect(bucketize(pr({ handledAction: 'DISMISSED' }))).toBe('handled');
  });

  it('APPROVED stays in Inbox — approved is the Cleared zone, not terminal', () => {
    expect(bucketize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe('inbox');
  });

  it('COMMENTED / CHANGES_REQUESTED stay in Inbox (Awaiting author)', () => {
    expect(bucketize(pr({ handledAction: 'COMMENTED' }))).toBe('inbox');
    expect(bucketize(pr({ handledAction: 'CHANGES_REQUESTED' }))).toBe('inbox');
  });
});

describe('isResurfaced', () => {
  it('is false for a fresh PR that was never reviewed', () => {
    expect(isResurfaced(pr({ reviewedAt: null }))).toBe(false);
  });

  it('is true for a COMMENTED PR whose author replied (updatedAt past grace)', () => {
    const reviewed = '2026-04-23T10:00:00Z';
    const updated = '2026-04-23T13:00:00Z';
    expect(isResurfaced(pr({
      reviewedAt: reviewed,
      updatedAt: updated,
      handledAction: 'COMMENTED',
    }))).toBe(true);
  });

  it('is false for MANUAL — Handled PRs do not auto-resurface', () => {
    expect(isResurfaced(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'MANUAL',
    }))).toBe(false);
  });

  it('is false for APPROVED — Cleared PRs stay Cleared', () => {
    expect(isResurfaced(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe(false);
  });

  it('exactly at the grace boundary is not resurfaced', () => {
    const reviewed = '2026-04-23T10:00:00Z';
    const updated = new Date(new Date(reviewed).getTime() + RESURFACE_GRACE_MS).toISOString();
    expect(isResurfaced(pr({
      reviewedAt: reviewed,
      updatedAt: updated,
      handledAction: 'COMMENTED',
    }))).toBe(false);
  });
});

describe('splitByBucket', () => {
  it('routes APPROVED to inbox and MERGED / MANUAL / DISMISSED to handled', () => {
    const list = [
      pr({ id: 1, handledAction: 'APPROVED', reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 2, handledAction: 'MERGED', reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 3, handledAction: 'MANUAL', reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 4, handledAction: 'DISMISSED', reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 5, handledAction: null, reviewedAt: null }),
    ];
    const { inbox, snoozed, handled } = splitByBucket(list);
    expect(inbox.map(p => p.id).sort()).toEqual([1, 5]);
    expect(snoozed).toEqual([]);
    expect(handled.map(p => p.id).sort()).toEqual([2, 3, 4]);
  });

  it('routes future-snoozed PRs to the snoozed bucket regardless of handled state', () => {
    const now = Date.parse('2026-05-05T12:00:00Z');
    const future = '2026-05-06T12:00:00Z';
    const past = '2026-05-05T11:00:00Z';
    const list = [
      pr({ id: 1, snoozedUntil: future }),
      pr({ id: 2, snoozedUntil: future, handledAction: 'MERGED', reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 3, snoozedUntil: past }),
      pr({ id: 4, snoozedUntil: null }),
    ];
    const { inbox, snoozed, handled } = splitByBucket(list, now);
    expect(snoozed.map(p => p.id).sort()).toEqual([1, 2]);
    expect(inbox.map(p => p.id).sort()).toEqual([3, 4]);
    expect(handled).toEqual([]);
  });
});

describe('handledBadge', () => {
  const cases: Array<[HandledAction | null, string]> = [
    ['APPROVED', 'Approved'],
    ['MERGED', 'Merged'],
    ['COMMENTED', 'Commented'],
    ['CHANGES_REQUESTED', 'Requested changes'],
    ['DISMISSED', 'Dismissed'],
    ['MANUAL', 'Handled'],
    [null, 'Handled'],
  ];
  it.each(cases)('maps %s to label %s', (action, label) => {
    expect(handledBadge(action).label).toBe(label);
  });
});

describe('formatRelative', () => {
  const now = new Date('2026-04-23T12:00:00Z').getTime();
  it('returns empty string for null input', () => {
    expect(formatRelative(null, now)).toBe('');
  });
  it('returns "just now" for sub-minute diffs', () => {
    expect(formatRelative('2026-04-23T11:59:50Z', now)).toBe('just now');
  });
  it('returns minutes for under an hour', () => {
    expect(formatRelative('2026-04-23T11:35:00Z', now)).toBe('25m ago');
  });
  it('returns hours for under 5 hours', () => {
    expect(formatRelative('2026-04-23T10:00:00Z', now)).toBe('2h ago');
  });
  it('returns "today" for 12-24h ago', () => {
    expect(formatRelative('2026-04-22T20:00:00Z', now)).toBe('today');
  });
  it('returns "yesterday" for 24-48h ago', () => {
    expect(formatRelative('2026-04-22T10:00:00Z', now)).toBe('yesterday');
  });
});

describe('groupHandledByTime', () => {
  const now = new Date('2026-04-23T12:00:00Z').getTime();

  it('puts a PR reviewed earlier today in today', () => {
    const p = pr({ reviewedAt: '2026-04-23T08:00:00Z' });
    const groups = groupHandledByTime([p], now);
    expect(groups.today).toHaveLength(1);
    expect(groups.thisWeek).toHaveLength(0);
    expect(groups.older).toHaveLength(0);
  });

  it('puts a 3-day-old PR in thisWeek', () => {
    const p = pr({ reviewedAt: '2026-04-20T12:00:00Z' });
    const groups = groupHandledByTime([p], now);
    expect(groups.thisWeek).toHaveLength(1);
    expect(groups.today).toHaveLength(0);
  });

  it('puts a 2-week-old PR in older', () => {
    const p = pr({ reviewedAt: '2026-04-09T12:00:00Z' });
    const groups = groupHandledByTime([p], now);
    expect(groups.older).toHaveLength(1);
    expect(groups.today).toHaveLength(0);
    expect(groups.thisWeek).toHaveLength(0);
  });

  it('handles multiple PRs across buckets', () => {
    const groups = groupHandledByTime(
      [
        pr({ id: 1, reviewedAt: '2026-04-23T08:00:00Z' }),
        pr({ id: 2, reviewedAt: '2026-04-20T08:00:00Z' }),
        pr({ id: 3, reviewedAt: '2026-04-01T08:00:00Z' }),
      ],
      now,
    );
    expect(groups.today.map(p => p.id)).toEqual([1]);
    expect(groups.thisWeek.map(p => p.id)).toEqual([2]);
    expect(groups.older.map(p => p.id)).toEqual([3]);
  });
});

describe('sortHandled', () => {
  it('sorts newest reviewedAt first', () => {
    const input = [
      pr({ id: 1, reviewedAt: '2026-04-21T10:00:00Z' }),
      pr({ id: 2, reviewedAt: '2026-04-23T10:00:00Z' }),
      pr({ id: 3, reviewedAt: '2026-04-22T10:00:00Z' }),
    ];
    expect(sortHandled(input).map(p => p.id)).toEqual([2, 3, 1]);
  });

  it('does not mutate the input', () => {
    const input = [pr({ id: 1, reviewedAt: '2026-04-21T10:00:00Z' }), pr({ id: 2, reviewedAt: '2026-04-23T10:00:00Z' })];
    const snapshot = input.map(p => p.id);
    sortHandled(input);
    expect(input.map(p => p.id)).toEqual(snapshot);
  });
});

describe('patchPr / markHandledPatch / reopenPatch', () => {
  it('patchPr replaces only the matching PR', () => {
    const list = [pr({ id: 1 }), pr({ id: 2 }), pr({ id: 3 })];
    const next = patchPr(list, 2, { title: 'updated' });
    expect(next[0].title).toBe('Test');
    expect(next[1].title).toBe('updated');
    expect(next[2].title).toBe('Test');
  });

  it('patchPr is a no-op when the id is not found', () => {
    const list = [pr({ id: 1 })];
    const next = patchPr(list, 999, { title: 'changed' });
    expect(next[0].title).toBe('Test');
  });

  it('patchPr does not mutate the input array', () => {
    const list = [pr({ id: 1, title: 'a' }), pr({ id: 2, title: 'b' })];
    patchPr(list, 1, { title: 'changed' });
    expect(list[0].title).toBe('a');
  });

  it('markHandledPatch produces a reviewedAt + action patch', () => {
    const patch = markHandledPatch('MANUAL', '2026-04-23T12:00:00Z');
    expect(patch.reviewedAt).toBe('2026-04-23T12:00:00Z');
    expect(patch.handledAction).toBe('MANUAL');
  });

  it('reopenPatch clears both fields', () => {
    const patch = reopenPatch();
    expect(patch.reviewedAt).toBeNull();
    expect(patch.handledAction).toBeNull();
  });

  it('applying markHandledPatch then reopenPatch returns a PR to the inbox', () => {
    let list = [pr({ id: 1, reviewedAt: null, handledAction: null })];
    expect(bucketize(list[0])).toBe('inbox');

    list = patchPr(list, 1, markHandledPatch('MANUAL', '2026-04-23T12:00:00Z'));
    // updatedAt is older than reviewedAt → handled
    expect(bucketize({ ...list[0], updatedAt: '2026-04-23T11:00:00Z' })).toBe('handled');

    list = patchPr(list, 1, reopenPatch());
    expect(bucketize(list[0])).toBe('inbox');
  });
});

describe('categorize', () => {
  it('categorises fresh review requests as needs_attention', () => {
    expect(categorize(pr({ origin: 'REVIEW_REQUESTED', viewedAt: null }))).toBe('needs_attention');
  });

  it('categorises authored PRs not yet opened as needs_attention', () => {
    expect(categorize(pr({ origin: 'AUTHORED', viewedAt: null }))).toBe('needs_attention');
  });

  it('moves a viewed review request into in_progress', () => {
    expect(categorize(pr({
      origin: 'REVIEW_REQUESTED',
      viewedAt: '2026-04-23T09:00:00Z',
      reviewedAt: null,
    }))).toBe('in_progress');
  });

  it('parks drafts in cleared so they don\'t pollute the action zones', () => {
    // Drafts (your own or someone else's) are not actionable review
    // requests yet. The kanban's DRAFTING column is the dedicated
    // home; the sidebar list keeps them out of the way in Cleared.
    expect(categorize(pr({ origin: 'AUTHORED', draft: true, viewedAt: null }))).toBe('cleared');
    expect(categorize(pr({ origin: 'REVIEW_REQUESTED', draft: true, viewedAt: null }))).toBe('cleared');
  });

  it('parks merged PRs in cleared even when local handledAction is null', () => {
    // GitHub-side merge that hasn't been mirrored into our local
    // handledAction yet (user merged on web, never opened our app)
    // would otherwise fall through to in_progress / needs_attention.
    expect(categorize(pr({ mergedAt: '2026-05-04T10:00:00Z', state: 'closed', handledAction: null }))).toBe('cleared');
  });

  it('parks closed-without-merge PRs in cleared', () => {
    expect(categorize(pr({ state: 'closed', mergedAt: null, handledAction: null }))).toBe('cleared');
  });

  it('puts CHANGES_REQUESTED into awaiting_author', () => {
    expect(categorize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'CHANGES_REQUESTED',
    }))).toBe('awaiting_author');
  });

  it('puts COMMENTED into awaiting_author', () => {
    expect(categorize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'COMMENTED',
    }))).toBe('awaiting_author');
  });

  it('puts APPROVED into cleared (Cleared zone — approved but not merged)', () => {
    expect(categorize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe('cleared');
  });

  it('CHANGES_REQUESTED resurfaces to needs_attention when author replies', () => {
    expect(categorize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-23T12:00:00Z',
      handledAction: 'CHANGES_REQUESTED',
    }))).toBe('needs_attention');
  });

  it('APPROVED stays cleared even with late activity (only rare CI/force-push events resurface)', () => {
    expect(categorize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-23T20:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe('cleared');
  });

  it('terminal actions (MERGED / DISMISSED / MANUAL) fall back to cleared as a safety net', () => {
    // Callers should filter these out via isHandled before calling categorize,
    // but if they slip through we don't want to throw — map to cleared.
    for (const action of ['MERGED', 'DISMISSED', 'MANUAL'] as HandledAction[]) {
      expect(categorize(pr({ reviewedAt: '2026-04-23T10:00:00Z', handledAction: action })))
        .toBe('cleared');
    }
  });
});

describe('groupByCategory', () => {
  it('buckets each non-handled PR into exactly one zone', () => {
    const a = pr({ id: 1, origin: 'REVIEW_REQUESTED', viewedAt: null }); // needs_attention
    // viewed-but-not-yet-reviewed → in_progress (drafts now park in cleared,
    // so the original draft fixture moved here)
    const b = pr({ id: 2, viewedAt: '2026-04-23T08:00:00Z', reviewedAt: null });
    const c = pr({ id: 3, reviewedAt: '2026-04-23T10:00:00Z', handledAction: 'COMMENTED' }); // awaiting_author
    const d = pr({ id: 4, reviewedAt: '2026-04-23T10:00:00Z', handledAction: 'APPROVED' }); // cleared
    const groups = groupByCategory([a, b, c, d]);
    expect(groups.needs_attention.map(p => p.id)).toEqual([1]);
    expect(groups.in_progress.map(p => p.id)).toEqual([2]);
    expect(groups.awaiting_author.map(p => p.id)).toEqual([3]);
    expect(groups.cleared.map(p => p.id)).toEqual([4]);
  });

  it('filters out handled PRs so the Inbox kanban never sees them', () => {
    const inboxPr = pr({ id: 1 });
    const mergedPr = pr({ id: 2, handledAction: 'MERGED' });
    const manuallyHandledPr = pr({ id: 3, handledAction: 'MANUAL' });
    const groups = groupByCategory([inboxPr, mergedPr, manuallyHandledPr]);
    const all = [...groups.needs_attention, ...groups.in_progress, ...groups.awaiting_author, ...groups.cleared];
    expect(all.map(p => p.id)).toEqual([1]);
  });
});

// ── Phase 3 kanban refactor ────────────────────────────────────────────────

const NOW = new Date('2026-04-28T12:00:00Z').getTime();

describe('categorizeMyPr', () => {
  it('returns null for review-requested PRs', () => {
    expect(categorizeMyPr(pr({ origin: 'REVIEW_REQUESTED' }), NOW)).toBeNull();
  });

  it('routes drafts to drafting', () => {
    expect(categorizeMyPr(pr({ draft: true }), NOW)).toBe('drafting');
  });

  it('no reviewer verdicts → waiting_on_review', () => {
    expect(categorizeMyPr(pr({}), NOW)).toBe('waiting_on_review');
  });

  it('any CHANGES_REQUESTED in the verdict map → needs_changes', () => {
    expect(categorizeMyPr(pr({
      reviewerVerdicts: { alice: 'APPROVED', bob: 'CHANGES_REQUESTED' },
    }), NOW)).toBe('needs_changes');
  });

  it('approval + clean CI + mergeable → ready_to_merge', () => {
    expect(categorizeMyPr(pr({
      reviewerVerdicts: { alice: 'APPROVED' },
      ciStatus: 'PASSING',
      mergeable: true,
    }), NOW)).toBe('ready_to_merge');
  });

  it('approval + CI passing + mergeable=null (still computing) DOES promote to ready_to_merge', () => {
    // GitHub returns mergeable=null for ~30s after every push while it
    // computes. The strict mergeable===true check left objectively-ready
    // PRs sitting in waiting_on_review until the next sync. Trust the
    // CI + approval signal; only mergeable===false (an actual conflict)
    // should hold the column.
    expect(categorizeMyPr(pr({
      reviewerVerdicts: { alice: 'APPROVED' },
      ciStatus: 'PASSING',
      mergeable: null,
    }), NOW)).toBe('ready_to_merge');
  });

  it('approval + CI passing but mergeable=false (real conflict) → waiting_on_review', () => {
    expect(categorizeMyPr(pr({
      reviewerVerdicts: { alice: 'APPROVED' },
      ciStatus: 'PASSING',
      mergeable: false,
    }), NOW)).toBe('waiting_on_review');
  });

  it('approval but CI failing → still in waiting_on_review (not ready)', () => {
    expect(categorizeMyPr(pr({
      reviewerVerdicts: { alice: 'APPROVED' },
      ciStatus: 'FAILING',
      mergeable: true,
    }), NOW)).toBe('waiting_on_review');
  });

  it('merged within 7 days → recently_merged', () => {
    const fiveDaysAgo = new Date(NOW - 5 * 24 * 60 * 60 * 1000).toISOString();
    expect(categorizeMyPr(pr({
      state: 'merged',
      mergedAt: fiveDaysAgo,
    }), NOW)).toBe('recently_merged');
  });

  it('merged more than 7 days ago → drops out of the kanban', () => {
    const tenDaysAgo = new Date(NOW - 10 * 24 * 60 * 60 * 1000).toISOString();
    expect(categorizeMyPr(pr({
      state: 'merged',
      mergedAt: tenDaysAgo,
    }), NOW)).toBeNull();
  });
});

describe('categorizeToReview', () => {
  it('returns null for authored PRs', () => {
    expect(categorizeToReview(pr({ origin: 'AUTHORED' }), NOW)).toBeNull();
  });

  it('attentionReason set → needs_attention', () => {
    expect(categorizeToReview(pr({
      origin: 'REVIEW_REQUESTED',
      attentionReason: 'MENTIONED',
    }), NOW)).toBe('needs_attention');
  });

  it('viewedAt set, no reviewedAt → in_progress', () => {
    expect(categorizeToReview(pr({
      origin: 'REVIEW_REQUESTED',
      viewedAt: '2026-04-23T10:00:00Z',
    }), NOW)).toBe('in_progress');
  });

  it('user left COMMENTED → awaiting_author', () => {
    expect(categorizeToReview(pr({
      origin: 'REVIEW_REQUESTED',
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'COMMENTED',
    }), NOW)).toBe('awaiting_author');
  });

  it('handled today → cleared_today', () => {
    const sameMorning = new Date(NOW - 30 * 60 * 1000).toISOString();
    expect(categorizeToReview(pr({
      origin: 'REVIEW_REQUESTED',
      reviewedAt: sameMorning,
      handledAction: 'APPROVED',
    }), NOW)).toBe('cleared_today');
  });

  it('handled yesterday → drops out (lives in the Handled tab instead)', () => {
    const yesterday = new Date(NOW - 26 * 60 * 60 * 1000).toISOString();
    expect(categorizeToReview(pr({
      origin: 'REVIEW_REQUESTED',
      reviewedAt: yesterday,
      handledAction: 'MANUAL',
    }), NOW)).toBeNull();
  });

  it('brand-new review request → needs_attention', () => {
    expect(categorizeToReview(pr({ origin: 'REVIEW_REQUESTED' }), NOW)).toBe('needs_attention');
  });
});

describe('groupToReview sort order', () => {
  it('attentionReason wins over plain brand-new in needs_attention', () => {
    // Both land in needs_attention, but the CI-failing one should sit on top.
    const ciFailing = pr({ id: 1, origin: 'REVIEW_REQUESTED', attentionReason: 'CI_FAILING' });
    const brandNew = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const groups = groupToReview([brandNew, ciFailing], NOW);
    expect(groups.needs_attention.map(p => p.id)).toEqual([1, 2]);
  });

  it('within same severity, latest-updated PR is on top', () => {
    const stale = pr({ id: 1, origin: 'REVIEW_REQUESTED', attentionReason: 'MENTIONED', updatedAt: '2026-04-20T10:00:00Z' });
    const fresh = pr({ id: 2, origin: 'REVIEW_REQUESTED', attentionReason: 'MENTIONED', updatedAt: '2026-04-25T10:00:00Z' });
    const groups = groupToReview([stale, fresh], NOW);
    expect(groups.needs_attention.map(p => p.id)).toEqual([2, 1]);
  });

  it('opening a PR (viewedAt set) demotes it out of needs_attention', () => {
    // Two brand-new review requests. After the user views one, that one
    // moves to in_progress and the other one is the top of needs_attention.
    const a = pr({ id: 1, origin: 'REVIEW_REQUESTED', createdAt: '2026-04-22T10:00:00Z' });
    const b = pr({ id: 2, origin: 'REVIEW_REQUESTED', createdAt: '2026-04-23T10:00:00Z' });
    const initial = groupToReview([a, b], NOW);
    expect(initial.needs_attention.map(p => p.id)).toEqual([1, 2]); // older first
    // User opens #1 — viewedAt is now set.
    const aViewed = { ...a, viewedAt: '2026-04-28T11:00:00Z' };
    const after = groupToReview([aViewed, b], NOW);
    expect(after.in_progress.map(p => p.id)).toEqual([1]);
    expect(after.needs_attention.map(p => p.id)).toEqual([2]);
  });
});

describe('groupMyPrs / groupToReview', () => {
  it('groupMyPrs filters out review-requested PRs entirely', () => {
    const mine = pr({ id: 1, origin: 'AUTHORED' });
    const others = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const groups = groupMyPrs([mine, others], NOW);
    const all = Object.values(groups).flat().map(p => p.id);
    expect(all).toEqual([1]);
  });

  it('groupToReview filters out authored PRs entirely', () => {
    const mine = pr({ id: 1, origin: 'AUTHORED' });
    const review = pr({ id: 2, origin: 'REVIEW_REQUESTED' });
    const groups = groupToReview([mine, review], NOW);
    const all = Object.values(groups).flat().map(p => p.id);
    expect(all).toEqual([2]);
  });

  it('groupMyPrs folds ready_to_merge PRs into waiting_on_review and leaves the column empty', () => {
    const ready = pr({
      id: 1,
      origin: 'AUTHORED',
      reviewerVerdicts: { alice: 'APPROVED' },
      ciStatus: 'PASSING',
      mergeable: true,
    });
    const waiting = pr({ id: 2, origin: 'AUTHORED' });
    const groups = groupMyPrs([ready, waiting], NOW);
    expect(groups.ready_to_merge).toEqual([]);
    expect(groups.waiting_on_review.map(p => p.id).sort()).toEqual([1, 2]);
  });
});
