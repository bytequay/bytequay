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
import { useCallback, useEffect, useState } from 'react';
import type { StageDetailData } from '../../types/brainView';

/** Poll cadence while the stage is still OPEN/ACTIVE. A CLOSED stage is
 *  immutable, so we stop polling after the first successful load. */
const POLL_MS = 5000;

type StageDetailState = {
  /** Null until the first fetch resolves (and in tests with no bridge). */
  data: StageDetailData | null;
  refresh: () => void;
};

/**
 * Fetches {@code GET /api/stages/{stageId}/detail} via the IPC bridge and
 * polls it while the stage is live. A drill-in surface, so the floor is
 * polling — no optimistic state, no fixture fallback (the page renders a
 * loading state until data arrives).
 */
export function useStageDetailData(stageId: string): StageDetailState {
  const [data, setData] = useState<StageDetailData | null>(null);

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getStageDetail) {
      return;
    }
    bridge.getStageDetail(stageId)
      .then(setData)
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
