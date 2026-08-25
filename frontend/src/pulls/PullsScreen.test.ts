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
import { mergePullRequestDetailStatus, pullRequestDetailRefreshDelay, returnedFromInAppBrowser, showBlockingDetailLoading } from './PullsScreen';

describe('mergePullRequestDetailStatus', () => {
  it('changes only mergeability and CI fields', () => {
    const result = mergePullRequestDetailStatus(
      { title: 'Keep me', body: 'Still here' },
      {
        mergeable: false,
        mergeableState: 'dirty',
        ciStatus: 'FAILING',
        checkRuns: [],
        unavailableSections: [],
      },
    );

    expect(result).toEqual({
      title: 'Keep me',
      body: 'Still here',
      mergeable: false,
      mergeableState: 'dirty',
      ciStatus: 'FAILING',
      checkRuns: [],
      unavailableSections: [],
    });
  });

  it('pauses conditional refreshes until an exhausted rate limit resets', () => {
    const now = Date.parse('2026-08-24T17:00:00Z');
    const resetAt = Date.parse('2026-08-24T17:33:20Z');

    expect(pullRequestDetailRefreshDelay(new Error(
      'GitHub core API rate limit reached; retry after 2026-08-24T17:33:20Z',
    ), now)).toBe(resetAt - now + 1_000);
  });

  it('forces a full refresh only when returning from the in-app browser', () => {
    expect(returnedFromInAppBrowser(false, false)).toBe(false);
    expect(returnedFromInAppBrowser(false, true)).toBe(false);
    expect(returnedFromInAppBrowser(true, true)).toBe(false);
    expect(returnedFromInAppBrowser(true, false)).toBe(true);
  });

  it('keeps existing detail visible while it refreshes', () => {
    expect(showBlockingDetailLoading(true, false)).toBe(true);
    expect(showBlockingDetailLoading(true, true)).toBe(false);
    expect(showBlockingDetailLoading(false, false)).toBe(false);
  });
});
