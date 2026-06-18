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
import { describe, it, expect } from 'vitest';
import {
  optimisticallyBumpReaction,
  optimisticallyToggleResolved,
  optimisticallyUpdateCommentBody,
} from './optimisticUpdates';
import type {
  ActivityItemDto,
  PullRequestDetailDto,
  ReactionsDto,
  ReviewMessageDto,
  ReviewThreadDto,
} from '../types';

const ZERO_REACTIONS: ReactionsDto = {
  plusOne: 0, minusOne: 0, laugh: 0, hooray: 0,
  confused: 0, heart: 0, rocket: 0, eyes: 0,
};

function activity(overrides: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'alice',
    eventType: 'commented',
    timestamp: '2026-04-29T10:00:00Z',
    body: 'hi',
    state: null,
    beforeSha: null,
    afterSha: null,
    requestedReviewer: null,
    reviewId: null,
    authorAssociation: null,
    githubId: 1001,
    reactions: { ...ZERO_REACTIONS },
    ...overrides,
  };
}

function message(overrides: Partial<ReviewMessageDto> = {}): ReviewMessageDto {
  return {
    githubId: 5001,
    author: 'alice',
    body: 'looks good',
    createdAt: null,
    reactions: { ...ZERO_REACTIONS },
    reviewId: null,
    authorAssociation: null,
    ...overrides,
  };
}

function thread(overrides: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 9001,
    filePath: 'src/foo.ts',
    line: 1,
    side: 'RIGHT',
    diffHunk: null,
    messages: [message()],
    resolved: false,
    outdated: false,
    startLine: null,
    startSide: null,
    originalLine: null,
    originalStartLine: null,
    ...overrides,
  };
}

function detail(overrides: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'trinodb/trino',
    number: 42,
    body: '',
    labels: [],
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    additions: 0,
    deletions: 0,
    changedFiles: 0,
    approvalCount: 0,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'NONE',
    files: [],
    recentActivity: [],
    checkRuns: [],
    reviewThreads: [],
    linkedIssues: [],
    viewerCanWrite: false,
    headRef: null,
    headRepo: null,
    baseRef: null,
    baseRepo: null,
    mergeQueueState: null,
    mergeQueueEnabled: false,
    ...overrides,
  };
}

describe('optimisticallyBumpReaction', () => {
  it('returns null when detail is null', () => {
    expect(optimisticallyBumpReaction(null, 1001, '+1')).toBeNull();
  });

  it('returns the same reference when no comment matches', () => {
    const d = detail({ recentActivity: [activity()] });
    expect(optimisticallyBumpReaction(d, 99999, '+1')).toBe(d);
  });

  it('bumps a top-level activity comment by +1', () => {
    const d = detail({
      recentActivity: [activity({ githubId: 1001, reactions: { ...ZERO_REACTIONS, heart: 1 } })],
    });
    const next = optimisticallyBumpReaction(d, 1001, 'heart');
    expect(next).not.toBe(d);
    expect(next!.recentActivity[0].reactions!.heart).toBe(2);
    // Original is untouched (immutability invariant — React relies on
    // identity to detect changes).
    expect(d.recentActivity[0].reactions!.heart).toBe(1);
  });

  it('bumps a review-thread message by +1', () => {
    const m = message({ githubId: 5001, reactions: { ...ZERO_REACTIONS, plusOne: 3 } });
    const d = detail({ reviewThreads: [thread({ messages: [m] })] });
    const next = optimisticallyBumpReaction(d, 5001, '+1');
    expect(next).not.toBe(d);
    expect(next!.reviewThreads[0].messages[0].reactions!.plusOne).toBe(4);
    // Untouched threads keep their reference.
    expect(d.reviewThreads[0].messages[0].reactions!.plusOne).toBe(3);
  });

  it('rolls a count back via delta = -1', () => {
    const d = detail({
      recentActivity: [activity({ githubId: 1001, reactions: { ...ZERO_REACTIONS, eyes: 2 } })],
    });
    const next = optimisticallyBumpReaction(d, 1001, 'eyes', -1);
    expect(next!.recentActivity[0].reactions!.eyes).toBe(1);
  });

  it('clamps a roll-back at zero (no negative counts)', () => {
    const d = detail({
      recentActivity: [activity({ githubId: 1001, reactions: { ...ZERO_REACTIONS, rocket: 0 } })],
    });
    const next = optimisticallyBumpReaction(d, 1001, 'rocket', -1);
    expect(next!.recentActivity[0].reactions!.rocket).toBe(0);
  });

  it('treats a null reactions object as all-zero before bumping', () => {
    const d = detail({
      recentActivity: [activity({ githubId: 1001, reactions: null })],
    });
    const next = optimisticallyBumpReaction(d, 1001, 'laugh');
    expect(next!.recentActivity[0].reactions!.laugh).toBe(1);
    // Other fields stay zero so the chip row isn't filled with phantom counts.
    expect(next!.recentActivity[0].reactions!.heart).toBe(0);
  });

  it('only the matched message gets a new identity', () => {
    const m1 = message({ githubId: 1, reactions: { ...ZERO_REACTIONS } });
    const m2 = message({ githubId: 2, reactions: { ...ZERO_REACTIONS } });
    const t1 = thread({ rootGithubId: 100, messages: [m1] });
    const t2 = thread({ rootGithubId: 200, messages: [m2] });
    const d = detail({ reviewThreads: [t1, t2] });
    const next = optimisticallyBumpReaction(d, 1, '+1')!;
    expect(next.reviewThreads[0]).not.toBe(t1);
    // Untouched thread keeps its reference — lets React skip its subtree.
    expect(next.reviewThreads[1]).toBe(t2);
  });

  it('finds a comment in either subtree (activity wins matches in activity, threads in threads)', () => {
    // Sanity: two different ids, one in each subtree, both should bump.
    const a = activity({ githubId: 1001, reactions: { ...ZERO_REACTIONS } });
    const m = message({ githubId: 5001, reactions: { ...ZERO_REACTIONS } });
    const d = detail({
      recentActivity: [a],
      reviewThreads: [thread({ messages: [m] })],
    });
    const afterActivity = optimisticallyBumpReaction(d, 1001, 'hooray')!;
    expect(afterActivity.recentActivity[0].reactions!.hooray).toBe(1);
    expect(afterActivity.reviewThreads[0].messages[0].reactions!.hooray).toBe(0);

    const afterThread = optimisticallyBumpReaction(d, 5001, 'hooray')!;
    expect(afterThread.recentActivity[0].reactions!.hooray).toBe(0);
    expect(afterThread.reviewThreads[0].messages[0].reactions!.hooray).toBe(1);
  });
});

describe('optimisticallyToggleResolved', () => {
  it('flips a thread to resolved', () => {
    const t = thread({ rootGithubId: 9001, resolved: false });
    const d = detail({ reviewThreads: [t] });
    const next = optimisticallyToggleResolved(d, 9001, true)!;
    expect(next.reviewThreads[0].resolved).toBe(true);
  });

  it('returns the same reference when the value is already what was asked for', () => {
    const t = thread({ rootGithubId: 9001, resolved: true });
    const d = detail({ reviewThreads: [t] });
    expect(optimisticallyToggleResolved(d, 9001, true)).toBe(d);
  });

  it('returns null when detail is null', () => {
    expect(optimisticallyToggleResolved(null, 9001, true)).toBeNull();
  });
});

describe('optimisticallyUpdateCommentBody', () => {
  it('updates a top-level issue comment by github id', () => {
    const a = activity({ githubId: 1001, body: 'old' });
    const d = detail({ recentActivity: [a] });
    const next = optimisticallyUpdateCommentBody(d, 1001, 'new')!;
    expect(next.recentActivity[0].body).toBe('new');
  });

  it('updates a per-line review-thread message by github id', () => {
    const m = message({ githubId: 5001, body: 'old reply' });
    const t = thread({ rootGithubId: 9001, messages: [m] });
    const d = detail({ reviewThreads: [t] });
    const next = optimisticallyUpdateCommentBody(d, 5001, 'new reply')!;
    expect(next.reviewThreads[0].messages[0].body).toBe('new reply');
  });

  it('returns the original reference when the id is not found', () => {
    const a = activity({ githubId: 1001 });
    const d = detail({ recentActivity: [a] });
    expect(optimisticallyUpdateCommentBody(d, 99999, 'x')).toBe(d);
  });

  it('leaves untouched threads with the same reference', () => {
    const m1 = message({ githubId: 5001, body: 'one' });
    const t1 = thread({ rootGithubId: 9001, messages: [m1] });
    const m2 = message({ githubId: 5002, body: 'two' });
    const t2 = thread({ rootGithubId: 9002, messages: [m2] });
    const d = detail({ reviewThreads: [t1, t2] });
    const next = optimisticallyUpdateCommentBody(d, 5001, 'one!')!;
    expect(next.reviewThreads[1]).toBe(t2);
  });

  it('returns null when detail is null', () => {
    expect(optimisticallyUpdateCommentBody(null, 1, 'x')).toBeNull();
  });
});
