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
import type { TaskCheckpointDto } from '../types';

export type CheckpointsState = {
  /** Active rows for the task — Overall first, then per-segment by
   *  descending seq. Empty until the first fetch resolves. */
  rows: TaskCheckpointDto[];
  /** True during the initial fetch; null state vs empty state. */
  loading: boolean;
  /** Last fetch error, if any. Cleared on the next successful fetch. */
  error: string | null;
  /** True while a manual-generate request is in flight, so the rail
   *  can disable the button and show a spinner. */
  generating: boolean;
  /** Last manual-generate error, separate from {@link #error} so the
   *  rail can surface it inline near the button without wiping the
   *  list. */
  generateError: string | null;
  /** Most recent failure the background scheduler recorded for this
   *  task (e.g. "Anthropic API key not configured"). Null when the
   *  latest attempt succeeded or hasn't run. Surfaced as a banner so
   *  an empty rail isn't silently confusing. */
  schedulerError: string | null;
};

/**
 * Reads the active checkpoints for a task and keeps them in sync.
 *
 * <p>The scheduler writes new segments (and refreshes the Overall)
 * asynchronously after a {@code turn_done} event lands; we refetch
 * on {@code TurnDone} so the rail picks up the result without the
 * user needing to reload. Other event types are ignored to keep the
 * refresh quiet.
 *
 * <p>{@link #generate} drives the manual "+ save" button. It posts
 * to the create endpoint, then refetches so the new segment (or the
 * unchanged list when the scheduler already covered the range)
 * lands in the rail.
 */
export function useCheckpoints(taskId: string): CheckpointsState & {
  /** Force-generate a checkpoint for whatever's accumulated since
   *  the last segment. No-op when nothing new exists; the backend
   *  returns 204 in that case and we just refetch. */
  generate: () => Promise<void>;
  /** Hook the upstream SSE event names so we refetch on TurnDone
   *  without opening a second stream. The parent stashes this in a
   *  ref and invokes it from its existing handler. */
  onUpstreamEvent: (eventName: string) => void;
} {
  const [rows, setRows] = useState<TaskCheckpointDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [schedulerError, setSchedulerError] = useState<string | null>(null);
  // Track current task in a ref so a slow response from the previous
  // task can't paint over a new one after a task switch.
  const taskIdRef = useRef(taskId);
  // Single in-flight refresh promise so SSE-driven refetches don't
  // pile up while the network is slow.
  const inflightRef = useRef<Promise<void> | null>(null);

  useEffect(() => {
    taskIdRef.current = taskId;
  }, [taskId]);

  const refresh = useCallback(async (id: string) => {
    if (inflightRef.current !== null) {
      return inflightRef.current;
    }
    const p = (async () => {
      try {
        // Fetch the list and the scheduler status in parallel so the
        // banner ("summariser disabled") and the rows update together.
        // Each is wrapped in its own try/catch so a status-endpoint
        // failure (e.g. the backend hasn't picked up the new route)
        // doesn't blank the list and vice versa.
        const listResult = await window.bridge.getTaskCheckpoints(id).catch(
          (e: unknown): TaskCheckpointDto[] | null => {
            if (taskIdRef.current === id) {
              setError(e instanceof Error ? e.message : String(e));
            }
            return null;
          });
        const statusResult = await window.bridge.getTaskCheckpointStatus(id).catch(
          (): { lastError: string | null } | null => null);
        const list = listResult;
        const status = statusResult;
        if (taskIdRef.current !== id) return;
        if (list !== null) {
          setRows(list);
          setError(null);
        }
        setSchedulerError(status?.lastError ?? null);
      }
      catch (e) {
        if (taskIdRef.current !== id) return;
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
    inflightRef.current = p;
    try {
      await p;
    }
    finally {
      inflightRef.current = null;
    }
  }, []);

  // Initial load on task switch. Reset state so a leftover list from
  // the previous task can't briefly render against the new task.
  useEffect(() => {
    setRows([]);
    setError(null);
    setGenerateError(null);
    setSchedulerError(null);
    setLoading(true);
    void (async () => {
      await refresh(taskId);
      if (taskIdRef.current === taskId) {
        setLoading(false);
      }
    })();
  }, [taskId, refresh]);

  const generate = useCallback(async () => {
    if (generating) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      await window.bridge.generateTaskCheckpoint(taskIdRef.current);
      await refresh(taskIdRef.current);
    }
    catch (e) {
      setGenerateError(e instanceof Error ? e.message : String(e));
    }
    finally {
      setGenerating(false);
    }
  }, [generating, refresh]);

  // Refetch only on events that could change the checkpoint list.
  // TurnDone is the trigger the scheduler hangs off; everything else
  // (deltas, usage updates, permission requests, …) is irrelevant.
  const onUpstreamEvent = useCallback((eventName: string) => {
    if (eventName === 'TurnDone') {
      void refresh(taskIdRef.current);
    }
  }, [refresh]);

  return {
    rows, loading, error, generating, generateError, schedulerError,
    generate, onUpstreamEvent,
  };
}
