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
import { INTERACTIVE_LANE_ID } from '../settings/model';
import { TaskWindows } from './TaskCard';
import type { AgentBridgePort } from './model';
import type { AgentTaskDto, ModelOptionDto } from '../types';

export interface TaskOverviewProps {
  workspaceId: string;
  bridge?: AgentBridgePort;
  /** The task open behind the overlay, ringed so the user knows where they came from. */
  activeTaskId: string | null;
  onOpenTask: (taskId: string) => void;
  onNewTask: () => void;
  onDismiss: () => void;
  laneId?: string;
}

/** Reads the tasks itself: the overlay is transient, and nothing else on screen owns this list. */
export default function TaskOverview({
  workspaceId, bridge: explicitBridge, activeTaskId, onOpenTask, onNewTask, onDismiss, laneId = INTERACTIVE_LANE_ID,
}: TaskOverviewProps): React.JSX.Element {
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = useCallback(() => live().listAgentTasks(workspaceId)
    .then((found) => setTasks(found.filter((task) => task.state === 'OPEN')))
    .catch((failure: unknown) => setNotice(failure instanceof Error ? failure.message : String(failure))), [live, workspaceId]);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    void live().getModelSettings()
      .then((catalog) => setModels(catalog.providers.find((provider) => provider.id === laneId)?.models ?? []))
      .catch(() => {});
  }, [live, laneId]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => { if (event.key === 'Escape') onDismiss(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onDismiss]);
  const close = useCallback((task: AgentTaskDto) => {
    // The card's ✕ is Complete: it closes the task and keeps its result branch, never a discard.
    if (!window.confirm(`Close "${task.title}"? Its clean checkout is removed and the result branch is kept.`)) return;
    void live().completeAgentTask(task.id, workspaceId)
      .then(() => refresh())
      .catch((failure: unknown) => setNotice(failure instanceof Error ? failure.message : String(failure)));
  }, [live, refresh, workspaceId]);
  return (
    <TaskOverviewView
      tasks={tasks}
      models={models}
      bridge={live}
      notice={notice}
      activeTaskId={activeTaskId}
      onOpenTask={onOpenTask}
      onCloseTask={close}
      onNewTask={onNewTask}
    />
  );
}

export function TaskOverviewView({ tasks, models, bridge, notice, activeTaskId, onOpenTask, onCloseTask, onNewTask }: {
  tasks: AgentTaskDto[];
  models: ModelOptionDto[];
  bridge: () => AgentBridgePort;
  notice: string | null;
  activeTaskId: string | null;
  onOpenTask: (taskId: string) => void;
  onCloseTask: (task: AgentTaskDto) => void;
  onNewTask: () => void;
}): React.JSX.Element {
  const running = tasks.filter((task) => task.mutationClaim?.kind === 'TURN').length;
  return (
    <div className="agt-overview" role="dialog" aria-label="All tasks">
      <div className="agt-overview__head">
        <h1>Tasks</h1>
        <span className="agt-muted agt-overview__summary">
          {tasks.length} open{running === 0 ? '' : ` · ${running} running`}
        </span>
        <span className="agt-spacer" />
        <button type="button" className="agt-action" onClick={onNewTask}><span aria-hidden="true">+ </span>New task</button>
      </div>
      {notice !== null && <div className="agt-notice agt-overview__notice" role="alert">{notice}</div>}
      <div className="agt-overview__grid">
        <TaskWindows tasks={tasks} models={models} width={294} bridge={bridge} activeTaskId={activeTaskId} onOpen={onOpenTask} onClose={onCloseTask} />
        <button type="button" className="agt-card-new" aria-label="New task" onClick={onNewTask}>
          <span className="agt-card-new__plus" aria-hidden="true">+</span>
          <span>New task</span>
        </button>
      </div>
      <div className="agt-overview__foot">
        <span><kbd className="agt-kbd">{'⇧⌘\\'}</kbd> toggle overview</span>
        <span><kbd className="agt-kbd">esc</kbd> back to the current task</span>
        <span>Click a window to open it · ✕ closes the task</span>
      </div>
    </div>
  );
}
