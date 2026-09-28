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
import type { PullRequestDto } from './types';
import {
  attentionActivityVersion,
  attentionCandidates,
  visibleAttention,
  type AttentionItem,
  type Handled,
} from './home/attention';

export type PrNotificationState = {
  handled: Handled;
  delivered: Record<string, string>;
  pinnedKeys?: string[];
};

export function notificationVersion(item: AttentionItem, handled?: Handled[string]): string {
  return JSON.stringify([item.pr?.monitoredAt, attentionActivityVersion(item), handled?.kind === 'snooze' ? handled.until : null]);
}

/** The monitoring baseline prevents enabling the bell from announcing existing activity. */
export function pendingPrNotifications(prs: PullRequestDto[], state: PrNotificationState, now: number): AttentionItem[] {
  return visibleAttention(attentionCandidates(prs), state.handled, now).filter(item => {
    const pr = item.pr;
    return !state.pinnedKeys?.includes(item.key) && pr?.monitoredAt != null
      && Date.parse(pr.activityAt ?? pr.updatedAt) > Date.parse(pr.monitoredAt)
      && state.delivered[item.key] !== notificationVersion(item, state.handled[item.key]);
  });
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Validate the small renderer-owned snooze snapshot before persisting it in Electron. */
export function parsePrNotificationState(value: unknown): PrNotificationState {
  const state: PrNotificationState = { handled: {}, delivered: {} };
  if (!object(value)) return state;
  if (Array.isArray(value.pinnedKeys)) state.pinnedKeys = value.pinnedKeys.filter((key): key is string => typeof key === 'string');
  if (object(value.delivered)) {
    state.delivered = Object.fromEntries(Object.entries(value.delivered).filter((entry): entry is [string, string] =>
      typeof entry[1] === 'string'));
  }
  if (object(value.handled)) {
    for (const [key, entry] of Object.entries(value.handled)) {
      if (!object(entry) || (entry.kind !== 'ack' && entry.kind !== 'snooze')
        || (entry.until !== null && (typeof entry.until !== 'number' || !Number.isFinite(entry.until)))
        || typeof entry.seenUpdatedAt !== 'string' || typeof entry.label !== 'string'
        || (entry.seenActivityVersion !== undefined && typeof entry.seenActivityVersion !== 'string')) continue;
      state.handled[key] = {
        kind: entry.kind,
        until: typeof entry.until === 'number' ? entry.until : null,
        seenUpdatedAt: entry.seenUpdatedAt,
        label: entry.label,
        ...(typeof entry.seenActivityVersion === 'string' ? { seenActivityVersion: entry.seenActivityVersion } : {}),
      };
    }
  }
  return state;
}
