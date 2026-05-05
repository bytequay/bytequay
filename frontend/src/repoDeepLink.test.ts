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
import { decideDeepLinkSelection } from './repoDeepLink';
import type { PullRequestDto } from './types';

function pr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'trinodb/trino',
    number: 1,
    title: 'Test PR',
    author: 'octocat',
    htmlUrl: 'https://github.com/trinodb/trino/pull/1',
    createdAt: '2026-04-29T00:00:00Z',
    updatedAt: '2026-04-29T00:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'NONE',
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
    reviewerVerdicts: {},
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

describe('decideDeepLinkSelection', () => {
  it('returns noop when no PR number is requested', () => {
    expect(decideDeepLinkSelection([], null)).toEqual({ kind: 'noop' });
    expect(decideDeepLinkSelection([], undefined)).toEqual({ kind: 'noop' });
    // A populated list with no requested PR is still a noop.
    expect(decideDeepLinkSelection([pr({ number: 7 })], null)).toEqual({ kind: 'noop' });
  });

  it('selects the matching PR when it is in the list', () => {
    const list = [pr({ id: 1, number: 7 }), pr({ id: 2, number: 8 })];
    const decision = decideDeepLinkSelection(list, 8);
    expect(decision.kind).toBe('select');
    if (decision.kind === 'select') {
      expect(decision.pr.id).toBe(2);
      expect(decision.pr.number).toBe(8);
    }
  });

  it('falls back when the PR is missing from the list (the 50-cap regression)', () => {
    // Simulates the user hitting a PR that's beyond the listPullRequests
    // page or has been closed and so is no longer in state=open.
    const list = Array.from({ length: 50 }, (_, i) => pr({ id: 100 + i, number: 100 + i }));
    const decision = decideDeepLinkSelection(list, 999);
    expect(decision).toEqual({ kind: 'fallback', number: 999 });
  });

  it('falls back on an empty list when a PR is requested', () => {
    expect(decideDeepLinkSelection([], 42)).toEqual({ kind: 'fallback', number: 42 });
  });
});
