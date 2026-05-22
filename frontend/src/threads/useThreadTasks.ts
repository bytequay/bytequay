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
import type { WorkUnitTaskDto } from '../types';

export type ThreadTasksState = {
  /** Work-unit tasks for the thread, oldest-seq first. Null during
   *  the initial fetch so callers can distinguish loading from
   *  empty-by-design (a 0-Task brainstorm thread). */
  tasks: WorkUnitTaskDto[] | null;
  /** Last fetch error, if any. Cleared on the next successful fetch. */
  error: string | null;
  /** Force a refetch. The TasksInThreadSection's ship-and-continue
   *  button calls this after a successful POST so the rail updates
   *  without waiting for a parent-driven re-render. */
  refresh: () => Promise<void>;
};

/**
 * Shared loader for the thread's work-unit Tasks. Used by the rail
 * section that lists tasks AND by the conversation pane that draws
 * task-boundary dividers between them — one fetch, two readers.
 */
export function useThreadTasks(threadId: string): ThreadTasksState {
  const [tasks, setTasks] = useState<WorkUnitTaskDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await window.bridge.listTasksForThread(threadId);
      setTasks(list);
      setError(null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [threadId]);

  useEffect(() => {
    let cancelled = false;
    setTasks(null);
    setError(null);
    void (async () => {
      if (!cancelled) {
        await refresh();
      }
    })();
    return () => { cancelled = true; };
  }, [threadId, refresh]);

  return { tasks, error, refresh };
}
