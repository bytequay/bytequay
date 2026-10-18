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
import { useSyncExternalStore } from 'react';
import { settingsRoute } from './settings/routes';

export type LabLink = { enabled: boolean };

export const LAB_LINK_CHANGED = 'bytequay:lab-link-changed';

const STORAGE_KEY = 'bytequay.lab-link';

function stored(): string | null {
  try { return window.localStorage.getItem(STORAGE_KEY); }
  catch { return null; }
}

function parsed(text: string | null): LabLink {
  try {
    const saved: unknown = JSON.parse(text ?? 'null');
    if (typeof saved === 'object' && saved !== null && 'enabled' in saved && typeof saved.enabled === 'boolean') {
      return { enabled: saved.enabled };
    }
  }
  catch { /* Not JSON: the same as nothing saved. */ }
  return { enabled: false };
}

export function readLabLink(): LabLink {
  return parsed(stored());
}

export function saveLabLink(link: LabLink): void {
  try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(link)); }
  catch { /* Storage is optional; the Lab then stays as it was. */ }
  window.dispatchEvent(new CustomEvent(LAB_LINK_CHANGED));
}

function subscribe(changed: () => void): () => void {
  window.addEventListener(LAB_LINK_CHANGED, changed);
  window.addEventListener('storage', changed);
  return () => {
    window.removeEventListener(LAB_LINK_CHANGED, changed);
    window.removeEventListener('storage', changed);
  };
}

export function useLabLink(): LabLink {
  // The stored text is the snapshot: a change made before this component began listening is still seen.
  return parsed(useSyncExternalStore(subscribe, stored, stored));
}

type LabLaunch = { opening: boolean; error: string | null };
let launch: LabLaunch = { opening: false, error: null };
const launchListeners = new Set<() => void>();

function setLaunch(next: LabLaunch): void {
  launch = next;
  launchListeners.forEach(changed => changed());
}

function subscribeLaunch(changed: () => void): () => void {
  launchListeners.add(changed);
  return () => { launchListeners.delete(changed); };
}

export function useLabLaunch(): LabLaunch {
  return useSyncExternalStore(subscribeLaunch, () => launch, () => launch);
}

export async function openLab(): Promise<void> {
  // Both entry points share one launch so a second click cannot open another tab while it starts.
  if (launch.opening) return;
  setLaunch({ opening: true, error: null });
  try {
    await window.bridge.openInAppBrowser(await window.bridge.openLab());
    setLaunch({ opening: false, error: null });
  }
  catch (error) {
    setLaunch({ opening: false, error: error instanceof Error ? error.message : String(error) });
    window.location.hash = settingsRoute('lab');
  }
}
