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

import React, { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import { ConfirmDialog } from '../ConfirmDialog';
import type { IdeUnsavedChanges } from '../ide/contracts';
import UnsavedChanges from '../ide/UnsavedChanges';
import type { AttentionItem } from '../home/attention';
import { useAttentionVisit } from '../home/useAttentionVisit';
import type { DiffAgentComment } from '../pulls/PullChanges';
import { carriedPanelTab } from '../pulls/PullsScreen';
import { useLocalReviewThreads } from '../pulls/local-review/useLocalReviewThreads';
import { pullRequestDiffRevision, usePullRequestChanges } from '../pulls/usePullRequestChanges';
import { gitHubRetryAt, usePullRequestDetail } from '../pulls/usePullRequestDetail';
import { errorMessage } from '../errorMessage';
import { isDeepSeekKeyLane, laneLabel, providerAvailable } from '../settings/model';
import { settingsRoute } from '../settings/routes';
import type {
  AgentApprovalDecision,
  AgentCapabilitiesDto,
  AgentPendingApprovalDto,
  AgentProviderStatus,
  AgentProviderUnavailableDto,
  AgentQueuedInputDto,
  AgentRuntimeStopImpactDto,
  AgentTaskDto,
  AgentTurnDto,
  DevelopmentAutomationDto,
  InlineReviewCommentDto,
  ModelOptionDto,
  PullRequestDto,
  WorkspaceDto
} from '../types';
import { useNow } from '../useNow';
import type { AttachmentPreview } from './AgentConversation';
import { AgentTaskBody } from './AgentTaskBody';
import DevelopmentLearningPanel from './DevelopmentLearningPanel';
import type { ReviewCardFocus } from './InstructionCard';
import ProviderSignInDialog from './ProviderSignInDialog';
import { taskPullRequest } from './TaskPullRequestPanel';
import type { PanelTab, UtilityTab } from './TaskSidePanel';
import { taskAttentionAfterQueue, taskAttentionItem } from './attention';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { ciMessage, ciMessageUnavailable } from './ciAutofix';
import { agentProviderUnavailableFrom, alreadySent, CHECKING_ACCEPTANCE, unconfirmedRequest } from './contracts';
import { developmentQuestion, includesLevel, LOCAL_COMMITS_ONLY, stoppingEndsDevelopment } from './developmentAutomation';
import { revealPassage } from './discussions/DiscussableAnswer';
import { useTaskDiscussions } from './discussions/useTaskDiscussions';
import {
  abandonsUncertainInput,
  acceptsImages,
  agentTaskReducer,
  canComplete,
  canQueue,
  canSend,
  canSteer,
  createDraftSubmissionTracker,
  historyAfter,
  historyBefore,
  initialAgentTaskState,
  readRecentAgentHistory,
  retryableInput,
  runtimeStatus,
  startAgentTaskSession,
  unloadedTurnIds,
  type AgentBridgePort,
  type DraftSubmissionTracker
} from './model';
import { prepareAgentInput, usePreparedAgentInput } from './preparedInput';
import { commentFixPrompt, requestTaskFix, type FixableComment } from './taskFix';
import { projectTranscript } from './transcript';

interface AgentTaskScreenProps {
  workspaceId: string;
  taskId: string;
  /** Electron's bridge unless a test supplies its own. */
  bridge?: AgentBridgePort;
  onBack: () => void;
  /** Opens the pull request found on the task's branch full width over the task. */
  onOpenPullRequest?: (number: number) => void;
  /** The workspace the task runs in; names the repository a draft pull request targets. */
  workspace?: WorkspaceDto;
  /** Opens the provider settings used by authentication recovery. */
  onOpenSettings?: () => void;
  headerAction?: React.ReactNode;
}

type TaskConfirmation = { kind: 'stop'; impact: AgentRuntimeStopImpactDto; automation: boolean }
  | { kind: 'fail' };

function failureText(failure: unknown): string {
  const unavailable = agentProviderUnavailableFrom(failure);
  if (unavailable !== null) {
    const command = unavailable.signInCommand ? ` Run: ${unavailable.signInCommand}` : '';
    return `${laneLabel(unavailable.laneId)} is not ready — ${unavailable.diagnostic}${command}`;
  }
  const message = errorMessage(failure);
  return message.startsWith('Task version changed:') ? 'This task changed in the background. Your input is preserved; try again.' : message;
}

/** Replaces a possibly stale READY row with the typed refusal returned by the turn preflight. */
export function providerStatusFromUnavailable(
  unavailable: AgentProviderUnavailableDto,
  current: AgentProviderStatus | null,
  fallbackCapabilities: AgentCapabilitiesDto,
): AgentProviderStatus {
  const sameLane = current?.laneId === unavailable.laneId;
  return {
    laneId: unavailable.laneId,
    readiness: unavailable.readiness,
    version: sameLane ? current.version : null,
    diagnostic: unavailable.diagnostic,
    capabilities: sameLane ? current.capabilities : fallbackCapabilities,
    signInCommand: unavailable.signInCommand,
  };
}

export default function AgentTaskScreen({
  workspaceId,
  taskId,
  bridge: explicitBridge,
  onBack,
  onOpenPullRequest,
  workspace,
  onOpenSettings,
  headerAction,
}: AgentTaskScreenProps): React.JSX.Element {
  const [state, dispatch] = useReducer(agentTaskReducer, undefined, initialAgentTaskState);
  const [models, setModels] = useState<ModelOptionDto[]>([]);
  const [provider, setProvider] = useState('');
  const [providerStatus, setProviderStatus] = useState<AgentProviderStatus | null>(null);
  const [keyRemoved, setKeyRemoved] = useState(false);
  const [signingIn, setSigningIn] = useState<{ laneId: string; providerName: string } | null>(null);
  const [providerStatusTurnId, setProviderStatusTurnId] = useState<string | null>(null);
  const [automationDefaults, setAutomationDefaults] = useState<DevelopmentAutomationDto | null>(null);
  const [providerChecking, setProviderChecking] = useState(false);
  const [confirmation, setConfirmation] = useState<TaskConfirmation | null>(null);
  const [confirming, setConfirming] = useState(false);
  /** A question the screen asks in its own dialog before running the action it holds. */
  const [ask, setAsk] = useState<{ title: string; body: React.ReactNode; label: string; act: () => void } | null>(null);
  const confirmationPendingRef = useRef(false);
  const confirmationRequestRef = useRef(0);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const editingQueuedRef = useRef(false);
  const [attaching, setAttaching] = useState(false);
  const attachmentPendingRef = useRef(false);
  // Fold choices are the user's, kept here so a live update can never override them.
  const [activityChoice, setActivityChoice] = useState<Record<string, boolean>>({});
  const [turnChoice, setTurnChoice] = useState<Record<string, boolean>>({});
  const [planOpen, setPlanOpen] = useState(true);
  // A switch from the reviewer keeps its panel open on the same tab; only the conversation changes.
  const [panelChoice, setPanelChoice] = useState<boolean | null>(() => carriedPanelTab() === null ? null : true);
  // Unsaved Tears of time drafts in the side panel are dropped when the panel closes or the task screen is left.
  const codeUnsaved = useRef<IdeUnsavedChanges>({ files: [], drafts: 0 });
  const trackCodeUnsaved = useCallback((changes: IdeUnsavedChanges) => { codeUnsaved.current = changes; }, []);
  const withCodeDrafts = (act: () => void): void => {
    if (codeUnsaved.current.files.length === 0 && codeUnsaved.current.drafts === 0) { act(); return; }
    setAsk({
      title: 'Discard unsaved changes?',
      body: <UnsavedChanges {...codeUnsaved.current} />,
      label: 'Discard',
      act,
    });
  };
  const [ciPollingError, setCiPollingError] = useState<string | null>(null);
  const [openAgent, setOpenAgent] = useState<string | null>(null);
  const [panelTab, setPanelTab] = useState<PanelTab>(() => {
    const carried = carriedPanelTab();
    return carried === null ? 'changes' : carried === 'overview' ? 'pr' : carried;
  });
  const [utilityTabs, setUtilityTabs] = useState<UtilityTab[]>([]);
  // The view a Subagents or Discussions tab covers, shown again when that tab closes.
  const coveredTab = useRef<PanelTab>('changes');
  if (panelTab !== 'agents' && panelTab !== 'discussions') coveredTab.current = panelTab;
  const openUtility = (tab: UtilityTab): void => {
    setUtilityTabs(open => (open.includes(tab) ? open : [...open, tab]));
    setPanelChoice(true);
    setPanelTab(tab);
  };
  // A fresh object per request: asking for the same discussion twice opens its card again.
  const [discussionFocus, setDiscussionFocus] = useState<{ id: string } | null>(null);
  const [reviewFocus, setReviewFocus] = useState<ReviewCardFocus | null>(null);
  const [logChoice, setLogChoice] = useState<string | null>(null);
  const [pullRequests, setPullRequests] = useState<PullRequestDto[]>([]);
  const pullRequestsShown = useRef(false);
  pullRequestsShown.current = pullRequests.length > 0;
  const [pullRequestError, setPullRequestError] = useState<string | null>(null);
  const [pullRequestLoading, setPullRequestLoading] = useState(false);
  const [pullRequestRefresh, setPullRequestRefresh] = useState(0);
  const pullRequestRefreshSeen = useRef(0);
  const [pullRequestRetry, setPullRequestRetry] = useState(0);
  // Whether a lookup has ever finished, so a later one refreshes under what is on screen instead
  // of replacing it with "Checking for a pull request…" again.
  const pullRequestChecked = useRef(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const historyRequestRef = useRef<symbol | null>(null);
  const followingHistory = useRef(true);
  const [pendingApprovals, setPendingApprovals] = useState<AgentPendingApprovalDto[] | null>(null);
  const [ciApprovalState, setCiApprovalState] = useState<{ runId: string; approvals: AgentPendingApprovalDto[]; error: string | null } | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;
  // Object URLs are browser resources: created when an image's bytes arrive, revoked when the
  // screen goes away or a draft is dropped, never left for the garbage collector to find.
  const [previews, setPreviews] = useState<Record<string, AttachmentPreview>>({});
  const previewsRef = useRef<Record<string, AttachmentPreview>>({});
  const ownedPreviewUrls = useRef(new Set<string>());
  const previewWork = useRef<Promise<void>>(Promise.resolve());
  previewsRef.current = previews;
  const running = runtimeStatus(state) === 'running';
  // The clock only ticks while a turn runs or a merge counts down; a finished turn's elapsed time is fixed by its events.
  const now = useNow(running || state.task?.autoMerge?.state === 'READY');
  // Resolved when used, not when rendered: the product route reaches Electron's bridge, and a
  // static render has no window to reach.
  const live = useCallback((): AgentBridgePort => explicitBridge ?? window.bridge, [explicitBridge]);
  const mountedRef = useRef(true);
  const actionRequestRef = useRef<symbol | null>(null);
  const sendRequestRef = useRef<symbol | null>(null);
  const currentTaskId = useRef(taskId);
  currentTaskId.current = taskId;
  const projectionRevisionRef = useRef(0);
  const submissionRef = useRef<DraftSubmissionTracker | null>(null);
  if (submissionRef.current === null) submissionRef.current = createDraftSubmissionTracker();
  const submissions = submissionRef.current;
  usePreparedAgentInput(taskId, text => {
    const draft = stateRef.current;
    const message = [draft.composer, text].filter(Boolean).join('\n\n');
    submissions.draftChanged(taskId, message, draft.attachments.map(attachment => attachment.id));
    dispatch({ type: 'composer/changed', text: message });
    composerRef.current?.focus();
  });
  const inlineSending = useRef(false);
  const ciSending = useRef(false);
  const ciRequest = useRef<{ runId: string; message: string; key: string; expectedTurnId?: string } | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    // A hidden page comes back here keeping its state: a send the cleanup below orphaned no longer owns the composer,
    // and the reloaded history shows whatever it delivered.
    if (stateRef.current.sending) dispatch({ type: 'composer/sending', sending: false });
    if (stateRef.current.pendingMessage !== null) dispatch({ type: 'message/cleared' });
    const projectionRevision = projectionRevisionRef;
    return () => {
      mountedRef.current = false;
      actionRequestRef.current = null;
      sendRequestRef.current = null;
      projectionRevision.current++;
    };
  }, [taskId, workspaceId]);

  /** Reads one coherent server projection; a later request or mutation invalidates its result. */
  const refreshProjection = useCallback(async (turnId: string | null) => {
    const projectionRevision = ++projectionRevisionRef.current;
    const [task, turn, queue] = await Promise.all([
      live().getAgentTask(taskId, workspaceId),
      turnId === null ? Promise.resolve(null) : live().getAgentTurn(turnId),
      live().listAgentQueue(taskId, workspaceId),
    ]);
    if (!mountedRef.current || currentTaskId.current !== taskId || projectionRevision !== projectionRevisionRef.current) return;
    dispatch({ type: 'task/loaded', task });
    if (turn !== null) dispatch({ type: 'turn/changed', turn });
    dispatch({ type: 'queue/loaded', queue });
  }, [live, taskId, workspaceId]);

  /** Makes an accepted mutation authoritative immediately, even if an older read is still open. */
  const applyQueuedInput = useCallback((input: AgentQueuedInputDto) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'queue/upserted', input });
  }, []);

  const removeQueuedInput = useCallback((queuedInputId: string) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'queue/removed', queuedInputId });
  }, []);

  /** Task versions reject stale reads without discarding an unrelated queue refresh. */
  const applyTask = useCallback((task: AgentTaskDto) => {
    dispatch({ type: 'task/loaded', task });
  }, []);

  const applyTurn = useCallback((turn: AgentTurnDto) => {
    projectionRevisionRef.current++;
    dispatch({ type: 'turn/changed', turn });
  }, []);

  const taskKeyLane = isDeepSeekKeyLane(state.task?.laneId ?? '');
  const automationEligible = state.task?.interactive === true;
  useEffect(() => {
    if (!automationEligible) return;
    let active = true;
    void live().getDevelopmentAutomation().then(value => { if (active) setAutomationDefaults(value); })
      .catch(failure => { if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) }); });
    return () => { active = false; };
  }, [automationEligible, live]);
  const automationSelection = state.task?.defaults.developmentAutomation ?? automationDefaults;
  const automationBlocked = automationEligible && automationSelection === null;
  const submissionAutomation = automationEligible && automationSelection !== null ? automationSelection : undefined;

  const taskLaneId = state.task?.laneId ?? null;
  useEffect(() => {
    let active = true;
    historyRequestRef.current = null;
    setHistoryLoading(false);
    // Streaming pauses only while an older page is on screen: reading earlier in the live page still
    // gets the output, held in place by the transcript's reading anchor.
    const session = startAgentTaskSession(live(), taskId, workspaceId, dispatch, () => active,
      () => historyAfter(stateRef.current) === null);
    session.catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => {
      active = false;
      historyRequestRef.current = null;
      void session.then((stop) => stop(), () => {});
    };
  }, [live, taskId, workspaceId]);

  const loadHistory = useCallback(async (direction: 'older' | 'newer' | 'latest') => {
    if (historyRequestRef.current !== null && direction !== 'latest') return;
    const current = stateRef.current;
    const before = direction === 'older' ? historyBefore(current) : null;
    const after = direction === 'newer' ? historyAfter(current) : null;
    if ((direction === 'older' && before === null) || (direction === 'newer' && after === null)) return;
    const request = Symbol();
    historyRequestRef.current = request;
    const isCurrent = () => mountedRef.current && historyRequestRef.current === request;
    setHistoryLoading(true);
    followingHistory.current = direction === 'latest';
    try {
      const history = direction === 'latest'
        ? await readRecentAgentHistory(live(), taskId)
        : { page: await live().readAgentHistory(taskId, before ?? undefined, after ?? undefined) };
      if (isCurrent()) dispatch({ type: 'history/loaded', ...history, direction });
    }
    catch (failure) {
      if (isCurrent()) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    }
    finally {
      if (isCurrent()) {
        historyRequestRef.current = null;
        setHistoryLoading(false);
      }
    }
  }, [live, taskId]);

  const authenticationTurnId = state.turn?.failureKind === 'AUTHENTICATION_REQUIRED'
    ? state.turn.id
    : null;
  useEffect(() => {
    setProviderStatusTurnId(null);
  }, [authenticationTurnId, taskLaneId]);

  useEffect(() => {
    let active = true;
    void (async () => {
      const [catalog, effective] = await Promise.all([
        live().getModelSettings(workspaceId),
        live().getEffectiveTurnSettings(taskId, workspaceId),
      ]);
      if (!active) return;
      const lane = catalog.providers.find((candidate) => candidate.id === taskLaneId);
      setModels(lane?.models ?? []);
      setProvider(lane?.label ?? (taskKeyLane ? 'DeepSeek' : ''));
      setKeyRemoved(lane === undefined && taskKeyLane);
      if (lane !== undefined) {
        dispatch({ type: 'capabilities/loaded', capabilities: lane.capabilities });
        setProviderStatus({
          laneId: lane.id,
          readiness: lane.readiness,
          version: lane.version,
          diagnostic: lane.diagnostic,
          capabilities: lane.capabilities,
          signInCommand: lane.signInCommand,
        });
      }
      else setProviderStatus(null);
      dispatch({ type: 'settings/loaded', settings: effective });
    })().catch(() => { /* the composer then shows no catalog, which the list already explains */ });
    return () => { active = false; };
  }, [live, taskId, taskKeyLane, taskLaneId, workspaceId]);

  useEffect(() => {
    void refreshProjection(null).catch((failure: unknown) => {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
  }, [refreshProjection]);

  // Each message shows the settings its turn ran with, which only the durable turn records.
  const unloaded = unloadedTurnIds(state).join(',');
  // After a failed read the messages show as sent rather than staying hidden.
  const [failedTurns, setFailedTurns] = useState('');
  useEffect(() => {
    if (unloaded === '') return;
    let active = true;
    void Promise.all(unloaded.split(',').map((turnId) => live().getAgentTurn(turnId)))
      .then((turns) => {
        if (!active) return;
        turns.forEach((turn) => dispatch({ type: 'turn/loaded', turn }));
      })
      .catch((failure: unknown) => {
        if (!active) return;
        setFailedTurns(unloaded);
        dispatch({ type: 'notice/changed', notice: failureText(failure) });
      });
    return () => { active = false; };
  }, [live, unloaded]);

  // Lifecycle and root-plan updates change durable task/turn facts, including the plan retained
  // beyond the transcript window, so each triggers a re-read.
  const { lifecycleEpoch } = state;
  const latestTurnId = state.task?.mutationClaim?.kind === 'TURN' ? state.task.mutationClaim.ownerId : state.latestTurnId;
  const viewedItem = useRef<AttentionItem | null>(null);
  if (viewedItem.current !== null
    && (viewedItem.current.task?.dto.id !== taskId || viewedItem.current.workspaceId !== workspaceId)) viewedItem.current = null;
  if (state.task?.id === taskId && state.task.workspaceId === workspaceId && state.connection === 'open'
    && (latestTurnId === null || (state.turn?.id === latestTurnId && pendingApprovals !== null))) {
    // A transient reload must not end the visit or forget the activity already shown.
    viewedItem.current = taskAttentionItem(state.task, {
      lastMessage: null,
      latestTurn: state.turn,
      pendingApproval: pendingApprovals?.reduce<AgentPendingApprovalDto | null>((latest, approval) =>
        latest === null || approval.occurredAt > latest.occurredAt ? approval : latest, null) ?? null,
    }, {
      id: workspaceId,
      name: workspace?.name ?? '',
      repository: workspace === undefined ? '' : `${workspace.monitoredOwner}/${workspace.monitoredRepo}`,
    });
  }
  const { status: attentionStatus, onAction: attentionAction } = useAttentionVisit(viewedItem.current);
  useEffect(() => {
    if (latestTurnId === null && lifecycleEpoch === 0) return;
    void refreshProjection(latestTurnId).catch((failure: unknown) => {
      if (mountedRef.current) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
  }, [refreshProjection, lifecycleEpoch, latestTurnId]);

  useEffect(() => {
    let active = true;
    setPendingApprovals(null);
    if (latestTurnId === null) return;
    void live().readAgentPendingApprovals(taskId, latestTurnId).then((approvals) => {
      if (active) setPendingApprovals(approvals);
    }).catch((failure: unknown) => {
      if (active) dispatch({ type: 'notice/changed', notice: failureText(failure) });
    });
    return () => { active = false; };
  }, [live, taskId, latestTurnId, lifecycleEpoch]);

  // Any ordinary turn can publish a PR, including a turn started outside the PR panel.
  const taskLoaded = state.task !== null;
  const pullRequestTurnId = state.turn?.id ?? null;
  const pullRequestTurnEndedAt = state.turn?.endedAt ?? null;
  const pullRequestTurnKnown = latestTurnId === null || latestTurnId === pullRequestTurnId;
  const taskPr = taskPullRequest(pullRequests, state.task?.pullRequest?.number);
  // A task whose branch already has a pull request opens on it; from the first deliberate
  // open or close the panel is the user's, until they leave the task.
  const panelOpen = panelChoice ?? taskPr !== null;
  const canRefreshPullRequests = taskLoaded && state.connection === 'open' && pullRequestTurnKnown && !running;
  const continuationUnfinished = state.task?.developmentContinuationUnfinished ?? false;
  const ciLive = state.task?.developmentCi != null && state.task.developmentCi.status !== 'FINISHED';
  const pollContinuation = (continuationUnfinished || ciLive) && state.task?.state === 'OPEN';
  useEffect(() => {
    if (!pollContinuation) { setCiApprovalState(null); return; }
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = async () => {
      try {
        const task = await live().getAgentTask(taskId, workspaceId);
        if (!active) return;
        dispatch({ type: 'task/loaded', task });
        setCiPollingError(null);
        if (task.state !== 'OPEN' || (!task.developmentContinuationUnfinished && (task.developmentCi == null || task.developmentCi.status === 'FINISHED'))) {
          setCiApprovalState(null);
          return;
        }
        const ci = task.developmentCi;
        if (ci?.status === 'ACTIVE' && ci.agentTaskId !== null) {
          try {
            const run = await live().getCiAutofixRun(ci.runId);
            if (!active) return;
            if (run.id !== ci.runId || run.parentTaskId !== taskId || run.workspaceId !== workspaceId || run.agentTaskId !== ci.agentTaskId) {
              throw new Error('CI run no longer belongs to this task');
            }
            const approvals = run.currentTurnId === null ? [] : await live().readAgentPendingApprovals(ci.agentTaskId, run.currentTurnId);
            if (active) setCiApprovalState({ runId: ci.runId, approvals, error: null });
          } catch (failure) {
            if (active) setCiApprovalState({ runId: ci.runId, approvals: [], error: `Could not load CI approvals. ${failureText(failure)}` });
          }
        } else setCiApprovalState(null);
      } catch (failure) {
        if (active) setCiPollingError(`Could not refresh CI activity. ${failureText(failure)}`);
      }
      if (active) timer = setTimeout(() => { void read(); }, 5000);
    };
    timer = setTimeout(() => { void read(); }, 5000);
    return () => { active = false; clearTimeout(timer); };
  }, [live, taskId, workspaceId, pollContinuation]);
  const [ciStopping, setCiStopping] = useState(false);
  const stopCi = useCallback(async () => {
    const ci = stateRef.current.task?.developmentCi;
    if (ci == null || ci.status === 'FINISHED') return;
    setCiStopping(true);
    try {
      await live().cancelCiAutofixRun(ci.runId);
      const task = await live().getAgentTask(taskId, workspaceId);
      if (!mountedRef.current) return;
      dispatch({ type: 'task/loaded', task });
      setCiPollingError(null);
    } catch (failure) {
      if (mountedRef.current) setCiPollingError(`Could not stop CI Autofix. ${failureText(failure)}`);
    } finally {
      if (mountedRef.current) setCiStopping(false);
    }
  }, [live, taskId, workspaceId]);
  const cancelAutoMerge = useCallback(() => {
    live().cancelAgentAutoMerge(taskId, workspaceId)
      .then(task => { if (mountedRef.current) dispatch({ type: 'task/loaded', task }); })
      .catch(failure => { if (mountedRef.current) dispatch({ type: 'notice/changed', notice: `Could not stop auto-merge. ${failureText(failure)}` }); });
  }, [live, taskId, workspaceId]);
  // Review fixes and merges move in the background, so a task that uses them reads its status again every half minute.
  const pollDelivery = !pollContinuation && state.task?.state === 'OPEN' && automationSelection !== null
    && includesLevel(automationSelection.level, 'REVIEW_FIX');
  useEffect(() => {
    if (!pollDelivery) return;
    const timer = setInterval(() => {
      live().getAgentTask(taskId, workspaceId).then(task => { if (mountedRef.current) dispatch({ type: 'task/loaded', task }); }).catch(() => {});
    }, 30_000);
    return () => clearInterval(timer);
  }, [live, taskId, workspaceId, pollDelivery]);
  useEffect(() => {
    if (!taskLoaded) return;
    let active = true;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const asked = pullRequestRefresh !== pullRequestRefreshSeen.current;
    pullRequestRefreshSeen.current = pullRequestRefresh;
    // Counts the pull request a previous lookup put on screen, so an unreachable backend stays quiet too.
    let shown = pullRequestsShown.current;
    setPullRequestLoading(true);
    const lookup = async (): Promise<void> => {
      const cached = await live().listAgentTaskPullRequests(taskId, workspaceId);
      if (!active) return;
      setPullRequests(cached);
      shown = cached.length > 0;
      // Saved associations are visible during a turn; remote discovery waits for it to settle.
      if (!canRefreshPullRequests) return;
      const found = await live().listAgentTaskPullRequests(taskId, workspaceId, true);
      if (active) {
        setPullRequests(found);
        setPullRequestError(null);
      }
    };
    void lookup()
      .catch((failure: unknown) => {
        if (!active) return;
        // A check nobody asked for stays quiet while the pull request is on screen, and one GitHub
        // paused for its rate limit runs again once the pause ends.
        if (asked || !shown) setPullRequestError(errorMessage(failure));
        const retryAt = gitHubRetryAt(failure);
        if (retryAt !== null) retry = setTimeout(() => setPullRequestRetry(value => value + 1), Math.max(0, retryAt - Date.now()) + 1_000);
      })
      .finally(() => {
        if (!active) return;
        pullRequestChecked.current = true;
        setPullRequestLoading(false);
      });
    return () => {
      active = false;
      clearTimeout(retry);
    };
  }, [live, taskLoaded, canRefreshPullRequests, pullRequestTurnId, pullRequestTurnEndedAt, pullRequestRefresh, pullRequestRetry, taskId, workspaceId,
    continuationUnfinished]);

  // The panel reads the pull request itself, so the detail and its diff are read here rather
  // than by the body, which stays a pure function of the state a test can hand it.
  // Every panel tab keeps the shared PR toolbar's permissions and status current.
  const detailSource = usePullRequestDetail(taskPr, {
    active: panelOpen,
    onDetail: () => { pullRequestChanges.retryFailedRead(); },
    onStatus: () => { pullRequestChanges.retryFailedRead(); },
  });
  const diffRevision = pullRequestDiffRevision(detailSource.detail, taskPr?.headPushedAt);
  const pullRequestChanges = usePullRequestChanges(taskPr, {
    active: panelOpen && (panelTab === 'files' || panelTab === 'changes'),
    revision: diffRevision,
  });
  const pullRequestDetail = {
    ...detailSource,
    refresh: async () => {
      await detailSource.refresh();
      if (currentReviewKey.current === reviewKey) pullRequestChanges.refresh();
    },
  };
  const pullRequestCommitRange = pullRequestChanges.commitRange;
  // A staged review is the reader's unsent writing, so it lasts as long as the panel shows this
  // pull request. Nothing the pull request itself does — a new head, a refresh, a narrowed commit
  // range — may throw it away; a comment written against an older head carries that head's SHA and
  // is refused at submission instead.
  const reviewKey = taskPr === null ? null : `${taskPr.repo.toLowerCase()}#${taskPr.number}`;
  const currentReviewKey = useRef(reviewKey);
  currentReviewKey.current = reviewKey;
  const [pullRequestReview, setPullRequestReview] = useState<{
    key: string | null;
    pendingComments: InlineReviewCommentDto[];
  }>({ key: reviewKey, pendingComments: [] });
  const pullRequestPendingComments = pullRequestReview.key === reviewKey ? pullRequestReview.pendingComments : [];

  /** Runs one bridge command, surfacing its failure in the notice strip instead of losing it. */
  const run = useCallback(async (action: (current: () => boolean) => Promise<void>) => {
    const request = Symbol();
    actionRequestRef.current = request;
    projectionRevisionRef.current++;
    const current = () => mountedRef.current && currentTaskId.current === taskId && actionRequestRef.current === request;
    dispatch({ type: 'notice/changed', notice: null });
    try {
      await action(current);
    }
    catch (failure) {
      if (!current()) return;
      dispatch({ type: 'notice/changed', notice: unconfirmedRequest(failure) ? CHECKING_ACCEPTANCE : failureText(failure) });
      // Another window or a background worktree operation can change durable task facts. Re-read them while
      // keeping the draft and its retry identity intact, so the next deliberate action can use
      // the newly observed version instead of conflicting forever.
      try {
        await refreshProjection(stateRef.current.latestTurnId);
      }
      catch {
        // The command's original failure is the useful notice; the live stream can reconnect the
        // projection later if this best-effort read also failed.
      }
    }
  }, [refreshProjection, taskId]);

  const refreshProviderStatus = useCallback(async (): Promise<AgentProviderStatus | null> => {
    const laneId = stateRef.current.task?.laneId;
    if (laneId === undefined) return null;
    const observedTurn = stateRef.current.turn;
    const observedAuthenticationTurnId = observedTurn?.failureKind === 'AUTHENTICATION_REQUIRED'
      ? observedTurn.id
      : null;
    const statuses = await live().refreshAgentProviderReadiness();
    const status = statuses.find((candidate) => candidate.laneId === laneId);
    if (status === undefined && isDeepSeekKeyLane(laneId)) {
      setKeyRemoved(true);
      setProviderStatus(null);
      setProviderStatusTurnId(null);
      return null;
    }
    if (status === undefined) throw new Error(`${laneLabel(laneId)} readiness was not returned`);
    setKeyRemoved(false);
    setProviderStatus(status);
    const currentTurn = stateRef.current.turn;
    setProviderStatusTurnId(currentTurn?.failureKind === 'AUTHENTICATION_REQUIRED'
      && currentTurn.id === observedAuthenticationTurnId
      ? currentTurn.id
      : null);
    dispatch({ type: 'capabilities/loaded', capabilities: status.capabilities });
    return status;
  }, [live]);

  const recordProviderUnavailable = useCallback((failure: unknown): boolean => {
    const unavailable = agentProviderUnavailableFrom(failure);
    if (unavailable === null) return false;
    setProviderStatus((current) => providerStatusFromUnavailable(
      unavailable,
      current,
      stateRef.current.capabilities ?? NO_AGENT_CAPABILITIES,
    ));
    setProviderStatusTurnId(null);
    if (isDeepSeekKeyLane(unavailable.laneId)) {
      void live().getModelSettings(workspaceId).then(catalog => {
        if (mountedRef.current) setKeyRemoved(!catalog.providers.some(provider => provider.id === unavailable.laneId));
      }).catch(() => {});
    }
    return true;
  }, [live, workspaceId]);

  const selectModel = useCallback((model: string, effort: string | null, serviceTier: string | null) => run(async () => {
    const task = state.task;
    if (task === null) return;
    // The response carries the bumped version; keeping the old one conflicts on the next change.
    const updated = await live().updateAgentTaskDefaults(taskId, workspaceId, task.version, {
      model,
      effort,
      serviceTier,
      permissionPolicy: task.defaults.permissionPolicy,
      developmentAutomation: task.defaults.developmentAutomation,
    });
    attentionAction('update');
    applyTask(updated);
    dispatch({
      type: 'settings/loaded',
      settings: await live().getEffectiveTurnSettings(taskId, workspaceId),
    });
  }), [applyTask, attentionAction, live, run, state.task, taskId, workspaceId]);

  const rename = useCallback((title: string) => run(async () => {
    const task = state.task;
    if (task === null) return;
    applyTask(await live().renameAgentTask(taskId, workspaceId, task.version, title));
    attentionAction('update');
  }), [applyTask, attentionAction, live, run, state.task, taskId, workspaceId]);

  const archive = useCallback((archived: boolean) => run(async () => {
    const task = state.task;
    if (task === null) return;
    applyTask(await live().archiveAgentTask(taskId, workspaceId, task.version, archived));
    attentionAction('update');
  }), [applyTask, attentionAction, live, run, state.task, taskId, workspaceId]);

  const startTurn = useCallback(async (message: string, attachments: string[], idempotencyKey: string, expectedTaskVersion: number, developmentAutomation?: DevelopmentAutomationDto) => {
    const request = actionRequestRef.current;
    const submission = sendRequestRef.current;
    const current = () => mountedRef.current && currentTaskId.current === taskId && actionRequestRef.current === request;
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const turn = await live().startAgentTurn({
        taskId,
        message,
        expectedTaskVersion,
        developmentAutomation,
        idempotencyKey,
        attachments,
      });
      submissions.settled(idempotencyKey);
      if (!current()) return turn;
      attentionAction('prompt');
      applyTurn(turn);
      try {
        // Starting also bumps the task version. Keep the composer busy until that authoritative
        // version is adopted, otherwise an immediate Run next would submit the pre-start version.
        await refreshProjection(turn.id);
      }
      catch {
        // The accepted turn is still authoritative; lifecycle replay can repair this projection.
      }
      return turn;
    }
    catch (failure) {
      if (current()) recordProviderUnavailable(failure);
      throw failure;
    }
    finally {
      if (sendRequestRef.current === submission) sendRequestRef.current = null;
      if (current()) dispatch({ type: 'composer/sending', sending: false });
    }
  }, [applyTurn, attentionAction, live, recordProviderUnavailable, refreshProjection, submissions, taskId]);

  const send = useCallback(() => {
    // Repeated Enter before React commits must not replace the action whose failure we are awaiting.
    if (sendRequestRef.current !== null) return Promise.resolve();
    const request = Symbol();
    sendRequestRef.current = request;
    return run(async current => {
      const draft = stateRef.current;
      const ciText = ciMessage(draft.composer);
      if (ciText !== null) {
        if (ciSending.current || attachmentPendingRef.current || confirmation !== null || confirming || ciPollingError !== null || ciMessageUnavailable(draft) !== null) return;
        const ci = draft.task?.developmentCi;
        if (ci == null) return;
        ciSending.current = true;
        dispatch({ type: 'composer/sending', sending: true });
        if (ciRequest.current?.runId !== ci.runId || ciRequest.current.message !== ciText) {
          ciRequest.current = { runId: ci.runId, message: ciText, key: crypto.randomUUID(),
            expectedTurnId: ci.status === 'ACTIVE' ? ci.currentTurnId ?? undefined : undefined };
        }
        try {
          // Retain both the delivery key and target on retry, even if polling shows a later repair turn.
          const result = await live().sendCiAutofixMessage(ci.runId, ciText, ciRequest.current.key, ciRequest.current.expectedTurnId);
          if (!mountedRef.current) return;
          if (result.id !== ci.runId || result.parentTaskId !== taskId || result.workspaceId !== workspaceId) {
            throw new Error('CI run does not belong to this development task');
          }
          attentionAction('prompt');
          ciRequest.current = null;
          dispatch({ type: 'composer/changed', text: '' });
          try { await refreshProjection(stateRef.current.latestTurnId); }
          catch { /* The CI service accepted the message; task polling will refresh its activity. */ }
        } finally {
          ciSending.current = false;
          if (sendRequestRef.current === request) sendRequestRef.current = null;
          if (mountedRef.current) dispatch({ type: 'composer/sending', sending: false });
        }
        return;
      }
      if (attachmentPendingRef.current || automationBlocked || !canSend(stateRef.current)) return;
      const message = state.composer.trim();
      if (ciMessage(message) !== null) return;
      const task = state.task;
      if (message.length === 0 || task === null) return;
      const attachmentIds = state.attachments.map((attachment) => attachment.id);
      const idempotencyKey = submissions.keyFor('send', taskId, taskId, message, attachmentIds);
      // The draft moves into the transcript at once, as local progress its durable user-message event
      // replaces; a development turn's request can return seconds after that event. Restoring the draft
      // without draftChanged keeps its idempotency key for a retry.
      const shownTurnId = state.latestTurnId;
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'attachments/cleared' });
      dispatch({ type: 'message/sent', text: message, attachments: state.attachments });
      const restore = () => {
        if (!current()) {
          if (!mountedRef.current) prepareAgentInput(taskId, message);
          return;
        }
        dispatch({ type: 'message/cleared' });
        dispatch({ type: 'composer/changed', text: state.composer });
        dispatch({ type: 'attachments/added', attachments: state.attachments });
      };
      let turn: AgentTurnDto;
      try {
        turn = await startTurn(message, attachmentIds, idempotencyKey, task.version, submissionAutomation);
      }
      catch (failure) {
        if (!current()) {
          // Shown again while it was in flight, the page no longer holds this draft either.
          if (!unconfirmedRequest(failure) && !alreadySent(failure)) prepareAgentInput(taskId, message);
          throw failure;
        }
        if (alreadySent(failure)) {
          // Queued or steered before under this key, so the message is already in the conversation.
          submissions.settled(idempotencyKey);
          dispatch({ type: 'message/cleared' });
        }
        else if (unconfirmedRequest(failure)) {
          dispatch({ type: 'message/unconfirmed' });
          dispatch({ type: 'composer/changed', text: state.composer });
          dispatch({ type: 'attachments/added', attachments: state.attachments });
        }
        else {
          restore();
        }
        throw failure;
      }
      if (!current()) return;
      if (turn.state === 'FAILED' && turn.knownUnsent) {
        // A retried key answered with the earlier attempt, which never reached the agent; the next Send is a new message.
        restore();
        throw new Error(`Not sent: ${turn.terminalReason ?? 'the agent did not start'}`);
      }
      // A retried key answers with the turn it already started, whose durable message is already shown.
      if (turn.id === shownTurnId) dispatch({ type: 'message/cleared' });
    }).finally(() => { if (sendRequestRef.current === request) sendRequestRef.current = null; });
  }, [attentionAction, automationBlocked, ciPollingError, confirmation, confirming, live, refreshProjection, run, startTurn, state.attachments, state.composer, state.latestTurnId, state.task, submissionAutomation, submissions, taskId, workspaceId]);

  const taskCommentDisabled = state.task?.state !== 'OPEN' ? 'This task is closed.'
    : state.task?.developmentContinuationUnfinished ? 'Wait for the current follow-up to finish.'
    : state.connection !== 'open' ? 'Reconnect to the task before replying.'
    : state.sending || attaching ? 'Wait for the current submission to finish.'
    : !['idle', 'running'].includes(runtimeStatus(state)) ? 'Wait until this task can accept another message.' : null;
  const agentCommentDisabled = taskPr === null || taskPr.state === 'merged' || taskPr.state === 'closed'
    ? 'Code feedback requires an open pull request.' : taskCommentDisabled;
  const localReview = useLocalReviewThreads({ taskId, workspaceId }, {
    active: state.task?.interactive === true,
    bridge: explicitBridge,
    disabledReason: agentCommentDisabled,
    learningDisabledReason: taskCommentDisabled,
    onDispatch: async () => { attentionAction('prompt'); await refreshProjection(stateRef.current.latestTurnId); },
  });
  const discussions = useTaskDiscussions(
    state.task?.interactive === true ? { taskId, workspaceId } : null,
    {
      active: state.task?.interactive === true,
      bridge: explicitBridge,
      // A discussion is private and never reaches the provider, so a closed pull request is nothing to it.
      // What stops it is the task releasing the files the agent would read.
      disabledReason: state.task?.checkoutRemovedAt == null ? null
        : 'This task released its files, so its discussions are read-only.',
    });
  const createLocalReview = localReview.create;
  const sendAgentComment = useCallback(async (comment: DiffAgentComment) => {
    const task = stateRef.current.task;
    if (inlineSending.current) throw new Error('A code comment is already being sent.');
    if (task === null || task.state !== 'OPEN' || task.interactive !== true) throw new Error('Open a development task to send code feedback.');
    if (taskPr === null || taskPr.state === 'merged' || taskPr.state === 'closed') throw new Error('Code feedback requires an open pull request.');
    if (agentCommentDisabled !== null || attachmentPendingRef.current) throw new Error(agentCommentDisabled ?? 'Wait for the attachment to finish.');
    inlineSending.current = true;
    dispatch({ type: 'composer/sending', sending: true });
    try {
      await createLocalReview(comment, { taskId, workspaceId, repo: taskPr.repo, number: taskPr.number, expectedTaskVersion: task.version });
    }
    catch (failure) {
      recordProviderUnavailable(failure);
      await refreshProjection(stateRef.current.latestTurnId).catch(() => {});
      throw failure;
    }
    finally {
      inlineSending.current = false;
      dispatch({ type: 'composer/sending', sending: false });
    }
  }, [agentCommentDisabled, createLocalReview, recordProviderUnavailable, refreshProjection, taskId, taskPr, workspaceId]);

  const fixKeys = useRef(new Map<string, string>());
  const fixComment = useCallback(async (comment: FixableComment) => {
    const message = commentFixPrompt(comment);
    await requestTaskFix(live(), fixKeys.current, message, taskId, workspaceId, message);
    attentionAction('prompt');
    await refreshProjection(stateRef.current.latestTurnId).catch(() => {});
  }, [attentionAction, live, refreshProjection, taskId, workspaceId]);

  /** One click, one publication: ByteQuay builds the request, starts the turn and owns the write. */
  const steer = useCallback(() => run(async () => {
    if (attachmentPendingRef.current || !canSteer(stateRef.current)) return;
    const message = state.composer.trim();
    if (ciMessage(message) !== null) return;
    const turn = state.turn;
    if (message.length === 0 || turn === null) return;
    const attachmentIds = state.attachments.map((attachment) => attachment.id);
    const idempotencyKey = submissions.keyFor('steer', turn.id, taskId, message, attachmentIds);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const delivery = await live().steerAgentTurn({
        turnId: turn.id,
        message,
        idempotencyKey,
        attachments: attachmentIds,
      }).catch((failure: unknown) => {
        if (!alreadySent(failure)) throw failure;
        // Sent before under this key, so the draft is done; the notice says why nothing new was sent.
        submissions.settled(idempotencyKey);
        dispatch({ type: 'composer/changed', text: '' });
        dispatch({ type: 'attachments/cleared' });
        throw failure;
      });
      if (delivery.state === 'DELIVERED') attentionAction('prompt');
      submissions.settled(idempotencyKey);
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'attachments/cleared' });
      // The message is already in the transcript; what the notice adds is that the agent did
      // not get it, or that nobody can say. Neither is resent from here.
      if (delivery.state === 'UNKNOWN') {
        dispatch({ type: 'notice/changed', notice: `The steer may not have reached the agent: ${delivery.failure ?? 'no answer'}. It is not sent again by itself.` });
      }
      else if (delivery.state !== 'DELIVERED') {
        dispatch({ type: 'notice/changed', notice: `The steer was not delivered: ${delivery.failure ?? delivery.state}` });
      }
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }), [attentionAction, live, run, state.attachments, state.composer, state.turn, submissions, taskId]);

  const queueNext = useCallback(() => run(async () => {
    if (attachmentPendingRef.current || automationBlocked || !canQueue(stateRef.current)) return;
    const message = state.composer.trim();
    if (ciMessage(message) !== null) return;
    const task = state.task;
    if (message.length === 0 || task === null) return;
    const attachmentIds = state.attachments.map((attachment) => attachment.id);
    const idempotencyKey = submissions.keyFor('queue', taskId, taskId, message, attachmentIds);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const queued = await live().queueAgentInput({
        taskId,
        workspaceId,
        message,
        expectedVersion: task.version,
        developmentAutomation: submissionAutomation,
        idempotencyKey,
        attachments: attachmentIds,
      }).catch((failure: unknown) => {
        if (!alreadySent(failure)) throw failure;
        // Sent before under this key, such as a Send whose answer was lost, so the draft is done.
        submissions.settled(idempotencyKey);
        dispatch({ type: 'composer/changed', text: '' });
        dispatch({ type: 'attachments/cleared' });
        throw failure;
      });
      attentionAction('prompt', 1, taskAttentionAfterQueue(viewedItem.current, queued));
      submissions.settled(idempotencyKey);
      applyQueuedInput(queued);
      dispatch({ type: 'composer/changed', text: '' });
      dispatch({ type: 'attachments/cleared' });
      // The write moved the task version; one ordered refresh adopts it and reconciles auto-start.
      await refreshProjection(state.latestTurnId);
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }), [applyQueuedInput, attentionAction, automationBlocked, live, refreshProjection, run, state.attachments, state.composer, state.latestTurnId, state.task, submissionAutomation, submissions, taskId, workspaceId]);

  const removeQueued = useCallback((queuedInputId: string) => run(async () => {
    await live().removeAgentQueuedInput(taskId, workspaceId, queuedInputId);
    attentionAction('update');
    removeQueuedInput(queuedInputId);
    await refreshProjection(state.latestTurnId);
  }), [attentionAction, live, refreshProjection, removeQueuedInput, run, state.latestTurnId, taskId, workspaceId]);

  const editQueued = useCallback((queuedInputId: string) => run(async () => {
    const current = stateRef.current;
    const input = current.queue.find(input => input.id === queuedInputId);
    if (editingQueuedRef.current || current.sending || attachmentPendingRef.current || current.composer !== '' || current.attachments.length > 0
      || current.task === null || input === undefined || input.turnId !== null || !['QUEUED', 'PAUSED'].includes(input.state)) return;
    editingQueuedRef.current = true;
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const draft = await live().editAgentQueuedInput(taskId, workspaceId, queuedInputId);
      removeQueuedInput(queuedInputId);
      dispatch({ type: 'composer/changed', text: draft.message });
      dispatch({ type: 'attachments/added', attachments: draft.attachments });
      attentionAction('update');
      const { model, effort, serviceTier, permissionPolicy, developmentAutomation } = draft.settings;
      const defaults = { model, effort, serviceTier, permissionPolicy, developmentAutomation };
      submissions.draftChanged(taskId, draft.message, draft.attachments.map(attachment => attachment.id));
      const task = await live().getAgentTask(taskId, workspaceId);
      if (task.defaults.model !== model || task.defaults.effort !== effort || (task.defaults.serviceTier ?? null) !== (serviceTier ?? null)
        || task.defaults.permissionPolicy !== permissionPolicy || JSON.stringify(task.defaults.developmentAutomation) !== JSON.stringify(developmentAutomation)) {
        applyTask(await live().updateAgentTaskDefaults(taskId, workspaceId, task.version, defaults));
      }
      await refreshProjection(current.latestTurnId);
    }
    finally {
      editingQueuedRef.current = false;
      dispatch({ type: 'composer/sending', sending: false });
      requestAnimationFrame(() => {
        composerRef.current?.focus();
        const length = composerRef.current?.value.length ?? 0;
        composerRef.current?.setSelectionRange(length, length);
      });
    }
  }), [applyTask, attentionAction, live, refreshProjection, removeQueuedInput, run, submissions, taskId, workspaceId]);

  const steerQueued = useCallback((queuedInputId: string) => run(async () => {
    const current = stateRef.current;
    const input = current.queue.find(input => input.id === queuedInputId);
    if (input === undefined || input.turnId !== null || !canSteer(current, input.preview) || current.turn === null) return;
    if (input.settings.attachments.length > 0 && !acceptsImages(models, current.turn.settings?.model, current.capabilities)) return;
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const delivery = await live().steerAgentQueuedInput(taskId, workspaceId, queuedInputId, current.turn.id);
      removeQueuedInput(queuedInputId);
      attentionAction(delivery.state === 'DELIVERED' ? 'prompt' : 'update');
      if (delivery.state !== 'DELIVERED') {
        dispatch({ type: 'notice/changed', notice: delivery.state === 'UNKNOWN'
          ? `The steer may not have reached the agent: ${delivery.failure ?? 'no answer'}. It is not sent again by itself.`
          : `The steer was not delivered: ${delivery.failure ?? delivery.state}` });
      }
      await refreshProjection(current.latestTurnId);
    }
    finally {
      dispatch({ type: 'composer/sending', sending: false });
    }
  }), [attentionAction, live, models, refreshProjection, removeQueuedInput, run, taskId, workspaceId]);

  const resumeQueue = useCallback(() => run(async () => {
    const current = stateRef.current;
    if (runtimeStatus(current) !== 'idle' || current.task?.developmentContinuationUnfinished || developmentQuestion(current) !== null) return;
    try {
      const turn = await live().resumeAgentQueue(taskId, workspaceId);
      attentionAction('prompt');
      applyTurn(turn);
      await refreshProjection(turn.id);
    }
    catch (failure) {
      recordProviderUnavailable(failure);
      throw failure;
    }
  }), [applyTurn, attentionAction, live, recordProviderUnavailable, refreshProjection, run, taskId, workspaceId]);

  const attach = useCallback((source: 'picker' | 'clipboard' = 'picker') => run(async () => {
    const current = stateRef.current;
    const status = runtimeStatus(current);
    const imagesAccepted = !isDeepSeekKeyLane(current.task?.laneId ?? '')
      && (acceptsImages(models, current.task?.defaults.model, current.capabilities)
        || (status === 'running' && current.capabilities?.steering === true && acceptsImages(models, current.turn?.settings?.model, current.capabilities)));
    if (attachmentPendingRef.current || current.sending || !imagesAccepted || (status !== 'idle' && status !== 'running')) return;
    attachmentPendingRef.current = true;
    setAttaching(true);
    dispatch({ type: 'composer/sending', sending: true });
    try {
      const stored = await (source === 'clipboard' ? live().pasteAgentAttachments(taskId, workspaceId) : live().pickAgentAttachments(taskId, workspaceId));
      if (!mountedRef.current || stateRef.current.task?.id !== taskId || stored.length === 0) return;
      const draft = stateRef.current;
      submissions.draftChanged(taskId, draft.composer, [...draft.attachments, ...stored].map((attachment) => attachment.id));
      dispatch({ type: 'attachments/added', attachments: stored });
    }
    finally {
      attachmentPendingRef.current = false;
      if (mountedRef.current) {
        setAttaching(false);
        dispatch({ type: 'composer/sending', sending: false });
      }
    }
  }), [live, models, run, submissions, taskId, workspaceId]);

  const removeAttachment = useCallback((attachmentId: string) => run(async () => {
    const draft = stateRef.current;
    submissions.draftChanged(taskId, draft.composer, draft.attachments.filter((attachment) => attachment.id !== attachmentId).map((attachment) => attachment.id));
    dispatch({ type: 'attachments/removed', attachmentId });
    const preview = previewsRef.current[attachmentId];
    if (preview?.status === 'ready') {
      URL.revokeObjectURL(preview.url);
      ownedPreviewUrls.current.delete(preview.url);
    }
    setPreviews((current) => {
      const next = { ...current };
      delete next[attachmentId];
      return next;
    });
    await live().removeAgentAttachment(taskId, workspaceId, attachmentId);
  }), [live, run, submissions, taskId, workspaceId]);

  // Keep only previews for the visible history window and drafts, with a fixed image count cap.
  const retainedImageIds = [...new Set([
    ...state.events.flatMap((event) => event.type === 'user_message' ? event.payload.attachments.map((attachment) => attachment.id) : []),
    ...state.attachments.map((attachment) => attachment.id),
    ...state.pendingMessage?.attachments.map((attachment) => attachment.id) ?? [],
  ])].slice(-16).join(',');
  useEffect(() => {
    let active = true;
    const ids = retainedImageIds === '' ? [] : retainedImageIds.split(',');
    const retained = new Set(ids);
    const current = previewsRef.current;
    for (const [id, preview] of Object.entries(current)) {
      if (!retained.has(id) && preview.status === 'ready') {
        URL.revokeObjectURL(preview.url);
        ownedPreviewUrls.current.delete(preview.url);
      }
    }
    const needed = ids.filter((id) => current[id] === undefined || current[id].status === 'loading');
    setPreviews(Object.fromEntries(ids.map((id) => [id, current[id] ?? { status: 'loading' as const }])));
    for (const id of needed) {
      // Decode one source image at a time; only 512px thumbnails remain in the renderer.
      previewWork.current = previewWork.current.then(async () => {
        if (!active) return;
        const image = await live().readAgentAttachment(taskId, workspaceId, id);
        if (!active) return;
        const bitmap = await createImageBitmap(new Blob([image.bytes as BlobPart], { type: image.mediaType }));
        try {
          if (!active) return;
          const scale = Math.min(1, 512 / Math.max(bitmap.width, bitmap.height));
          const canvas = new OffscreenCanvas(Math.max(1, Math.round(bitmap.width * scale)), Math.max(1, Math.round(bitmap.height * scale)));
          const context = canvas.getContext('2d');
          if (context === null) throw new Error('Image preview is unavailable');
          context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
          const thumbnail = await canvas.convertToBlob({ type: 'image/png' });
          if (!active) return;
          const url = URL.createObjectURL(thumbnail);
          // Own the URL before React commits state, so an intervening unmount still releases it.
          ownedPreviewUrls.current.add(url);
          const loadOriginal = async (): Promise<Blob> => {
            const original = await live().readAgentAttachment(taskId, workspaceId, id);
            return new Blob([original.bytes as BlobPart], { type: original.mediaType });
          };
          setPreviews((loaded) => ({ ...loaded, [id]: { status: 'ready', url, loadOriginal } }));
        }
        finally { bitmap.close(); }
      }).catch(() => {
        if (active) setPreviews((loaded) => ({ ...loaded, [id]: { status: 'unavailable' } }));
      });
    }
    return () => { active = false; };
  }, [live, taskId, workspaceId, retainedImageIds]);

  useEffect(() => {
    const turns = projectTranscript(state.events);
    const turnIds = new Set(turns.map((turn) => turn.turnId ?? `turn-${turn.ordinal}`));
    const blockIds = new Set(state.events.map((event) => event.eventId));
    setTurnChoice((choices) => Object.fromEntries(Object.entries(choices).filter(([id]) => turnIds.has(id))));
    setActivityChoice((choices) => Object.fromEntries(Object.entries(choices).filter(([id]) => blockIds.has(id))));
  }, [state.events]);

  useEffect(() => {
    const urls = ownedPreviewUrls.current;
    return () => {
      for (const url of urls) URL.revokeObjectURL(url);
      urls.clear();
    };
  }, []);

  const cancel = useCallback(() => run(async () => {
    if (state.turn === null) return;
    const turn = await live().cancelAgentTurn(state.turn.id);
    attentionAction('update');
    applyTurn(turn);
    try {
      await refreshProjection(turn.id);
    }
    catch {
      // Terminal lifecycle replay can repair a projection read that failed here.
    }
  }), [applyTurn, attentionAction, live, refreshProjection, run, state.turn]);

  const completeTask = useCallback(() => run(async () => {
    await live().completeAgentTask(taskId, workspaceId);
    attentionAction('update');
    await refreshProjection(stateRef.current.latestTurnId);
  }), [attentionAction, live, refreshProjection, run, taskId, workspaceId]);

  const complete = useCallback(() => {
    if (attachmentPendingRef.current || !canComplete(state)) return;
    if (!abandonsUncertainInput(state.queue)) { void completeTask(); return; }
    setAsk({
      title: 'Complete this task with an uncertain queued message?',
      body: <p>It will be marked abandoned and will never be retried.</p>,
      label: 'Complete task',
      act: () => { void completeTask(); },
    });
  }, [completeTask, state]);

  const cleanupWorktree = useCallback(() => run(async () => {
    applyTask(await live().cleanupAgentTaskWorktree(taskId, workspaceId));
    attentionAction('update');
  }), [applyTask, attentionAction, live, run, taskId, workspaceId]);

  const stopServer = useCallback(() => {
    const request = ++confirmationRequestRef.current;
    return run(async () => {
      const impact = await live().getAgentRuntimeStopImpact(taskId, workspaceId);
      if (mountedRef.current && request === confirmationRequestRef.current) {
        setConfirmation({ kind: 'stop', impact, automation: stoppingEndsDevelopment(stateRef.current, impact.activeTurnId) });
      }
    });
  }, [live, run, taskId, workspaceId]);

  const markFailed = useCallback(() => {
    if (stateRef.current.task?.developmentContinuationUnfinished) return;
    confirmationRequestRef.current++;
    dispatch({ type: 'notice/changed', notice: null });
    setConfirmation({ kind: 'fail' });
  }, []);

  const confirmTaskAction = useCallback(async () => {
    if (confirmation === null || confirmationPendingRef.current) return;
    confirmationPendingRef.current = true;
    setConfirming(true);
    try {
      await run(async () => {
        const task = confirmation.kind === 'stop'
          ? await live().stopAgentRuntime(taskId, workspaceId, confirmation.impact.generation)
          : await live().markAgentTaskFailed(taskId, workspaceId, 'Marked failed by the user during recovery');
        applyTask(task);
        attentionAction('update');
        await refreshProjection(stateRef.current.latestTurnId);
      });
    }
    finally {
      confirmationPendingRef.current = false;
      if (mountedRef.current) {
        setConfirmation(null);
        setConfirming(false);
      }
    }
  }, [applyTask, attentionAction, confirmation, live, refreshProjection, run, taskId, workspaceId]);

  const refreshProvider = useCallback(() => run(async () => {
    setProviderChecking(true);
    try {
      await refreshProviderStatus();
    }
    finally {
      setProviderChecking(false);
    }
  }), [refreshProviderStatus, run]);

  const retryLastMessage = useCallback(() => run(async () => {
    const current = stateRef.current;
    const task = current.task;
    const input = retryableInput(current);
    if (task === null
      || current.turn?.failureKind !== 'AUTHENTICATION_REQUIRED'
      || input === null
      || providerStatus === null
      || providerStatusTurnId !== current.turn.id
      || !providerAvailable(providerStatus.readiness)) return;
    const attachmentIds = input.attachments.map((attachment) => attachment.id);
    // Retrying the accepted input retains its publication choices, which may differ from the task defaults.
    const developmentAutomation = task.interactive
      ? current.turn.settings?.developmentAutomation ?? LOCAL_COMMITS_ONLY
      : undefined;
    // The failed turn's key is never reused. The tracker retains this new key only if this reply is
    // ambiguous, so a second confirmed click cannot create a duplicate retry turn.
    const idempotencyKey = submissions.keyFor('retry', task.id, taskId, input.text, attachmentIds);
    await startTurn(input.text, attachmentIds, idempotencyKey, task.version, developmentAutomation);
  }), [providerStatus, providerStatusTurnId, run, startTurn, submissions, taskId]);

  // The guards run again on confirmation, so a turn that starts while the question is open cannot be retried behind it.
  const retryAuthentication = useCallback(() => {
    const input = retryableInput(stateRef.current);
    if (input === null) return;
    setAsk({
      title: 'Retry the last message?',
      body: <>
        <p>This creates a new turn and may run commands again.</p>
        <p style={{ whiteSpace: 'pre-wrap' }}>{input.text}</p>
      </>,
      label: 'Retry',
      act: () => { void retryLastMessage(); },
    });
  }, [retryLastMessage]);

  const decide = useCallback((approvalId: string, decision: AgentApprovalDecision, answers?: Record<string, string>) => run(async () => {
    await live().resolveAgentApproval(approvalId, decision, undefined, answers);
    attentionAction('update');
    setPendingApprovals(current => current?.filter(approval => approval.approvalId !== approvalId) ?? null);
    setCiApprovalState(current => current === null ? null : {
      ...current, approvals: current.approvals.filter(approval => approval.approvalId !== approvalId),
    });
    // The buttons that had focus disappear with the decision; the composer is the next thing to do.
    composerRef.current?.focus();
  }), [attentionAction, live, run]);

  const currentCiApprovals = ciApprovalState?.runId === state.task?.developmentCi?.runId ? ciApprovalState : null;
  const conversationApprovals = useMemo(() => [...(pendingApprovals ?? []), ...((currentCiApprovals?.approvals ?? []).map(approval => ({
    ...approval, title: `CI Autofix · ${approval.title}`,
  })))], [pendingApprovals, currentCiApprovals?.approvals]);

  return (<>
    {signingIn !== null && (
      <ProviderSignInDialog
        laneId={signingIn.laneId}
        providerName={signingIn.providerName}
        bridge={live()}
        onClose={(signedIn) => {
          setSigningIn(null);
          if (signedIn) void refreshProvider();
        }}
      />
    )}
    <AgentTaskBody
      state={state}
      attentionStatus={attentionStatus}
      headerAction={headerAction}
      submissionBlocked={automationBlocked}
      onAskAgent={state.task?.interactive === true ? sendAgentComment : undefined}
      onAskAgentDisabled={agentCommentDisabled}
      onFixComment={state.task?.interactive === true ? fixComment : undefined}
      localReview={localReview}
      discussions={discussions}
      onSelectPassage={(passage, question) => discussions.open({ ...passage, question }).then(() => openUtility('discussions'))}
      discussionFocus={discussionFocus}
      onOpenDiscussion={id => { openUtility('discussions'); setDiscussionFocus({ id }); }}
      onJumpToPassage={revealPassage}
      learning={(onSelectCommit, review) => state.task === null ? null : <DevelopmentLearningPanel key={taskId} task={state.task} onSelectCommit={onSelectCommit} review={review} repository={workspace === undefined ? '' : `${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
        refreshKey={state.lifecycleEpoch} refining={state.task?.developmentContinuationUnfinished === true && state.turn?.developmentOrigin?.kind === 'REFINE_SUMMARY'}
        disabledReason={taskCommentDisabled}
        localReview={localReview} bridge={live()} />}
      automation={automationEligible ? automationSelection : undefined}
      historyLoading={historyLoading}
      durableTurnsLoading={unloaded !== '' && unloaded !== failedTurns}
      onLoadHistory={(direction) => { void loadHistory(direction); }}
      onFollowHistory={(following) => {
        const resumed = following && !followingHistory.current;
        followingHistory.current = following;
        if (resumed && stateRef.current.lastSequence > (stateRef.current.events.at(-1)?.sequence ?? 0)) void loadHistory('latest');
      }}
      pendingApprovals={conversationApprovals}
      ciApprovalError={currentCiApprovals?.error}
      models={models}
      provider={provider}
      providerStatus={providerStatus}
      keyRemoved={keyRemoved}
      providerStatusFresh={authenticationTurnId !== null && providerStatusTurnId === authenticationTurnId}
      providerChecking={providerChecking}
      composerRef={composerRef}
      onBack={() => withCodeDrafts(onBack)}
      onSend={() => { void send(); }}
      onCancel={() => { void cancel(); }}
      onComplete={complete}
      onCleanup={() => { void cleanupWorktree(); }}
      onDecide={(approvalId, decision, answers) => { void decide(approvalId, decision, answers); }}
      onCompose={(text) => {
        const draft = stateRef.current;
        submissions.draftChanged(taskId, text, draft.attachments.map((attachment) => attachment.id));
        dispatch({ type: 'composer/changed', text });
      }}
      onSelectModel={(model, effort, serviceTier) => { void selectModel(model, effort, serviceTier); }}
      onStopServer={() => { void stopServer(); }}
      ciStopping={ciStopping}
      onStopCi={() => { void stopCi(); }}
      onCancelAutoMerge={cancelAutoMerge}
      onMarkFailed={() => { void markFailed(); }}
      onRefreshProvider={() => { void refreshProvider(); }}
      onSignInProvider={taskKeyLane || providerStatus === null ? undefined
        : () => setSigningIn({ laneId: providerStatus.laneId, providerName: provider || laneLabel(providerStatus.laneId) })}
      onRetryAuthentication={retryAuthentication}
      onOpenSettings={taskKeyLane ? () => { window.location.hash = settingsRoute('models'); } : onOpenSettings}
      onRename={(title) => { void rename(title); }}
      onArchive={(archived) => { void archive(archived); }}
      now={now}
      activityChoice={activityChoice}
      turnChoice={turnChoice}
      planOpen={planOpen}
      previews={previews}
      onToggleActivity={(id, open) => setActivityChoice((choices) => ({ ...choices, [id]: open }))}
      onToggleTurn={(id, open) => setTurnChoice((choices) => ({ ...choices, [id]: open }))}
      onTogglePlan={() => setPlanOpen((open) => !open)}
      onAttach={() => { void attach(); }}
      onPasteImages={() => { void attach('clipboard'); }}
      attaching={attaching}
      onRemoveAttachment={(attachmentId) => { void removeAttachment(attachmentId); }}
      panelOpen={panelOpen}
      openAgent={openAgent}
      panelTab={panelTab}
      reviewFocus={reviewFocus}
      onOpenReview={target => {
        setReviewFocus(current => ({ target, token: (current?.token ?? 0) + 1 }));
        setPanelChoice(true);
        setPanelTab('review');
        setOpenAgent(null);
      }}
      pullRequests={pullRequests}
      pullRequestError={pullRequestError}
      pullRequestLoading={pullRequestLoading}
      pullRequestChecked={pullRequestChecked.current}
      ciPollingError={ciPollingError}
      ciSubmissionBlocked={confirmation !== null || confirming}
      onRefreshPullRequests={canRefreshPullRequests ? () => setPullRequestRefresh((revision) => revision + 1) : undefined}
      pullRequestDetail={pullRequestDetail}
      pullRequestChanges={pullRequestChanges}
      pullRequestCommitRange={pullRequestCommitRange}
      pullRequestPendingComments={pullRequestPendingComments}
      onPullRequestCommitRangeChange={pullRequestChanges.changeCommitRange}
      onPullRequestPendingCommentsChange={pendingComments => setPullRequestReview(current => currentReviewKey.current !== reviewKey
        ? current
        : { key: reviewKey, pendingComments })}
      repository={workspace === undefined ? null : `${workspace.monitoredOwner}/${workspace.monitoredRepo}`}
      onTogglePanel={() => {
        if (!panelOpen) setPanelChoice(true);
        else withCodeDrafts(() => setPanelChoice(false));
      }}
      onCodeUnsavedChange={trackCodeUnsaved}
      onOpenAgent={(threadId) => { openUtility('agents'); setOpenAgent(threadId); }}
      onCloseAgent={() => setOpenAgent(null)}
      onSelectPanelTab={(tab) => { setPanelTab(tab); setOpenAgent(null); }}
      utilityTabs={utilityTabs}
      onCloseUtility={(tab) => {
        setUtilityTabs(open => open.filter(item => item !== tab));
        if (panelTab === tab) { setPanelTab(coveredTab.current); setOpenAgent(null); }
      }}
      logChoice={logChoice}
      onToggleLog={setLogChoice}
      onOpenPullRequest={onOpenPullRequest}
      onSteer={() => { void steer(); }}
      onQueue={() => { void queueNext(); }}
      onRemoveQueued={(queuedInputId) => { void removeQueued(queuedInputId); }}
      onEditQueued={(queuedInputId) => { void editQueued(queuedInputId); }}
      onSteerQueued={(queuedInputId) => { void steerQueued(queuedInputId); }}
      onResumeQueue={() => { void resumeQueue(); }}
    />
    {confirmation !== null && (
      <ConfirmDialog
        title={confirmation.kind === 'stop' ? confirmation.automation ? 'Stop automation?' : 'Stop this task’s agent?'
          : 'Mark task as failed?'}
        confirmLabel={confirmation.kind === 'stop'
          ? confirming ? 'Stopping…' : confirmation.automation ? 'Stop automation' : 'Stop agent'
          : confirming ? 'Marking failed…' : 'Mark as failed'}
        busy={confirming}
        destructive={confirmation.kind !== 'stop'}
        onConfirm={() => { void confirmTaskAction(); }}
        onCancel={() => setConfirmation(null)}
      >
        {confirmation.kind === 'stop' ? <>
          <p>Only this task is affected. Your conversation and files will be kept.</p>
          <p>{confirmation.impact.activeTurnId === null
            ? 'The last turn ended. This checks for and stops any remaining agent activity.'
            : 'The current response will be interrupted and pending approvals declined.'}</p>
          <p>{confirmation.automation
            ? 'This stops the current automation and keeps the task open. Once shutdown is verified, you can send another message here. The stopped work is not retried automatically.'
            : 'You can continue once ByteQuay confirms the agent has stopped.'}</p>
        </> : <>
          <p>This ends the task. You won’t be able to send more messages here.</p>
          <p>Your conversation and any uncommitted changes are kept. ByteQuay may remove a clean checkout, but keeps its branch.</p>
        </>}
      </ConfirmDialog>
    )}
    {ask !== null && (
      <ConfirmDialog
        title={ask.title}
        confirmLabel={ask.label}
        onConfirm={() => { const act = ask.act; setAsk(null); act(); }}
        onCancel={() => setAsk(null)}
      >
        {ask.body}
      </ConfirmDialog>
    )}
  </>);
}
