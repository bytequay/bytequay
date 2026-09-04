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

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Chevron } from '../Chevron';
import { relativeTime } from '../relativeTime';
import { INTERACTIVE_LANE_ID, displayModelName, laneLabel, providerAvailable, providerRemedy, remedyCommand } from '../settings/model';
import { ModelPicker } from './ModelPicker';
import '../css/agents.css';
import type { AgentTaskDto, ModelOptionDto, ModelSettingsDto } from '../types';
import { createTaskCreationTracker, type AgentBridgePort, type TaskCreationTracker } from './model';

export interface AgentTasksViewProps {
  workspaceId: string;
  /** The branch new tasks start from; shown on the composer as a fact. */
  branch?: string;
  /** The rest of the Tasks page, drawn above the agent tasks under the one heading. */
  children?: React.ReactNode;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  /** The route owns which task is open, so opening one is a navigation. */
  onOpenTask: (taskId: string) => void;
  /** Where a provider that is not ready gets fixed. */
  onOpenSettings?: () => void;
  /** Fixed Codex lane; Claude arrives with its parity checkpoint. */
  laneId?: string;
}

/** A task state reads as prose; the wire enum is never shown to the user. */
const STATE_LABELS: Record<string, string> = {
  OPEN: 'Ready',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

/** The pill for one task: a reserved checkout is worth calling out over plain "open". */
/**
 * The list knows the durable task, not its events: a turn's claim on the checkout is what says a
 * task is running, and a completed task reads as done in grey, as the design draws it.
 */
export function taskStatePill(task: AgentTaskDto): { label: string; className: string; spinning: boolean } {
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') {
    return { label: 'Recovery required', className: 'is-recovery', spinning: false };
  }
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'TURN') {
    return { label: 'Running', className: 'is-running', spinning: true };
  }
  switch (task.state) {
    case 'OPEN':
      return { label: STATE_LABELS.OPEN, className: 'is-idle', spinning: false };
    case 'COMPLETED':
      return { label: STATE_LABELS.COMPLETED, className: '', spinning: false };
    case 'FAILED':
      return { label: STATE_LABELS.FAILED, className: 'is-failed', spinning: false };
    default:
      return { label: task.state, className: '', spinning: false };
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

/**
 * Everything creating a task needs: the list, the catalog, the chosen model, the draft, why
 * creation is blocked, and the create call itself — shared by the Tasks page and the Home hero.
 */
export function useAgentTaskCreation({ workspaceId, laneId, bridge, onOpenTask }: {
  workspaceId: string;
  laneId: string;
  bridge: () => AgentBridgePort;
  onOpenTask: (taskId: string) => void;
}) {
  const [tasks, setTasks] = useState<AgentTaskDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ModelSettingsDto | null>(null);
  const [selection, setSelection] = useState<{ model: string; effort: string | null } | null>(null);
  const [draft, setDraft] = useState('');
  const submissionRef = useRef<TaskCreationTracker | null>(null);
  if (submissionRef.current === null) submissionRef.current = createTaskCreationTracker();
  const submissions = submissionRef.current;

  useEffect(() => {
    let active = true;
    setLoading(true);
    bridge().listAgentTasks(workspaceId)
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
    bridge().getModelSettings()
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
  }, [bridge, laneId]);

  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  const models: ModelOptionDto[] = provider?.models ?? [];
  const blocker = creationBlocker(catalog, laneId, selection);

  const create = useCallback(async () => {
    const message = draft.trim();
    if (selection === null || blocker !== null || message === '') return;
    const request = submissions.requestFor(workspaceId, message, laneId, {
      model: selection.model,
      effort: selection.effort,
      permissionPolicy: 'AUTO',
    });
    const acceptCreatedTask = (task: AgentTaskDto) => {
      submissions.settled(request.taskId);
      setTasks((current) => [task, ...current.filter((existing) => existing.id !== task.id)]);
      setDraft('');
      setError(null);
      onOpenTask(task.id);
    };
    try {
      // Creation and its first turn are one replay-safe backend operation.
      acceptCreatedTask(await bridge().createAgentTask(request));
    }
    catch (failure) {
      // The retained request makes the same click safe when the backend answer was lost.
      setError(failure instanceof Error ? failure.message : 'Unable to create the agent task');
      try {
        // A failed IPC answer is ambiguous. Replaying the whole composite request is what finishes
        // a task that persisted just before a crash; task existence alone does not prove its first
        // turn was accepted.
        acceptCreatedTask(await bridge().createAgentTask(request));
      }
      catch {
        // Still unresolved: keep the exact request for a safe retry.
      }
    }
  }, [blocker, bridge, draft, laneId, onOpenTask, selection, submissions, workspaceId]);

  return { tasks, loading, error, models, selection, setSelection, draft, setDraft, blocker, create };
}

export default function AgentTasksView({
  workspaceId,
  branch,
  children,
  bridge: explicitBridge,
  onOpenTask,
  onOpenSettings,
  laneId = INTERACTIVE_LANE_ID,
}: AgentTasksViewProps): React.JSX.Element {
  // Resolved when used, not when rendered: a static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, laneId, bridge: live, onOpenTask });
  return (
    <AgentTaskList
      tasks={creation.tasks}
      loading={creation.loading}
      error={creation.error}
      laneId={laneId}
      models={creation.models}
      selection={creation.selection}
      draft={creation.draft}
      blocker={creation.blocker}
      branch={branch}
      onDraftChange={creation.setDraft}
      onSelectModel={(model, effort) => creation.setSelection({ model, effort })}
      onCreate={() => { void creation.create(); }}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
    >
      {children}
    </AgentTaskList>
  );
}

/** The card that creates a task: the first message above the branch, provider, permission and model facts. */
export function TaskComposer({
  draft, blocker, branch, laneId, models, selection, onDraftChange, onSelectModel, onCreate, onOpenSettings,
}: {
  draft: string;
  blocker: string | null;
  branch?: string;
  laneId: string;
  models: ModelOptionDto[];
  selection: { model: string; effort: string | null } | null;
  onDraftChange: (draft: string) => void;
  onSelectModel: (model: string, effort: string | null) => void;
  onCreate: () => void;
  onOpenSettings?: () => void;
}): React.JSX.Element {
  return (
    <div className="agt-compose-dock">
      {blocker !== null && (
        <div className="agt-compose-blocker" role="status">
          {blocker}
          {onOpenSettings !== undefined && (
            <>
              {' '}
              <button type="button" className="agt-action" onClick={onOpenSettings}>Open workspace settings</button>
            </>
          )}
        </div>
      )}
      <div className="agt-compose">
        {branch !== undefined && (
          <div className="agt-compose__bar">
            <span className="agt-compose__branch" title="Branch the task starts from">
              <span className="agt-compose__branch-mark" aria-hidden="true">⑂</span>{branch}
            </span>
          </div>
        )}
        <input
          className="agt-compose__input"
          type="text"
          aria-label="Describe a task"
          placeholder="Describe a task — sending the first message creates it…"
          value={draft}
          disabled={blocker !== null}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={(event) => { if (event.key === 'Enter') onCreate(); }}
        />
        <div className="agt-compose__row">
          <button type="button" className="agt-attach" aria-label="Attach an image" title="Create the task first, then attach images" disabled>+</button>
          {/* Codex is the only lane until Claude parity lands, so this cannot open a menu yet. */}
          <button
            type="button"
            className="agt-compose__chip agt-fake"
            data-fake="CP11 · provider choice"
            title={`${laneLabel(laneId)} is the only provider until Claude parity lands`}
            disabled
          >
            {laneLabel(laneId)} <Chevron open size={11} />
          </button>
          <button type="button" className="agt-compose__chip agt-fake" data-fake="CP11 · permission choice" title="Auto asks before anything risky" disabled>
            Auto <Chevron open size={11} />
          </button>
          <span className="agt-spacer" />
          <ModelPicker
            ariaLabel="Model and effort for the new task"
            disabled={models.length === 0}
            models={models}
            selectedModel={selection?.model ?? ''}
            selectedEffort={selection?.effort ?? undefined}
            onSelect={onSelectModel}
          />
          <button
            type="button"
            className="agt-send"
            aria-label="Send — creates the task"
            title="Sends the first message, which creates the task"
            onClick={onCreate}
            disabled={blocker !== null || draft.trim() === ''}
          >
            ↑
          </button>
        </div>
      </div>
    </div>
  );
}

export function AgentTaskList({
  tasks, loading, error, laneId, models, selection, draft, blocker, branch, children,
  onDraftChange, onSelectModel, onCreate, onOpenTask, onOpenSettings,
}: {
  tasks: AgentTaskDto[];
  loading: boolean;
  error: string | null;
  laneId: string;
  models: ModelOptionDto[];
  selection: { model: string; effort: string | null } | null;
  /** The first message; sending it creates the task and names it until the agent answers. */
  draft: string;
  /** Why creation is unavailable; null when the task can be created. */
  blocker: string | null;
  branch?: string;
  children?: React.ReactNode;
  onDraftChange: (draft: string) => void;
  onSelectModel: (model: string, effort: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
}): React.JSX.Element {
  const now = Date.now();
  return (
    <div className="agt-tasks-page">
      <div className="agt-tasks-scroll">
        <h1>Tasks</h1>
        {children}
        <section className="agt-section" aria-label="Agent tasks">
          <div className="agt-head">
            <span className="agt-section-label">Agent tasks</span>
          </div>
          {error !== null && <div className="cmt-error" role="alert">{error}</div>}
          <div className="agt-list">
            {loading && <div className="agt-loading">Loading agent tasks…</div>}
            {!loading && tasks.length === 0 && (
              <div className="agt-empty-hero">
                <div className="agt-empty-hero__mark" aria-hidden="true">❋</div>
                <div className="agt-empty-hero__title">No agent tasks yet</div>
                <div className="agt-muted">
                  Describe a task below — sending the first message creates it. Every command and file change stays visible.
                </div>
              </div>
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
                  <span className="agt-row__mark" aria-hidden="true">❋</span>
                  <span className="agt-row-title agt-ellipsis">{task.title}</span>
                  <span className="agt-mono agt-row-meta">
                    {displayModelName(models.find((model) => model.id === task.defaults.model)?.name ?? task.defaults.model ?? laneLabel(task.laneId))}
                    {' · '}
                    {relativeTime(task.updatedAt, { suffix: false, now })}
                  </span>
                  <span className={`agt-state ${pill.className}`}>
                    {pill.spinning && <span className="agt-spin" aria-hidden="true" />}
                    {pill.label}
                  </span>
                  <Chevron />
                </button>
              );
            })}
          </div>
        </section>
      </div>

      {/* Anchored under the list: sending this first message is what creates a task. */}
      <TaskComposer
        draft={draft}
        blocker={blocker}
        branch={branch}
        laneId={laneId}
        models={models}
        selection={selection}
        onDraftChange={onDraftChange}
        onSelectModel={onSelectModel}
        onCreate={onCreate}
        onOpenSettings={onOpenSettings}
      />
    </div>
  );
}
