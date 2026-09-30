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
import { pendingCounts } from './model';

/** Every page that changes notes tells the sidebar count and Home to re-read them. */
export const CONTEXT_CHANGED = 'bytequay:repository-context-changed';

/** What waits for the user in Repository context, from local notebook and plan reads only. */
export type ContextReview = { sources: number; total: number; latestAt: string | null; useInDevelopment: boolean };

/** Re-read on a change, on window focus, or when {@code refresh} changes; null until the first read lands. */
export function useContextReview(workspaceId: string | null, refresh: unknown): ContextReview | null {
  const [read, setRead] = useState<{ workspaceId: string; review: ContextReview } | null>(null);
  useEffect(() => {
    if (workspaceId === null) return;
    let live = true;
    // A failed read, even a synchronous one, leaves the last summary; the count never breaks the page it sits on.
    const load = (): void => void Promise.resolve().then(() => Promise.all([window.bridge.getRepositoryContext(workspaceId),
      window.bridge.getRepositoryContextLearningPlan(workspaceId)]))
      .then(([notebook, view]) => {
        if (!live) return;
        const stamps = (view.plan?.documentInventory.suggestions ?? []).map(suggestion => suggestion.foundAt);
        setRead({ workspaceId, review: { ...pendingCounts(view.plan), latestAt: stamps.sort().at(-1) ?? null, useInDevelopment: notebook.useInDevelopment } });
      })
      .catch(() => {});
    const onChange = (event: Event) => { if (!(event instanceof CustomEvent) || event.detail === workspaceId) load(); };
    load();
    window.addEventListener(CONTEXT_CHANGED, onChange);
    window.addEventListener('focus', load);
    return () => {
      live = false;
      window.removeEventListener(CONTEXT_CHANGED, onChange);
      window.removeEventListener('focus', load);
    };
  }, [workspaceId, refresh]);
  // A re-read keeps showing the last summary; only another workspace's summary is withheld.
  return read !== null && read.workspaceId === workspaceId ? read.review : null;
}
