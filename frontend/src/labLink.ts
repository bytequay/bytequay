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

/** The lab is its own program. ByteQuay keeps only whether to show it and where its dashboard is served. */
export type LabLink = { enabled: boolean; address: string };

export const DEFAULT_LAB_ADDRESS = 'http://127.0.0.1:8517/';
export const LAB_LINK_CHANGED = 'bytequay:lab-link-changed';

const STORAGE_KEY = 'bytequay.lab-link';
const THIS_MAC = new Set(['127.0.0.1', 'localhost', '[::1]']);

/** The dashboard is served to this Mac only, so an address anywhere else is not the lab's. Null when the text is not one. */
export function labAddress(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const typed = value.trim();
  try {
    const url = new URL(/^[a-z][a-z\d+.-]*:\/\//i.test(typed) ? typed : `http://${typed}`);
    const web = url.protocol === 'http:' || url.protocol === 'https:';
    return web && THIS_MAC.has(url.hostname) && url.username === '' && url.password === '' ? url.href : null;
  }
  catch { return null; }
}

function stored(): string | null {
  try { return window.localStorage.getItem(STORAGE_KEY); }
  catch { return null; }
}

/** Anything that is not a saved lab link reads as the Lab turned off at its usual address. */
function parsed(text: string | null): LabLink {
  try {
    const saved: unknown = JSON.parse(text ?? 'null');
    if (typeof saved === 'object' && saved !== null && 'enabled' in saved && 'address' in saved) {
      const address = labAddress(saved.address);
      if (typeof saved.enabled === 'boolean' && address !== null) return { enabled: saved.enabled, address };
    }
  }
  catch { /* Not JSON: the same as nothing saved. */ }
  return { enabled: false, address: DEFAULT_LAB_ADDRESS };
}

export function readLabLink(): LabLink {
  return parsed(stored());
}

/** Changes the switch or the address and leaves the other as it is saved now, not as a page last drew it. */
export function saveLabLink(change: Partial<LabLink>): void {
  try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...readLabLink(), ...change })); }
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
