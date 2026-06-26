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

/** A task is terminal once it's finished, errored, canceled, or archived —
 *  so the rail resolves the genuinely-live task, not a closed one. */
function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'ERRORED'
    || status === 'CANCELED' || status === 'ARCHIVED';
}

/** Task lifecycle → the rail dot, so the task row shows its state at a
 *  glance: closed/terminal reads done, paused sleeps, otherwise it's live. */
function taskDot(view: { terminal: boolean; paused: boolean }): StatusDotVariant {
  if (view.terminal) return 'done';
  if (view.paused) return 'sleep';
  return 'active';
}

export type ThreadStages = {
  /** The task whose stages these are (the resolved active task), or null. */
  taskId: string | null;
  /** That task's display name — the rail's sub-header above the stages. */
  taskLabel: string | null;
  /** That task's lifecycle dot (done when closed, active while running). */
  taskDot: StatusDotVariant | undefined;
  stages: StageNavRow[];
};

/**
 * Resolves the stages to nest under a thread in the left rail — the dev /
 * plan / CI-fix stages of the thread's active task (so the rail shows what
 * the work is doing, not a redundant list of same-named tasks). When a
 * {@code taskId} is supplied (the user is inside a task) its stages are
 * used directly; otherwise the thread's active (non-terminal, else latest)
 * task is resolved first. Polls so a newly-opened stage appears without a
 * reload. Empty when no thread is open.
 */
export function useThreadStages(threadId: string | null, taskId?: string): ThreadStages {
  const [data, setData] = useState<ThreadStages>({ taskId: null, taskLabel: null, taskDot: undefined, stages: [] });

  useEffect(() => {
    if (threadId === null) {
      setData({ taskId: null, taskLabel: null, taskDot: undefined, stages: [] });
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
          if (!cancelled) setData({ taskId: null, taskLabel: null, taskDot: undefined, stages: [] });
          return;
        }
        const view = await bridge.getBrainView(tid);
        if (cancelled) return;
        setData({
          taskId: tid,
          taskLabel: view.task.title,
          taskDot: taskDot(view.task),
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
