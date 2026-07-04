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
import type { ReviewRoundDto } from '../../types/brainView';

/** Same cadence as {@link useStageDetailData} — these feed the same live rail. */
const POLL_MS = 5000;

type TaskRoundsState = {
  rounds: ReviewRoundDto[];
  refresh: () => void;
};

/**
 * Fetches a task's review rounds (newest-first) and polls them — backs the
 * Comments stage feed's `RoundEpisode` list (plan-rail-runs.md Phase 5).
 */
export function useTaskRounds(taskId: string): TaskRoundsState {
  const [rounds, setRounds] = useState<ReviewRoundDto[]>([]);

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getTaskRounds) {
      return;
    }
    bridge.getTaskRounds(taskId)
      .then(setRounds)
      .catch(() => { /* transient; the next poll retries */ });
  }, [taskId]);

  useEffect(() => {
    fetchOnce();
    const id = setInterval(fetchOnce, POLL_MS);
    return () => clearInterval(id);
  }, [fetchOnce]);

  return { rounds, refresh: fetchOnce };
}
