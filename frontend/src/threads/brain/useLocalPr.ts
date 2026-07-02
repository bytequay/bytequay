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
import type { LocalPRBundle } from '../../types/localPr';
import { makeIdCache } from './idCache';

/** Poll cadence while a task's local PR is live (matches the stage detail). */
const POLL_MS = 5000;

/** Last-known bundle per task id, so switching back paints instantly while a
 *  fresh fetch revalidates underneath — same stale-while-revalidate pattern as
 *  {@link useStageDetailData}. */
const cache = makeIdCache<LocalPRBundle>();

type LocalPrState = {
  /** The task's local PR bundle, or null when it has none yet (the common
   *  case until Dev records its first commit) — the host then falls back to
   *  the remote PR view. Undefined only before the first fetch resolves. */
  bundle: LocalPRBundle | null | undefined;
  refresh: () => void;
};

/**
 * Fetches {@code GET /api/tasks/{taskId}/local-pr/bundle} via the bridge and
 * polls it while the task is live. A 404 (no local PR yet) resolves to null,
 * not an error, so the caller can decide whether to render {@code <PRView>} or
 * fall back to the remote PR panel.
 */
export function useLocalPr(taskId: string): LocalPrState {
  const [bundle, setBundle] = useState<LocalPRBundle | null | undefined>(() => cache.get(taskId) ?? undefined);

  const shownIdRef = useRef(taskId);
  if (shownIdRef.current !== taskId) {
    shownIdRef.current = taskId;
    setBundle(cache.get(taskId) ?? undefined);
  }

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getLocalPrBundle) {
      return;
    }
    void bridge.getLocalPrBundle(taskId)
      .then(b => {
        if (b !== null) cache.set(taskId, b);
        setBundle(b);
      })
      .catch(() => { /* transient; the next poll retries */ });
  }, [taskId]);

  useEffect(() => {
    fetchOnce();
    const id = window.setInterval(fetchOnce, POLL_MS);
    return () => window.clearInterval(id);
  }, [fetchOnce]);

  return { bundle, refresh: fetchOnce };
}
