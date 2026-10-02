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
import { flagged, MEMORY_CHANGED } from './model';

/** How many memories need the user, from a local read: re-read on a change, on window focus, or when `refresh` changes; null until it lands. */
export function useMemoryNeeds(workspaceId: string | null, refresh: unknown): number | null {
  const [read, setRead] = useState<{ workspaceId: string; count: number } | null>(null);
  useEffect(() => {
    if (workspaceId === null) return;
    let live = true;
    // A failed read, even a synchronous one, leaves the last count; the badge never breaks the sidebar.
    const load = (): void => void Promise.resolve().then(() => window.bridge.getMemoryPage(workspaceId))
      .then(page => { if (live) setRead({ workspaceId, count: page.memories.filter(flagged).length }); })
      .catch(() => {});
    const onChange = (event: Event) => { if (!(event instanceof CustomEvent) || event.detail === workspaceId) load(); };
    load();
    window.addEventListener(MEMORY_CHANGED, onChange);
    window.addEventListener('focus', load);
    return () => {
      live = false;
      window.removeEventListener(MEMORY_CHANGED, onChange);
      window.removeEventListener('focus', load);
    };
  }, [workspaceId, refresh]);
  return read !== null && read.workspaceId === workspaceId ? read.count : null;
}
