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
import { buildRawTimelineEntries } from './githubActivityRows';
import type { ActivityItemDto, ReviewThreadDto } from '../../types';

function activity(over: Partial<ActivityItemDto> = {}): ActivityItemDto {
  return {
    actor: 'octocat', eventType: 'commented', timestamp: '2026-06-20T10:00:00Z', body: null,
    state: null, beforeSha: null, afterSha: null, requestedReviewer: null, reviewId: null,
    authorAssociation: null, githubId: 1, reactions: null,
    labelName: null, labelColor: null, milestoneTitle: null, assigneeLogin: null,
    crossRefNumber: null, crossRefTitle: null, crossRefUrl: null, crossRefIsPullRequest: false,
    ...over,
  };
}

function thread(reviewId: number | null, over: Partial<ReviewThreadDto> = {}): ReviewThreadDto {
  return {
    rootGithubId: 501, filePath: 'src/Foo.java', line: 10, side: 'RIGHT', diffHunk: null,
    messages: [{
      githubId: 601, author: 'octocat', body: 'Please fix this.', createdAt: '2026-06-20T10:05:00Z',
      reactions: null, reviewId, authorAssociation: null,
    }],
    resolved: null, outdated: false, startLine: null, startSide: null, originalLine: null, originalStartLine: null,
    ...over,
  };
}

describe('buildRawTimelineEntries', () => {
  it('attaches a thread to the reviewed activity that shares its reviewId', () => {
    const reviewed = activity({ eventType: 'reviewed', reviewId: 9 });
    const entries = buildRawTimelineEntries([reviewed], [thread(9)]);

    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ kind: 'activity', item: reviewed, attachedThreads: [thread(9)] });
  });

  it('renders a thread with no matching review as a standalone entry', () => {
    const entries = buildRawTimelineEntries([], [thread(null)]);

    expect(entries).toHaveLength(1);
    expect(entries[0].kind).toBe('thread');
  });

  it('renders a thread whose reviewId matches no fetched activity as standalone rather than dropping it', () => {
    const entries = buildRawTimelineEntries([activity({ eventType: 'commented', reviewId: null })], [thread(999)]);

    expect(entries).toHaveLength(2);
    expect(entries.some(e => e.kind === 'thread')).toBe(true);
  });

  it('sorts entries oldest first regardless of input order', () => {
    const older = activity({ timestamp: '2026-06-20T09:00:00Z', githubId: 1 });
    const newer = activity({ timestamp: '2026-06-20T11:00:00Z', githubId: 2 });
    const entries = buildRawTimelineEntries([newer, older], []);

    expect(entries.map(e => (e.kind === 'activity' ? e.item.githubId : null))).toEqual([1, 2]);
  });

  it('drops the redundant closed event emitted with a merge', () => {
    const timestamp = '2026-06-20T11:00:00Z';
    const merged = activity({ eventType: 'merged', timestamp, githubId: 2 });
    const closed = activity({ eventType: 'closed', timestamp, githubId: 3 });
    const entries = buildRawTimelineEntries([merged, closed], []);

    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ kind: 'activity', item: merged });
  });
});
