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
import { afterEach, expect, it, vi } from 'vitest';
import { enrichActivityPrTitles } from './HomePage';
import type { RecentEventDto } from '../types';

function event(values: Partial<RecentEventDto> = {}): RecentEventDto {
  return {
    type: 'PullRequestEvent', repo: 'example/harbor', createdAt: '2026-09-29T00:00:00Z', commitCount: 0,
    action: 'closed', prTitle: null, prNumber: 32005, refType: null, actorLogin: 'djsagain', ...values,
  };
}

afterEach(() => { vi.unstubAllGlobals(); });

it('resolves missing activity titles only from stored PRs and shares duplicate lookups', async () => {
  const read = vi.fn().mockResolvedValue({ title: 'More dao overrides' });
  const fetch = vi.fn();
  vi.stubGlobal('window', { bridge: { findStoredPullRequest: read } });
  vi.stubGlobal('fetch', fetch);
  const events = [event(), event({ type: 'PullRequestReviewEvent' }), event({ prTitle: 'Title from the activity payload' })];

  const enriched = await enrichActivityPrTitles(events);

  expect(read).toHaveBeenCalledTimes(1);
  expect(read).toHaveBeenCalledWith('example/harbor', 32005);
  expect(enriched.map(value => value.prTitle)).toEqual(['More dao overrides', 'More dao overrides', 'Title from the activity payload']);
  expect(events[0].prTitle).toBeNull();
  expect(fetch).not.toHaveBeenCalled();
});

it('keeps number fallbacks for uncached or unreadable PRs without importing them', async () => {
  const read = vi.fn().mockResolvedValueOnce(null).mockRejectedValueOnce(new Error('Local store unavailable'));
  const fetch = vi.fn();
  vi.stubGlobal('window', { bridge: { findStoredPullRequest: read } });
  vi.stubGlobal('fetch', fetch);
  const events = [event(), event({ prNumber: 32006 }), event({ type: 'IssuesEvent' }),
    event({ prNumber: 0 }), event({ repo: 'invalid' }), event({ prTitle: 'Already named' })];

  expect(await enrichActivityPrTitles(events)).toEqual(events);
  expect(read.mock.calls).toEqual([['example/harbor', 32005], ['example/harbor', 32006]]);
  expect(fetch).not.toHaveBeenCalled();
});
