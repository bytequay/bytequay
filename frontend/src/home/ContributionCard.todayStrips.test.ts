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
import { todayPrStrips } from './ContributionCard';
import type { PullRequestDto } from '../types';

const TODAY = new Date().toISOString();
const OLD = '2020-01-01T00:00:00Z';

let seq = 0;
function pr(over: Partial<PullRequestDto>): PullRequestDto {
  seq += 1;
  return {
    id: seq, repo: 'o/r', number: seq, title: `PR ${seq}`, author: 'me', htmlUrl: '',
    createdAt: OLD, updatedAt: OLD, origin: 'AUTHORED', labels: [], labelColors: null,
    draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [],
    ciStatus: null, additions: 0, deletions: 0, commentCount: 0, attentionReason: null,
    state: 'open', closedAt: null, mergedAt: null, mergeable: null, mergeableState: null,
    headPushedAt: null, reviewerVerdicts: null, snoozedUntil: null, snoozeWakeReason: null,
    ...over,
  };
}

const nums = (list: PullRequestDto[]) => list.map(p => p.number);

describe('todayPrStrips', () => {
  it('buckets reviewed / in-progress / merged by today', () => {
    const reviewedToday = pr({ number: 1, origin: 'REVIEW_REQUESTED', reviewedAt: TODAY });
    const reviewedOld = pr({ number: 2, origin: 'REVIEW_REQUESTED', reviewedAt: OLD });
    const openToday = pr({ number: 3, state: 'open', updatedAt: TODAY });
    const mergedToday = pr({ number: 4, state: 'merged', mergedAt: TODAY, updatedAt: TODAY });

    const { reviewed, inProgress, merged } = todayPrStrips([
      reviewedToday, reviewedOld, openToday, mergedToday,
    ]);

    expect(nums(reviewed)).toEqual([1]);
    expect(nums(inProgress)).toEqual([3]);
    expect(nums(merged)).toEqual([4]);
  });

  it('keeps a merged PR out of in-progress and a closed-unmerged PR out of both', () => {
    const merged = pr({ number: 1, mergedAt: TODAY, state: 'merged', updatedAt: TODAY });
    const closedUnmerged = pr({ number: 2, state: 'closed', closedAt: TODAY, updatedAt: TODAY });
    const out = todayPrStrips([merged, closedUnmerged]);
    expect(nums(out.inProgress)).toEqual([]);          // merged and closed both excluded
    expect(nums(out.merged)).toEqual([1]);             // only the merged one
  });

  it('excludes not-today activity from every strip', () => {
    const out = todayPrStrips([
      pr({ origin: 'REVIEW_REQUESTED', reviewedAt: OLD }),
      pr({ state: 'open', updatedAt: OLD }),
      pr({ mergedAt: OLD, state: 'merged', updatedAt: OLD }),
    ]);
    expect(out.reviewed).toEqual([]);
    expect(out.inProgress).toEqual([]);
    expect(out.merged).toEqual([]);
  });

  it('caps each strip at 5 rows', () => {
    const many = Array.from({ length: 8 }, (_, i) => pr({ number: i + 1, state: 'open', updatedAt: TODAY }));
    expect(todayPrStrips(many).inProgress).toHaveLength(5);
  });

  it('handles a null PR list', () => {
    const out = todayPrStrips(null);
    expect(out).toEqual({ reviewed: [], inProgress: [], merged: [] });
  });
});
