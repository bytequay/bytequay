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
import type { ActivityItemDto, ReviewThreadDto } from '../../types';
import type { RawTimelineEntry } from '../timelineGrouping';

function toMs(iso: string | null): number {
  return iso !== null ? new Date(iso).getTime() : 0;
}

/**
 * Builds {@link RawTimelineEntry}s for {@link groupTimelineEntries}: every
 * `recentActivity` item, sorted oldest-first, with each `reviewThreads` entry
 * attached to the `reviewed` activity that submitted it (matched by
 * `reviewId` against a thread message's own `reviewId` — a thread's root
 * comment and a review can be submitted together, or the thread can predate
 * the review it's grouped under). A thread that matches no review (e.g. a
 * standalone reply added after the fact) becomes its own standalone entry,
 * ordered by its root message's timestamp.
 */
export function buildRawTimelineEntries(
  activity: ActivityItemDto[],
  reviewThreads: ReviewThreadDto[],
): RawTimelineEntry[] {
  const reviewIdToThreads = new Map<number, ReviewThreadDto[]>();
  const unattached: ReviewThreadDto[] = [];
  for (const thread of reviewThreads) {
    const reviewId = thread.messages.find(m => m.reviewId !== null)?.reviewId ?? null;
    if (reviewId === null) {
      unattached.push(thread);
      continue;
    }
    const existing = reviewIdToThreads.get(reviewId);
    if (existing === undefined) reviewIdToThreads.set(reviewId, [thread]);
    else existing.push(thread);
  }

  const entries: RawTimelineEntry[] = activity.map(item => ({
    kind: 'activity',
    ts: toMs(item.timestamp),
    item,
    attachedThreads: item.reviewId !== null ? reviewIdToThreads.get(item.reviewId) : undefined,
  }));
  const attachedReviewIds = new Set(activity.map(item => item.reviewId).filter((id): id is number => id !== null));
  for (const thread of unattached) {
    entries.push({ kind: 'thread', ts: toMs(thread.messages[0]?.createdAt ?? null), thread });
  }
  // Threads whose reviewId matched no activity item (the review itself fell
  // outside the fetched window) still need to render — treat them as
  // unattached too rather than silently dropping them.
  for (const [reviewId, threads] of reviewIdToThreads) {
    if (attachedReviewIds.has(reviewId)) continue;
    for (const thread of threads) {
      entries.push({ kind: 'thread', ts: toMs(thread.messages[0]?.createdAt ?? null), thread });
    }
  }

  entries.sort((a, b) => a.ts - b.ts);
  return entries;
}
