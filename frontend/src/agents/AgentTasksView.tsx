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
import { ModelChip } from '../settings/SettingsShell';
import { INTERACTIVE_LANE_ID, laneLabel } from '../settings/model';
import '../css/agents.css';
import type { AgentTaskDto, ModelOptionDto, ModelSettingsDto } from '../types';
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
  laneId = INTERACTIVE_LANE_ID,
}: AgentTasksViewProps): React.JSX.Element {
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ModelSettingsDto | null>(null);
  const [selection, setSelection] = useState<{ model: string; effort: string | null } | null>(null);

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

  useEffect(() => {
    let active = true;
    bridge.getModelSettings()
      .then((settings) => {
        if (!active) return;
        setCatalog(settings);
        const provider = settings.providers.find((candidate) => candidate.id === laneId);
        // The user's own default selection, not a guess this screen invents.
        if (provider !== undefined && provider.selectedModel !== '') {
          setSelection({
            model: provider.selectedModel,
            effort: provider.selectedEffort === '' ? null : provider.selectedEffort,
          });
        }
      })
      .catch(() => { /* the row below reports an unusable catalog on its own */ });
    return () => { active = false; };
  }, [bridge, laneId]);

  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  const models: ModelOptionDto[] = provider?.models ?? [];
  // Creation needs a model the provider actually offers; without one it must not be attempted.
  const creatable = selection !== null && models.some((model) => model.id === selection.model);

  const create = useCallback(async () => {
    if (selection === null) return;
    const task = await bridge.createAgentTask({
      workspaceId,
      title: 'New agent task',
      laneId,
      defaults: { model: selection.model, effort: selection.effort, permissionPolicy: 'AUTO' },
    });
    setTasks((current) => [task, ...current.filter((existing) => existing.id !== task.id)]);
    onOpenTask(task.id);
  }, [bridge, laneId, onOpenTask, selection, workspaceId]);

  return (
    <AgentTaskList
      tasks={tasks}
      loading={loading}
      error={error}
      laneId={laneId}
      models={models}
      selection={selection}
      creatable={creatable}
      onSelectModel={(model, effort) => setSelection({ model, effort })}
      onCreate={() => { void create(); }}
      onOpenTask={onOpenTask}
    />
  );
}

/** The list as a function of what was loaded, so empty, loading, error and populated all render. */
export function AgentTaskList({
  tasks, loading, error, laneId, models, selection, creatable, onSelectModel, onCreate, onOpenTask,
}: {
  tasks: AgentTaskDto[];
  loading: boolean;
  error: string | null;
  laneId: string;
  models: ModelOptionDto[];
  selection: { model: string; effort: string | null } | null;
  creatable: boolean;
  onSelectModel: (model: string, effort: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
}): React.JSX.Element {
  const openCount = tasks.filter((task) => task.state === 'OPEN').length;
  const chosen = models.find((model) => model.id === selection?.model);
  return (
    <div className="agt-section">
      <div className="agt-head">
        <h2>Agent tasks</h2>
        <span className="agt-sub">{openCount} open · {tasks.length - openCount} finished</span>
        <span className="agt-fixture-badge">Fixture data</span>
        <span className="agt-spacer" />
        {/* The lane is fixed at creation, so it reads as a label rather than a picker. */}
        <span className="agt-lane" title="Agent tasks run on Codex in this build">
          {laneLabel(laneId)}
        </span>
        <ModelChip
          label={chosen?.name ?? 'No model'}
          detail={selection?.effort ?? undefined}
          disabled={models.length === 0}
          models={models}
          selectedModel={selection?.model ?? ''}
          selectedEffort={selection?.effort ?? undefined}
          onSelect={onSelectModel}
        />
        <button type="button" className="agt-action" onClick={onCreate} disabled={!creatable}>
          New agent task
        </button>
      </div>
      {!creatable && (
        <div className="agt-empty">
          No Codex model is available yet. Check the provider in Workspace settings, then reopen
          this tab.
        </div>
      )}

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
