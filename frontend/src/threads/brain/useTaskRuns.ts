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
import type { AgentRunDto } from '../../types/brainView';

/** Same cadence as {@link useStageDetailData} — these feed the same live rail. */
const POLL_MS = 5000;

type TaskRunsState = {
  runs: AgentRunDto[];
  error: string | null;
};

/**
 * Fetches a task's agent runs (live and finished) and polls them — backs
 * the Development stage feed's folded `ci_fix` episodes (plan-rail-runs.md
 * Phase 5).
 */
export function useTaskRuns(taskId: string): TaskRunsState {
  const [runs, setRuns] = useState<AgentRunDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const shownTaskIdRef = useRef(taskId);
  const requestGenerationRef = useRef(0);

  if (shownTaskIdRef.current !== taskId) {
    shownTaskIdRef.current = taskId;
    requestGenerationRef.current += 1;
    setRuns([]);
    setError(null);
  }

  const fetchOnce = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.getTaskRuns) {
      return;
    }
    const generation = ++requestGenerationRef.current;
    bridge.getTaskRuns(taskId)
      .then(next => {
        if (shownTaskIdRef.current !== taskId || requestGenerationRef.current !== generation) return;
        setRuns(next);
        setError(null);
      })
      .catch((reason: unknown) => {
        if (shownTaskIdRef.current !== taskId || requestGenerationRef.current !== generation) return;
        setError(reason instanceof Error ? reason.message : 'Failed to refresh task runs');
      });
  }, [taskId]);

  useEffect(() => {
    fetchOnce();
    const id = setInterval(fetchOnce, POLL_MS);
    return () => {
      clearInterval(id);
      requestGenerationRef.current += 1;
    };
  }, [fetchOnce]);

  return { runs, error };
}
