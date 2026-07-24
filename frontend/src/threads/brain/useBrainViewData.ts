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
import type { TaskBrainViewData } from '../../types/brainView';
import { buildEmptyBrainView } from './brainViewFixture';
import { makeIdCache } from './idCache';

/** Last-known brain view per task id, so returning to a task's brain (or a
 *  stage page that mounts it) paints the prior snapshot at once while the
 *  poll revalidates — see {@link makeIdCache}. */
const cache = makeIdCache<TaskBrainViewData>();

/** Steady poll cadence while the brain view is open. */
const POLL_MS = 5000;
/** Tightened cadence right after the user submits, so the YOU bubble and
 *  the brain reply land quickly. */
const FAST_POLL_MS = 1000;
/** How long to stay on the fast cadence after a submit. */
const FAST_WINDOW_MS = 10_000;

type BrainViewState = {
  /** The latest brain-view payload. Seeded with an EMPTY view (not the mock
   *  fixture) so a remount — e.g. returning to the Root node from a stage —
   *  paints a neutral empty shell for one cycle rather than flashing fake
   *  data; replaced by real backend data once the first fetch resolves. */
  data: TaskBrainViewData;
  /** The last fetch error, or null when the latest fetch succeeded. The
   *  view keeps showing the last good data (or the seed) underneath. */
  error: string | null;
  /** Re-fetch now. */
  refresh: () => void;
  /** Tighten the cadence for a short window — call after a submit. */
  pollFast: () => void;
};

/**
 * Brain-view data hook. Fetches {@code GET /api/tasks/{taskId}/brain} via
 * the IPC bridge and polls it; falls back to the in-memory fixture when no
 * bridge is wired (tests / very first paint). Returns {@link refresh} to
 * pull immediately and {@link pollFast} to tighten the cadence after the
 * user sends a message.
 */
export function useBrainViewData(taskId: string): BrainViewState {
  const [data, setData] = useState<TaskBrainViewData>(() => cache.get(taskId) ?? buildEmptyBrainView(taskId));
  const [error, setError] = useState<string | null>(null);
  const fastUntilRef = useRef<number>(0);
  const requestGenerationRef = useRef(0);

  // On a task switch (no remount), swap to that task's cached snapshot — or a
  // neutral empty shell — rather than showing the previous task's brain.
  const shownIdRef = useRef(taskId);
  if (shownIdRef.current !== taskId) {
    shownIdRef.current = taskId;
    requestGenerationRef.current += 1;
    setData(cache.get(taskId) ?? buildEmptyBrainView(taskId));
    setError(null);
  }

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getBrainView) {
      return;
    }
    const generation = ++requestGenerationRef.current;
    bridge.getBrainView(taskId)
      .then(d => {
        if (shownIdRef.current !== taskId || requestGenerationRef.current !== generation) return;
        cache.set(taskId, d);
        setData(d);
        setError(null);
      })
      .catch((e: unknown) => {
        if (shownIdRef.current !== taskId || requestGenerationRef.current !== generation) return;
        setError(e instanceof Error ? e.message : 'Failed to load the brain view');
      });
  }, [taskId]);

  const refresh = useCallback(() => fetchOnce(), [fetchOnce]);
  const pollFast = useCallback(() => {
    fastUntilRef.current = Date.now() + FAST_WINDOW_MS;
    fetchOnce();
  }, [fetchOnce]);

  useEffect(() => {
    fetchOnce();
    let timer: ReturnType<typeof setTimeout>;
    const tick = () => {
      fetchOnce();
      const delay = Date.now() < fastUntilRef.current ? FAST_POLL_MS : POLL_MS;
      timer = setTimeout(tick, delay);
    };
    timer = setTimeout(tick, POLL_MS);
    return () => {
      clearTimeout(timer);
      requestGenerationRef.current += 1;
    };
  }, [fetchOnce]);

  return { data, error, refresh, pollFast };
}
