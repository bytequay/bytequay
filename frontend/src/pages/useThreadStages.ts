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
import type { StageDto, StageState, StageType, TaskPhase } from '../types/brainView';

const STAGE_LABEL: Record<StageType, string> = {
  PLAN_STAGE: 'Plan',
  DEVELOPMENT_STAGE: 'Dev',
  CI_FIXING_STAGE: 'CI Fix',
  REVIEW_MONITOR_STAGE: 'Comments',
  CLEANUP_STAGE: 'Cleanup',
  REVIEW_STAGE: 'Review',
};

/** Once a task is shipped for review, the CI-fixing and comment-addressing
 *  stages run together — show both from the moment the PR is open, even
 *  before the backend lazily opens the row for one of them. */
const IN_REVIEW_PHASES = new Set<TaskPhase>([
  'PUSHED_AWAITING_CI', 'CI_FIXING', 'AWAITING_READY',
  'AWAITING_REMOTE_REVIEW', 'ADDRESSING_COMMENTS', 'AGENT_RE_REVIEW', 'AWAITING_UPDATE_PUSH',
]);
const MONITOR_STAGES: StageType[] = ['CI_FIXING_STAGE', 'REVIEW_MONITOR_STAGE'];

/** Stage lifecycle state → the sidebar dot. OPEN reads as live (awake /
 *  monitoring) — e.g. a CI-fixing stage watching a shipped PR's checks — so
 *  it's visibly distinct from a not-yet-opened 'future' placeholder. */
function stageDot(state: StageState): StatusDotVariant | undefined {
  switch (state) {
    case 'ACTIVE':
    case 'OPEN': return 'active';
    case 'CLOSED': return 'done';
    case 'PAUSED': return 'sleep';
    default: return 'future';
  }
}

/**
 * The stage nav rows for a task: its real stages, plus — once it's in the
 * review window — both monitor stages shown together. A monitor stage with
 * no backing row yet renders as a dimmed, non-clickable "pending" entry, so
 * the user sees CI-fixing and Addressing-comments side by side from ship.
 */
function buildStageNav(stages: StageDto[], phase: TaskPhase, prNumber: number | null): StageNavRow[] {
  const rows: StageNavRow[] = stages
    // The Plan "stage" is the brain/root conversation, not a drill-in stage —
    // it's reached via the task (its brain page), not a stage row.
    .filter(s => s.type !== 'PLAN_STAGE')
    .map(s => ({ id: s.id, label: STAGE_LABEL[s.type], dot: stageDot(s.state) }));
  const inReview = IN_REVIEW_PHASES.has(phase) || prNumber !== null;
  if (!inReview) return rows;
  for (const type of MONITOR_STAGES) {
    if (!stages.some(s => s.type === type)) {
      rows.push({ label: STAGE_LABEL[type], dot: 'future', pending: true });
    }
  }
  return rows;
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
          stages: buildStageNav(view.stages, view.task.currentPhase, view.task.prNumber),
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
