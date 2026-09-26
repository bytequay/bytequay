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
import { attentionCandidates, handledEntry } from './home/attention';
import { notificationVersion, parsePrNotificationState, pendingPrNotifications, type PrNotificationState } from './prNotifications';
import type { PullRequestDto } from './types';

const baseline = '2026-09-06T10:00:00Z';
const activity = '2026-09-06T10:01:00Z';
const now = Date.parse(activity);
const pr: PullRequestDto = {
  id: 1, repo: 'owner/repo', number: 42, title: 'A monitored PR', author: 'someone', htmlUrl: '',
  createdAt: baseline, updatedAt: baseline, origin: 'OTHER', labels: [], labelColors: null, draft: false,
  viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: 'FAILING',
  additions: 0, deletions: 0, commentCount: 0, state: 'open', mergeable: false,
  mergeableState: 'unstable', headPushedAt: null, headRef: 'branch', monitoredAt: baseline, activityAt: activity,
};
const empty = (): PrNotificationState => ({ handled: {}, delivered: {} });

describe('monitored PR notifications', () => {
  it('stays quiet at enable, notifies later CI activity even without updatedAt, and deduplicates restarts', () => {
    expect(pendingPrNotifications([{ ...pr, activityAt: baseline }], empty(), now)).toEqual([]);
    const [item] = pendingPrNotifications([pr], empty(), now);
    expect(item.number).toBe(42);
    const state = parsePrNotificationState(JSON.parse(JSON.stringify({
      handled: {}, delivered: { [item.key]: notificationVersion(item) },
    })));
    expect(pendingPrNotifications([pr], state, now)).toEqual([]);
    expect(pendingPrNotifications([{ ...pr, ciStatus: 'PASSING', activityAt: '2026-09-06T10:02:00Z' }], state, now)).toHaveLength(1);
  });

  it('keeps timed snoozes silent during changes, then delivers the pending change once the snooze expires', () => {
    const item = attentionCandidates([pr])[0];
    const state = { handled: { [item.key]: handledEntry(item, 'snooze', now + 60_000, '1 minute') }, delivered: {} };
    const changed = { ...pr, commentCount: 1, activityAt: '2026-09-06T10:02:00Z' };
    expect(pendingPrNotifications([changed], state, now)).toEqual([]);
    expect(pendingPrNotifications([changed], state, now + 60_000)).toHaveLength(1);
  });

  it('honors acknowledgment and activity snooze, ignores disabled monitors, and reports closure', () => {
    const item = attentionCandidates([pr])[0];
    for (const kind of ['ack', 'snooze'] as const) {
      const state = { handled: { [item.key]: handledEntry(item, kind, null, '') }, delivered: {} };
      expect(pendingPrNotifications([pr], state, now)).toEqual([]);
      expect(pendingPrNotifications([{ ...pr, state: 'closed', activityAt: '2026-09-06T10:02:00Z' }], state, now)).toHaveLength(1);
    }
    expect(pendingPrNotifications([{ ...pr, monitoredAt: null }], empty(), now)).toEqual([]);
    expect(pendingPrNotifications([{ ...pr, origin: 'AUTHORED', monitoredAt: null }], empty(), now)).toEqual([]);
  });

  it('discards invalid persisted snooze and delivery data', () => {
    expect(parsePrNotificationState(null)).toEqual(empty());
    expect(parsePrNotificationState({ handled: { bad: { kind: 'snooze', until: 'forever' } }, delivered: { bad: false } })).toEqual(empty());
  });
});
