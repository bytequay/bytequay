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
import { CheckCircle2, ChevronDown, ChevronRight, CirclePause, CircleX, LoaderCircle, TriangleAlert } from 'lucide-react';
import type { PinnedItem } from '../home/pinned';
import { workspaceAgentTaskRoute, workspaceCherryPickRoute, workspacePullRequestRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { RUNNING_TASK_LABELS, RUNNING_TASK_TYPES, visibleRunningTasks, type RunningTask } from './runningTaskModel';
import { TaskTypeIcon } from './TaskListCards';
import '../css/running-tasks.css';

const POLL_MS = 5000;

/** The sidebar's Working rows; pinned rows read the same snapshot to mark their own work. */
export function useRunningTasks(): RunningTask[] {
  const [tasks, setTasks] = useState<RunningTask[]>([]);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    let active = true;
    let pending = false;
    const read = async () => {
      if (pending || document.hidden) return;
      pending = true;
      try {
        const found = await window.bridge.listRunningTasks();
        if (active) { setTasks(found); setNow(Date.now()); }
      }
      catch { /* Keep the last snapshot through a failed read; completion deadlines still expire. */ }
      finally { pending = false; }
    };
    void read();
    const timer = window.setInterval(() => { setNow(Date.now()); void read(); }, POLL_MS);
    const refresh = () => { setNow(Date.now()); void read(); };
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, []);
  useEffect(() => {
    const deadlines = tasks.flatMap(task => task.expiresAt === null ? [] : [Date.parse(task.expiresAt)]).filter(at => at > now);
    if (deadlines.length === 0) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.max(0, Math.min(...deadlines) - Date.now()));
    return () => window.clearTimeout(timer);
  }, [tasks, now]);
  return visibleRunningTasks(tasks, now);
}

/** A pinned task or review runs itself; a pinned pull request works while any task on it runs. */
export function pinIsWorking(target: PinnedItem['target'], tasks: RunningTask[]): boolean {
  return tasks.some(task => task.status === 'RUNNING' && (target.kind === 'task' ? task.id === target.taskId
    : target.kind === 'review' ? task.kind === 'REVIEW' && task.targetId === target.reviewId
    : target.kind === 'pull' && task.pullRequestNumber === target.number && task.repository.toLowerCase() === target.repo.toLowerCase()));
}

/** A working name carries a sweep of light; the copy under the moving window is what lights up. */
export function WorkingLabel({ text }: { text: string }) {
  return <span className="app-sidebar__label is-working">
    {text}<span className="app-sidebar__shimmer" aria-hidden="true"><span>{text}</span></span>
  </span>;
}

export function runningTaskRoute(task: RunningTask): string {
  switch (task.kind) {
    case 'TASK': return workspaceAgentTaskRoute(task.workspaceId, task.targetId);
    case 'REVIEW': return workspaceReviewTaskRoute(task.workspaceId, task.targetId);
    case 'CHERRY_PICK': return workspaceCherryPickRoute(task.workspaceId, task.targetId);
    case 'CI_FIX': return workspacePullRequestRoute(task.workspaceId, Number(task.targetId));
  }
}

export function RunningTaskList({ tasks }: { tasks: RunningTask[] }) {
  const [expanded, setExpanded] = useState(true);
  if (tasks.length === 0) return null;
  return <section className="app-sidebar__running" aria-label="Working tasks">
    <button type="button" className="app-sidebar__pinned-heading" aria-label="Working tasks"
      aria-expanded={expanded} aria-controls="running-tasks" onClick={() => setExpanded(value => !value)}>
      <LoaderCircle size={14} aria-hidden="true" />
      <span className="app-sidebar__label">Working</span>
      {expanded ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}
    </button>
    {expanded && <nav id="running-tasks" className="app-sidebar__nav app-sidebar__running-list" aria-label="Working task navigation">
      {tasks.map(task => {
        const label = RUNNING_TASK_LABELS[task.status];
        // The eye icon already says "Review".
        const name = task.kind === 'REVIEW' ? task.title.replace(/^Review:? /, '') : task.title;
        const Icon = task.status === 'COMPLETED' ? CheckCircle2 : task.status === 'FAILED' ? CircleX : task.status === 'STOPPED' ? CirclePause : TriangleAlert;
        return <button key={task.id} type="button" className={`app-sidebar__item app-sidebar__running-item is-${task.status.toLowerCase()}`}
          aria-label={`${task.title}, ${label}, ${task.repository}`} title={`${task.title} · ${task.repository} · ${label}`}
          onClick={() => { window.location.hash = runningTaskRoute(task); }}>
          <span aria-hidden="true"><TaskTypeIcon type={RUNNING_TASK_TYPES[task.kind]} size={16} /></span>
          {task.status === 'RUNNING' ? <WorkingLabel text={name} /> : <>
            <span className="app-sidebar__label">{name}</span>
            <span className="app-sidebar__running-status" role="status" aria-label={label}><Icon size={14} aria-hidden="true" /></span>
          </>}
        </button>;
      })}
    </nav>}
  </section>;
}
