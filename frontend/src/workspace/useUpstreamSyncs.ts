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
import { workspaceApi, type UpstreamCherryPickJobDto } from './workspaceApi';

const REFRESH_MS = 5_000;

/**
 * The workspace's sync runs, newest first. Both entry points — Today and the CI
 * Harness surface — read them from here so a live run's progress ticks in one
 * place rather than two competing polls.
 *
 * @return null until the first answer arrives, so a caller can tell "none" from
 *         "not yet" instead of flashing an empty state over a full list.
 */
export function useUpstreamSyncs(
  workspaceId: string | null,
): UpstreamCherryPickJobDto[] | null {
  const [syncs, setSyncs] = useState<UpstreamCherryPickJobDto[] | null>(null);

  useEffect(() => {
    if (workspaceId === null) {
      setSyncs([]);
      return undefined;
    }
    let cancelled = false;
    const load = () => {
      void workspaceApi.upstreamCherryPicks(workspaceId)
        // The nav renders this on every workspace, including ones whose
        // sidecar answers with nothing at all — an empty list, never null.
        .then(next => { if (!cancelled) setSyncs(Array.isArray(next) ? next : []); })
        // A workspace with no upstream relation has no syncs; an empty list is
        // the right answer either way.
        .catch(() => { if (!cancelled) setSyncs([]); });
    };
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [workspaceId]);

  return syncs;
}
