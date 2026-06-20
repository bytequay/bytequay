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
import { buildMockBrainView } from './brainViewFixture';

/** Steady poll cadence while the brain view is open. */
const POLL_MS = 5000;
/** Tightened cadence right after the user submits, so the YOU bubble and
 *  the brain reply land quickly. */
const FAST_POLL_MS = 1000;
/** How long to stay on the fast cadence after a submit. */
const FAST_WINDOW_MS = 10_000;

type BrainViewState = {
  /** The latest brain-view payload. Seeded with the mock fixture so the
   *  first paint (and component tests, where {@code window.bridge} is
   *  absent) have content; replaced by real backend data once a fetch
   *  resolves. */
  data: TaskBrainViewData;
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
  const [data, setData] = useState<TaskBrainViewData>(() => buildMockBrainView(Date.now()));
  const fastUntilRef = useRef<number>(0);

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getBrainView) {
      return;
    }
    bridge.getBrainView(taskId)
      .then(setData)
      .catch(() => { /* transient; the next poll retries */ });
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
    return () => clearTimeout(timer);
  }, [fetchOnce]);

  return { data, refresh, pollFast };
}
