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
import type { StageDetailData } from '../../types/brainView';
import { makeIdCache } from './idCache';

/** Poll cadence while the stage is still OPEN/ACTIVE. A CLOSED stage is
 *  immutable, so we stop polling after the first successful load. */
const POLL_MS = 5000;

/** Last-known detail per stage id, so switching back to a stage paints its
 *  snapshot instantly while the poll revalidates — see {@link makeIdCache}. */
const cache = makeIdCache<StageDetailData>();

type StageDetailState = {
  /** Null only until the first-ever fetch for this stage resolves (and in
   *  tests with no bridge). On revisit it is the cached snapshot. */
  data: StageDetailData | null;
  refresh: () => void;
};

/**
 * Fetches {@code GET /api/stages/{stageId}/detail} via the IPC bridge and
 * polls it while the stage is live. Backed by a per-id stale-while-revalidate
 * cache: switching to a previously-seen stage shows its last snapshot at once
 * (no blank, no flash of the prior stage's data) while a fresh fetch refreshes
 * it underneath.
 */
export function useStageDetailData(stageId: string): StageDetailState {
  const [data, setData] = useState<StageDetailData | null>(() => cache.get(stageId) ?? null);

  // The caller switches stages by passing a new id (no remount). Swap to that
  // stage's cached snapshot synchronously — never leave the previous stage's
  // data showing under the new stage's header.
  const shownIdRef = useRef(stageId);
  if (shownIdRef.current !== stageId) {
    shownIdRef.current = stageId;
    setData(cache.get(stageId) ?? null);
  }

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getStageDetail) {
      return;
    }
    bridge.getStageDetail(stageId)
      .then(d => { cache.set(stageId, d); setData(d); })
      .catch(() => { /* transient; the next poll retries */ });
  }, [stageId]);

  useEffect(() => {
    fetchOnce();
    // Keep polling; a CLOSED stage simply returns identical data each tick.
    const id = setInterval(fetchOnce, POLL_MS);
    return () => clearInterval(id);
  }, [fetchOnce]);

  return { data, refresh: fetchOnce };
}
