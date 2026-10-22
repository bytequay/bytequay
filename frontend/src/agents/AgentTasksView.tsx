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

import { AgentSetupNotice, useAgentAvailability } from './availability';
import { BackportButton, BackportRequestHint, backportDraft } from './BackportRequestHint';
import { notifyJiraChanged, type JiraIssue } from '../jira/contracts';
import { JiraSourceAttachment } from '../jira/JiraSourceAttachment';
import React, { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Archive, ArchiveRestore, Check, Circle, Link2, LoaderCircle, Trash2 } from 'lucide-react';
import { ConfirmDialog } from '../ConfirmDialog';
import { Chevron } from '../Chevron';
import RepoAvatar from '../RepoAvatar';
import { INTERACTIVE_LANE_ID, developmentDefaults, displayModelName, isDeepSeekKeyLane, laneDefaults, laneLabel, providerAvailable } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import { getCached, setCached } from '../dataCache';
import { pollVisible } from '../ide/pollVisible';
import Toast from '../Toast';
import { GOALS_CHANGED } from '../workitems/changes';
import { recordAttentionAction } from '../home/useAttentionVisit';
import { notifyIssueDevelopmentChanged, type DevelopmentIssue } from '../workspaces/issueDevelopment';
import { workspaceAgentTaskRoute } from '../workspaces/model';
import { LinkedWorkList, useLinkedWorkspaceWork } from './LinkedWorkspaceWork';
import { IssueAttachment } from './IssueAttachment';
import { WorkItemPrompt } from './WorkItemPrompt';
import { AutomationControl } from './AutomationControl';
import Composer, { ComposerAttachments } from '../Composer';
import ComposerModelControls from './ComposerModelControls';
import '../css/agents.css';
import type {
  AgentTaskActivityDto, AgentTaskChangesDto, AgentTaskDto, AgentTaskStartupPhase, CherryPickExecution, CiAutofixRunDto, DevelopmentAutomationDto,
  ModelOptionDto, ModelProviderDto, ModelSettingsDto, UserProfileDto,
} from '../types';
import { acceptsImages, createTaskCreationTracker, type AgentBridgePort, type TaskCreationTracker } from './model';
import { agentProviderUnavailableFrom, CHECKING_ACCEPTANCE, unconfirmedRequest } from './contracts';
import { pasteImage } from './clipboard';
import { ImageAttachment } from './ImageAttachment';
import { ATTENTION_SEED } from '../home/attention';
import type { TaskAttention } from './attention';
import { taskArchived, taskBusy } from './taskStatus';
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
  /** Where a provider that is not ready gets fixed. */
  onOpenSettings?: () => void;
  /** A launch chosen before the composer opened; the person may change it until the first message creates a task. */
  launch?: TaskLaunch;
  /** An instruction prepared for the source issue, which the person reviews before sending. */
  initialDraft?: string;
  /** The source issue's ended task this composer starts another attempt after; the issue's task opens otherwise. */
  replaces?: string;
  /** The work item a task without a source is for; the task the composer creates becomes its attempt. */
  workItemId?: string;
  /** The start was already decided, as by an Epic's Run: the composer sends its message itself once it has loaded. */
  autoStart?: boolean;
  /** The composer took that start; a composer opened later must not send it again. */
  onAutoStart?: () => void;
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
    return 'Choose an available provider below, or configure one in settings.';
  }
  if (selection === null || !provider.models.some((model) => model.id === selection.model)) {
    return isDeepSeekKeyLane(laneId)
      ? 'No DeepSeek model is available yet. Check the key in Models settings.'
      : `No ${laneLabel(laneId)} model is available yet. Checking the provider in Workspace settings also fetches its models.`;
  }
  return null;
}

/**
 * Everything creating a task needs: the list, the catalog, the chosen model, the draft, why
 * creation is blocked, and the create call itself — shared by the Tasks page and the Home hero.
 */
export function useAgentTaskCreation({ workspaceId, initialLaunch, bridge, onOpenTask, sourceIssueNumber = null, sourceJira = null, onOpenCreatedTask, initialDraft, replaces,
  workItemId, autoStart = false, onAutoStart }: {
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
  /** The source's ended task a new attempt replaces, which the composer does not open. */
  replaces?: string;
  /** The work item a task without a source is for. */
  workItemId?: string;
  /** The start was already decided: the message sends itself once the composer has loaded. */
  autoStart?: boolean;
  /** The composer took that start. */
  onAutoStart?: () => void;
}) {
  // Home and the Tasks page remount on every tab switch; the last list paints the first frame, and
  // the mount still re-reads it.
  const availability = useAgentAvailability(workspaceId);
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
  const [savedAutomation, setSavedAutomation] = useState<DevelopmentAutomationDto | null>(null);
  const [automationError, setAutomationError] = useState<string | null>(null);
  const [images, setImages] = useState<DraftImage[]>([]);
  const [operation, setOperation] = useState<'attachment' | 'sending' | null>(null);
  const [startup, setStartup] = useState<AgentTaskStartupPhase | null>(null);
  const [autoStarting, setAutoStarting] = useState(autoStart);
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
   * Use the resolved Development setting, even when its provider needs setup.
   * Explicit launches retain their lane until the user changes it.
   */
  const applyCatalog = useCallback((settings: ModelSettingsDto, preferredLaneId = initialLaunch?.laneId) => {
    setCatalog(settings);
    // An explicitly supplied launch keeps its provider and model, including while it needs setup.
    if (initialLaunch !== undefined && preferredLaneId === initialLaunch.laneId
      && settings.providers.some((candidate) => candidate.id === initialLaunch.laneId)) {
      setLaneId(initialLaunch.laneId);
      setSelection({ model: initialLaunch.model, effort: initialLaunch.effort });
      return;
    }
    if (preferredLaneId !== undefined) {
      const named = settings.providers.find((candidate) => candidate.id === preferredLaneId);
      if (named !== undefined && named.selectedModel !== '') {
        setLaneId(named.id);
        setSelection({ model: named.selectedModel, effort: named.selectedEffort === '' ? null : named.selectedEffort });
        return;
      }
      if (named === undefined) {
        setLaneId(preferredLaneId);
        setSelection(null);
        return;
      }
    }
    const defaults = developmentDefaults(settings);
    setLaneId(defaults.laneId);
    setSelection(defaults.model === '' ? null : { model: defaults.model, effort: defaults.effort });
  }, [initialLaunch]);

  const selectionMissing = selection === null;
  useEffect(() => {
    if (availability.settings === null) return;
    if (selectionMissing) applyCatalog(availability.settings);
    else setCatalog(availability.settings);
  }, [applyCatalog, availability.settings, selectionMissing]);

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
        applyCatalog(settings);
      })
      // The blocker below reports an unusable catalog on its own; a decided start has nothing to send with.
      .catch(() => { if (active) setAutoStarting(false); });
    return () => { active = false; };
  }, [applyCatalog, bridge, workspaceId]);

  useEffect(() => {
    let active = true;
    void bridge().getDevelopmentAutomation().then(value => { if (active) { setAutomation(value); setSavedAutomation(value); } })
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
      if (updated === null) throw new Error('Issue is no longer available locally. Refresh the workspace issues before starting development.');
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
    ?? (sourceJira?.isEpic || sourceJira?.hasChildren ? 'Choose a leaf ticket to start development.' : null)
    ?? (issue?.isEpic ? 'Epics organize work and cannot start development. Unmark this issue as an Epic to run it.' : null)
    ?? creationBlocker(catalog, laneId, selection)
    ?? automationError
    ?? (taskAutomation === null ? 'Loading automation settings…' : null)
    ?? (images.length > 0 && !imagesAccepted ? 'Choose a model that accepts images or remove the images.' : null);

  const selectProvider = useCallback((nextLaneId: string) => {
    const next = catalog?.providers.find((candidate) => candidate.id === nextLaneId);
    if (next === undefined || !providerAvailable(next.readiness)) return;
    setLaneId(next.id);
    const defaults = laneDefaults(catalog?.providers ?? [], next.id);
    setSelection(defaults.model === '' ? null : { model: defaults.model, effort: defaults.effort || null });
    setError(null);
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
    }, images.map((image) => image.id), taskAutomation, issue === null ? undefined : {
      issueId: issue.id, number: issue.number, updatedAt: issue.updatedAt, ...(replaces === undefined ? {} : { replaces }),
    }, sourceJira === null ? undefined : {
      connectionId: sourceJira.connectionId, cloudId: sourceJira.cloudId, issueId: sourceJira.id,
      key: sourceJira.key, title: sourceJira.title, body: sourceJira.body, updatedAt: sourceJira.updatedAt, ...(replaces === undefined ? {} : { replaces }),
    }, workItemId);
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
      // The work's new attempt shows wherever its work is read.
      if (workItemId !== undefined) window.dispatchEvent(new CustomEvent(GOALS_CHANGED, { detail: workspaceId }));
      setDraft('');
      // A choice made for one task does not carry over to the next.
      setAutomation(savedAutomation);
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
        setError(`${laneLabel(unavailable.laneId)} could not start. Choose another provider or check its settings.`);
        // Refresh readiness without replacing a provider the user selects while the read is pending.
        void bridge().getModelSettings(workspaceId)
          .then(setCatalog)
          .catch(() => {});
        return;
      }
      setError(unconfirmedRequest(failure) ? CHECKING_ACCEPTANCE : failure instanceof Error ? failure.message : 'Unable to create the agent task');
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
  }, [blocker, bridge, clearImages, draft, images, issue, sourceJira, laneId, onOpenCreatedTask, onOpenTask, replaces, savedAutomation, selection, submissions, taskAutomation,
    workItemId, workspaceId]);

  // A decided start reaches the composer that opens for it, or one already open on the same work; saying it was taken keeps a composer
  // opened later from sending it again.
  useEffect(() => {
    if (!autoStart) return;
    setAutoStarting(true);
    onAutoStart?.();
  }, [autoStart, onAutoStart]);
  // A decided start is sent once, when the catalog, the automation settings and the source have loaded. Whatever is in its way then
  // leaves the draft with the user, so a later change such as another provider never sends it.
  useEffect(() => {
    if (!autoStarting || catalog === null || sourceLoading || (taskAutomation === null && automationError === null)) return;
    setAutoStarting(false);
    // An issue that already has a task opens that task instead, and leaves this composer without its source.
    if (blocker === null && (sourceIssueNumber === null || issue !== null)) void create();
  }, [autoStarting, automationError, blocker, catalog, create, issue, sourceIssueNumber, sourceLoading, taskAutomation]);

  return {
    /** Everything TaskComposer needs, so a screen spreads it instead of re-listing eighteen props. */
    composer: {
      workspaceId,
      laneId,
      providers: catalog?.providers ?? [],
      models,
      selection,
      draft,
      blocker,
      busy,
      attaching: operation === 'attachment',
      images,
      automation: taskAutomation === null ? null : <AutomationControl value={taskAutomation} defaultValue={savedAutomation ?? undefined}
        disabled={busy} onChange={setAutomation} />,
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
    /** A decided start is waiting for the composer to load; its page can show the task starting already. */
    autoStarting,
    startup,
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
export function StartingAgentTask({ draft, images, laneId, providers, models, selection, startup = null, error = null, headerAction, workspaceId, workItemId }: {
  draft: string;
  images: DraftImage[];
  laneId: string;
  providers: ModelProviderDto[];
  models: ModelOptionDto[];
  selection: TaskModelSelection | null;
  startup?: AgentTaskStartupPhase | null;
  error?: string | null;
  headerAction?: React.ReactNode;
  workspaceId?: string;
  workItemId?: string;
}): React.JSX.Element {
  const provider = providers.find((candidate) => candidate.id === laneId)?.label ?? laneLabel(laneId);
  const model = models.find((candidate) => candidate.id === selection?.model);
  const steps = startupSteps(startup, provider, images.length > 0);
  const current = steps.find(step => step.state === 'current');
  const attachments = images.length > 0 && <div className="agt-user__files">
    {images.map(image => <ImageAttachment key={image.id} url={image.url} name={image.name} />)}
  </div>;
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
      {error !== null && <div className="agt-notice" role="alert">{error}</div>}
      <div className="agt-transcript" aria-label="Starting task conversation">
        <div>
          <div className="agt-user">
            <WorkItemPrompt workspaceId={workspaceId} itemId={workItemId} attachments={attachments}>
              <div className="agt-user__bubble">
                {attachments}
                <div>{draft.trim()}</div>
              </div>
            </WorkItemPrompt>
          </div>
          <details className="agt-act agt-starting">
            <summary className="agt-act__fold">
              <Chevron />
              <span role="status">{error === CHECKING_ACCEPTANCE ? 'Checking acceptance…' : <>Starting task…{current && ` ${current.label}`}</>}</span>
            </summary>
            <ol className="agt-act__body agt-starting-steps" aria-label="Task startup steps">
              {steps.map((step) => (
                <li key={step.label} className={`agt-starting-step is-${step.state}`}>
                  <span className={`agt-icon${step.state === 'current' ? ' is-running' : step.state === 'done' ? ' is-ok' : ''}`} aria-hidden="true">
                    {step.state === 'current' ? <LoaderCircle className="agt-spin" /> : step.state === 'done' ? <Check /> : <Circle />}
                  </span>
                  <span>{step.label}</span>
                </li>
              ))}
            </ol>
          </details>
        </div>
      </div>
      <div className="agt-bottom">
        <div className="agt-compose-dock is-task">
          <Composer aria-label="Message agent" placeholder="Waiting for task to start…" rows={2} disabled busy
            onSubmit={() => {}} submitEnabled={false} showSubmit={false} />
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
    const read = () => {
      const port = bridge();
      // ponytail: every list on every tick; per-source change events if these reads ever show up.
      return Promise.allSettled([
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
    const stop = pollVisible(read, WORK_POLL_MS);
    return () => { active = false; stop(); };
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
  onOpenSettings,
  launch,
  initialDraft,
  replaces,
  workItemId,
  autoStart,
  onAutoStart,
}: AgentTasksViewProps): React.JSX.Element {
  // Resolved when used, not when rendered: a static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const creation = useAgentTaskCreation({ workspaceId, initialLaunch: launch, bridge: live, onOpenTask, sourceIssueNumber, onOpenCreatedTask, initialDraft, replaces,
    workItemId, autoStart, onAutoStart });
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
  const items = useMemo(() => {
    // A delivering cherry-pick runs its own CI rounds and shows them inside its task.
    const owned = new Set(work.executions.flatMap((execution) => execution.lifecycle.ciRunIds));
    return [
      ...work.reviews.map(reviewItem),
      ...work.executions.map(cherryPickItem),
      ...work.runs.filter((run) => !owned.has(run.id)).map(ciFixItem),
    ];
  }, [work]);
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
      workItemId={workItemId}
      headerAction={headerAction}
      repository={repository}
      {...creation.composer}
      busy={creation.composer.busy || creation.autoStarting}
      issueAttachment={creation.issueAttachment}
      tasks={tasks}
      items={items}
      activity={taskAttention.activity}
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

export type TaskComposerProps = {
  workspaceId?: string;
  /** The first message; sending it creates the task and names it until the agent answers. */
  draft: string;
  /** Why creation is unavailable; null when the task can be created. */
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
};

/** The card that creates a task: the first message above its provider, automation and model controls. */
export function TaskComposer({
  draft, blocker, laneId, providers, models, selection, onDraftChange, onSelectProvider, onSelectModel, onCreate, onOpenSettings, workspaceId,
  images = [], busy = false, attaching = false, onAttach, onPasteImages, onRemoveImage, automation, issueAttachment,
  backport = false,
}: TaskComposerProps & { backport?: boolean }): React.JSX.Element {
  const availability = useAgentAvailability(workspaceId);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const composing = useRef(false);
  const nextCaret = useRef(false);
  const dismissed = useRef<{ value: string; caret: number } | null>(null);
  const [mention, setMention] = useState<string | null>(null);
  const mentionId = useId();
  const disabled = busy && !attaching;
  const showMention = backport && !disabled && mention === draft;
  const updateMention = (input: HTMLTextAreaElement): void => {
    const caret = input.selectionStart;
    if (dismissed.current?.value === input.value && dismissed.current.caret === caret) return;
    dismissed.current = null;
    const match = /^\s*@([^\s@]*)$/.exec(input.value.slice(0, caret));
    const suffix = input.value.slice(caret).match(/^\S*/)?.[0] ?? '';
    setMention(backport && !composing.current && caret === input.selectionEnd && match !== null
      && 'backport'.startsWith((match[1] + suffix).toLowerCase()) ? input.value : null);
  };
  const selectBackport = (): void => {
    nextCaret.current = true;
    onDraftChange(backportDraft(draft));
    setMention(null);
  };
  useLayoutEffect(() => {
    if (!nextCaret.current || inputRef.current === null) return;
    inputRef.current.focus();
    inputRef.current.setSelectionRange('@backport '.length, '@backport '.length);
    nextCaret.current = false;
  });
  if (!availability.any && !busy) return <AgentSetupNotice workspaceId={workspaceId} />;
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
      <Composer className="is-new-task" inputRef={inputRef} aria-label="Describe a task" placeholder="Let's ship something"
        value={draft} disabled={disabled} busy={busy} onSubmit={onCreate}
        submitEnabled={!busy && blocker === null && selection !== null && draft.trim() !== ''}
        submitLabel="Send — creates the task" submitTitle="Sends the first message, which creates the task"
        header={<>
          <ComposerAttachments items={images.map(image => ({ ...image, preview: <ImageAttachment url={image.url} name={image.name} compact /> }))}
            disabled={busy} onRemove={onRemoveImage} />
          <BackportRequestHint text={draft} />
          {showMention && <div className="agt-mentions" id={mentionId} role="listbox" aria-label="Task suggestions">
            <button type="button" id={`${mentionId}-backport`} role="option" aria-selected="true" tabIndex={-1}
              onMouseDown={event => event.preventDefault()} onClick={selectBackport}>
              <strong>@backport</strong><span>Backport a PR through related workspaces</span>
            </button>
          </div>}
        </>}
        aria-autocomplete={backport ? 'list' : undefined}
        aria-controls={showMention ? mentionId : undefined}
        aria-activedescendant={showMention ? `${mentionId}-backport` : undefined}
        onChange={event => { onDraftChange(event.target.value); updateMention(event.currentTarget); }}
        onFocus={event => updateMention(event.currentTarget)} onSelect={event => updateMention(event.currentTarget)}
        onBlur={() => setMention(null)}
        onCompositionStart={() => { composing.current = true; setMention(null); }}
        onCompositionEnd={event => { composing.current = false; updateMention(event.currentTarget); }}
        onPaste={event => pasteImage(event, imagesAccepted && blocker === null && !busy, onPasteImages)}
        onKeyDown={event => {
          if (!showMention) return;
          if (event.key === 'Escape') {
            event.preventDefault();
            dismissed.current = { value: draft, caret: event.currentTarget.selectionStart };
            setMention(null);
          }
          else if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey) {
            event.preventDefault(); selectBackport();
          }
          else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') event.preventDefault();
        }}>
        <ComposerModelControls models={models} model={selection?.model ?? null} effort={selection?.effort} serviceTier={selection?.serviceTier}
          providers={providers} laneId={laneId} onSelectProvider={onSelectProvider} onSelectModel={onSelectModel}
          onOpenSettings={onOpenSettings !== undefined || providers.some(provider => isDeepSeekKeyLane(provider.id)) ? openProviderSettings : undefined}
          disabled={busy} modelLabel="Model and effort for the new task" automation={automation}
          onAttach={onAttach} attachEnabled={imagesAccepted}
          attachTitle={imagesAccepted ? 'Attach images or paste with ⌘V' : 'This model does not accept images'} />
      </Composer>
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
type TypeTab = 'all' | TaskType | 'linked';
const TYPE_TABS: TypeTab[] = ['all', 'dev', 'review', 'cherry', 'ci', 'linked'];

function inType(item: TaskListItem, type: TypeTab): boolean {
  return type === 'all' || item.type === type;
}

export function AgentTaskList({
  workItemId, tasks, items = [], activity = {}, loading, error, headerAction, repository,
  onOpenTask, onOpenItem, onOpenReviewWorkspace, onNewItem, onArchive, onDelete,
  startup = null, now = Date.now(), initialTab = 'active', initialType = 'all', changes = {}, ...composer
}: TaskComposerProps & {
  workItemId?: string;
  tasks: AgentTaskDto[];
  /** The workspace's reviews, cherry-picks and CI fixes, listed beside its agent tasks. */
  items?: TaskListItem[];
  /** What each agent task last said and waits on, by task id. */
  activity?: Record<string, AgentTaskActivityDto>;
  loading: boolean;
  error: string | null;
  headerAction?: React.ReactNode;
  /** "owner/repo" new tasks start in. */
  repository?: string;
  onOpenTask: (taskId: string) => void;
  /** Opens a review, cherry-pick or CI fix. */
  onOpenItem?: (item: TaskListItem) => void;
  /** A review's own conversation, beside its pull request. */
  onOpenReviewWorkspace?: (review: AgentReviewDto) => void;
  /** Goes where a review, cherry-pick or CI fix begins; a development task begins in the composer below. */
  onNewItem?: (type: Exclude<TaskType, 'dev'>) => void;
  /** Shelves or restores the tasks; the caller updates its list as each answer arrives. */
  onArchive?: (ids: string[], archived: boolean) => Promise<void>;
  /** Removes the tasks for good; the caller drops each from its list as it goes. */
  onDelete?: (ids: string[]) => Promise<void>;
  /** Which creation step the in-flight create request is in, for the starting screen. */
  startup?: AgentTaskStartupPhase | null;
  /** The clock the day groups are drawn against. */
  now?: number;
  initialTab?: TaskTab;
  initialType?: TypeTab;
  /** What each task's checkout holds, by task id, for tasks without a pull request to name. */
  changes?: Record<string, AgentTaskChangesDto>;
}): React.JSX.Element {
  const { workspaceId, draft, images = [], laneId, providers, models, selection, busy = false, attaching = false, onDraftChange } = composer;
  const availability = useAgentAvailability(workspaceId);
  const linked = useLinkedWorkspaceWork(workspaceId);
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

  if (busy && !attaching) return <StartingAgentTask {...{ draft, images, laneId, providers, models, selection, startup, error, headerAction, workspaceId, workItemId }} />;

  const active = tab === 'active';
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
    if (task !== null) {
      return {
        now, you, selecting,
        checked: selected.includes(task.id),
        onOpen: () => onOpenTask(task.id),
        onToggle: () => toggle(task.id),
        onArchive: (archived) => archive([task.id], archived),
        onDelete: () => requestDelete([task.id]),
      };
    }
    return {
      now, you, selecting,
      checked: false,
      onOpen: () => onOpenItem?.(item),
      onOpenWorkspace: review === null || onOpenReviewWorkspace === undefined ? undefined : () => onOpenReviewWorkspace(review),
    };
  };
  const startNew = (event: React.MouseEvent<HTMLButtonElement>): void => {
    if (type === 'all' || type === 'dev') {
      event.currentTarget.closest('.agt-page')?.querySelector<HTMLTextAreaElement>('.agt-compose__input')?.focus();
    }
    else if (type !== 'linked') onNewItem?.(type);
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
                <span className={`agt-type-tab__icon is-${key}`}>{key === 'linked' ? <Link2 size={14} /> : <TaskTypeIcon type={key} />}</span>
                {key === 'linked' ? 'Linked work' : key === 'all' ? 'All' : TASK_TYPES[key].label}
                <span className="agt-type-tab__count">{key === 'linked' ? linkedShelf.length : shelf.filter((item) => inType(item, key)).length}</span>
                {shelf.some((item) => inType(item, key) && item.state.label === 'Needs you') && <span className="agt-type-tab__needs" title="Needs you" />}
              </button>
            ))}
          </div>
          {active && (type === 'review' ? availability.review : availability.any) && type !== 'linked' && (
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
        {type === 'linked' && <LinkedWorkList links={linkedShelf} error={linked.error} />}
        {type !== 'linked' && !loading && visible.length === 0 && (active ? (
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
        {active && availability.any && (
          <ComposerDock repository={repository} chips={<BackportButton draft={draft} onDraftChange={onDraftChange} disabled={busy} />}>
            <TaskComposer {...composer} backport />
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
