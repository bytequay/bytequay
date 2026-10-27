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
import { enrichActivityPrTitles, followingActivityRows } from './HomePage';
import type { AgendaDto, AgendaItemDto, RecentEventDto } from '../types';

function event(values: Partial<RecentEventDto> = {}): RecentEventDto {
  return {
    type: 'PullRequestEvent', repo: 'example/harbor', createdAt: '2026-09-29T00:00:00Z', commitCount: 0,
    action: 'closed', prTitle: null, prNumber: 32005, refType: null, actorLogin: 'djsagain', ...values,
  };
}

afterEach(() => { vi.unstubAllGlobals(); });

function agenda(statuses: AgendaItemDto['status'][]): AgendaDto {
  return {
    day: '2026-10-25', state: 'READY', enabled: true, at: 540, generatedAt: null, model: null, summary: '', recap: null, calendar: null, failure: null,
    items: statuses.map((status, index) => ({
      ref: `pr:${index}`, action: 'review', title: `Review ${index}`, label: `#${index}`, why: '', at: null, workspaceId: null, where: null,
      status, byYou: false, doneText: null, doneAt: null,
      target: { kind: 'PULL', workspaceId: null, repo: 'example/harbor', number: index, id: null, connectionId: null, cloudId: null },
    })),
  };
}

it('shares fifteen entries between Today and the newest following activity', () => {
  const events = Array.from({ length: 30 }, (_, index) => event({ prNumber: 100 - index }));
  expect(followingActivityRows(events, null)).toEqual(events.slice(0, 15));
  expect(followingActivityRows(events, agenda(['open', 'done', 'open']))).toEqual(events.slice(0, 12));
  expect(followingActivityRows(events, agenda(['open', 'open', 'open', 'open', 'open']))).toEqual(events.slice(0, 10));
  expect(followingActivityRows(events, agenda(['done', 'done', 'done', 'dismissed']))).toEqual(events.slice(0, 12));
  expect(followingActivityRows(events, { ...agenda(['open']), enabled: false, state: 'NONE' })).toEqual(events.slice(0, 15));
});

it('counts grouped activity rows and reserves a row for the weekly recap', () => {
  const events = Array.from({ length: 30 }, (_, index) => event({ type: 'PushEvent', prNumber: Math.floor(index / 2), commitCount: 1 }));
  const today = agenda(['open', 'done', 'open']);
  const rows = followingActivityRows(events, today);
  expect(rows).toHaveLength(12);
  expect(rows.map(row => row.prNumber)).toEqual(Array.from({ length: 12 }, (_, index) => index));
  expect(rows.every(row => row.commitCount === 2 && row.pushCount === 2)).toBe(true);
  expect(followingActivityRows(events, { ...today, recap: { text: 'This week', merged: [], slipped: [] } })).toHaveLength(11);
});

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
