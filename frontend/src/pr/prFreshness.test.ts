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
import type { ActivityItemDto, PullRequestDetailDto } from '../types';
import { describePrChange, prDetailChanged, prDetailFingerprint } from './prFreshness';

function activity(over: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'octocat',
    eventType: 'commented',
    timestamp: '2026-06-21T10:00:00Z',
    body: 'looks good',
    state: null,
    beforeSha: null,
    afterSha: null,
    requestedReviewer: null,
    reviewId: null,
    authorAssociation: 'MEMBER',
    githubId: 1,
    reactions: null,
    labelName: null,
    labelColor: null,
    milestoneTitle: null,
    assigneeLogin: null,
    crossRefNumber: null,
    crossRefTitle: null,
    crossRefUrl: null,
    crossRefIsPullRequest: false,
    ...over,
  };
}

function detail(over: Partial<PullRequestDetailDto> = {}): PullRequestDetailDto {
  return {
    repo: 'trinodb/trino',
    number: 42,
    body: 'body',
    labels: [],
    draft: false,
    mergeable: true,
    mergeableState: 'clean',
    additions: 10,
    deletions: 5,
    changedFiles: 2,
    approvalCount: 0,
    changesRequestedCount: 0,
    pendingReviewerCount: 0,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    files: [],
    recentActivity: [activity()],
    checkRuns: [],
    reviewThreads: [],
    linkedIssues: [],
    viewerCanWrite: true,
    headRef: 'feat/x',
    headRepo: 'trinodb/trino',
    baseRef: 'master',
    baseRepo: 'trinodb/trino',
    mergeQueueState: null,
    mergeQueueEnabled: false,
    ...over,
  };
}

describe('prDetailFingerprint', () => {
  it('is stable across equal snapshots', () => {
    expect(prDetailFingerprint(detail())).toBe(prDetailFingerprint(detail()));
    expect(prDetailChanged(detail(), detail())).toBe(false);
    expect(describePrChange(detail(), detail())).toBeNull();
  });

  it('ignores label, reaction, and CI churn', () => {
    const a = detail();
    const b = detail({
      labels: ['needs-review'],
      ciStatus: 'FAILING',
      recentActivity: [activity({ reactions: { '+1': 3 } as never })],
    });
    expect(prDetailChanged(a, b)).toBe(false);
  });
});

describe('describePrChange', () => {
  it('flags a new comment', () => {
    const shown = detail();
    const fresh = detail({ recentActivity: [activity(), activity({ githubId: 2, body: 'one more thing' })] });
    expect(prDetailChanged(shown, fresh)).toBe(true);
    expect(describePrChange(shown, fresh)).toMatch(/1 new comment or event/);
  });

  it('flags a new review reply', () => {
    const shown = detail({
      reviewThreads: [{ rootGithubId: 9, side: null, messages: [{}], resolved: false } as never],
    });
    const fresh = detail({
      reviewThreads: [{ rootGithubId: 9, side: null, messages: [{}, {}], resolved: false } as never],
    });
    expect(describePrChange(shown, fresh)).toMatch(/1 new review reply/);
  });

  it('flags new commits when the diff shape moves', () => {
    const shown = detail();
    const fresh = detail({ additions: 40, changedFiles: 5 });
    expect(describePrChange(shown, fresh)).toMatch(/new commits/);
  });

  it('flags a draft toggle', () => {
    expect(describePrChange(detail(), detail({ draft: true }))).toMatch(/converted to draft/);
  });

  it('returns a generic line when something changed but no delta is recognised', () => {
    const shown = detail({ approvalCount: 0 });
    const fresh = detail({ approvalCount: 1 });
    expect(describePrChange(shown, fresh)).toBe(
      'This pull request changed on GitHub since you opened it.',
    );
  });
});
