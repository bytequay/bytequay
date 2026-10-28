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
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { handledEntry, issueAttentionItem, loadHandled, pullRequestItem, saveHandled, type AttentionItem } from './attention';
import { notifyIssueDevelopmentChanged } from '../workspaces/issueDevelopment';

export type AttentionAction = 'comment' | 'review' | 'prompt' | 'update';
type Visit = { item: AttentionItem; readers: number; actions: Record<AttentionAction, number>; pending: Promise<void> | null };
const visits = new Map<string, Visit>();
const listeners = new Set<() => void>();
const changed = () => listeners.forEach(listener => listener());
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };

function readStoredItem(visit: Visit, afterWrite = false): void {
  if (window.bridge === undefined || visit.item.task || visit.item.jira) return;
  // Consume accepted writes before closing, even when their response only patched the detail cache.
  visit.pending = (visit.pending ?? Promise.resolve()).then(async () => {
    const item = visit.item;
    if (item.pr !== null && window.bridge.fetchAttentionPrs !== undefined) {
      if (afterWrite) await window.bridge.refreshPullRequestDetailByRef?.(item.repo, item.number, undefined, undefined, item.workspaceId ?? undefined).catch(() => {});
      const rows = item.workspaceId === null ? await window.bridge.fetchAttentionPrs()
        : (await window.bridge.fetchWorkspaceHostingAttention(item.workspaceId)).pullRequests;
      const row = rows.find(pr => pr.id === item.pr?.id);
      if (row !== undefined && Date.parse(row.updatedAt) >= Date.parse(visit.item.updatedAt)) {
        visit.item = pullRequestItem(row, item.kind, item.workspaceId);
        window.dispatchEvent(new CustomEvent('bytequay:pr-attention-updated', { detail: item.workspaceId === null ? rows : undefined }));
      }
    }
    else if (item.type === 'issue' && item.workspaceId !== null && window.bridge.fetchWorkspaceIssue !== undefined) {
      const row = await window.bridge.fetchWorkspaceIssue(item.workspaceId, item.number);
      if (row !== null && Date.parse(row.updatedAt) >= Date.parse(visit.item.updatedAt)) {
        visit.item = issueAttentionItem(row, item.repo, item.workspaceId);
        notifyIssueDevelopmentChanged(row.id);
      }
    }
  }).catch(() => { /* Keep the successfully displayed snapshot when a local read fails. */ });
}

function visitStatus(visit: Visit): string {
  const { comment, review, prompt, update } = visit.actions;
  return [
    review > 0 ? 'Reviewed' : null,
    prompt > 0 ? 'Prompted' : null,
    update > 0 ? 'Updated state' : null,
    comment > 0 ? `Posted ${comment} comment${comment === 1 ? '' : 's'}` : null,
  ].filter(Boolean).join(' · ') || 'Viewed';
}

/** Cards and details name only successful actions in the current visit. */
export function useAttentionStatus(key: string): string | null {
  return useSyncExternalStore(subscribe, () => {
    const visit = visits.get(key);
    const status = visit === undefined ? null : visitStatus(visit);
    return status === 'Viewed' ? null : status;
  }, (): string | null => null);
}

/** Called after a write succeeds; failed writes and drafts never count as handling. */
export function recordAttentionAction(key: string, action: AttentionAction, count = 1, updatedItem?: AttentionItem): void {
  const visit = visits.get(key);
  if (visit === undefined) return;
  visit.actions[action] += count;
  if (updatedItem?.key === key) visit.item = updatedItem;
  readStoredItem(visit, true);
  changed();
}

/** Actions without a list row still identify the open request by its workspace account. */
export function recordPullRequestAttentionAction(repo: string, number: number, action: AttentionAction, workspaceId?: string): void {
  for (const [key, visit] of visits) {
    if (visit.item.pr !== null && visit.item.repo === repo && visit.item.number === number && visit.item.workspaceId === (workspaceId ?? null)) {
      recordAttentionAction(key, action);
    }
  }
}

/** Zoom can close back into an already mounted detail pane. That still consumes this update. */
export async function completeAttentionVisit(key: string): Promise<void> {
  const visit = visits.get(key);
  if (visit === undefined) return;
  let pending;
  do { pending = visit.pending; await pending; } while (pending !== visit.pending);
  if (visits.get(key) !== visit) return;
  const handled = loadHandled();
  // Opening a snoozed item from elsewhere must not cancel its promised reminder.
  if (handled[key]?.kind !== 'snooze' || (handled[key].until ?? 0) <= Date.now()) {
    saveHandled({ ...handled, [key]: handledEntry(visit.item, 'ack', null, visitStatus(visit)) });
  }
}

/** A visit consumes the last displayed snapshot only when its last detail surface closes. */
export function useAttentionVisit(item: AttentionItem | null, active = true): {
  status: string;
  onAction: (action: AttentionAction, count?: number, updatedItem?: AttentionItem) => void;
} {
  const key = active ? item?.key ?? null : null;
  const status = useAttentionStatus(key ?? '');
  useEffect(() => {
    if (key === null || item === null) return;
    let visit = visits.get(key);
    if (visit === undefined) {
      visit = { item, readers: 0, actions: { comment: 0, review: 0, prompt: 0, update: 0 }, pending: null };
      visits.set(key, visit);
      if (item.pr !== null) readStoredItem(visit);
    }
    visit.readers += 1;
    changed();
    return () => {
      visit.readers -= 1;
      // React's development remount and moving a detail between hosts are still the same visit.
      queueMicrotask(async () => {
        let pending;
        do { pending = visit.pending; await pending; } while (pending !== visit.pending);
        if (visit.readers !== 0 || visits.get(key) !== visit) return;
        await completeAttentionVisit(key);
        if (visit.readers !== 0 || visits.get(key) !== visit) return;
        visits.delete(key);
        changed();
      });
    };
    // The identity owns the visit; incoming snapshots update it without closing it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  useEffect(() => {
    const visit = key === null ? undefined : visits.get(key);
    if (visit === undefined || item === null) return;
    if (Date.parse(item.task?.dto.updatedAt ?? item.updatedAt) < Date.parse(visit.item.task?.dto.updatedAt ?? visit.item.updatedAt)) return;
    // List hosts may briefly rerender the pre-write row while the canonical SQLite read settles.
    if (item.pr !== null && visit.item.pr !== null
      && Date.parse(item.updatedAt) <= Date.parse(visit.item.updatedAt)
      && (Date.parse(item.pr.activityAt ?? '') || 0) < (Date.parse(visit.item.pr.activityAt ?? '') || 0)) return;
    visit.item = item;
  }, [item, key]);
  const onAction = useCallback((action: AttentionAction, count = 1, updatedItem?: AttentionItem) => {
    if (key !== null) recordAttentionAction(key, action, count, updatedItem);
  }, [key]);
  return { status: status ?? '', onAction };
}
