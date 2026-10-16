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
import { workspaceAgentTaskRoute, workspaceCherryPickRoute, workspacePullRequestRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { RUNNING_TASK_LABELS, visibleRunningTasks, type RunningTask } from './runningTaskModel';
import '../css/running-tasks.css';

const POLL_MS = 5000;

export default function RunningTasks() {
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
  return <RunningTaskList tasks={visibleRunningTasks(tasks, now)} />;
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
  return <section className="app-sidebar__running" aria-label="Running tasks">
    <button type="button" className="app-sidebar__pinned-heading" aria-label="Running tasks"
      aria-expanded={expanded} aria-controls="running-tasks" onClick={() => setExpanded(value => !value)}>
      <LoaderCircle size={14} aria-hidden="true" />
      <span className="app-sidebar__label">Running</span>
      {expanded ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}
    </button>
    {expanded && <nav id="running-tasks" className="app-sidebar__nav app-sidebar__running-list" aria-label="Running task navigation">
      {tasks.map(task => {
        const label = RUNNING_TASK_LABELS[task.status];
        const Icon = task.status === 'RUNNING' ? LoaderCircle : task.status === 'COMPLETED' ? CheckCircle2
          : task.status === 'FAILED' ? CircleX : task.status === 'STOPPED' ? CirclePause : TriangleAlert;
        return <button key={task.id} type="button" className={`app-sidebar__item app-sidebar__running-item is-${task.status.toLowerCase()}`}
          aria-label={`${task.title}, ${label}, ${task.repository}`} title={`${task.title} · ${task.repository} · ${label}`}
          onClick={() => { window.location.hash = runningTaskRoute(task); }}>
          <span aria-hidden="true"><Icon size={16} /></span>
          <span className="app-sidebar__label">{task.title}</span>
          <span className="app-sidebar__running-status app-sidebar__label" role="status">{label}</span>
        </button>;
      })}
    </nav>}
  </section>;
}
