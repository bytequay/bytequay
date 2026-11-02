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

import { Archive, ArchiveRestore, CircleCheck, PanelRight } from 'lucide-react';
import React, { useCallback, useMemo } from 'react';
import '../css/agents.css';
import PinButton from '../home/PinButton';
import type { IdeUnsavedChanges } from '../ide/contracts';
import { JiraSourceAttachment } from '../jira/JiraSourceAttachment';
import { useNoticeSource } from '../notices/useNoticeSource';
import type { CommitRange, DiffAgentComment } from '../pulls/PullChanges';
import { PullRequestChip } from '../pulls/PullRequestChip';
import { PendingReviewComments } from '../pulls/ReviewDrafts';
import type { TaskReviewOptions } from '../pulls/agent-review/TaskReviewSummary';
import type { LocalReviewThreads } from '../pulls/local-review/types';
import { useRenameDraft } from '../renameDraft';
import { isDeepSeekKeyLane, providerAvailable } from '../settings/model';
import type { AgentPendingApprovalDto, AgentProviderStatus, DevelopmentAutomationDto, InlineReviewCommentDto, ModelOptionDto, PullRequestDto } from '../types';
import {
  AgentConversation,
  ConversationComposer,
  ConversationQueue,
  modelName,
  PlanCard,
  serviceTierName,
  Spinner,
  useConversationHistory,
  type ApprovalDecider,
  type AttachmentPreview
} from './AgentConversation';
import { AutomationControl, AutomationPill } from './AutomationControl';
import { BackportChainCard, useBackportChains } from './BackportChains';
import type { ReviewCardFocus, ReviewRequestTarget } from './InstructionCard';
import { IssueAttachment } from './IssueAttachment';
import { LinkedWorkCard, LinkedWorkRequest, useLinkedWorkspaceWork } from './LinkedWorkspaceWork';
import TaskHeader from './TaskHeader';
import TaskPaneDivider, { useTaskPane } from './TaskPaneDivider';
import { NO_PULL_REQUEST_CHANGES, NO_PULL_REQUEST_DETAIL, samePullRequest, taskPullRequest, type TaskPullRequestChanges, type TaskPullRequestDetail } from './TaskPullRequestPanel';
import { ProviderRecoveryCard, RecoveryCard, TerminalSummary } from './TaskRecovery';
import TaskSidePanel, { type PanelTab, type UtilityTab } from './TaskSidePanel';
import { TaskStats } from './TaskStats';
import { ciActivity, ciMessage, ciMessageUnavailable } from './ciAutofix';
import { pasteImage } from './clipboard';
import { automationSteps, developmentQuestion, mergeLabel } from './developmentAutomation';
import type { SelectedPassage } from './discussions/DiscussableAnswer';
import { discussionTurnSummaries } from './discussions/DiscussionsPanel';
import type { TaskDiscussions } from './discussions/useTaskDiscussions';
import { renderMarkdown } from './markdown';
import {
  acceptsImages,
  canComplete,
  canQueue,
  canSend,
  canSteer,
  historyAfter,
  historyBefore,
  retryableInput,
  runtimeStatus,
  type AgentRuntimeStatus,
  type AgentTaskState
} from './model';
import type { FixableComment } from './taskFix';
import { taskArchived } from './taskStatus';
import { activeAgents, currentPlan, projectAgents, projectTranscript, settleFinalTurn, withApprovalAuthority, type TranscriptTurn } from './transcript';

const CONNECTION_TEXT: Record<string, string> = {
  connecting: 'Connecting — loading task history…',
  open: 'Live',
  failed: 'Disconnected',
};

/**
 * The task's name, and the control that changes it: click to edit, Enter or blur to keep, Escape
 * to drop. While the background naming request runs, the title is a compact first-message
 * fallback and reads as provisional.
 */
function TaskTitle({ title, provisional, onRename }: {
  title: string;
  provisional: boolean;
  onRename?: (title: string) => void;
}): React.JSX.Element {
  const rename = useRenameDraft(title, onRename);
  const className = `agt-title${provisional ? ' is-provisional' : ''}`;
  // Text, not a dead control, while there is nothing to rename yet.
  if (onRename === undefined) {
    return <strong className={className}>{title}</strong>;
  }
  if (!rename.editing) {
    return (
      <button
        type="button"
        className={className}
        title={provisional
          ? 'Using your first message while a short name is generated. Click to rename.'
          : 'Click to rename'}
        onClick={rename.start}
      >
        {title}
      </button>
    );
  }
  return <input className="agt-title-edit" {...rename.inputProps} />;
}

/**
 * What the connection strip says.
 *
 * Reconnecting names the sequence it is resuming from, because "catching up" without a resume
 * point asks the user to take the claim that nothing was lost on faith.
 */
function connectionText(state: AgentTaskState): string {
  if (state.connection === 'reconnecting') {
    return `Reconnecting — catching up from event ${state.lastSequence}. Nothing is lost.`;
  }
  const text = CONNECTION_TEXT[state.connection] ?? state.connection;
  return state.connectionDetail === null ? text : `${text} — ${state.connectionDetail}`;
}

const STATUS_LABELS: Record<AgentRuntimeStatus, string> = {
  loading: 'Connecting',
  idle: 'Idle',
  running: 'Running',
  closing: 'Closing',
  recovery: 'Shutdown blocked',
  completed: 'Completed',
  failed: 'Failed',
};

/** Why Complete is unavailable, told on the button rather than left to be guessed. */
function completeTitle(status: AgentRuntimeStatus, state: AgentTaskState): string {
  if (state.task?.developmentContinuationUnfinished) return 'Available when automation finishes';
  switch (status) {
    case 'idle':
      return canComplete(state)
        ? 'Mark it complete'
        : 'Run or remove every waiting queued message first';
    case 'recovery':
      return 'Available after agent shutdown is verified';
    case 'closing':
      return 'Closing…';
    default:
      return 'Available when the task is idle';
  }
}

/** The task view renders supplied task state; AgentTaskScreen owns its stream, actions, and clock. */
export function AgentTaskBody({
  state, models, onBack, onSend: send, onCancel, onComplete, onDecide, onCompose, onSelectModel,
  discussions, onSelectPassage, onOpenDiscussion, onJumpToPassage, onHoverDiscussion, discussionFocus,
  onStopServer, onMarkFailed, onRename, onArchive, composerRef,
  provider = '',
  providerStatus = null,
  keyRemoved = false,
  providerStatusFresh = false,
  providerChecking = false,
  onRefreshProvider = () => {},
  onSignInProvider,
  onRetryAuthentication = () => {},
  onOpenSettings,
  now = Date.now(),
  activityChoice = {},
  turnChoice = {},
  planOpen = true,
  previews = {},
  panelOpen = false,
  openAgent = null,
  panelTab = 'pr',
  pullRequests = [],
  pullRequestError = null,
  pullRequestLoading = false,
  pullRequestChecked = false,
  ciPollingError = null,
  ciApprovalError = null,
  ciSubmissionBlocked = false,
  ciStopping = false,
  onStopCi = () => {},
  onCancelAutoMerge = () => {},
  onRefreshPullRequests,
  pullRequestDetail = NO_PULL_REQUEST_DETAIL,
  pullRequestChanges = NO_PULL_REQUEST_CHANGES,
  pullRequestCommitRange = null,
  pullRequestPendingComments = [],
  onPullRequestCommitRangeChange = () => {},
  onPullRequestPendingCommentsChange = () => {},
  repository = null,
  logChoice = null,
  onToggleLog = () => {},
  onToggleActivity = () => {},
  onToggleTurn = () => {},
  onTogglePlan = () => {},
  onAttach = () => {},
  onPasteImages,
  attaching = false,
  onRemoveAttachment = () => {},
  onTogglePanel = () => {},
  onCodeUnsavedChange,
  onOpenAgent = () => {},
  onCloseAgent = () => {},
  onSelectPanelTab = () => {},
  utilityTabs = [],
  onCloseUtility = () => {},
  onOpenPullRequest,
  onSteer: steer = () => {},
  onQueue = () => {},
  onRemoveQueued = () => {},
  onEditQueued,
  onSteerQueued = () => {},
  onResumeQueue = () => {},
  onCleanup = () => {},
  historyLoading = false,
  durableTurnsLoading = false,
  onLoadHistory = () => {},
  onFollowHistory = () => {},
  pendingApprovals = null,
  automation,
  headerAction,
  attentionStatus,
  submissionBlocked = false,
  onAskAgent, onAskAgentDisabled, onFixComment, localReview, learning, reviewFocus, onOpenReview,
}: {
  state: AgentTaskState;
  /** The task's accepted automation, null while it loads; absent for a task without automation. */
  automation?: DevelopmentAutomationDto | null;
  headerAction?: React.ReactNode;
  attentionStatus?: string;
  submissionBlocked?: boolean;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  onAskAgentDisabled?: string | null;
  onFixComment?: (comment: FixableComment) => Promise<void>;
  localReview?: LocalReviewThreads;
  discussions?: TaskDiscussions;
  onSelectPassage?: (passage: SelectedPassage, question: string) => Promise<unknown>;
  onOpenDiscussion?: (discussionId: string) => void;
  /** The discussion a transcript footer asked to see, which opens its card in the panel. */
  discussionFocus?: { id: string } | null;
  onJumpToPassage?: (target: { turnId: string; discussionId?: string }) => void;
  onHoverDiscussion?: (discussionId: string | null) => void;
  /** Renders the Summary; a development task's reviewer comes with it, so it can sit in the Summary's own strip and timeline. */
  learning?: React.ReactNode | ((onSelectCommit: (sha: string) => void, review?: TaskReviewOptions) => React.ReactNode);
  reviewFocus?: ReviewCardFocus | null;
  onOpenReview?: (target: ReviewRequestTarget) => void;
  models: ModelOptionDto[];
  historyLoading?: boolean;
  durableTurnsLoading?: boolean;
  onLoadHistory?: (direction: 'older' | 'newer' | 'latest') => void;
  onFollowHistory?: (following: boolean) => void;
  pendingApprovals?: AgentPendingApprovalDto[] | null;
  /** The lane's label, as the heading of its model list. */
  provider?: string;
  /** Fresh status for the immutable task lane, used only by typed authentication recovery. */
  providerStatus?: AgentProviderStatus | null;
  /** The task's key lane is absent from the current catalog after its key was removed. */
  keyRemoved?: boolean;
  /** A readiness probe completed after the typed failed turn currently on screen. */
  providerStatusFresh?: boolean;
  providerChecking?: boolean;
  onRefreshProvider?: () => void;
  /** Opens the provider's own sign-in terminal; absent when no bridge can open one. */
  onSignInProvider?: () => void;
  onRetryAuthentication?: () => void;
  onOpenSettings?: () => void;
  onBack: () => void;
  onSend: () => void;
  onCancel: () => void;
  onComplete: () => void;
  onDecide: ApprovalDecider;
  onCompose: (text: string) => void;
  onSelectModel: (model: string, effort: string | null, serviceTier: string | null) => void;
  onStopServer: () => void;
  onMarkFailed: () => void;
  /** A person's name for the task; absent while the task is still loading or in a static render. */
  onRename?: (title: string) => void;
  /** Shelves the task or brings it back; absent while the task is still loading or in a static render. */
  onArchive?: (archived: boolean) => void;
  composerRef?: React.Ref<HTMLTextAreaElement>;
  /** The clock reading the elapsed times are drawn against; ticks once a second while a turn runs. */
  now?: number;
  /** Whether the user opened or folded an activity group, by block id; live updates never change it. */
  activityChoice?: Record<string, boolean>;
  turnChoice?: Record<string, boolean>;
  planOpen?: boolean;
  /** Object URLs for stored images, by attachment id; owned and revoked by the wrapper. */
  previews?: Record<string, AttachmentPreview>;
  panelOpen?: boolean;
  /** Which subagent the panel is showing, by thread id; null lists them all. */
  openAgent?: string | null;
  onToggleActivity?: (id: string, open: boolean) => void;
  onToggleTurn?: (id: string, open: boolean) => void;
  onTogglePlan?: () => void;
  onAttach?: () => void;
  onPasteImages?: () => void;
  attaching?: boolean;
  onRemoveAttachment?: (attachmentId: string) => void;
  onTogglePanel?: () => void;
  onCodeUnsavedChange?: (changes: IdeUnsavedChanges) => void;
  onOpenAgent?: (threadId: string) => void;
  onCloseAgent?: () => void;
  /** Which side-panel tab is showing, and the pull requests found on the task's branch. */
  panelTab?: PanelTab;
  pullRequests?: PullRequestDto[];
  pullRequestError?: string | null;
  pullRequestLoading?: boolean;
  pullRequestChecked?: boolean;
  ciPollingError?: string | null;
  ciApprovalError?: string | null;
  ciSubmissionBlocked?: boolean;
  /** CI Autofix watches the pull request beside the task; this stops its current run. */
  ciStopping?: boolean;
  onStopCi?: () => void;
  onCancelAutoMerge?: () => void;
  onRefreshPullRequests?: () => void;
  /** What the wrapper has read for the panel's pull request; this body reads nothing itself. */
  pullRequestDetail?: TaskPullRequestDetail;
  pullRequestChanges?: TaskPullRequestChanges;
  pullRequestCommitRange?: CommitRange;
  pullRequestPendingComments?: InlineReviewCommentDto[];
  onPullRequestCommitRangeChange?: (range: CommitRange) => void;
  onPullRequestPendingCommentsChange?: (comments: InlineReviewCommentDto[]) => void;
  /** The repository a pull request from this task targets, named in the no-pull-request state. */
  repository?: string | null;
  onSelectPanelTab?: (tab: PanelTab) => void;
  /** The Subagents and Discussions tabs the conversation opened; none until it opens one. */
  utilityTabs?: UtilityTab[];
  onCloseUtility?: (tab: UtilityTab) => void;
  onOpenPullRequest?: (number: number) => void;
  logChoice?: string | null;
  onToggleLog?: (choice: string) => void;
  /** Steer sends the draft into the running turn; Run next queues it behind that turn. */
  onSteer?: () => void;
  onQueue?: () => void;
  onRemoveQueued?: (queuedInputId: string) => void;
  onEditQueued?: (queuedInputId: string) => void;
  onSteerQueued?: (queuedInputId: string) => void;
  onResumeQueue?: () => void;
  /** Retries safe checkout removal for a terminal task whose first attempt failed. */
  onCleanup?: () => void;
}): React.JSX.Element {
  const linkedWork = useLinkedWorkspaceWork(state.task?.workspaceId, state.task?.id);
  const backports = useBackportChains(state.task?.workspaceId);
  const sidePanel = useTaskPane();
  const before = historyBefore(state);
  const after = historyAfter(state);
  const scroll = useConversationHistory(state.events, state.turn?.endedAt, {
    before, after, loading: historyLoading, onLoad: onLoadHistory, onFollow: onFollowHistory,
  });
  const { following, changeFollowing, followSend, loadHistory } = scroll;
  useNoticeSource(state.task?.id ?? null);
  const pendingIds = useMemo(() => new Set(pendingApprovals?.map((approval) => approval.approvalId) ?? []), [pendingApprovals]);
  const atLatest = after === null && (following || (state.events.at(-1)?.sequence ?? 0) >= state.lastSequence);
  const hasHistoryPages = state.historyPages.length > 0;
  const project = useCallback((threadId: string | null): TranscriptTurn[] => {
    const projected = projectTranscript(state.events, threadId, state.turns, before === null, atLatest);
    return hasHistoryPages ? withApprovalAuthority(projected, pendingIds) : projected;
  }, [state.events, state.turns, before, atLatest, hasHistoryPages, pendingIds]);
  const projectedTurns = useMemo(() => project(null), [project]);
  const terminalTask = state.task !== null && state.task.state !== 'OPEN';
  const turns = useMemo(() => projectedTurns.map((turn, index) => (
    terminalTask && index === projectedTurns.length - 1
      ? settleFinalTurn(turn, state.task?.terminalAt ?? null, state.task?.state === 'COMPLETED')
      : turn
  )), [projectedTurns, terminalTask, state.task?.terminalAt, state.task?.state]);
  // The group header names the answer a thread hangs under; a turn that has left the loaded window
  // has no ordinal and no synopsis, and the panel then draws no header rather than a useless one.
  const discussionTurns = useMemo(() => discussionTurnSummaries(turns), [turns]);
  const onSend = (): void => { followSend(); send(); };
  const onSteer = (): void => { changeFollowing(true); steer(); };
  const supportsNestedAgents = state.capabilities?.nestedAgentActivity === true;
  const activeAgentTurnId = !hasHistoryPages ? undefined : state.turn?.state === 'RUNNING' ? state.turn.id : null;
  const agents = useMemo(() => supportsNestedAgents ? projectAgents(state.events, activeAgentTurnId, after === null) : [],
    [supportsNestedAgents, state.events, activeAgentTurnId, after]);
  const agentTranscript = useMemo(() => !supportsNestedAgents || !panelOpen || openAgent === null ? [] : project(openAgent),
    [supportsNestedAgents, panelOpen, openAgent, project]);
  const agentLabels = useMemo(() => Object.fromEntries(agents.map((agent) => [agent.threadId, agent.label])), [agents]);
  const working = useMemo(() => activeAgents(agents), [agents]);
  // A child the summary names but the events no longer hold: its steps were cleaned up.
  const expiredChildren = (supportsNestedAgents ? state.task?.summary?.children ?? [] : [])
    .filter((child) => !agents.some((agent) => agent.threadId === child.threadId));
  const hasAgents = agents.length > 0 || expiredChildren.length > 0;
  const visiblePanelTab: PanelTab = supportsNestedAgents || panelTab !== 'agents' ? panelTab : 'pr';
  const taskPr = taskPullRequest(pullRequests, state.task?.pullRequest?.number);
  const resultBranch = state.task?.summary?.resultBranch ?? null;
  /** The chip and a transcript link both open the panel on the pull request, never another tab. */
  const showPullRequest = (): void => {
    onSelectPanelTab('pr');
    if (!panelOpen) onTogglePanel();
  };
  const pullRequestShowing = panelOpen && visiblePanelTab === 'pr';
  /**
   * A link to this task's own pull request reads in the panel instead of leaving for the provider.
   * The task route does not change, and every other link keeps its ordinary behaviour. It runs in the
   * capture phase because a pull request title link navigates from its own click handler, which runs first.
   */
  const selectPullRequestTab = (event: React.MouseEvent<HTMLDivElement>): void => {
    if (taskPr === null || event.defaultPrevented || event.metaKey || event.ctrlKey) return;
    const link = (event.target as Element | null)?.closest?.('a');
    if (link === null || link === undefined || !samePullRequest(link.getAttribute('href'), taskPr)) return;
    event.preventDefault();
    showPullRequest();
  };
  const status = runtimeStatus(state);
  const keyLane = isDeepSeekKeyLane(state.task?.laneId ?? '');
  const routedToCi = ciMessage(state.composer) !== null;
  const ciUnavailable = ciMessageUnavailable(state);
  const automationActive = state.task?.developmentContinuationUnfinished === true;
  const currentCi = state.task?.developmentCi?.status === 'FINISHED' ? null : state.task?.developmentCi;
  const automationFailure = !automationActive && state.developmentProgress?.message.startsWith('Automation stopped.') ? state.developmentProgress.message : null;
  const ciEndedAt = Date.parse(state.task?.developmentCi?.terminalAt ?? '');
  const sourceStartedAt = state.turn?.id === state.latestTurnId ? Date.parse(state.turn.createdAt) : NaN;
  // A new source turn can fail CI attachment before replacing the previous run.
  const recordedCiFailure = state.task?.developmentCi?.status === 'FINISHED' && sourceStartedAt < ciEndedAt
    && automationFailure?.startsWith('Automation stopped. CI Autofix:') ? automationFailure : null;
  const terminal = status === 'completed' || status === 'failed';
  const waiting = state.historyPages.length > 0 ? (pendingApprovals?.length ?? 0) > 0
    : turns.some((turn) => turn.blocks.some((block) => block.kind === 'approval' && block.actionable && block.outcome === null));
  const question = developmentQuestion(state);
  const generatedActivity = state.turn?.developmentOrigin?.kind === 'AUTO_COMMIT' ? 'Committing'
    : state.turn?.developmentOrigin?.kind === 'PRIVACY_REPAIR' ? 'Checking for private information'
    : state.turn?.developmentOrigin?.kind === 'CREATE_DRAFT_PR' ? 'Creating draft PR'
    : state.turn?.developmentOrigin?.kind === 'REFINE_SUMMARY' ? 'Refining summary…' : null;
  const ciWaiting = pendingApprovals?.some(approval => approval.taskId !== state.task?.id) ?? false;
  const pillTone = ciWaiting || currentCi?.status === 'PAUSED' || currentCi?.activity === 'FIXING' ? 'amber' : 'blue';
  // A paused run says why on hover, such as a pipeline the provider stopped before any job.
  const ciPausedWhy = currentCi?.status === 'PAUSED' ? currentCi.summary ?? undefined : undefined;
  const autoMerge = state.task?.autoMerge;
  const automationPill = status === 'idle' && automationActive
    ? <AutomationPill tone={pillTone} label={currentCi != null ? ciWaiting ? 'Needs approval' : ciActivity(currentCi) : 'Automation active'} title={ciPausedWhy}
      stopLabel="Stop automation" stopTitle="Stop automation · your draft is kept" stopDisabled={state.sending || ciSubmissionBlocked} onStop={onStopServer} />
    : !terminal && !automationActive && currentCi != null && status !== 'recovery'
      ? <AutomationPill tone={pillTone} label={ciWaiting ? 'Needs approval' : ciActivity(currentCi)} title={ciPausedWhy}
        stopLabel="Stop CI Autofix" stopTitle={ciStopping ? 'Stopping…' : 'Stop CI Autofix'} stopDisabled={ciStopping} onStop={onStopCi} />
      : !terminal && (autoMerge?.state === 'READY' || autoMerge?.state === 'MERGING')
        ? <AutomationPill tone="blue" label={mergeLabel(autoMerge, now)} stopLabel="Stop auto-merge"
          stopTitle="Stop auto-merge · the pull request stays open" stopDisabled={autoMerge.state === 'MERGING'} onStop={onCancelAutoMerge} />
        : null;
  const automationControl = automation === undefined ? undefined
    : automation === null ? <span className="agt-meta">Loading automation settings…</span>
    : <AutomationControl value={automation} status={automationSteps({
      level: automation.level,
      generated: status === 'running' ? state.turn?.developmentOrigin?.kind ?? null : null,
      delivered: !automationActive && automationFailure === null && state.turn?.state === 'COMPLETED' && state.turn.developmentResult?.readyForDelivery === true,
      failure: automationFailure,
      pullRequest: taskPr,
      ci: state.task?.developmentCi ?? null,
      reviewFix: state.task?.reviewFix ?? null,
      autoMerge: autoMerge ?? null,
      now,
    })} />;
  const statusLabel =question !== null ? 'Waiting for your response'
    : status === 'running' && waiting ? 'Needs approval'
    : status === 'running' && generatedActivity !== null ? generatedActivity
    : STATUS_LABELS[status];
  const statusText = state.turn === null
    ? statusLabel
    : `${statusLabel} · turn ${state.turn.state.toLowerCase()}`;
  const planTurnId = state.task?.mutationClaim?.kind === 'TURN' ? state.task.mutationClaim.ownerId : state.latestTurnId ?? state.turn?.id ?? null;
  const plan = status === 'running' && after === null && state.capabilities?.structuredPlans === true
    ? state.turn?.id === planTurnId && state.turn.plan != null ? { steps: state.turn.plan }
      : currentPlan(planTurnId === null ? turns : turns.filter((turn) => turn.turnId === planTurnId)) : null;
  const nextImagesAccepted = !keyLane && acceptsImages(models, state.task?.defaults.model, state.capabilities);
  const activeImagesAccepted = !keyLane && acceptsImages(models, state.turn?.settings?.model, state.capabilities);
  const imagesAccepted = status === 'running'
    ? nextImagesAccepted || (state.capabilities?.steering === true && activeImagesAccepted)
    : nextImagesAccepted;
  const attachTitle = status === 'running'
    ? (imagesAccepted ? 'The current or next-turn model accepts images' : 'Neither the current nor next-turn model accepts images')
    : (imagesAccepted
      ? `${modelName(models, state.task?.defaults.model ?? null)} accepts images`
      : `${modelName(models, state.task?.defaults.model ?? null)} does not accept images`);
  const hasDraftImages = state.attachments.length > 0;
  const steerImageBlocked = hasDraftImages && !activeImagesAccepted;
  const queueImageBlocked = hasDraftImages && !nextImagesAccepted;
  const authenticationFailure = state.turn?.failureKind === 'AUTHENTICATION_REQUIRED';
  const providerBlocked = keyRemoved || (providerStatus !== null && !providerAvailable(providerStatus.readiness));
  const providerRecoveryRequired = !routedToCi && status === 'idle' && !automationActive && (authenticationFailure || providerBlocked);
  const providerStartBlocked = providerBlocked || (authenticationFailure && !providerStatusFresh);
  const authenticationRetry = authenticationFailure ? retryableInput(state) : null;
  return (
    <PendingReviewComments value={{ comments: pullRequestPendingComments, onChange: onPullRequestPendingCommentsChange }}>
    <div className="agt-screen">
      <div className="agt-body" onClickCapture={selectPullRequestTab}>
      <section className="agt-task-column" aria-label="Agent conversation">
      <TaskHeader onBack={onBack}
        title={<TaskTitle
          title={state.task?.title ?? 'Agent task'}
          provisional={state.task?.titleSource === 'PROVISIONAL'}
          onRename={state.task === null ? undefined : onRename}
        />}
        status={<>
          <span className={`agt-state is-${status}${waiting && status === 'running' ? ' is-waiting' : ''}`}>
            {status === 'running' && !waiting && <Spinner />}
            {(status === 'loading' || (status === 'running' && waiting)) && <span className="agt-state__dot" aria-hidden="true" />}
            {statusLabel}
          </span>
          {attentionStatus && <span className="attention-visit-status" role="status">{attentionStatus}</span>}
        </>}
        actions={<>
          {state.task !== null && <PinButton className="agt-headbtn" item={{
            key: `task:${state.task.id}`, title: state.task.title, reference: 'Task',
            target: { kind: 'task', workspaceId: state.task.workspaceId, taskId: state.task.id },
          }} />}
          {state.task !== null && onArchive !== undefined && (
            <button
              type="button"
              className="agt-headbtn is-shelf"
              title={taskArchived(state.task) ? 'Restore' : 'Archive'}
              aria-label={taskArchived(state.task) ? 'Restore' : 'Archive'}
              disabled={state.sending}
              onClick={() => onArchive(!taskArchived(state.task ?? { archivedAt: null }))}
            >
              {taskArchived(state.task) ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
            </button>
          )}
          {!terminal && (
            <button
              type="button"
              className="agt-headbtn"
              aria-label="Mark it complete"
              title={completeTitle(status, state)}
              onClick={onComplete}
              disabled={state.sending || !canComplete(state)}
            >
              <CircleCheck aria-hidden="true" />
            </button>
          )}
          {state.task !== null && status !== 'loading' && (
            <button
              type="button"
              className="agt-headbtn"
              aria-label="Show or hide the side panel"
              aria-pressed={panelOpen}
              title={supportsNestedAgents
                ? (hasAgents
                  ? `PR, changes and subagents (${agents.length + expiredChildren.length})`
                  : 'PR, changes and subagents')
                : 'PR and changes'}
              onClick={onTogglePanel}
            >
              <PanelRight aria-hidden="true" />
            </button>
          )}
          {headerAction}
        </>} />


      {/* Only connection states needing attention take space in the conversation. */}
      {((state.connection !== 'open' && state.connection !== 'connecting') || state.gap) && (
        <div className={`agt-conn is-${state.connection}`}>
          {state.connection === 'reconnecting' && <Spinner warn />}
          {connectionText(state)}
          {state.gap ? ' — waiting for a missing event' : ''}
        </div>
      )}
      {state.notice !== null && <div className="agt-notice" role="alert">{state.notice}</div>}

      <AgentConversation
        turnRequest={turnId => linkedWork.incoming?.turnId === turnId ? <LinkedWorkRequest work={linkedWork.incoming} /> : null}
        turnFooter={turnId => <>{linkedWork.links.filter(work => work.sourceTaskId === state.task?.id && work.sourceTurnId === turnId)
          .map(work => <LinkedWorkCard key={`${work.createdAt}:${work.targetWorkspaceId}`} work={work} />)}
          {backports.chains.filter(view => view.chain.requestingTaskId === state.task?.id && view.chain.acceptances[0]?.turnId === turnId)
            .map(view => <BackportChainCard key={view.chain.id} view={view} />)}</>}
        turns={turns} repository={repository} durableTurns={state.turns} durableTurnsLoading={durableTurnsLoading} models={models} now={now} scroll={scroll} workspaceId={state.task?.workspaceId}
        localReview={localReview} onOpenReview={onOpenReview} issueSource={state.task?.issueSource} jiraSource={state.task?.jiraSource}
        discussions={discussions?.discussions} discussionBusy={discussions?.busy} onSelectPassage={onSelectPassage} onOpenDiscussion={onOpenDiscussion}
        generatedBranch={state.task?.generatedBranch}
        connection={state.connection} running={status === 'running'} activeTurn={state.turn} activeTurnId={state.turn?.id ?? state.latestTurnId}
        waiting={waiting} gap={state.gap} pendingMessage={state.pendingMessage} activityChoice={activityChoice} turnChoice={turnChoice}
        previews={previews} agentLabels={agentLabels} pendingApprovals={pendingApprovals ?? []}
        onToggleTurn={onToggleTurn} onToggleActivity={onToggleActivity} onDecide={onDecide} onOpenAgent={onOpenAgent}
        transcriptFooter={<>
          {working.length > 0 && (
            <div className="agt-barrier">
              {working.length === 1 ? '1 subagent is still working' : `${working.length} subagents are still working`}
              {' — Send, queued messages, and Complete unlock when every agent and command finishes.'}
            </div>
          )}
        </>}>
      {/* Bounded: the status and turn state change a handful of times, streamed text never lands here. */}
      <div className="agt-status" role="status" aria-live="polite">{statusText}</div>

      {terminal && state.task !== null && <TerminalSummary task={state.task} repository={repository} onCleanup={onCleanup} />}

      {(automationActive || currentCi != null) && ciPollingError !== null && <div className="agt-notice" role="alert">{ciPollingError}</div>}
      {(automationActive || currentCi != null) && ciApprovalError !== null && <div className="agt-notice" role="alert">{ciApprovalError}</div>}

      {automationFailure !== null && recordedCiFailure === null && <div className="agt-notice" role="alert">
        {automationFailure}
      </div>}

      {currentCi?.status === 'PAUSED' && currentCi.canMessage && currentCi.summary !== null
        && <div className="agt-barrier" role="status">{renderMarkdown(currentCi.summary, repository)}</div>}

      {!terminal && automationActive && currentCi == null && status !== 'recovery' && state.developmentProgress?.message
        && <div className="agt-barrier" role="status">{state.developmentProgress.message}</div>}

      {!terminal && plan !== null && plan.steps.length > 0 && <PlanCard steps={plan.steps} open={planOpen} onToggle={onTogglePlan} repository={repository} />}

      {!terminal && (
        <ConversationQueue
          queue={state.queue.map(input => ({
            ...input,
            preview: localReview?.threads.flatMap(thread => thread.messages).find(message => message.queuedInputId === input.id)?.body ?? input.preview,
            attachmentCount: input.settings.attachments.length,
            steerBlockedReason: input.settings.attachments.length > 0 && !activeImagesAccepted ? 'The running turn’s model does not accept images' : undefined,
            modelLabel: `${modelName(models, input.settings.model)}${input.settings.effort === null ? '' : ` ${input.settings.effort}`}${input.settings.serviceTier == null ? '' : ` · ${serviceTierName(models, input.settings.model, input.settings.serviceTier)}`}`,
          }))}
          running={status === 'running'}
          idle={status === 'idle'}
          resumeBlocked={providerStartBlocked || automationActive || question !== null}
          resumeBlockedReason={question !== null ? 'Answer the agent’s question before resuming earlier queued messages'
            : automationActive ? 'Available when automation finishes' : undefined}
          onRemove={onRemoveQueued}
          onEdit={onEditQueued}
          editEnabled={!state.sending && state.composer === '' && state.attachments.length === 0 && status !== 'closing'}
          steerEnabled={canSteer(state, state.queue[0]?.preview ?? '')}
          onSteer={onSteerQueued}
          onResume={onResumeQueue}
        />
      )}

      {status === 'recovery' && state.task !== null && (
        <RecoveryCard task={state.task} turn={state.turn} working={working} onStopServer={onStopServer} onMarkFailed={onMarkFailed} />
      )}

      {providerRecoveryRequired && (
        <ProviderRecoveryCard
          provider={provider}
          laneId={state.task?.laneId ?? ''}
          status={providerStatus}
          keyRemoved={keyRemoved}
          statusFresh={providerStatusFresh}
          failedTurn={authenticationFailure}
          checking={providerChecking}
          input={authenticationRetry}
          sending={state.sending}
          onRefresh={onRefreshProvider}
          onRetry={onRetryAuthentication}
          onOpenSettings={onOpenSettings}
          onSignIn={onSignInProvider}
        />
      )}

      {state.task?.jiraSource != null && <JiraSourceAttachment source={state.task.jiraSource} />}
      {state.task?.issueSource != null && <IssueAttachment source={state.task.issueSource} />}

      {/* What automation is doing now on the left, the task's pull request on the right. */}
      {(automationPill !== null || taskPr !== null) && <div className="agt-auto-row">
        {automationPill}
        {taskPr !== null && (
          <PullRequestChip
            pullRequest={taskPr}
            pressed={pullRequestShowing}
            onClick={() => { if (pullRequestShowing) onTogglePanel(); else showPullRequest(); }}
          />
        )}
      </div>}

      {/* The design's composer: the message above one row of controls, and one round button. */}
      {!terminal && status !== 'recovery' && !providerRecoveryRequired && (
        <ConversationComposer
          automation={automationControl}
          ciMention={state.task?.interactive === true} routedToCi={routedToCi}
          ciMessageHint={routedToCi ? ciUnavailable ?? (currentCi?.status === 'ACTIVE' ? 'Steer the running CI agent' : 'Send to CI Autofix') : undefined}
          placeholder={question !== null ? 'Waiting for your response…'
            : status === 'idle' && automationActive && !routedToCi ? 'Draft your next message…' : undefined}
          value={state.composer} onChange={onCompose} onSend={onSend} onStop={automationActive ? onStopServer : onCancel} onSteer={onSteer} onQueue={onQueue}
          running={status === 'running'} sending={state.sending}
          stopLabel={automationActive ? 'Stop automation' : undefined}
          stopTitle={automationActive ? 'Stops automation and keeps this task open after its agents stop' : undefined}
          disabled={status === 'closing' || state.connection === 'connecting' || (state.sending && !attaching)}
          sendEnabled={routedToCi ? ciUnavailable === null && ciPollingError === null && !ciSubmissionBlocked : canSend(state) && !submissionBlocked}
          steerEnabled={!routedToCi && canSteer(state) && !steerImageBlocked} queueEnabled={!routedToCi && canQueue(state) && !queueImageBlocked && !submissionBlocked}
          connection={state.connection} closing={status === 'closing'}
          composerRef={composerRef}
          onEditQueued={onEditQueued !== undefined && state.queue[0]?.turnId === null
            && ['QUEUED', 'PAUSED'].includes(state.queue[0].state) ? () => onEditQueued(state.queue[0].id) : undefined}
          onPaste={event => pasteImage(event, !routedToCi && imagesAccepted && (status === 'idle' || status === 'running') && !state.sending, onPasteImages)}
          steerTitle={steerImageBlocked ? 'The running turn’s model does not accept images'
            : state.capabilities?.steering === true ? 'Sends into the running turn now' : 'This provider cannot take input into a running turn'}
          queueTitle={queueImageBlocked ? 'The next-turn model does not accept images' : 'Runs after this turn finishes'}
          attachments={state.attachments} previews={previews} onRemoveAttachment={onRemoveAttachment}
          onAttach={onAttach} attachEnabled={!routedToCi && imagesAccepted} attachTitle={routedToCi ? 'CI Autofix accepts text only' : attachTitle}
          models={models} provider={provider} model={state.task?.defaults.model ?? null}
          effort={state.task?.defaults.effort} serviceTier={state.task?.defaults.serviceTier} onSelectModel={onSelectModel}
          footer={<TaskStats metrics={state.task?.metrics ?? null} turns={turns} now={now} />} />
      )}
      </AgentConversation>
      </section>
      {panelOpen && state.task !== null && (
        <>
          <TaskPaneDivider pane={sidePanel} />
          <TaskSidePanel
            discussions={discussions}
            discussionTurns={discussionTurns}
            discussionFocus={discussionFocus}
            onJumpToPassage={onJumpToPassage}
            onHoverDiscussion={onHoverDiscussion}
            agents={agents}
            openAgent={supportsNestedAgents ? openAgent : null}
            expiredChildren={expiredChildren}
            deleteAfter={state.task.deleteAfter}
            transcript={agentTranscript}
            historyUnloaded={before !== null || after !== null}
            onLoadEarlier={() => loadHistory('older')}
            historyLoading={historyLoading}
            now={now}
            tab={visiblePanelTab}
            task={state.task}
            pullRequest={taskPr}
            pullRequestError={pullRequestError}
            pullRequestLoading={pullRequestLoading}
            pullRequestChecked={pullRequestChecked}
            onRefreshPullRequests={onRefreshPullRequests}
            pullRequestDetail={pullRequestDetail}
            pullRequestChanges={pullRequestChanges}
            pullRequestCommitRange={pullRequestCommitRange}
            pullRequestPendingComments={pullRequestPendingComments}
            localReview={localReview}
            learning={learning}
            reviewFocus={reviewFocus}
            onAskAgent={state.task?.state === 'OPEN' ? onAskAgent : undefined}
            onAskAgentDisabled={onAskAgentDisabled}
            onFixComment={state.task?.state === 'OPEN' ? onFixComment : undefined}
            onPullRequestCommitRangeChange={onPullRequestCommitRangeChange}
            onPullRequestPendingCommentsChange={onPullRequestPendingCommentsChange}
            workspaceId={state.task.workspaceId}
            branch={resultBranch}
            repository={repository}
            activityChoice={activityChoice}
            onOpenAgent={onOpenAgent}
            onCloseAgent={onCloseAgent}
            onToggleActivity={onToggleActivity}
            onDecide={onDecide}
            onSelectTab={onSelectPanelTab}
            onCodeUnsavedChange={onCodeUnsavedChange}
            onOpenPullRequest={onOpenPullRequest}
            width={sidePanel.width}
            showAgents={supportsNestedAgents}
            utilityTabs={utilityTabs}
            onCloseUtility={onCloseUtility}
            logChoice={logChoice}
            onToggleLog={onToggleLog}
          />
        </>
      )}
      </div>
    </div>
    </PendingReviewComments>
  );
}
