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
import { todayBuckets, todayMarkdown } from './RecentList';
import type { PullRequestDto } from '../../types';

const TODAY = new Date().toISOString();
const OLD = '2020-01-01T00:00:00Z';

let seq = 0;
function pr(over: Partial<PullRequestDto>): PullRequestDto {
  seq += 1;
  const num = over.number ?? seq;
  return {
    id: seq, repo: 'o/r', number: num, title: `PR ${num}`, author: 'me',
    htmlUrl: `https://github.com/o/r/pull/${num}`,
    createdAt: OLD, updatedAt: OLD, origin: 'AUTHORED', labels: [], labelColors: null,
    draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
    ciStatus: null, additions: 0, deletions: 0, commentCount: 0, attentionReason: null,
    state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
    headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    ...over,
  };
}

describe('todayBuckets', () => {
  it('splits into working-on / reviewed / merged for today only', () => {
    const b = todayBuckets([
      pr({ number: 1, state: 'open', updatedAt: TODAY }),                       // working on
      pr({ number: 2, origin: 'REVIEW_REQUESTED', reviewedAt: TODAY }),         // reviewed
      pr({ number: 3, mergedAt: TODAY, state: 'merged', updatedAt: TODAY }),    // merged (not working-on)
      pr({ number: 4, state: 'open', updatedAt: OLD }),                          // stale — excluded
      pr({ number: 5, handledAction: 'DISMISSED', viewedAt: TODAY }),           // dismissed — not reviewed
    ]);
    expect(b.workingOn.map(p => p.number)).toEqual([1]);
    expect(b.reviewed.map(p => p.number)).toEqual([2]);
    expect(b.merged.map(p => p.number)).toEqual([3]);
  });
});

describe('todayMarkdown', () => {
  it('renders three sections with linked PR numbers', () => {
    const md = todayMarkdown(todayBuckets([
      pr({ number: 7, title: 'Add cache', state: 'open', updatedAt: TODAY }),
      pr({ number: 8, title: 'Fix race', mergedAt: TODAY, state: 'merged', updatedAt: TODAY }),
    ]));
    expect(md).toContain('## Working on\n* Add cache [#7](https://github.com/o/r/pull/7)');
    expect(md).toContain('## Reviewed\n_None_');
    expect(md).toContain('## Merged\n* Fix race [#8](https://github.com/o/r/pull/8)');
  });

  it('marks an empty bucket as _None_', () => {
    expect(todayMarkdown(todayBuckets([]))).toBe('## Working on\n_None_\n\n## Reviewed\n_None_\n\n## Merged\n_None_');
  });
});
