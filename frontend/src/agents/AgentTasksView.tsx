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

import React, { useCallback, useEffect, useState } from 'react';
import '../css/agents.css';
import type { AgentTaskDto } from '../types';
import type { AgentBridgePort } from './preview';

export interface AgentTasksViewProps {
  workspaceId: string;
  bridge: AgentBridgePort;
  /** The route owns which task is open, so opening one is a navigation. */
  onOpenTask: (taskId: string) => void;
  /** Fixed Codex lane and backend defaults; the picker arrives with CP7. */
  laneId?: string;
}

function stateClass(state: string): string {
  switch (state) {
    case 'OPEN':
      return 'is-running';
    case 'COMPLETED':
      return 'is-completed';
    case 'FAILED':
      return 'is-failed';
    default:
      return '';
  }
}

/** A task state reads as prose; the wire enum is never shown to the user. */
const STATE_LABELS: Record<string, string> = {
  OPEN: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

export default function AgentTasksView({
  workspaceId,
  bridge,
  onOpenTask,
  laneId = 'codex',
}: AgentTasksViewProps): React.JSX.Element {
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    bridge.listAgentTasks(workspaceId)
      .then((found) => {
        if (!active) return;
        setTasks(found);
        setError(null);
      })
      .catch((failure: unknown) => {
        if (!active) return;
        setError(failure instanceof Error ? failure.message : 'Unable to list agent tasks');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [bridge, workspaceId]);

  const create = useCallback(async () => {
    const task = await bridge.createAgentTask({
      workspaceId,
      title: 'New agent task',
      laneId,
      // Backend and workspace defaults; no model, effort or permission picker before CP7.
      defaults: { model: null, effort: null, permissionPolicy: 'AUTO' },
    });
    setTasks((current) => [task, ...current.filter((existing) => existing.id !== task.id)]);
    onOpenTask(task.id);
  }, [bridge, laneId, onOpenTask, workspaceId]);

  return (
    <AgentTaskList
      tasks={tasks}
      loading={loading}
      error={error}
      onCreate={() => { void create(); }}
      onOpenTask={onOpenTask}
    />
  );
}

/** The list as a function of what was loaded, so empty, loading, error and populated all render. */
export function AgentTaskList({ tasks, loading, error, onCreate, onOpenTask }: {
  tasks: AgentTaskDto[];
  loading: boolean;
  error: string | null;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
}): React.JSX.Element {
  const openCount = tasks.filter((task) => task.state === 'OPEN').length;
  return (
    <div className="agt-section">
      <div className="agt-head">
        <h2>Agent tasks</h2>
        <span className="agt-sub">{openCount} open · {tasks.length - openCount} finished</span>
        <span className="agt-fixture-badge">Fixture data</span>
        <span className="agt-spacer" />
        <button type="button" className="agt-action" onClick={onCreate}>New agent task</button>
      </div>

      {error !== null && <div className="cmt-error" role="alert">{error}</div>}

      <div className="agt-list">
        {loading && <div className="agt-loading">Loading agent tasks…</div>}
        {!loading && tasks.length === 0 && (
          <div className="agt-empty">
            No agent tasks yet. This preview reads checked-in fixture data and never runs an agent.
          </div>
        )}
        {tasks.map((task) => (
          <button
            key={task.id}
            type="button"
            className="agt-row"
            onClick={() => onOpenTask(task.id)}
          >
            <span className="agt-row-title">{task.title}</span>
            <span className="agt-spacer" />
            <span className="agt-row-meta">{task.laneId}</span>
            <span className={`agt-state ${stateClass(task.state)}`}>
              {STATE_LABELS[task.state] ?? task.state}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}
