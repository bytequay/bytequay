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
import { useEffect, useState } from 'react';
import { zoomReference, type ZoomedReference } from '../githubReference';
import { workspaceReviewTaskRoute } from '../workspaces/model';

export type PinnedItem = {
  key: string;
  title: string;
  reference?: string;
  target: ZoomedReference | { kind: 'review'; workspaceId: string; reviewId: string };
};

const STORAGE_KEY = 'bytequay.attention.pinned.v1';
export const PINNED_CHANGED = 'bytequay:attention-pinned-changed';

function validPinned(value: unknown): value is PinnedItem {
  if (typeof value !== 'object' || value === null || !('key' in value) || !('title' in value) || !('target' in value)) return false;
  if (typeof value.key !== 'string' || typeof value.title !== 'string'
    || ('reference' in value && typeof value.reference !== 'string')) return false;
  const target = value.target;
  if (typeof target !== 'object' || target === null || !('kind' in target)) return false;
  if (target.kind === 'jira') return 'connectionId' in target && typeof target.connectionId === 'string'
    && 'cloudId' in target && typeof target.cloudId === 'string' && 'issueId' in target && typeof target.issueId === 'string';
  if (target.kind === 'task') return 'workspaceId' in target && typeof target.workspaceId === 'string'
    && 'taskId' in target && typeof target.taskId === 'string';
  if (target.kind === 'review') return 'workspaceId' in target && typeof target.workspaceId === 'string'
    && 'reviewId' in target && typeof target.reviewId === 'string';
  return (target.kind === 'pull' || target.kind === 'issue')
    && 'repo' in target && typeof target.repo === 'string' && 'number' in target && typeof target.number === 'number'
    && Number.isSafeInteger(target.number) && target.number > 0
    && (target.kind === 'pull' || ('workspaceId' in target && typeof target.workspaceId === 'string'));
}

export function loadPinned(): PinnedItem[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(validPinned) : [];
  }
  catch { return []; }
}

export function setPinned(item: PinnedItem, pinned: boolean): void {
  const items = loadPinned();
  const existing = items.findIndex(candidate => candidate.key === item.key);
  if (pinned) {
    if (existing === -1) items.push(item);
    else items[existing] = item;
  }
  else if (existing !== -1) items.splice(existing, 1);
  try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items)); }
  catch { /* A full or disabled store leaves the previous pins intact. */ }
  void window.bridge?.setPrAttentionPinned?.(loadPinned().map(candidate => candidate.key)).catch(() => {});
  window.dispatchEvent(new CustomEvent(PINNED_CHANGED));
}

export function usePinnedItems(): PinnedItem[] {
  const [items, setItems] = useState(loadPinned);
  useEffect(() => {
    const reload = () => setItems(loadPinned());
    window.addEventListener(PINNED_CHANGED, reload);
    window.addEventListener('storage', reload);
    return () => {
      window.removeEventListener(PINNED_CHANGED, reload);
      window.removeEventListener('storage', reload);
    };
  }, []);
  return items;
}

export function openPinned({ target }: PinnedItem): void {
  if (target.kind === 'review') window.location.hash = workspaceReviewTaskRoute(target.workspaceId, target.reviewId);
  else zoomReference(target.kind === 'jira' ? { ...target, start: false } : target);
}
