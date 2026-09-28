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
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as attentionVisits from '../../home/useAttentionVisit';
import { reviewFixture } from './fixtures/review';
import { recordReviewAttentionAction } from './useFindingActions';

afterEach(() => { vi.restoreAllMocks(); });

describe('review attention actions', () => {
  it('records a successful prompt against the pull request card and source task', () => {
    const record = vi.spyOn(attentionVisits, 'recordAttentionAction');
    recordReviewAttentionAction(reviewFixture({ repo: 'Owner/Repo', sourceTaskId: 'task-1' }), 'prompt');
    expect(record.mock.calls).toEqual([
      ['Owner/Repo#30304', 'prompt'],
      ['task:task-1', 'prompt'],
    ]);
  });

  it('records a local review update against only its source task', () => {
    const record = vi.spyOn(attentionVisits, 'recordAttentionAction');
    recordReviewAttentionAction(reviewFixture({ number: null, sourceTaskId: 'task-2' }), 'update');
    expect(record.mock.calls).toEqual([['task:task-2', 'update']]);
  });
});
