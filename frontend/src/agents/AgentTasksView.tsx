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
import { INTERACTIVE_LANE_ID, laneLabel, providerAvailable, providerRemedy, remedyCommand } from '../settings/model';
import '../css/agents.css';
import type { AgentTaskDto, ModelOptionDto, ModelSettingsDto } from '../types';
import type { AgentBridgePort } from './model';

export interface AgentTasksViewProps {
  workspaceId: string;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  /** The route owns which task is open, so opening one is a navigation. */
  onOpenTask: (taskId: string) => void;
  /** Where a provider that is not ready gets fixed. */
  onOpenSettings?: () => void;
  /** Fixed Codex lane; Claude arrives with its parity checkpoint. */
  laneId?: string;
}

const DEFAULT_TITLE = 'New agent task';

/** A task state reads as prose; the wire enum is never shown to the user. */
const STATE_LABELS: Record<string, string> = {
  OPEN: 'Open',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

/** The pill for one task: a reserved checkout is worth calling out over plain "open". */
export function taskStatePill(task: AgentTaskDto): { label: string; className: string } {
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') {
    return { label: 'Recovery required', className: 'is-recovery' };
  }
  switch (task.state) {
    case 'OPEN':
      return { label: STATE_LABELS.OPEN, className: 'is-running' };
    case 'COMPLETED':
      return { label: STATE_LABELS.COMPLETED, className: 'is-completed' };
    case 'FAILED':
      return { label: STATE_LABELS.FAILED, className: 'is-failed' };
    default:
      return { label: task.state, className: '' };
  }
}

/**
 * Why a task cannot be created right now, or null when it can.
 *
 * Creation needs a provider that is ready and a model it actually offers; the backend refuses
 * anything less, so the button is disabled with the reason rather than letting the POST fail.
 */
export function creationBlocker(
  catalog: ModelSettingsDto | null,
  laneId: string,
  selection: { model: string; effort: string | null } | null,
): string | null {
  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  if (provider === null) {
    return `${laneLabel(laneId)} has not been checked yet. Check it in Workspace settings → Agent providers.`;
  }
  if (!providerAvailable(provider.readiness)) {
    const remedy = providerRemedy(provider.readiness);
    const command = remedyCommand(provider, remedy);
    // The probe's own diagnostic names the actual obstacle; the remedy alone can be generic.
    return `${laneLabel(laneId)} is not ready — ${remedy.title}. ${remedy.detail}`
      + (command === null ? '' : ` Run: ${command}`)
      + (provider.diagnostic === '' ? '' : ` ${provider.diagnostic}`);
  }
  if (selection === null || !provider.models.some((model) => model.id === selection.model)) {
    return `No ${laneLabel(laneId)} model is available yet. Checking the provider in Workspace settings also fetches its models.`;
  }
  return null;
}

export default function AgentTasksView({
  workspaceId,
  bridge: explicitBridge,
  onOpenTask,
  onOpenSettings,
  laneId = INTERACTIVE_LANE_ID,
}: AgentTasksViewProps): React.JSX.Element {
  // Resolved when used, not when rendered: a static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ModelSettingsDto | null>(null);
  const [selection, setSelection] = useState<{ model: string; effort: string | null } | null>(null);
  const [title, setTitle] = useState(DEFAULT_TITLE);

  useEffect(() => {
    let active = true;
    setLoading(true);
    live().listAgentTasks(workspaceId)
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
  }, [live, workspaceId]);

  useEffect(() => {
    let active = true;
    live().getModelSettings()
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
      .catch(() => { /* the blocker below reports an unusable catalog on its own */ });
    return () => { active = false; };
  }, [live, laneId]);

  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  const models: ModelOptionDto[] = provider?.models ?? [];
  const blocker = creationBlocker(catalog, laneId, selection);

  const create = useCallback(async () => {
    if (selection === null || blocker !== null) return;
    try {
      const task = await live().createAgentTask({
        workspaceId,
        title: title.trim() === '' ? DEFAULT_TITLE : title.trim(),
        laneId,
        defaults: { model: selection.model, effort: selection.effort, permissionPolicy: 'AUTO' },
      });
      setTasks((current) => [task, ...current.filter((existing) => existing.id !== task.id)]);
      onOpenTask(task.id);
    }
    catch (failure) {
      // Creation is gated on a fresh readiness probe, so this is the probe saying no.
      setError(failure instanceof Error ? failure.message : 'Unable to create the agent task');
    }
  }, [blocker, laneId, live, onOpenTask, selection, title, workspaceId]);

  return (
    <AgentTaskList
      tasks={tasks}
      loading={loading}
      error={error}
      laneId={laneId}
      models={models}
      selection={selection}
      title={title}
      blocker={blocker}
      onTitleChange={setTitle}
      onSelectModel={(model, effort) => setSelection({ model, effort })}
      onCreate={() => { void create(); }}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
    />
  );
}

/** The list as a function of what was loaded, so empty, loading, error and populated all render. */
export function AgentTaskList({
  tasks, loading, error, laneId, models, selection, title, blocker, onTitleChange, onSelectModel, onCreate, onOpenTask, onOpenSettings,
}: {
  tasks: AgentTaskDto[];
  loading: boolean;
  error: string | null;
  laneId: string;
  models: ModelOptionDto[];
  selection: { model: string; effort: string | null } | null;
  title: string;
  /** Why creation is unavailable; null when the task can be created. */
  blocker: string | null;
  onTitleChange: (title: string) => void;
  onSelectModel: (model: string, effort: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
}): React.JSX.Element {
  const openCount = tasks.filter((task) => task.state === 'OPEN').length;
  const chosen = models.find((model) => model.id === selection?.model);
  return (
    <div className="agt-section">
      <div className="agt-head">
        <h2>Agent tasks</h2>
        <span className="agt-sub">{openCount} open · {tasks.length - openCount} finished</span>
        <span className="agt-spacer" />
        <input
          className="agt-title-input"
          type="text"
          aria-label="Title for the new agent task"
          value={title}
          onChange={(event) => onTitleChange(event.target.value)}
        />
        {/* The lane is fixed at creation, so it reads as a label rather than a picker. */}
        <span className="agt-lane" title="Agent tasks run on Codex in this build" aria-label="Provider">
          {laneLabel(laneId)}
        </span>
        <ModelChip
          ariaLabel="Model and effort for the new task"
          label={chosen?.name ?? 'No model'}
          detail={selection?.effort ?? undefined}
          disabled={models.length === 0}
          models={models}
          selectedModel={selection?.model ?? ''}
          selectedEffort={selection?.effort ?? undefined}
          onSelect={onSelectModel}
        />
        <span className="agt-lane" title="Permission policy for every turn" aria-label="Permission policy">Auto</span>
        <button type="button" className="agt-action" onClick={onCreate} disabled={blocker !== null}>
          New agent task
        </button>
      </div>
      {blocker !== null && (
        <div className="agt-empty">
          {blocker}
          {onOpenSettings !== undefined && (
            <>
              {' '}
              <button type="button" className="agt-action" onClick={onOpenSettings}>Open workspace settings</button>
            </>
          )}
        </div>
      )}

      {error !== null && <div className="cmt-error" role="alert">{error}</div>}

      <div className="agt-list">
        {loading && <div className="agt-loading">Loading agent tasks…</div>}
        {!loading && tasks.length === 0 && (
          <div className="agt-empty">No agent tasks yet.</div>
        )}
        {tasks.map((task) => {
          const pill = taskStatePill(task);
          return (
            <button
              key={task.id}
              type="button"
              className="agt-row"
              onClick={() => onOpenTask(task.id)}
            >
              <span className="agt-row-title">{task.title}</span>
              <span className="agt-spacer" />
              <span className="agt-row-meta">{laneLabel(task.laneId)}</span>
              <span className={`agt-state ${pill.className}`}>{pill.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
