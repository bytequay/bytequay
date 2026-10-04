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

import { notifyJiraChanged, type JiraIssue } from '../jira/contracts';
import { JiraSourceAttachment } from '../jira/JiraSourceAttachment';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Archive, ArchiveRestore, Check, Circle, Link2, LoaderCircle, Trash2, X } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import RepoAvatar from '../RepoAvatar';
import { INTERACTIVE_LANE_ID, displayModelName, isDeepSeekKeyLane, laneLabel, providerAvailable, providerRemedy, remedyCommand } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import { getCached, setCached } from '../dataCache';
import Toast from '../Toast';
import { recordAttentionAction } from '../home/useAttentionVisit';
import { notifyIssueDevelopmentChanged, type DevelopmentIssue } from '../workspaces/issueDevelopment';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { LinkedWorkList, useLinkedWorkspaceWork } from './LinkedWorkspaceWork';
import { BackportChainList, useBackportChains } from './BackportChains';
import { IssueAttachment } from './IssueAttachment';
import { AutomationControl } from './AutomationControl';
import { automationForSubmission } from './developmentAutomation';
import { ModelPicker } from './ModelPicker';
import { ProviderPicker } from './ProviderPicker';
import '../css/agents.css';
import type {
  AgentTaskActivityDto, AgentTaskChangesDto, AgentTaskDto, AgentTaskStartupPhase, CherryPickExecution, CiAutofixRunDto, DevelopmentAutomationDto,
  ModelOptionDto, ModelProviderDto, ModelSettingsDto, UserProfileDto,
} from '../types';
import { acceptsImages, createTaskCreationTracker, type AgentBridgePort, type TaskCreationTracker } from './model';
import { agentProviderUnavailableFrom } from './contracts';
import { pasteImage } from './clipboard';
import { ImageAttachment } from './ImageAttachment';
import { ATTENTION_SEED } from '../home/attention';
import type { TaskAttention } from './attention';
import { TaskItemCard, TaskItemRow, TaskTypeIcon, viewerInitials, type TaskItemHandlers } from './TaskListCards';
import { TASK_TYPES, cherryPickItem, ciFixItem, developmentItem, groupTaskList, reviewItem, type TaskListItem, type TaskType } from './taskList';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';

export type DraftImage = { id: string; name: string; url: string };
export type TaskModelSelection = { model: string; effort: string | null; serviceTier?: string | null };

/** A provider, model and effort chosen before the composer opened. */
export type TaskLaunch = { laneId: string } & TaskModelSelection;
type TaskTab = 'active' | 'archived';

/** What the backend's sweep does to a completed task nobody came back to; the hint names it. */
const AUTO_ARCHIVE_DAYS = 7;
/** How often the starting screen asks which creation step the in-flight request is in. */
const STARTUP_POLL_MS = 500;

interface AgentTasksViewProps {
  workspaceId: string;
  /** "owner/repo" new tasks start in, named by the chip above the composer. */
  repository?: string;
  sourceIssueNumber?: number | null;
  onOpenCreatedTask?: (task: AgentTaskDto) => void;
  headerAction?: React.ReactNode;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  /** The route owns which task is open, so opening one is a navigation. */
  onOpenTask: (taskId: string) => void;
  onOpenReview: (review: AgentReviewDto) => void;
  onOpenReviewWorkspace?: (review: AgentReviewDto) => void;
  onOpenCherryPick?: (execution: CherryPickExecution) => void;
  /** A CI fix that neither a task nor a cherry-pick started opens on its pull request. */
  onOpenPullRequest?: (number: number, repo: string) => void;
  /** Goes where a review, cherry-pick or CI fix begins. */
  onNewItem?: (type: Exclude<TaskType, 'dev'>) => void;
  /** The workspace's tasks and what they wait on, re-read for all its pages; without it the list shows what it loaded. */
  taskAttention?: TaskAttention;
  /** Whether a task, or a pull request by number, is in the workspace's pinned goal. */
  inGoal?: (item: TaskListItem) => boolean;
  /** Where a provider that is not ready gets fixed. */
  onOpenSettings?: () => void;
  /** A launch chosen before the composer opened; the person may change it until the first message creates a task. */
  launch?: TaskLaunch;
  /** An instruction prepared for the source issue, which the person reviews before sending. */
  initialDraft?: string;
  /** The source issue's ended task this composer starts another attempt after; the issue's task opens otherwise. */
  replaces?: string;
}

/** A task state reads as prose; the wire enum is never shown to the user. */
const STATE_LABELS: Record<string, string> = {
  OPEN: 'Idle',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

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

/**
 * A task something may still be writing to; deleting waits for it to stop. A recovery claim is not
 * one: it holds the worktree of a launch that could not be shown stopped, and deleting is how a
 * task leaves that state.
 */
function taskBusy(task: AgentTaskDto): boolean {
  const claim = task.mutationClaim ?? null;
  return claim !== null && claim.kind !== 'RECOVERY';
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
    if (isDeepSeekKeyLane(laneId)) return 'DeepSeek key removed. Add a key in Models settings and start a new task.';
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
    return isDeepSeekKeyLane(laneId)
      ? 'No DeepSeek model is available yet. Check the key in Models settings.'
      : `No ${laneLabel(laneId)} model is available yet. Checking the provider in Workspace settings also fetches its models.`;
  }
  return null;
}

/** Issue starts cannot use the attended-only DeepSeek lane. */
export function taskCreationCatalog(settings: ModelSettingsDto, sourceIssueNumber: number | null): ModelSettingsDto {
  return sourceIssueNumber === null
    ? settings
    : { ...settings, providers: settings.providers.filter(provider => !isDeepSeekKeyLane(provider.id)) };
}

/**
 * Everything creating a task needs: the list, the catalog, the chosen model, the draft, why
 * creation is blocked, and the create call itself — shared by the Tasks page and the Home hero.
 */
export function useAgentTaskCreation({ workspaceId, initialLaunch, bridge, onOpenTask, sourceIssueNumber = null, sourceJira = null, onOpenCreatedTask, initialDraft, replaces }: {
  workspaceId: string;
  sourceJira?: JiraIssue | null;
  /** A launch the caller chose; without one the workspace's Development agent decides. */
  initialLaunch?: TaskLaunch;
  bridge: () => AgentBridgePort;
  onOpenTask: (taskId: string) => void;
  sourceIssueNumber?: number | null;
  onOpenCreatedTask?: (task: AgentTaskDto) => void;
  /** A prepared instruction; the issue's default text does not replace it. */
  initialDraft?: string;
  /** The issue's ended task a new attempt replaces, which the composer does not open. */
  replaces?: string;
}) {
  // Home and the Tasks page remount on every tab switch; the last list paints the first frame, and
  // the mount still re-reads it.
  const sourceBound = sourceIssueNumber !== null || sourceJira !== null;
  const seed = `agent-tasks:${workspaceId}`;
  const [tasks, setTasks] = useState<AgentTaskDto[]>(() => getCached<AgentTaskDto[]>(seed) ?? []);
  const [loading, setLoading] = useState(() => getCached(seed) === undefined);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<ModelSettingsDto | null>(null);
  const [laneId, setLaneId] = useState(initialLaunch?.laneId ?? INTERACTIVE_LANE_ID);
  const [selection, setSelection] = useState<TaskModelSelection | null>(null);
  const [draft, setDraft] = useState(initialDraft ?? (sourceJira === null ? '' : `Implement ${sourceJira.key}: ${sourceJira.title}`));
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
   * next task is meant to launch with — unless a launch was chosen for it, or a
   * blocked start is retried on the lane it failed on.
   */
  const applyCatalog = useCallback((settings: ModelSettingsDto, preferredLaneId = initialLaunch?.laneId) => {
    const available = taskCreationCatalog(settings, sourceBound ? 0 : null);
    setCatalog(available);
    // The caller's own choice is taken as made; a retry that lands on another lane takes that lane's own.
    if (initialLaunch !== undefined && preferredLaneId === initialLaunch.laneId
      && available.providers.some((candidate) => candidate.id === initialLaunch.laneId)) {
      setLaneId(initialLaunch.laneId);
      setSelection({ model: initialLaunch.model, effort: initialLaunch.effort });
      return;
    }
    if (preferredLaneId !== undefined) {
      const named = available.providers.find((candidate) => candidate.id === preferredLaneId);
      if (named !== undefined && named.selectedModel !== '') {
        setLaneId(named.id);
        setSelection({ model: named.selectedModel, effort: named.selectedEffort === '' ? null : named.selectedEffort });
        return;
      }
      if (!sourceBound && isDeepSeekKeyLane(preferredLaneId) && named === undefined) {
        setLaneId(preferredLaneId);
        setSelection(null);
        return;
      }
    }
    const development = available.agents.find((agent) => agent.type === 'DEVELOPMENT');
    if (development !== undefined && available.providers.some((candidate) => candidate.id === development.configId)) {
      setLaneId(development.configId);
      setSelection({ model: development.model, effort: development.effort ?? null });
      return;
    }
    const provider = available.providers.find((candidate) => candidate.id === (initialLaunch?.laneId ?? INTERACTIVE_LANE_ID)) ?? available.providers[0];
    if (provider !== undefined && provider.selectedModel !== '') {
      setLaneId(provider.id);
      setSelection({
        model: provider.selectedModel,
        effort: provider.selectedEffort === '' ? null : provider.selectedEffort,
      });
    }
  }, [initialLaunch, sourceBound]);

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
      if (updated.developmentTask != null && updated.developmentTask.taskId !== replaces) {
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
  }, [bridge, replaces, sourceIssueNumber, workspaceId]);

  useEffect(() => {
    void refreshIssue();
    return () => { sourceRequest.current += 1; };
  }, [refreshIssue]);

  const provider = catalog?.providers.find((candidate) => candidate.id === laneId) ?? null;
  const deepSeek = isDeepSeekKeyLane(laneId);
  const taskAutomation = automation;
  const models: ModelOptionDto[] = provider?.models ?? [];
  const imagesAccepted = !deepSeek && acceptsImages(models, selection?.model, provider?.capabilities ?? null);
  const blocker = (sourceLoading ? 'Loading issue…' : sourceError)
    ?? (sourceJira?.status.category === 'done' ? 'This ticket is done. Reopen it in Jira before starting new development.' : null)
    ?? (sourceJira?.isGoal || sourceJira?.hasChildren ? 'Choose a leaf ticket to start development.' : null)
    ?? (issue?.isGoal ? 'Goals organize work and cannot start development. Unmark this issue as a Goal to run it.' : null)
    ?? creationBlocker(catalog, laneId, selection)
    ?? automationError
    ?? (taskAutomation === null ? 'Loading automation settings…' : null)
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
    if (busyRef.current || selection === null || taskAutomation === null || blocker !== null || message === '') return;
    busyRef.current = true;
    setOperation('sending');
    setError(null);
    const request = submissions.requestFor(workspaceId, message, laneId, {
      model: selection.model,
      effort: selection.effort,
      permissionPolicy: 'AUTO',
      serviceTier: selection.serviceTier ?? null,
      developmentAutomation: taskAutomation,
    }, images.map((image) => image.id), automationForSubmission(taskAutomation, localOnly), issue === null ? undefined : {
      issueId: issue.id, number: issue.number, updatedAt: issue.updatedAt, ...(replaces === undefined ? {} : { replaces }),
    }, sourceJira === null ? undefined : {
      connectionId: sourceJira.connectionId, cloudId: sourceJira.cloudId, issueId: sourceJira.id,
      key: sourceJira.key, title: sourceJira.title, body: sourceJira.body, updatedAt: sourceJira.updatedAt,
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
      if (issue !== null) {
        if (task.issueSource !== null && task.issueSource !== undefined) recordAttentionAction(`${task.issueSource.repo}#${task.issueSource.number}`, 'prompt');
        notifyIssueDevelopmentChanged(issue.id);
      }
      if (sourceJira !== null) notifyJiraChanged();
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
  }, [applyCatalog, blocker, bridge, clearImages, draft, images, issue, sourceJira, laneId, localOnly, onOpenCreatedTask, onOpenTask, replaces, selection, submissions, taskAutomation,
    workspaceId]);

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
      automation: taskAutomation === null ? null : <AutomationControl value={taskAutomation} providers={catalog?.providers ?? []} disabled={busy}
        localOnly={localOnly} onChange={setAutomation} onLocalOnlyChange={setLocalOnly} />,
      onAttach: () => { void attach(); },
      onPasteImages: () => { void attach('clipboard'); },
      onRemoveImage: removeImage,
      onDraftChange: setDraft,
      onSelectProvider: selectProvider,
      onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => setSelection({ model, effort, serviceTier }),
      onCreate: () => { void create(); },
    },
    issueAttachment: sourceJira !== null ? <JiraSourceAttachment source={{ ...sourceJira, issueId: sourceJira.id }} /> : issue === null ? null : <IssueAttachment source={{
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

const NO_TASK_ATTENTION: TaskAttention = { items: [], tasks: [], activity: {}, refresh: () => {} };

/** How often Home and Tasks re-read workflow activity. */
const WORK_POLL_MS = 5000;

type WorkspaceWork = { reviews: AgentReviewDto[]; executions: CherryPickExecution[]; runs: CiAutofixRunDto[]; failed: string[] };

const NO_WORK: WorkspaceWork = { reviews: [], executions: [], runs: [], failed: [] };

/**
 * The workspace's workflow runs, re-read while the page is visible. A source
 * that cannot be read keeps its last answer and is named until a read succeeds again.
 */
export function useWorkspaceWork(workspaceId: string, bridge: () => AgentBridgePort): WorkspaceWork {
  const seed = `task-work:${workspaceId}`;
  const [work, setWork] = useState<WorkspaceWork>(() => getCached<WorkspaceWork>(seed) ?? NO_WORK);
  useEffect(() => {
    let active = true;
    const read = (): void => {
      if (document.hidden) return;
      const port = bridge();
      // ponytail: every list on every tick; per-source change events if these reads ever show up.
      void Promise.allSettled([
        port.listAgentReviews?.(workspaceId) ?? Promise.resolve([]),
        port.listCherryPickExecutions?.(200) ?? Promise.resolve([]),
        port.listCiAutofixRuns?.(100) ?? Promise.resolve([]),
      ]).then(([reviews, executions, runs]) => {
        if (!active) return;
        setWork((last) => ({
          reviews: reviews.status === 'fulfilled' ? reviews.value : last.reviews,
          executions: executions.status === 'fulfilled' ? executions.value.filter((execution) => execution.workspaceId === workspaceId) : last.executions,
          runs: runs.status === 'fulfilled' ? runs.value.filter((run) => run.workspaceId === workspaceId) : last.runs,
          failed: [reviews.status === 'rejected' ? 'reviews' : null, executions.status === 'rejected' ? 'cherry-picks' : null,
            runs.status === 'rejected' ? 'CI fixes' : null]
            .filter((name): name is string => name !== null),
        }));
      });
    };
    read();
    const timer = window.setInterval(read, WORK_POLL_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, [bridge, workspaceId]);
  useEffect(() => { setCached(seed, work); }, [seed, work]);
  return work;
}

/** Each listed task in its newest copy: the page's own changes and the polled list both move the version. */
function freshest(tasks: AgentTaskDto[], polled: { task: AgentTaskDto }[]): AgentTaskDto[] {
  const latest = new Map(polled.map(({ task }) => [task.id, task]));
  return tasks.map((task) => {
    const fresh = latest.get(task.id);
    return fresh !== undefined && fresh.version > task.version ? fresh : task;
  });
}

export default function AgentTasksView({
  workspaceId,
  repository,
  sourceIssueNumber = null,
  onOpenCreatedTask,
  headerAction,
  bridge: explicitBridge,
  onOpenTask,
  onOpenReview,
  onOpenReviewWorkspace,
  onOpenCherryPick,
  onOpenPullRequest,
  onNewItem,
  taskAttention = NO_TASK_ATTENTION,
  inGoal,
  onOpenSettings,
  launch,
  initialDraft,
  replaces,
}: AgentTasksViewProps): React.JSX.Element {
  // Resolved when used, not when rendered: a static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, initialLaunch: launch, bridge: live, onOpenTask, sourceIssueNumber, onOpenCreatedTask, initialDraft, replaces });
  const { setTasks } = creation;
  const tasks = useMemo(() => freshest(creation.tasks, taskAttention.tasks), [creation.tasks, taskAttention.tasks]);
  const tasksRef = useRef(tasks);
  tasksRef.current = tasks;
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
  const changes = useTaskChanges(workspaceId, tasks, live);
  const work = useWorkspaceWork(workspaceId, live);
  const items = useMemo(() => [
    ...work.reviews.map(reviewItem),
    ...work.executions.map(cherryPickItem),
    ...work.runs.map(ciFixItem),
  ], [work]);
  const openItem = useCallback((item: TaskListItem) => {
    const { review, execution, run } = item;
    if (review !== null) onOpenReview(review);
    else if (execution !== null) onOpenCherryPick?.(execution);
    else if (run !== null) {
      // A CI fix is followed where it started: the development task, or the cherry-pick that delivered its pull request.
      const delivery = work.executions.find((candidate) => candidate.delivery?.pullRequestNumber === run.pullRequestNumber);
      if (run.parentTaskId !== null) onOpenTask(run.parentTaskId);
      else if (delivery !== undefined) onOpenCherryPick?.(delivery);
      else onOpenPullRequest?.(run.pullRequestNumber, run.repository);
    }
  }, [onOpenCherryPick, onOpenPullRequest, onOpenReview, onOpenTask, work.executions]);
  const error = creation.error ?? (work.failed.length === 0 ? null : `Could not read ${work.failed.join(', ')}; trying again.`);
  return (
    <AgentTaskList
      workspaceId={workspaceId}
      headerAction={headerAction}
      repository={repository}
      {...creation.composer}
      issueAttachment={creation.issueAttachment}
      tasks={tasks}
      items={items}
      activity={taskAttention.activity}
      inGoal={inGoal}
      changes={changes}
      loading={creation.loading}
      error={error}
      startup={creation.startup}
      onOpenTask={onOpenTask}
      onOpenItem={openItem}
      onOpenReviewWorkspace={onOpenReviewWorkspace}
      onNewItem={onNewItem}
      onOpenSettings={onOpenSettings}
      onArchive={archive}
      onDelete={remove}
    />
  );
}

/**
 * The composer anchored at the foot of a workspace page, over a fade, under a chip naming the
 * repository new tasks start in. It sticks to the bottom of the page's own scroller, so the last row
 * always scrolls clear of it however tall the draft grows.
 */
export function ComposerDock({ repository, chips, children }: {
  /** "owner/repo"; without one the chip row holds only the chips. */
  repository?: string;
  chips?: React.ReactNode;
  children: React.ReactNode;
}): React.JSX.Element {
  const [owner, repo = ''] = (repository ?? '').split('/');
  return (
    <div className="agt-dock">
      <div className="agt-dock__inner">
        {(repository !== undefined || chips !== undefined) && (
          <div className="agt-dock__chips">
            {repository !== undefined && (
              <span className="agt-repo-chip" title={`${repository} · tasks start in this repo`}>
                <RepoAvatar owner={owner} repo={repo} size={18} />
              </span>
            )}
            {chips}
          </div>
        )}
        {children}
      </div>
    </div>
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
  const imagesAccepted = !isDeepSeekKeyLane(laneId)
    && acceptsImages(models, selection?.model, providers.find((provider) => provider.id === laneId)?.capabilities ?? null);
  const openProviderSettings = (providerId: string): void => {
    if (isDeepSeekKeyLane(providerId)) window.location.hash = settingsRoute('models');
    else onOpenSettings?.();
  };
  return (
    <div className="agt-compose-dock">
      {blocker !== null && (
        <div className="agt-compose-blocker" role="status">
          {blocker}
          {(onOpenSettings !== undefined || isDeepSeekKeyLane(laneId)) && (
            <>
              {' '}
              <button type="button" className="agt-action" onClick={() => openProviderSettings(laneId)}>
                Open {isDeepSeekKeyLane(laneId) ? 'Models' : 'workspace'} settings
              </button>
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
        <textarea
          className="agt-compose__input"
          rows={1}
          aria-label="Describe a task"
          placeholder="Let's ship..."
          value={draft}
          disabled={blocker !== null || (busy && !attaching)}
          onChange={(event) => onDraftChange(event.target.value)}
          onPaste={(event) => pasteImage(event, imagesAccepted && blocker === null && !busy, onPasteImages)}
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
            event.preventDefault();
            onCreate();
          }}
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
            onOpenSettings={onOpenSettings !== undefined || providers.some(provider => isDeepSeekKeyLane(provider.id)) ? openProviderSettings : undefined}
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

/**
 * What each listed task's checkout holds, by task id. Reading checkouts takes Git a moment each, so a
 * page paints with the last answer and takes the new one when it lands.
 */
export function useTaskChanges(workspaceId: string, tasks: AgentTaskDto[], bridge: () => AgentBridgePort): Record<string, AgentTaskChangesDto> {
  const seed = `agent-task-changes:${workspaceId}`;
  const [changes, setChanges] = useState<Record<string, AgentTaskChangesDto>>(() => getCached(seed) ?? {});
  // Keyed by the ids alone, so archiving a row does not read every checkout again.
  const taskIds = tasks.map((task) => task.id).join(' ');
  useEffect(() => {
    if (taskIds === '') return;
    let active = true;
    // A failed read keeps the last answer; a row without one simply names nothing.
    void bridge().listAgentTaskChanges(workspaceId).then((found) => {
      setCached(seed, found);
      if (active) setChanges(found);
    }, () => {});
    return () => { active = false; };
  }, [bridge, seed, taskIds, workspaceId]);
  return changes;
}

/** Commits first, since they are the result a pull request would carry; uncommitted files are work still in the checkout. */
export function changesLabel({ uncommitted, commits }: AgentTaskChangesDto): string {
  const committed = commits === 0 ? null : `${commits} ${commits === 1 ? 'commit' : 'commits'}`;
  if (committed === null) return uncommitted ? 'Uncommitted changes' : 'No changes';
  return uncommitted ? `${committed} · uncommitted changes` : committed;
}

type ToastState = { id: number; text: string; undo?: () => void };

/** The type tabs across the Tasks page, each filtering the whole page. */
type TypeTab = 'all' | TaskType | 'linked' | 'backports';
const TYPE_TABS: TypeTab[] = ['all', 'dev', 'review', 'cherry', 'ci', 'backports', 'linked'];

function inType(item: TaskListItem, type: TypeTab): boolean {
  return type === 'all' || item.type === type;
}

export function AgentTaskList({
  workspaceId, tasks, items = [], activity = {}, loading, error, laneId, providers, models, selection, draft, blocker, headerAction, repository,
  onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenTask, onOpenItem, onOpenReviewWorkspace, onNewItem, inGoal, onOpenSettings, onArchive, onDelete,
  images = [], busy = false, startup = null, attaching = false, onAttach, onPasteImages, onRemoveImage, automation, issueAttachment,
  now = Date.now(), initialTab = 'active', initialType = 'all', changes = {},
}: {
  workspaceId?: string;
  tasks: AgentTaskDto[];
  /** The workspace's reviews, cherry-picks and CI fixes, listed beside its agent tasks. */
  items?: TaskListItem[];
  /** What each agent task last said and waits on, by task id. */
  activity?: Record<string, AgentTaskActivityDto>;
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
  headerAction?: React.ReactNode;
  /** "owner/repo" new tasks start in. */
  repository?: string;
  onDraftChange: (draft: string) => void;
  onSelectProvider: (laneId: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onCreate: () => void;
  onOpenTask: (taskId: string) => void;
  /** Opens a review, cherry-pick or CI fix. */
  onOpenItem?: (item: TaskListItem) => void;
  /** A review's own conversation, beside its pull request. */
  onOpenReviewWorkspace?: (review: AgentReviewDto) => void;
  /** Goes where a review, cherry-pick or CI fix begins; a development task begins in the composer below. */
  onNewItem?: (type: Exclude<TaskType, 'dev'>) => void;
  /** Whether an item is in the workspace's pinned goal, marked on its card or row. */
  inGoal?: (item: TaskListItem) => boolean;
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
  initialType?: TypeTab;
  /** What each task's checkout holds, by task id, for tasks without a pull request to name. */
  changes?: Record<string, AgentTaskChangesDto>;
}): React.JSX.Element {
  const linked = useLinkedWorkspaceWork(workspaceId);
  const backports = useBackportChains(workspaceId);
  const [tab, setTab] = useState<TaskTab>(initialTab);
  const [type, setType] = useState<TypeTab>(initialType);
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
  const chainShelf = backports.chains.filter(view => view.archived === !active);
  const linkedShelf = linked.links.filter(work => work.archived === !active);
  const archivedCount = tasks.filter(taskArchived).length;
  // Only agent tasks archive; reviews, cherry-picks and CI fixes stay on the active shelf.
  const shelf = [
    ...tasks.filter((task) => taskArchived(task) === !active)
      .map((task) => developmentItem(task, activity[task.id] ?? null, changes[task.id] ?? null, providers)),
    ...(active ? items : []),
  ];
  const visible = shelf.filter((item) => inType(item, type));
  const { today, todayDate, groups } = groupTaskList(visible, now);
  const visibleTasks = visible.flatMap((item) => (item.task === null ? [] : [item.task]));
  const selectedTasks = selected.map((id) => visibleTasks.find((task) => task.id === id)).filter((task): task is AgentTaskDto => task !== undefined);
  const selectionRunning = selectedTasks.some(taskBusy);
  const you = viewerInitials(getCached<UserProfileDto>(ATTENTION_SEED.profile));

  const showToast = (text: string, undo?: () => void): void => {
    toastId.current += 1;
    setToast({ id: toastId.current, text, undo });
  };
  const switchTab = (next: TaskTab): void => {
    setTab(next);
    setSelected([]);
  };
  const switchType = (next: TypeTab): void => {
    setType(next);
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
  const handlers = (item: TaskListItem): TaskItemHandlers => {
    const { task, review } = item;
    const goal = inGoal?.(item) ?? false;
    if (task !== null) {
      return {
        now, you, selecting, inGoal: goal,
        checked: selected.includes(task.id),
        onOpen: () => onOpenTask(task.id),
        onToggle: () => toggle(task.id),
        onArchive: (archived) => archive([task.id], archived),
        onDelete: () => requestDelete([task.id]),
      };
    }
    return {
      now, you, selecting, inGoal: goal,
      checked: false,
      onOpen: () => onOpenItem?.(item),
      onOpenWorkspace: review === null || onOpenReviewWorkspace === undefined ? undefined : () => onOpenReviewWorkspace(review),
    };
  };
  const startNew = (event: React.MouseEvent<HTMLButtonElement>): void => {
    if (type === 'all' || type === 'dev') {
      event.currentTarget.closest('.agt-page')?.querySelector<HTMLTextAreaElement>('.agt-compose__input')?.focus();
    }
    else if (type !== 'linked' && type !== 'backports') onNewItem?.(type);
  };

  return (
    <div className="agt-tasks-page">
      <div className="agt-page">
        <div className="agt-tasks-head">
          <h1>Tasks</h1>
          <span className="agt-spacer" />
          <div className="agt-tabs" role="tablist" aria-label="Task lists">
            {([
              ['active', 'Active', tasks.length - archivedCount + items.length, undefined],
              ['archived', 'Archived', archivedCount, `Completed tasks archive automatically after ${AUTO_ARCHIVE_DAYS} days`],
            ] as const).map(([id, label, count, hint]) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={tab === id}
                title={hint}
                className={`agt-tab${tab === id ? ' is-active' : ''}`}
                onClick={() => switchTab(id)}
              >
                {label}<span className="agt-tab__count">{count}</span>
              </button>
            ))}
          </div>
          {headerAction}
        </div>
        <div className="agt-type-tabs">
          <div className="agt-type-tabs__list" role="tablist" aria-label="Task types">
            {TYPE_TABS.map((key) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={type === key}
                className={`agt-type-tab${type === key ? ' is-active' : ''}`}
                onClick={() => switchType(key)}
              >
                <span className={`agt-type-tab__icon is-${key}`}>{(key === 'linked' || key === 'backports') ? <Link2 size={14} /> : <TaskTypeIcon type={key} />}</span>
                {key === 'backports' ? 'Backport chains' : key === 'linked' ? 'Linked work' : key === 'all' ? 'All' : TASK_TYPES[key].label}
                <span className="agt-type-tab__count">{key === 'backports' ? chainShelf.length : key === 'linked' ? linkedShelf.length : shelf.filter((item) => inType(item, key)).length}</span>
                {shelf.some((item) => inType(item, key) && item.state.label === 'Needs you') && <span className="agt-type-tab__needs" title="Needs you" />}
              </button>
            ))}
          </div>
          {active && type !== 'linked' && type !== 'backports' && (
            <button type="button" className="agt-type-new" onClick={startNew}>
              + {TASK_TYPES[type === 'all' ? 'dev' : type].newLabel}
            </button>
          )}
        </div>
        {selecting && (
          <div className="agt-selbar">
            <span className="agt-selbar__count">{selected.length} selected</span>
            <button type="button" className="agt-selbar__link" onClick={() => setSelected(visibleTasks.map((task) => task.id))}>Select all</button>
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
        )}
        {error !== null && <div className="cmt-error" role="alert">{error}</div>}
        {loading && <div className="agt-loading">Loading agent tasks…</div>}
        {type === 'backports' && <BackportChainList chains={chainShelf} error={backports.error} />}
        {type === 'linked' && <LinkedWorkList links={linkedShelf} error={linked.error} />}
        {type !== 'linked' && type !== 'backports' && !loading && visible.length === 0 && (active ? (
          <div className="agt-items-empty">{type === 'all' ? 'No tasks yet.' : 'No tasks of this type yet.'}</div>
        ) : (
          <div className="agt-empty-hero">
            <div className="agt-empty-hero__title">Nothing archived</div>
            <div className="agt-muted">
              {`Archive a task from the Active tab, or wait — completed tasks move here after ${AUTO_ARCHIVE_DAYS} days.`}
            </div>
          </div>
        ))}
        <div className={`agt-items${selecting ? ' has-selection' : ''}`}>
          {today.length > 0 && (
            <section className="agt-day is-today" aria-label="Today">
              <div className="agt-day__head">
                <span className="agt-day__label">Today</span>
                <span className="agt-day__date">{todayDate}</span>
              </div>
              <div className="agt-item-grid">
                {today.map((item) => <TaskItemCard key={item.key} item={item} {...handlers(item)} />)}
              </div>
            </section>
          )}
          {groups.map((group) => (
            <section className="agt-day" key={group.key} aria-label={group.label}>
              <div className="agt-day__head">
                <span className="agt-day__label">{group.label}</span>
                <span className="agt-day__date">{group.date}</span>
              </div>
              <div className="agt-item-rows">
                {group.items.map((item) => <TaskItemRow key={item.key} item={item} {...handlers(item)} />)}
              </div>
            </section>
          ))}
        </div>

        {/* Anchored under the list: sending this first message is what creates a task. */}
        {active && (
          <ComposerDock repository={repository}>
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
          </ComposerDock>
        )}
      </div>
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
