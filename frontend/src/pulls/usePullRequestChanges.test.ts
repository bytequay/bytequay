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

import { timelineActivity } from './fixtures/pullRequest';
import { expect, it } from 'vitest';
import type { PullRequestDetailDto } from '../types';
import { EMPTY_TIMELINE_PAYLOAD } from './model';
import { pullRequestDiffRevision } from './usePullRequestChanges';

const payload = { ...EMPTY_TIMELINE_PAYLOAD, commitSha: 'first-sha' };
const commit: PullRequestDetailDto['recentActivity'][number] = timelineActivity({ actor: 'developer', eventType: 'committed', timestamp: '2026-01-01T12:00:00Z', githubId: null }, payload);
const detail = { baseRef: 'main', headRef: 'feature', additions: 5, deletions: 3, changedFiles: 1, recentActivity: [commit] };

it('keeps selected code stable when discussion, reviews, or reactions update', () => {
  const expected = pullRequestDiffRevision(detail);
  for (const eventType of ['commented', 'reviewed', 'review_thread', 'labeled', 'renamed']) {
    expect(pullRequestDiffRevision({ ...detail, recentActivity: [...detail.recentActivity, { ...commit, eventType, githubId: 10 }] })).toBe(expected);
  }
  expect(pullRequestDiffRevision({ ...detail, recentActivity: [{ ...commit, payload: { ...payload, resolved: true, actorAvatarUrl: 'https://example.test/avatar' } }] }))
    .toBe(expected);
  const discussionUpdate = { ...detail, updatedAt: 'later' };
  expect(pullRequestDiffRevision(discussionUpdate)).toBe(expected);
});

it('detects rewritten commits and force pushes with identical timestamps and diff statistics', () => {
  expect(pullRequestDiffRevision({ ...detail, recentActivity: [{ ...commit, payload: { ...payload, commitSha: 'rewritten-sha' } }] }))
    .not.toBe(pullRequestDiffRevision(detail));
  const forcePush: PullRequestDetailDto['recentActivity'][number] = { ...commit, eventType: 'head_ref_force_pushed', githubId: 20, payload: { ...payload, commitSha: null } };
  expect(pullRequestDiffRevision({ ...detail, recentActivity: [commit, { ...forcePush, githubId: 21 }] }))
    .not.toBe(pullRequestDiffRevision({ ...detail, recentActivity: [commit, forcePush] }));
  expect(pullRequestDiffRevision({ ...detail, recentActivity: [forcePush, commit] }))
    .toBe(pullRequestDiffRevision({ ...detail, recentActivity: [commit, forcePush] }));
});

it('detects changed refs and stats, using the known push time when commit history is unavailable', () => {
  expect(pullRequestDiffRevision({ ...detail, baseRef: 'release' })).not.toBe(pullRequestDiffRevision(detail));
  expect(pullRequestDiffRevision({ ...detail, headRef: 'replacement' })).not.toBe(pullRequestDiffRevision(detail));
  expect(pullRequestDiffRevision({ ...detail, additions: 6 })).not.toBe(pullRequestDiffRevision(detail));
  expect(pullRequestDiffRevision({ ...detail, recentActivity: [] }, 'first-push'))
    .not.toBe(pullRequestDiffRevision({ ...detail, recentActivity: [] }, 'second-push'));
  expect(pullRequestDiffRevision(null, 'known-push')).toBe('known-push');
});
