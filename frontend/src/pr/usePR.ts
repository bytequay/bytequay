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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { LocalPRBundle } from '../types/localPr';
import { makeIdCache } from '../threads/brain/idCache';

/** Visible-poll cadence (unified-pr-view.md U4) — cheap with ETags, good
 *  enough for a single-user desktop app. Manual refresh (the sync chip, or
 *  right after a mutation) always probes immediately regardless of this. */
const POLL_MS = 60000;

/** Last-known bundle per PR id — stale-while-revalidate, same pattern as
 *  every other id-keyed hook in this app (see idCache). */
const cache = makeIdCache<LocalPRBundle>();

export type UsePRState = {
  /** undefined = before the first fetch resolves; null = the PR doesn't
   *  exist (404) — not an error, the caller decides what to render instead. */
  bundle: LocalPRBundle | null | undefined;
  /** Force an immediate fetch, bypassing the poll cadence — every mutation's
   *  `.then()` calls this so the view repaints right away. */
  refresh: () => void;
  /** True while a fetch is in flight — drives the sync chip's spinner. */
  syncing: boolean;
};

/**
 * The one hook every PR surface reads from (U5) — fetches `GET
 * /api/prs/{prId}/bundle` via the bridge and polls it at {@link POLL_MS}.
 * `prId === null` means the caller hasn't resolved an id yet (e.g. a task
 * page still awaiting its PR-for-task resolver); the hook just idles.
 */
export function usePR(prId: string | null): UsePRState {
  const [bundle, setBundle] = useState<LocalPRBundle | null | undefined>(
    () => (prId === null ? null : cache.get(prId) ?? undefined),
  );
  const [syncing, setSyncing] = useState(false);

  const shownIdRef = useRef(prId);
  if (shownIdRef.current !== prId) {
    shownIdRef.current = prId;
    setBundle(prId === null ? null : cache.get(prId) ?? undefined);
  }

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getLocalPrBundle || prId === null) {
      return;
    }
    setSyncing(true);
    void bridge.getLocalPrBundle(prId)
      .then(b => {
        if (b !== null) cache.set(prId, b);
        setBundle(b);
      })
      .catch(() => { /* transient; the next poll retries */ })
      .finally(() => setSyncing(false));
  }, [prId]);

  useEffect(() => {
    if (prId === null) {
      return;
    }
    fetchOnce();
    const id = window.setInterval(fetchOnce, POLL_MS);
    return () => window.clearInterval(id);
  }, [fetchOnce, prId]);

  return { bundle, refresh: fetchOnce, syncing };
}
