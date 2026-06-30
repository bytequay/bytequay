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
import type { PrGlyphState, StatusDotVariant } from '../ui/primitives';
import type { TaskNavRow } from '../ui/workspace';
import type { WorkUnitTaskDto } from '../types';
import { taskLabel } from '../threads/taskLabel';

/** A task row's status dot. A thread can run several tasks at once, so each
 *  row carries its own state: done (green) when merged/completed, sleep
 *  (gray) when paused / errored / closed, active otherwise. */
function navDot(status: string): StatusDotVariant {
  switch (status) {
    case 'COMPLETED': return 'done';
    case 'PAUSED':
    case 'ERRORED':
    case 'CANCELED':
    case 'ARCHIVED': return 'sleep';
    default: return 'active';
  }
}

/** The PR-state glyph before the task's name in the rail: merged once the
 *  work landed, a draft / open pull-request mark while it's in flight,
 *  nothing before a PR exists. */
function navPr(t: WorkUnitTaskDto): PrGlyphState | undefined {
  if (t.status === 'COMPLETED') return 'merged';
  if (t.prNumber == null) return undefined;
  return typeof t.prState === 'string' && t.prState.toUpperCase() === 'DRAFT' ? 'draft' : 'open';
}

/**
 * The task rows to nest under a thread in the left rail — **every** task the
 * thread owns (newest sequence last), each a clickable row with its name +
 * status dot + PR glyph. A thread can run several at once, and closed
 * (canceled / completed / archived) tasks stay listed so the user can still
 * open them — they just read as inactive via the gray dot. Built straight
 * from the task list (no per-task brain-view fetch). Polls so a newly-cut
 * task appears without a reload. Empty when no thread is open.
 */
export function useThreadTasks(threadId: string | null): TaskNavRow[] {
  const [tasks, setTasks] = useState<TaskNavRow[]>([]);

  useEffect(() => {
    if (threadId === null) {
      setTasks([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
      if (bridge?.listTasksForThread === undefined) return;
      try {
        const list = await bridge.listTasksForThread(threadId);
        if (cancelled) return;
        setTasks(list.map(t => ({ id: t.id, label: taskLabel(t), dot: navDot(t.status), pr: navPr(t) })));
      }
      catch { /* leave the last loaded state */ }
    };
    void load();
    const iv = setInterval(() => { void load(); }, 8000);
    return () => { cancelled = true; clearInterval(iv); };
  }, [threadId]);

  return tasks;
}
