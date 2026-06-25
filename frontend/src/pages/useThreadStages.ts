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
import { useEffect, useState } from 'react';
import type { StatusDotVariant } from '../ui/primitives';
import type { StageNavRow } from '../ui/workspace';
import type { StageState, StageType } from '../types/brainView';

const STAGE_LABEL: Record<StageType, string> = {
  PLAN_STAGE: 'Plan',
  DEVELOPMENT_STAGE: 'Dev',
  CI_FIXING_STAGE: 'CI Fix',
  REVIEW_MONITOR_STAGE: 'Comments',
  CLEANUP_STAGE: 'Cleanup',
  REVIEW_STAGE: 'Review',
};

/** Stage lifecycle state → the sidebar dot. */
function stageDot(state: StageState): StatusDotVariant | undefined {
  switch (state) {
    case 'ACTIVE': return 'active';
    case 'CLOSED': return 'done';
    case 'PAUSED': return 'sleep';
    default: return 'future';
  }
}

/** A task is terminal once it has finished or errored — its stages are
 *  still navigable, but it's no longer the thread's live work. */
function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'ERRORED';
}

export type ThreadStages = {
  /** The task whose stages these are (the resolved active task), or null. */
  taskId: string | null;
  stages: StageNavRow[];
};

/**
 * Resolves the stages to show nested under a thread in the left rail.
 * When a {@code taskId} is supplied (the user is already inside a task)
 * its stages are used directly; otherwise the thread's active (non-terminal,
 * else latest) task is resolved first. Polls so a newly-opened stage —
 * Dev opening after a plan is approved, CI Fix after a push — appears
 * without a reload. Returns nothing when no thread is open.
 */
export function useThreadStages(threadId: string | null, taskId?: string): ThreadStages {
  const [data, setData] = useState<ThreadStages>({ taskId: null, stages: [] });

  useEffect(() => {
    if (threadId === null) {
      setData({ taskId: null, stages: [] });
      return;
    }
    let cancelled = false;
    const load = async () => {
      const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
      if (bridge?.getBrainView === undefined || bridge.listTasksForThread === undefined) return;
      try {
        let tid = taskId;
        if (tid === undefined) {
          const tasks = await bridge.listTasksForThread(threadId);
          const active = tasks.find(t => !isTerminal(t.status)) ?? tasks[tasks.length - 1];
          tid = active?.id;
        }
        if (tid === undefined) {
          if (!cancelled) setData({ taskId: null, stages: [] });
          return;
        }
        const view = await bridge.getBrainView(tid);
        if (cancelled) return;
        setData({
          taskId: tid,
          stages: view.stages.map(s => ({ id: s.id, label: STAGE_LABEL[s.type], dot: stageDot(s.state) })),
        });
      }
      catch { /* leave the last loaded state */ }
    };
    void load();
    const iv = setInterval(() => { void load(); }, 8000);
    return () => { cancelled = true; clearInterval(iv); };
  }, [threadId, taskId]);

  return data;
}
