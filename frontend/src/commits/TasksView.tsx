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
import { useEffect, useMemo, useState } from 'react';
import type { CherryPickExecution } from '../types';
import {
  completedExecutionItems,
  filterTasks,
  isOpenTask,
  orderTasks,
  taskState,
  type TaskFilter,
  type TaskState,
} from './model';

const PAGE_SIZE = 10;

const STATE_LABEL: Record<TaskState, string> = {
  running: 'RUNNING',
  paused: 'PAUSED',
  done: 'DONE',
  canceled: 'CANCELED',
};

export function taskTitle(execution: CherryPickExecution): string {
  if (execution.pullRequestDraft?.title) return execution.pullRequestDraft.title;
  return `Cherry-pick ${execution.items.length} commits into ${execution.localTargetBranch}`;
}

function taskMeta(execution: CherryPickExecution): string {
  const route = `${execution.items.length} commits · ${execution.repository} → ${execution.localTargetBranch}`;
  const state = taskState(execution);
  if (state === 'running') {
    const position = execution.currentPosition;
    const step = execution.currentStep === 'RESOLVING' ? 'resolving conflict on' : 'applying';
    const item = position ? execution.items.find(entry => entry.position === position) : undefined;
    return position && item
      ? `${route} · item ${position} of ${execution.items.length} · ${step} ${item.sourceSha.slice(0, 7)}`
      : route;
  }
  if (state === 'paused') {
    return `${route} · paused on item ${execution.currentPosition ?? '?'} — needs your input`;
  }
  if (state === 'canceled') return `${route} · canceled`;
  const applied = execution.items.filter(item => item.result === 'APPLIED').length;
  const skipped = execution.items.filter(item => item.result === 'USER_SKIPPED').length;
  const already = execution.items.filter(item => item.result === 'ALREADY_ON_TARGET').length;
  const parts = [`${applied} applied`];
  if (skipped > 0) parts.push(`${skipped} skipped`);
  if (already > 0) parts.push(`${already} already on target`);
  return `${route} · ${parts.join(' · ')}`;
}

export default function TasksView({ workspaceId, embedded = false, onOpenTask, onNewCherryPick }: {
  workspaceId: string;
  /** Drawn as one section of a wider Tasks page rather than as a page of its own. */
  embedded?: boolean;
  onOpenTask: (execution: CherryPickExecution) => void;
  onNewCherryPick: () => void;
}) {
  const [tasks, setTasks] = useState<CherryPickExecution[]>([]);
  const [filter, setFilter] = useState<TaskFilter>('all');
  const [shown, setShown] = useState(PAGE_SIZE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    window.bridge.listCherryPickExecutions(200)
      .then(executions => {
        if (!active) return;
        setTasks(executions.filter(task => task.workspaceId === workspaceId));
      })
      .catch(failure => {
        if (active) setError(failure instanceof Error ? failure.message : String(failure));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [workspaceId]);

  const sorted = useMemo(() => orderTasks(filterTasks(tasks, filter)), [tasks, filter]);
  const rows = sorted.slice(0, shown);
  const openCount = tasks.filter(isOpenTask).length;

  return (
    <div className={`cmt-tasks${embedded ? ' is-embedded' : ''}`}>
      <div className="cmt-tasks-head">
        {embedded ? <span className="cmt-tasks-label">Cherry-pick executions</span> : <h1>Cherry-pick tasks</h1>}
        <span className="cmt-tasks-sub">
          {openCount} running or paused · {tasks.length - openCount} finished
        </span>
        <div className="cmt-tasks-spacer" />
        <button type="button" className="cmt-btn-green" onClick={onNewCherryPick}>
          + New cherry-pick
        </button>
        <div className="cmt-seg">
          {([['all', 'All'], ['open', 'Not done'], ['done', 'Done']] as const).map(([key, label]) => (
            <button
              type="button"
              key={key}
              className={filter === key ? 'is-active' : ''}
              onClick={() => { setFilter(key); setShown(PAGE_SIZE); }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {error && <div className="cmt-error" role="alert">{error}</div>}

      <div className="cmt-task-list">
        {loading && <div className="cmt-loading">Loading tasks…</div>}
        {!loading && rows.length === 0 && (
          <div className="cmt-loading">No cherry-pick tasks yet.</div>
        )}
        {rows.map(task => {
          const state = taskState(task);
          return (
            <button
              type="button"
              key={task.executionId}
              className="cmt-task-row"
              onClick={() => onOpenTask(task)}
            >
              <span className={`cmt-task-dot is-${state}`} />
              <span className="cmt-task-id">{task.executionId.slice(0, 8)}</span>
              <span className="cmt-task-main">
                <span className="cmt-task-title">{taskTitle(task)}</span>
                <span className="cmt-task-meta">{taskMeta(task)}</span>
              </span>
              <span className="cmt-task-progress">
                {completedExecutionItems(task.items)}/{task.items.length}
              </span>
              <span className={`cmt-task-state is-${state}`}>{STATE_LABEL[state]}</span>
              <span className="cmt-task-chev">›</span>
            </button>
          );
        })}
      </div>
      {sorted.length > rows.length && (
        <button
          type="button"
          className="cmt-load-more"
          onClick={() => setShown(current => current + PAGE_SIZE)}
        >
          Load more ({sorted.length - rows.length} older)
        </button>
      )}
      <div className="cmt-tasks-footnote">
        To start a new cherry-pick, open <strong>History</strong> on the upstream tab, select a
        range of commits, and confirm.
      </div>
    </div>
  );
}
