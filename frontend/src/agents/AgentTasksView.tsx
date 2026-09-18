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
import { Archive, ArchiveRestore, Check, ChevronRight, Circle, FilePen, GitCommitHorizontal, LoaderCircle, SquareCheck, Trash2, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { relativeTime } from '../relativeTime';
import { INTERACTIVE_LANE_ID, displayModelName, laneLabel, providerAvailable, providerRemedy, remedyCommand } from '../settings/model';
import { getCached, setCached } from '../dataCache';
import Toast from '../Toast';
import { notifyIssueDevelopmentChanged, type DevelopmentIssue } from '../workspaces/issueDevelopment';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { IssueAttachment } from './IssueAttachment';
import { AutomationControl } from './AutomationControl';
import { automationForSubmission } from './developmentAutomation';
import { ModelPicker } from './ModelPicker';
import { ProviderPicker } from './ProviderPicker';
import '../css/agents.css';
import type { AgentTaskChangesDto, AgentTaskDto, AgentTaskPullRequestDto, AgentTaskStartupPhase, DevelopmentAutomationDto, ModelOptionDto, ModelProviderDto, ModelSettingsDto } from '../types';
import { acceptsImages, createTaskCreationTracker, type AgentBridgePort, type TaskCreationTracker } from './model';
import { agentProviderUnavailableFrom } from './contracts';
import { pasteImage } from './clipboard';
import { ImageAttachment } from './ImageAttachment';
import { ReviewTasksView } from './ReviewTasksView';
import { PullRequestStateIcon } from './TaskPullRequestPanel';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';

export type DraftImage = { id: string; name: string; url: string };
export type TaskModelSelection = { model: string; effort: string | null; serviceTier?: string | null };
type TaskTab = 'active' | 'archived';

/** What the backend's sweep does to a completed task nobody came back to; the hint names it. */
const AUTO_ARCHIVE_DAYS = 7;
/** How often the starting screen asks which creation step the in-flight request is in. */
const STARTUP_POLL_MS = 500;

interface AgentTasksViewProps {
  workspaceId: string;
  sourceIssueNumber?: number | null;
  onOpenCreatedTask?: (task: AgentTaskDto) => void;
  headerAction?: React.ReactNode;
  /** The rest of the Tasks page, drawn above the agent tasks under the one heading. */
  children?: React.ReactNode;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  /** The route owns which task is open, so opening one is a navigation. */
  onOpenTask: (taskId: string) => void;
  onOpenReview: (review: AgentReviewDto) => void;
  onOpenReviewWorkspace?: (review: AgentReviewDto) => void;
  /** Where a provider that is not ready gets fixed. */
  onOpenSettings?: () => void;
  /** Initial provider selection; the person may change it until the first message creates a task. */
  laneId?: string;
}

/** A task state reads as prose; the wire enum is never shown to the user. */
const STATE_LABELS: Record<string, string> = {
  OPEN: 'Idle',
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
  if (task.state === 'OPEN' && task.developmentContinuationUnfinished) {
    return { label: 'Automation running', className: 'is-running', spinning: true };
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

/** Archived is a shelf, not a state: the task keeps everything and simply leaves the active list. */
export function taskArchived(task: Pick<AgentTaskDto, 'archivedAt'>): boolean {
  return task.archivedAt !== null && task.archivedAt !== undefined;
}

/** A task something may still be writing to; deleting waits for it to stop. */
function taskBusy(task: AgentTaskDto): boolean {
  return task.state === 'OPEN' && task.mutationClaim !== null && task.mutationClaim !== undefined;
}

type TaskGroup = { key: string; label: string; date: string; tasks: AgentTaskDto[] };

function dayStart(at: Date): Date {
  return new Date(at.getFullYear(), at.getMonth(), at.getDate());
}

function dayKey(day: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}

/** Tasks by the local day they last moved, newest day first and newest task first within it. */
export function groupTasksByDay(tasks: AgentTaskDto[], now: number): TaskGroup[] {
  const today = dayStart(new Date(now)).getTime();
  const groups = new Map<string, TaskGroup>();
  const ordered = [...tasks].sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
  for (const task of ordered) {
    const day = dayStart(new Date(task.updatedAt));
    const key = dayKey(day);
    let group = groups.get(key);
    if (group === undefined) {
      const daysAgo = Math.round((today - day.getTime()) / 86_400_000);
      group = {
        key,
        label: daysAgo === 0 ? 'Today' : daysAgo === 1 ? 'Yesterday' : day.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' }),
        date: day.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
        tasks: [],
      };
      groups.set(key, group);
    }
    group.tasks.push(task);
  }
  return [...groups.values()];
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
  selection: TaskModelSelection | null,
): string | null {
  // No catalog yet means the settings fetch is still in flight, not that the provider is unchecked:
  // the backend lists every driver with a probed readiness, so there is no "unchecked" state.
  if (catalog === null) return null;
  const provider = catalog.providers.find((candidate) => candidate.id === laneId) ?? null;
  if (provider === null) {
    return `${laneLabel(laneId)} is not available in this build.`;
  }
  if (!providerAvailable(provider.readiness)) {
    const remedy = providerRemedy(provider.readiness, provider.id);
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
export function useAgentTaskCreation({ workspaceId, initialLaneId, bridge, onOpenTask, sourceIssueNumber = null, onOpenCreatedTask }: {
  workspaceId: string;
  initialLaneId: string;
  bridge: () => AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  sourceIssueNumber?: number | null;
  onOpenCreatedTask?: (task: AgentTaskDto) => void;
}) {
  // Home and the Tasks page remount on every tab switch; the last list paints the first frame, and
  // the mount still re-reads it.
  const seed = `agent-tasks:${workspaceId}`;
  const [tasks, setTasks] = useState<AgentTaskDto[]>(() => getCached<AgentTaskDto[]>(seed) ?? []);
  const [loading, setLoading] = useState(() => getCached(seed) === undefined);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ModelSettingsDto | null>(null);
  const [laneId, setLaneId] = useState(initialLaneId);
  const [selection, setSelection] = useState<TaskModelSelection | null>(null);
  const [draft, setDraft] = useState('');
  const [issue, setIssue] = useState<DevelopmentIssue | null>(null);
  const [sourceLoading, setSourceLoading] = useState(sourceIssueNumber !== null);
  const [sourceError, setSourceError] = useState<string | null>(null);
  const sourceRequest = useRef(0);
  const sourceTitle = useRef<string | null>(null);
  const [automation, setAutomation] = useState<DevelopmentAutomationDto | null>(null);
  const [localOnly, setLocalOnly] = useState(false);
  const [automationError, setAutomationError] = useState<string | null>(null);
  const [images, setImages] = useState<DraftImage[]>([]);
  const [operation, setOperation] = useState<'attachment' | 'sending' | null>(null);
  const [startup, setStartup] = useState<AgentTaskStartupPhase | null>(null);
  const busy = operation !== null;
  const busyRef = useRef(false);
  const imagesRef = useRef<DraftImage[]>([]);
  const mountedRef = useRef(true);
  const clearImages = useCallback(() => {
    const current = imagesRef.current;
    imagesRef.current = [];
    current.forEach((image) => URL.revokeObjectURL(image.url));
    if (current.length > 0) void bridge().discardAgentDraftAttachments(current.map((image) => image.id)).catch(() => {});
  }, [bridge]);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; clearImages(); };
  }, [clearImages]);

  const submissionRef = useRef<TaskCreationTracker | null>(null);
  if (submissionRef.current === null) submissionRef.current = createTaskCreationTracker();
  const submissions = submissionRef.current;

  /**
   * Seeds the composer from the Development setting — the model this workspace's
   * next task is meant to launch with — unless the caller names a lane, which
   * happens when a blocked start is retried on the lane it failed on.
   */
  const applyCatalog = useCallback((settings: ModelSettingsDto, preferredLaneId?: string) => {
    setCatalog(settings);
    if (preferredLaneId !== undefined) {
      const named = settings.providers.find((candidate) => candidate.id === preferredLaneId);
      if (named !== undefined && named.selectedModel !== '') {
        setLaneId(named.id);
        setSelection({ model: named.selectedModel, effort: named.selectedEffort === '' ? null : named.selectedEffort });
        return;
      }
    }
    const development = settings.agents.find((agent) => agent.type === 'DEVELOPMENT');
    if (development !== undefined && settings.providers.some((candidate) => candidate.id === development.configId)) {
      setLaneId(development.configId);
      setSelection({ model: development.model, effort: development.effort ?? null });
      return;
    }
    const provider = settings.providers.find((candidate) => candidate.id === initialLaneId) ?? settings.providers[0];
    if (provider !== undefined && provider.selectedModel !== '') {
      setLaneId(provider.id);
      setSelection({
        model: provider.selectedModel,
        effort: provider.selectedEffort === '' ? null : provider.selectedEffort,
      });
    }
  }, [initialLaneId]);

  useEffect(() => {
    let active = true;
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
    // A failed read leaves nothing worth painting on the next visit.
    if (!loading && error === null) setCached(seed, tasks);
  }, [error, loading, seed, tasks]);

  useEffect(() => {
    let active = true;
    bridge().getModelSettings(workspaceId)
      .then((settings) => {
        if (!active) return;
        // The user's own default selection, not a guess this screen invents.
        applyCatalog(settings);
      })
      .catch(() => { /* the blocker below reports an unusable catalog on its own */ });
    return () => { active = false; };
  }, [applyCatalog, bridge, workspaceId]);

  useEffect(() => {
    let active = true;
    void bridge().getDevelopmentAutomation().then(value => { if (active) setAutomation(value); })
      .catch(() => { if (active) setAutomationError('Unable to load automation settings. Reopen this page to try again.'); });
    return () => { active = false; };
  }, [bridge, workspaceId]);

  const refreshIssue = useCallback(async () => {
    if (sourceIssueNumber === null) return;
    const request = ++sourceRequest.current;
    setSourceLoading(true);
    setSourceError(null);
    try {
      const updated = await bridge().fetchWorkspaceIssue(workspaceId, sourceIssueNumber);
      if (!mountedRef.current || request !== sourceRequest.current) return;
      if (updated.developmentTask != null) {
        notifyIssueDevelopmentChanged(updated.id);
        window.location.hash = workspaceAgentTaskRoute(updated.developmentTask.workspaceId, updated.developmentTask.taskId);
        return;
      }
      const previousTitle = sourceTitle.current;
      setDraft(current => current === '' || current === `Implement ${previousTitle}` ? `Implement ${updated.title}` : current);
      sourceTitle.current = updated.title;
      setIssue(updated);
    }
    catch (failure) {
      if (mountedRef.current && request === sourceRequest.current) {
        setSourceError(failureMessage(failure));
      }
    }
    finally { if (mountedRef.current && request === sourceRequest.current) setSourceLoading(false); }
  }, [bridge, sourceIssueNumber, workspaceId]);

  useEffect(() => {
    void refreshIssue();
    return () => { sourceRequest.current += 1; };
  }, [refreshIssue]);

  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  const models: ModelOptionDto[] = provider?.models ?? [];
  const imagesAccepted = acceptsImages(models, selection?.model, provider?.capabilities ?? null);
  const blocker = (sourceLoading ? 'Loading issue…' : sourceError)
    ?? (issue?.isGoal ? 'Goals organize work and cannot start development. Unmark this issue as a Goal to run it.' : null)
    ?? creationBlocker(catalog, laneId, selection)
    ?? automationError
    ?? (automation === null ? 'Loading automation settings…' : null)
    ?? (images.length > 0 && !imagesAccepted ? 'Choose a model that accepts images or remove the images.' : null);

  const selectProvider = useCallback((nextLaneId: string) => {
    const next = catalog?.providers.find((candidate) => candidate.id === nextLaneId);
    if (next === undefined || !providerAvailable(next.readiness)) return;
    setLaneId(next.id);
    setSelection(next.selectedModel === ''
      ? null
      : { model: next.selectedModel, effort: next.selectedEffort === '' ? null : next.selectedEffort });
  }, [catalog]);

  const attach = useCallback(async (source: 'picker' | 'clipboard' = 'picker') => {
    if (busyRef.current || !imagesAccepted) return;
    busyRef.current = true;
    setOperation('attachment');
    try {
      const selected = await (source === 'clipboard' ? bridge().pasteAgentDraftAttachments() : bridge().pickAgentDraftAttachments());
      if (!mountedRef.current) {
        void bridge().discardAgentDraftAttachments(selected.map((image) => image.id)).catch(() => {});
        return;
      }
      const added = selected.map((image) => ({
        id: image.id, name: image.name,
        url: URL.createObjectURL(new Blob([new Uint8Array(image.bytes)], { type: image.mediaType })),
      }));
      imagesRef.current = [...imagesRef.current, ...added];
      setImages(imagesRef.current);
      setError(null);
    }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to attach images');
    }
    finally {
      busyRef.current = false;
      setOperation(null);
    }
  }, [bridge, imagesAccepted]);

  const removeImage = useCallback((id: string) => {
    if (busyRef.current) return;
    const removed = imagesRef.current.find((image) => image.id === id);
    if (removed === undefined) return;
    URL.revokeObjectURL(removed.url);
    imagesRef.current = imagesRef.current.filter((image) => image.id !== id);
    setImages(imagesRef.current);
    void bridge().discardAgentDraftAttachments([id]).catch(() => {});
  }, [bridge]);

  const create = useCallback(async () => {
    const message = draft.trim();
    if (busyRef.current || selection === null || automation === null || blocker !== null || message === '') return;
    busyRef.current = true;
    setOperation('sending');
    setError(null);
    const request = submissions.requestFor(workspaceId, message, laneId, {
      model: selection.model,
      effort: selection.effort,
      permissionPolicy: 'AUTO',
      serviceTier: selection.serviceTier ?? null,
      developmentAutomation: automation,
    }, images.map((image) => image.id), automationForSubmission(automation, localOnly), issue === null ? undefined : {
      issueId: issue.id, number: issue.number, updatedAt: issue.updatedAt,
    });
    // The create call blocks until the first turn is running; the backend names the step it is in meanwhile.
    let watching = true;
    const watch = setInterval(() => {
      bridge().getAgentTaskStartup(request.taskId)
        .then((progress) => { if (watching && mountedRef.current) setStartup(progress.phase); })
        .catch(() => {});
    }, STARTUP_POLL_MS);
    const acceptCreatedTask = (task: AgentTaskDto) => {
      submissions.settled(request.taskId);
      if (task.workspaceId === workspaceId) setTasks((current) => [task, ...current.filter((existing) => existing.id !== task.id)]);
      if (issue !== null) notifyIssueDevelopmentChanged(issue.id);
      setDraft('');
      setLocalOnly(false);
      clearImages();
      setImages([]);
      setError(null);
      if (onOpenCreatedTask !== undefined) onOpenCreatedTask(task);
      else onOpenTask(task.id);
    };
    try {
      // Creation and its first turn are one replay-safe backend operation.
      acceptCreatedTask(await bridge().createAgentTask(request));
    }
    catch (failure) {
      const unavailable = agentProviderUnavailableFrom(failure);
      if (unavailable !== null) {
        const remedy = providerRemedy(unavailable.readiness, unavailable.laneId);
        const command = remedyCommand(unavailable, remedy);
        setError(`${laneLabel(unavailable.laneId)} is not ready — ${remedy.title}. ${unavailable.diagnostic}`
          + (command === null ? '' : ` Run: ${command}`));
        // The preflight contract says no mutation happened, so do not replay it as an ambiguous
        // delivery. Refresh the typed catalog and wait for another deliberate click.
        void bridge().getModelSettings(workspaceId)
          .then((settings) => applyCatalog(settings, unavailable.laneId))
          .catch(() => {});
        return;
      }
      // The retained request makes the same click safe when the backend answer was lost.
      try {
        // A failed IPC answer is ambiguous. Replaying the whole composite request is what finishes
        // a task that persisted just before a crash; task existence alone does not prove its first
        // turn was accepted.
        acceptCreatedTask(await bridge().createAgentTask(request));
      }
      catch {
        // Still unresolved: keep the exact request for a safe retry.
        setError(failure instanceof Error ? failure.message : 'Unable to create the agent task');
      }
    }
    finally {
      watching = false;
      clearInterval(watch);
      setStartup(null);
      busyRef.current = false;
      setOperation(null);
    }
  }, [applyCatalog, automation, blocker, bridge, clearImages, draft, images, issue, laneId, localOnly, onOpenCreatedTask, onOpenTask, selection, submissions, workspaceId]);

  return {
    /** Everything TaskComposer needs, so a screen spreads it instead of re-listing eighteen props. */
    composer: {
      laneId,
      providers: catalog?.providers ?? [],
      models,
      selection,
      draft,
      blocker,
      busy,
      attaching: operation === 'attachment',
      images,
      automation: automation === null ? null : <AutomationControl value={automation} providers={catalog?.providers ?? []} disabled={busy}
        localOnly={localOnly} onChange={setAutomation} onLocalOnlyChange={setLocalOnly} />,
      onAttach: () => { void attach(); },
      onPasteImages: () => { void attach('clipboard'); },
      onRemoveImage: removeImage,
      onDraftChange: setDraft,
      onSelectProvider: selectProvider,
      onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => setSelection({ model, effort, serviceTier }),
      onCreate: () => { void create(); },
    },
    issueAttachment: issue === null ? null : <IssueAttachment source={{
      number: issue.number, title: issue.title, body: issue.body, url: issue.htmlUrl,
      repo: new URL(issue.htmlUrl).pathname.split('/').slice(1, 3).join('/'),
    }} onRefresh={() => { void refreshIssue(); }} refreshing={busy || sourceLoading} />,
    tasks,
    setTasks,
    loading,
    error,
    catalog,
    laneId,
    selectProvider,
    models,
    selection,
    setSelection,
    draft,
    setDraft,
    blocker,
    busy,
    startup,
    attaching: operation === 'attachment',
    images,
    attach,
    removeImage,
    create,
  };
}

/** The creation steps in backend order; the image step exists only when images were attached. */
const STARTUP_STEPS: { phase: AgentTaskStartupPhase; withImages?: true; label: (provider: string) => string }[] = [
  { phase: 'PROVIDER', label: (provider) => `Checking that ${provider} is installed and signed in` },
  { phase: 'REPOSITORY', label: () => 'Fetching the latest commits from GitHub' },
  { phase: 'NAMING', label: () => 'Naming task and branch' },
  { phase: 'WORKTREE', label: () => 'Creating the task branch and worktree' },
  { phase: 'ATTACHMENTS', withImages: true, label: () => 'Storing the attached images' },
  { phase: 'SESSION', label: (provider) => `Starting ${provider} and sending your message` },
];

/** One reported phase places every step: earlier ones are done, later ones pending, none current before the first report. */
export function startupSteps(
  phase: AgentTaskStartupPhase | null,
  provider: string,
  withImages: boolean,
): { label: string; state: 'done' | 'current' | 'pending' }[] {
  const steps = STARTUP_STEPS.filter((step) => withImages || step.withImages === undefined);
  const at = steps.findIndex((step) => step.phase === phase);
  return steps.map((step, index) => ({
    label: step.label(provider),
    state: at !== -1 && index < at ? 'done' : index === at ? 'current' : 'pending',
  }));
}

/** The first message is visible while the existing create-and-start request is in flight. */
export function StartingAgentTask({ draft, images, laneId, providers, models, selection, startup = null, headerAction }: {
  draft: string;
  images: DraftImage[];
  laneId: string;
  providers: ModelProviderDto[];
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  startup?: AgentTaskStartupPhase | null;
  headerAction?: React.ReactNode;
}): React.JSX.Element {
  const provider = providers.find((candidate) => candidate.id === laneId)?.label ?? laneLabel(laneId);
  const model = models.find((candidate) => candidate.id === selection?.model);
  return (
    <div className="agt-screen">
      <div className="agt-screen-head">
        <span className="agt-title">New task</span>
        <span className="agt-state is-running">Starting</span>
        <span className="agt-mono agt-meta">
          {provider} · {displayModelName(model?.name ?? selection?.model ?? '')}{selection?.effort ? ` · ${selection.effort}` : ''}
        </span>
        <span className="agt-spacer" />
        {headerAction}
      </div>
      <div className="agt-transcript" aria-label="Starting task conversation">
        <div>
          <div className="agt-user">
            <div className="agt-user__bubble">
              {images.length > 0 && (
                <div className="agt-user__files">
                  {images.map((image) => <ImageAttachment key={image.id} url={image.url} name={image.name} />)}
                </div>
              )}
              <div>{draft.trim()}</div>
            </div>
          </div>
          <div className="agt-starting-status" role="status">
            <LoaderCircle className="agt-spin" aria-hidden="true" />
            <span>Starting task…</span>
          </div>
          <ol className="agt-starting-steps" aria-label="Task startup steps">
            {startupSteps(startup, provider, images.length > 0).map((step) => (
              <li key={step.label} className={`agt-starting-step is-${step.state}`}>
                <span className={`agt-icon${step.state === 'current' ? ' is-running' : step.state === 'done' ? ' is-ok' : ''}`} aria-hidden="true">
                  {step.state === 'current' ? <LoaderCircle className="agt-spin" /> : step.state === 'done' ? <Check /> : <Circle />}
                </span>
                <span>{step.label}</span>
              </li>
            ))}
          </ol>
        </div>
      </div>
      <div className="agt-bottom">
        <div className="agt-compose-dock is-task">
          <div className="agt-compose" aria-busy="true">
            <textarea className="agt-compose__input" aria-label="Message agent" placeholder="Waiting for task to start…" rows={2} disabled />
          </div>
        </div>
      </div>
    </div>
  );
}

function failureMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure);
}

export default function AgentTasksView({
  workspaceId,
  sourceIssueNumber = null,
  onOpenCreatedTask,
  headerAction,
  children,
  bridge: explicitBridge,
  onOpenTask,
  onOpenReview,
  onOpenReviewWorkspace,
  onOpenSettings,
  laneId = INTERACTIVE_LANE_ID,
}: AgentTasksViewProps): React.JSX.Element {
  // Resolved when used, not when rendered: a static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, initialLaneId: laneId, bridge: live, onOpenTask, sourceIssueNumber, onOpenCreatedTask });
  const { setTasks } = creation;
  const tasksRef = useRef(creation.tasks);
  tasksRef.current = creation.tasks;
  // One task at a time: each answer carries the version the next request must echo, and the list
  // reflects what actually happened when one of a batch fails.
  const archive = useCallback(async (ids: string[], archived: boolean) => {
    for (const id of ids) {
      const current = tasksRef.current.find((task) => task.id === id);
      if (current === undefined) continue;
      const updated = await live().archiveAgentTask(id, workspaceId, current.version, archived);
      setTasks((list) => list.map((task) => (task.id === id ? updated : task)));
    }
  }, [live, setTasks, workspaceId]);
  const remove = useCallback(async (ids: string[]) => {
    for (const id of ids) {
      await live().deleteAgentTask(id, workspaceId);
      setTasks((list) => list.filter((task) => task.id !== id));
    }
  }, [live, setTasks, workspaceId]);
  // Reading checkouts takes Git a moment each, so rows paint with the last answer and take the new one when it lands.
  const changesSeed = `agent-task-changes:${workspaceId}`;
  const [changes, setChanges] = useState<Record<string, AgentTaskChangesDto>>(() => getCached(changesSeed) ?? {});
  // Keyed by the ids alone, so archiving a row does not read every checkout again.
  const taskIds = creation.tasks.map((task) => task.id).join(' ');
  useEffect(() => {
    if (taskIds === '') return;
    let active = true;
    // A failed read keeps the last answer; a row without one simply names nothing.
    void live().listAgentTaskChanges(workspaceId).then((found) => {
      setCached(changesSeed, found);
      if (active) setChanges(found);
    }, () => {});
    return () => { active = false; };
  }, [live, changesSeed, taskIds, workspaceId]);
  return (
    <AgentTaskList
      headerAction={headerAction}
      {...creation.composer}
      issueAttachment={creation.issueAttachment}
      tasks={creation.tasks}
      changes={changes}
      loading={creation.loading}
      error={creation.error}
      startup={creation.startup}
      onOpenTask={onOpenTask}
      onOpenSettings={onOpenSettings}
      onArchive={archive}
      onDelete={remove}
      reviewTasks={<ReviewTasksView workspaceId={workspaceId} onOpenReview={onOpenReview} onOpenWorkspace={onOpenReviewWorkspace} />}
    >
      {children}
    </AgentTaskList>
  );
}

/** The card that creates a task: the first message above its provider, automation and model controls. */
export function TaskComposer({
  draft, blocker, laneId, providers, models, selection, onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenSettings,
  images = [], busy = false, attaching = false, onAttach, onPasteImages, onRemoveImage, automation, issueAttachment,
}: {
  draft: string;
  blocker: string | null;
  laneId: string;
  providers: ModelProviderDto[];
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onCreate: () => void;
  onOpenSettings?: () => void;
  automation?: React.ReactNode;
  issueAttachment?: React.ReactNode;
  images?: DraftImage[];
  busy?: boolean;
  attaching?: boolean;
  onAttach?: () => void;
  onPasteImages?: () => void;
  onRemoveImage?: (id: string) => void;
}): React.JSX.Element {
  const imagesAccepted = acceptsImages(models, selection?.model, providers.find((provider) => provider.id === laneId)?.capabilities ?? null);
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
      {issueAttachment}
      <div className="agt-compose is-new-task">
        <span className="agt-compose__streamer" aria-hidden="true" />
        {images.length > 0 && (
          <div className="agt-drafts" aria-label="Images to send">
            {images.map((image) => (
              <span className="agt-draft" key={image.id}>
                <ImageAttachment url={image.url} name={image.name} compact />
                <button type="button" className="agt-draft__remove" aria-label={`Remove image ${image.name}`} disabled={busy} onClick={() => onRemoveImage?.(image.id)}><X aria-hidden="true" /></button>
              </span>
            ))}
          </div>
        )}
        <input
          className="agt-compose__input"
          type="text"
          aria-label="Describe a task"
          placeholder="Let's ship..."
          value={draft}
          disabled={blocker !== null || (busy && !attaching)}
          onChange={(event) => onDraftChange(event.target.value)}
          onPaste={(event) => pasteImage(event, imagesAccepted && blocker === null && !busy, onPasteImages)}
          onKeyDown={(event) => { if (event.key === 'Enter') onCreate(); }}
        />
        <div className="agt-compose__row">
          <button
            type="button"
            className="agt-attach"
            aria-label="Attach an image"
            title={imagesAccepted
              ? 'Attach images or paste with ⌘V' : 'This model does not accept images'}
            disabled={busy || onAttach === undefined || !imagesAccepted}
            onClick={onAttach}
          >+</button>
          <ProviderPicker
            providers={providers}
            selectedId={laneId}
            onSelect={onSelectProvider}
            onOpenSettings={onOpenSettings}
          />
          {automation}
          <span className="agt-spacer" />
          <ModelPicker
            ariaLabel="Model and effort for the new task"
            disabled={busy || models.length === 0}
            models={models}
            selectedModel={selection?.model ?? ''}
            selectedEffort={selection?.effort ?? undefined}
            selectedServiceTier={selection?.serviceTier ?? null}
            onSelect={onSelectModel}
          />
          <button
            type="button"
            className="agt-send"
            aria-label="Send — creates the task"
            title="Sends the first message, which creates the task"
            onClick={onCreate}
            disabled={busy || blocker !== null || selection === null || draft.trim() === ''}
          >
            ↑
          </button>
        </div>
      </div>
    </div>
  );
}

/** One task as a row of a day group: the selection slot, the name, its facts, and what can be done to it. */
/** Commits first, since they are the result a pull request would carry; uncommitted files are work still in the checkout. */
function changesLabel({ uncommitted, commits }: AgentTaskChangesDto): string {
  const committed = commits === 0 ? null : `${commits} ${commits === 1 ? 'commit' : 'commits'}`;
  if (committed === null) return uncommitted ? 'Uncommitted changes' : 'No changes';
  return uncommitted ? `${committed} · uncommitted changes` : committed;
}

function TaskRow({ task, pullRequest, changes, providers, now, checked, selecting, onOpen, onToggle, onArchive, onDelete }: {
  task: AgentTaskDto;
  pullRequest: AgentTaskPullRequestDto | null;
  /** What the checkout holds, named only while the task has no pull request to name instead. */
  changes: AgentTaskChangesDto | null;
  providers: ModelProviderDto[];
  now: number;
  checked: boolean;
  /** While anything is selected, a click on a row selects it rather than opening it. */
  selecting: boolean;
  onOpen: () => void;
  onToggle: () => void;
  onArchive: (archived: boolean) => void;
  onDelete: () => void;
}): React.JSX.Element {
  const pill = taskStatePill(task);
  const provider = providers.find((candidate) => candidate.id === task.laneId);
  const model = displayModelName(provider?.models.find((option) => option.id === task.defaults.model)?.name ?? task.defaults.model ?? 'No model');
  const archived = taskArchived(task);
  const running = taskBusy(task);
  const activate = (): void => { if (selecting) onToggle(); else onOpen(); };
  return (
    <div
      className={`agt-trow${checked ? ' is-selected' : ''}`}
      role="button"
      tabIndex={0}
      onClick={activate}
      onKeyDown={(event) => {
        if (event.target !== event.currentTarget) return;
        if (event.key !== 'Enter' && event.key !== ' ') return;
        event.preventDefault();
        activate();
      }}
    >
      <button
        type="button"
        className="agt-trow__slot"
        aria-label={`${checked ? 'Deselect' : 'Select'} ${task.title}`}
        aria-pressed={checked}
        onClick={(event) => { event.stopPropagation(); onToggle(); }}
      >
        <span className="agt-trow__check" aria-hidden="true"><Check size={10} strokeWidth={3.2} /></span>
        <SquareCheck className="agt-trow__glyph" size={14} strokeWidth={1.8} aria-hidden="true" />
      </button>
      <span className="agt-trow__name">
        <span className={`agt-trow__title${archived ? ' is-archived' : ''}`}>{task.title}</span>
        {pullRequest !== null ? (
          <span className="agt-trow__pr" title={`#${pullRequest.number} ${pullRequest.title}`}>
            <PullRequestStateIcon pullRequest={pullRequest} size={13} />
            {`#${pullRequest.number} ${pullRequest.title}`}
          </span>
        ) : changes !== null && (
          <span className="agt-trow__changes" title="Commits since the task started, and files its checkout has not committed">
            {changes.uncommitted ? <FilePen size={13} aria-hidden="true" /> : changes.commits > 0 && <GitCommitHorizontal size={13} aria-hidden="true" />}
            {changesLabel(changes)}
          </span>
        )}
      </span>
      <span className="agt-mono agt-trow__meta">
        {provider?.label ?? laneLabel(task.laneId)} · {model} · {relativeTime(task.updatedAt, { suffix: false, now })}
      </span>
      <span className={`agt-state ${pill.className}`}>
        {pill.spinning && <LoaderCircle className="agt-spin" aria-hidden="true" />}
        {pill.label}
      </span>
      <span className="agt-trow__actions">
        {!selecting && (
          <>
            <button
              type="button"
              className="agt-trow__btn"
              title={archived ? 'Restore to Active' : 'Archive'}
              aria-label={`${archived ? 'Restore' : 'Archive'} task: ${task.title}`}
              onClick={(event) => { event.stopPropagation(); onArchive(!archived); }}
            >
              {archived ? <ArchiveRestore size={15} aria-hidden="true" /> : <Archive size={15} aria-hidden="true" />}
            </button>
            <button
              type="button"
              className="agt-trow__btn is-danger"
              title={running ? 'Stop the task before deleting' : 'Delete permanently'}
              aria-label={`Delete task: ${task.title}`}
              disabled={running}
              onClick={(event) => { event.stopPropagation(); onDelete(); }}
            >
              <Trash2 size={15} aria-hidden="true" />
            </button>
          </>
        )}
        <ChevronRight className="agt-trow__chev" size={14} aria-hidden="true" />
      </span>
    </div>
  );
}

type ToastState = { id: number; text: string; undo?: () => void };

export function AgentTaskList({
  tasks, loading, error, laneId, providers, models, selection, draft, blocker, children, reviewTasks, headerAction,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenSettings, onArchive, onDelete,
  images = [], busy = false, startup = null, attaching = false, onAttach, onPasteImages, onRemoveImage, automation, issueAttachment,
  now = Date.now(), initialTab = 'active', changes = {},
}: {
  tasks: AgentTaskDto[];
  loading: boolean;
  error: string | null;
  laneId: string;
  providers: ModelProviderDto[];
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  /** The first message; sending it creates the task and names it until the agent answers. */
  draft: string;
  /** Why creation is unavailable; null when the task can be created. */
  blocker: string | null;
  children?: React.ReactNode;
  reviewTasks?: React.ReactNode;
  headerAction?: React.ReactNode;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  onOpenSettings?: () => void;
  /** Shelves or restores the tasks; the caller updates its list as each answer arrives. */
  onArchive?: (ids: string[], archived: boolean) => Promise<void>;
  /** Removes the tasks for good; the caller drops each from its list as it goes. */
  onDelete?: (ids: string[]) => Promise<void>;
  automation?: React.ReactNode;
  issueAttachment?: React.ReactNode;
  images?: DraftImage[];
  busy?: boolean;
  /** Which creation step the in-flight create request is in, for the starting screen. */
  startup?: AgentTaskStartupPhase | null;
  attaching?: boolean;
  onAttach?: () => void;
  onPasteImages?: () => void;
  onRemoveImage?: (id: string) => void;
  /** The clock the day groups are drawn against. */
  now?: number;
  initialTab?: TaskTab;
  /** What each task's checkout holds, by task id, for rows without a pull request to name. */
  changes?: Record<string, AgentTaskChangesDto>;
}): React.JSX.Element {
  const [tab, setTab] = useState<TaskTab>(initialTab);
  const [selected, setSelected] = useState<string[]>([]);
  const [confirm, setConfirm] = useState<AgentTaskDto[] | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [toast, setToast] = useState<ToastState | null>(null);
  const toastId = useRef(0);
  const selecting = selected.length > 0;

  useEffect(() => {
    if (!selecting) return;
    const onKey = (event: KeyboardEvent): void => { if (event.key === 'Escape') setSelected([]); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [selecting]);

  if (busy && !attaching) return <StartingAgentTask {...{ draft, images, laneId, providers, models, selection, startup, headerAction }} />;

  const active = tab === 'active';
  const visible = tasks.filter((task) => taskArchived(task) === !active);
  const activeCount = tasks.filter((task) => !taskArchived(task)).length;
  const groups = groupTasksByDay(visible, now);
  const selectedTasks = selected.map((id) => visible.find((task) => task.id === id)).filter((task): task is AgentTaskDto => task !== undefined);
  const selectionRunning = selectedTasks.some(taskBusy);

  const showToast = (text: string, undo?: () => void): void => {
    toastId.current += 1;
    setToast({ id: toastId.current, text, undo });
  };
  const switchTab = (next: TaskTab): void => {
    setTab(next);
    setSelected([]);
  };
  const toggle = (id: string): void => {
    setSelected((current) => (current.includes(id) ? current.filter((other) => other !== id) : [...current, id]));
  };
  const describe = (ids: string[]): string => (
    ids.length === 1 ? `“${tasks.find((task) => task.id === ids[0])?.title ?? ''}”` : `${ids.length} tasks`
  );
  const archive = (ids: string[], archived: boolean, undoable = true): void => {
    if (onArchive === undefined || ids.length === 0) return;
    const what = describe(ids);
    setSelected([]);
    onArchive(ids, archived)
      .then(() => showToast(`${archived ? 'Archived' : 'Restored'} ${what}`, undoable ? () => archive(ids, !archived, false) : undefined))
      .catch((failure: unknown) => showToast(`Could not ${archived ? 'archive' : 'restore'} ${what}: ${failureMessage(failure)}`));
  };
  const requestDelete = (ids: string[]): void => {
    const rows = ids
      .map((id) => tasks.find((task) => task.id === id))
      .filter((task): task is AgentTaskDto => task !== undefined && !taskBusy(task));
    if (rows.length > 0) setConfirm(rows);
  };
  const confirmDelete = (): void => {
    if (confirm === null || onDelete === undefined || deleting) return;
    const ids = confirm.map((task) => task.id);
    const what = describe(ids);
    setDeleting(true);
    onDelete(ids)
      .then(() => showToast(`Deleted ${what}`))
      .catch((failure: unknown) => showToast(`Could not delete ${what}: ${failureMessage(failure)}`))
      .finally(() => {
        setDeleting(false);
        setConfirm(null);
        setSelected([]);
      });
  };

  return (
    <div className="agt-tasks-page">
      {headerAction}
      <div className="agt-tasks-scroll">
        <div className="agt-tasks-head">
          <h1>Tasks</h1>
          <span className="agt-spacer" />
          <div className="agt-tabs" role="tablist" aria-label="Task lists">
            {([['active', 'Active', activeCount], ['archived', 'Archived', tasks.length - activeCount]] as const).map(([id, label, count]) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={tab === id}
                className={`agt-tab${tab === id ? ' is-active' : ''}`}
                onClick={() => switchTab(id)}
              >
                {label}<span className="agt-tab__count">{count}</span>
              </button>
            ))}
          </div>
        </div>
        {active && children}
        <section className="agt-section" aria-label={active ? 'Agent tasks' : 'Archived tasks'}>
          <div className="agt-head">
            {selecting ? (
              <div className="agt-selbar">
                <span className="agt-selbar__count">{selected.length} selected</span>
                <button type="button" className="agt-selbar__link" onClick={() => setSelected(visible.map((task) => task.id))}>Select all</button>
                <span className="agt-spacer" />
                <button type="button" className="agt-selbar__btn" onClick={() => archive(selected, active)}>
                  {active ? <Archive size={14} aria-hidden="true" /> : <ArchiveRestore size={14} aria-hidden="true" />}
                  {active ? 'Archive' : 'Restore'}
                </button>
                <button
                  type="button"
                  className="agt-selbar__btn is-danger"
                  disabled={selectionRunning}
                  title={selectionRunning ? 'Selection includes a running task' : 'Delete permanently'}
                  onClick={() => requestDelete(selected)}
                >
                  <Trash2 size={14} aria-hidden="true" />
                  Delete
                </button>
                <button type="button" className="agt-selbar__link is-muted" onClick={() => setSelected([])}>Cancel</button>
              </div>
            ) : (
              <>
                <span className="agt-section-label">{active ? 'Agent tasks' : 'Archived tasks'}</span>
                <span className="agt-spacer" />
                <span className="agt-section-hint">
                  {active
                    ? `Completed tasks archive automatically after ${AUTO_ARCHIVE_DAYS} days`
                    : 'Archived tasks keep their transcript and branch until deleted'}
                </span>
              </>
            )}
          </div>
          {error !== null && <div className="cmt-error" role="alert">{error}</div>}
          {loading && <div className="agt-loading">Loading agent tasks…</div>}
          {!loading && visible.length === 0 && (
            <div className="agt-empty-hero">
              <div className="agt-empty-hero__title">{active ? 'No agent tasks yet' : 'Nothing archived'}</div>
              <div className="agt-muted">
                {active
                  ? 'Send a message below to start one.'
                  : `Archive a task from the Active tab, or wait — completed tasks move here after ${AUTO_ARCHIVE_DAYS} days.`}
              </div>
            </div>
          )}
          <div className={`agt-groups${selecting ? ' has-selection' : ''}`}>
            {groups.map((group) => (
              <div className="agt-group" key={group.key}>
                <div className="agt-group__head">
                  <span className="agt-group__label">{group.label}</span>
                  <span className="agt-mono agt-group__date">{group.date}</span>
                  <span className="agt-spacer" />
                  <span className="agt-group__count">{group.tasks.length === 1 ? '1 task' : `${group.tasks.length} tasks`}</span>
                </div>
                {group.tasks.map((task) => (
                  <TaskRow
                    key={task.id}
                    task={task}
                    pullRequest={task.pullRequest ?? null}
                    changes={changes[task.id] ?? null}
                    providers={providers}
                    now={now}
                    checked={selected.includes(task.id)}
                    selecting={selecting}
                    onOpen={() => onOpenTask(task.id)}
                    onToggle={() => toggle(task.id)}
                    onArchive={(archived) => archive([task.id], archived)}
                    onDelete={() => requestDelete([task.id])}
                  />
                ))}
              </div>
            ))}
          </div>
        </section>
        {active && reviewTasks}
      </div>

      {/* Anchored under the list: sending this first message is what creates a task. */}
      {active && (
        <TaskComposer
          automation={automation}
          issueAttachment={issueAttachment}
          draft={draft}
          blocker={blocker}
          busy={busy}
          attaching={attaching}
          images={images}
          onAttach={onAttach}
          onPasteImages={onPasteImages}
          onRemoveImage={onRemoveImage}
          laneId={laneId}
          providers={providers}
          models={models}
          selection={selection}
          onDraftChange={onDraftChange}
          onSelectProvider={onSelectProvider}
          onSelectModel={onSelectModel}
          onCreate={onCreate}
          onOpenSettings={onOpenSettings}
        />
      )}
      {toast !== null && (
        <Toast
          key={toast.id}
          className={active ? 'is-raised' : ''}
          durationMs={5000}
          text={toast.text}
          onUndo={toast.undo}
          onDismiss={() => setToast(null)}
        />
      )}
      {confirm !== null && (
        <ConfirmDialog
          title={confirm.length === 1 ? 'Delete this task?' : `Delete ${confirm.length} tasks?`}
          confirmLabel={deleting ? 'Deleting…' : confirm.length === 1 ? 'Delete task' : `Delete ${confirm.length} tasks`}
          busy={deleting}
          destructive
          onConfirm={confirmDelete}
          onCancel={() => setConfirm(null)}
        >
          <p>
            This permanently removes everything ByteQuay stored for {confirm.length === 1 ? `“${confirm[0].title}”` : 'these tasks'}.
            Nothing on GitHub is touched.
          </p>
          <ul className="agt-del-list">
            <li>Transcript, events, approvals and queued inputs</li>
            <li>Image attachments and runtime logs</li>
            {confirm.map(task => task.generatedBranch === null ? null : (
              <li key={task.id}>Generated branch <code>{task.generatedBranch}</code> and its worktree</li>
            ))}
          </ul>
          {confirm.some(task => task.developmentCi != null) && <>
            <p>Linked CI tasks and their local worktrees are also removed after their agents stop. These remote results remain on GitHub:</p>
            <ul className="agt-del-list">
              {confirm.map(task => task.developmentCi == null ? null : <li key={task.id}>
                <a href={task.developmentCi.pullRequestUrl}>PR #{task.developmentCi.pullRequestNumber}</a>{' · '}
                branch <code>{task.developmentCi.headRef}</code>
              </li>)}
            </ul>
          </>}
        </ConfirmDialog>
      )}
    </div>
  );
}
