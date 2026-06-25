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
import type { TaskNavRow } from '../ui/workspace';

/** A work-unit task's run status → the sidebar dot. */
function taskDot(status: string): StatusDotVariant {
  switch (status) {
    case 'RUNNING': return 'active';
    case 'AWAITING': case 'AWAITING_REVIEW': case 'NEEDS_ATTENTION': return 'planning';
    case 'COMPLETED': return 'done';
    case 'ERRORED': return 'sleep';
    default: return 'sleep';
  }
}

/**
 * The tasks to nest under a thread in the left rail. Loads the thread's
 * work-unit tasks (seq order) and maps each to a nav row — its rename,
 * else its branch, else "Task N" — with a status dot. Polls so a newly
 * cut task (or a status change) shows without a reload. Empty when no
 * thread is open.
 */
export function useThreadTaskRows(threadId: string | null): TaskNavRow[] {
  const [rows, setRows] = useState<TaskNavRow[]>([]);

  useEffect(() => {
    if (threadId === null) {
      setRows([]);
      return;
    }
    let cancelled = false;
    const load = async () => {
      const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
      if (bridge?.listTasksForThread === undefined) return;
      try {
        const tasks = await bridge.listTasksForThread(threadId);
        if (cancelled) return;
        setRows(tasks.map(t => ({
          id: t.id,
          label: (t.name ?? '') || t.branchName || `Task ${t.seq}`,
          dot: taskDot(t.status),
        })));
      }
      catch { /* leave the last loaded state */ }
    };
    void load();
    const iv = setInterval(() => { void load(); }, 5000);
    return () => { cancelled = true; clearInterval(iv); };
  }, [threadId]);

  return rows;
}
