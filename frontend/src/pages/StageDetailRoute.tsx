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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useStageDetailData } from '../threads/brain/useStageDetailData';
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { prUrl } from '../activityNarrative';
import { useLocalPrActions } from '../pr/localpr/useLocalPrActions';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useThreadStream } from '../threads/useThreadStream';
import { ShipReviewPrompt, StaleMarkReadyGatePrompt, StaleShipGatePrompt } from '../threads/ShipReviewPrompt';
import type { DiffFileDto } from '../types';
import type { AgentRunDto, StageType, TaskPhase } from '../types/brainView';
import {
  Conv, EventRow, EventTimestamp, QueuedMessages, RoundEpisode, RunEpisode, Spine, Working,
} from '../ui/conv';
import { useTaskRuns } from '../threads/brain/useTaskRuns';
import { useTaskRounds } from '../threads/brain/useTaskRounds';
import { useMessageQueue } from '../threads/useMessageQueue';
import { stageFeed } from './stageConversationRow';
import type { PermissionDecideHandler } from '../threads/PermissionCard';
import { StageDetailPage } from './StageDetailPage';
import type { StageKind } from './StageDetailPage';
import { WorkModelPill } from '../workspace/WorkModelPill';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import { diffInlineCommentFromLocalPr, isPublishableReviewDraft } from '../diff/DiffInlineComments';
import { PlanCard, planStepComments } from '../threads/brain/TaskRootNode';
import { PlanOverlay } from './PlanOverlay';
import { TaskSidebar } from '../ui/shell/TaskSidebar';
import { buildLivePlan } from '../ui/shell/livePlanModel';
import { makeIdCache } from '../threads/brain/idCache';
import { useAgentReviewState } from '../review/useAgentReviewState';
import { ConvIndex } from '../threads/ConvIndex';
import { PullDetailBody } from '../pulls/PullDetailPane';
import { PullDetailHost } from '../pulls/PullDetailZoom';
import { pullRowFromLocal } from '../pulls/localRow';
import type { PullRow } from '../pulls/model';
import { derivePRCapabilities } from '../pr/prCapabilities';
import type { AgentReviewNavTarget } from '../pulls/agentColumnModel';
import { formatDuration } from '../threads/brain/format';
import { TaskChangedFilesCard } from './TaskChangedFilesCard';
import type { WsNavKey } from '../ui/workspace';
import PublishGatePane from '../PublishGatePane';
import { PushDialog } from '../pr/localpr/PushDialog';
import { deriveLocalReviewApproval, deriveLocalReviewGate } from '../pr/localpr/localReviewGate';
import { activelySubmittedCommentIds } from '../pr/localpr/localReviewSubmission';

/** Last-known cumulative diff per thread+task, so switching stages within a
 *  task paints the diff at once (the diff is task-wide, identical across the
 *  task's stages) instead of flashing "Loading diff…" on every hop. */
const diffCache = makeIdCache<DiffFileDto[]>();

/** Parse a server ISO timestamp to epoch-ms, or null when absent/invalid.
 *  Used to anchor the "working" elapsed counter to server time so it keeps
 *  ticking across a tab switch instead of restarting from a local mount time. */
function epochOrNull(ts: string | undefined): number | null {
  if (ts === undefined) {
    return null;
  }
  const ms = Date.parse(ts);
  return Number.isNaN(ms) ? null : ms;
}

const KIND: Partial<Record<StageType, StageKind>> = {
  PLAN_STAGE: 'plan',
  DEVELOPMENT_STAGE: 'dev',
  REMOTE_DEVELOPMENT_STAGE: 'remote-dev',
  CI_FIXING_STAGE: 'ci-fix',
  REVIEW_MONITOR_STAGE: 'comments',
  CLEANUP_STAGE: 'cleanup',
};

function stageKindLabel(kind: StageKind): string {
  switch (kind) {
    case 'plan': return 'PLAN STAGE';
    case 'dev': return 'DEV STAGE';
    case 'remote-dev': return 'REMOTE DEV STAGE';
    case 'ci-fix': return 'CI FIX STAGE';
    case 'comments': return 'COMMENTS STAGE';
    case 'cleanup': return 'CLEANUP STAGE';
  }
}

/**
 * Data adapter mounting the V3 {@link StageDetailPage} on the live stage
 * detail data. Maps the stage transcript → conversation (agent turns,
 * tool blocks, your steering, iteration markers) and wires the composer to
 * the stage's agent via {@code steerStage}. The right pane carries the full
 * PR · Changes · CI strip: PR renders the unified local/remote PR view
 * (falling back to the remote panel until the task has a local PR), Changes
 * renders the task's cumulative diff with local inline comments, and the CI
 * tab (CI-fix stage only) shows the live check run.
 */
export function StageDetailRoute({
  threadId, taskId, stageId, onOpenStage, onOpenRun, onOpenTask,
  onBack, onHistoryBack, onForward, backEnabled, forwardEnabled, collapsed, onToggleCollapse, onOpenBrain,
  trunkLabel, workspaceName, workspaceRepository,
  onNavigateGlobal, onSwitchWorkspace,
  onOpenAgentReview,
}: {
  threadId: string;
  taskId: string;
  stageId: string;
  /** Jump to another stage after approving the plan or from the task flow. */
  onOpenStage?: (stageId: string) => void;
  /** Navigate to a live run's own log from the stage feed's run/round episodes. */
  onOpenRun?: (runId: string) => void;
  /** Navigate to a sibling task under the same trunk from the sidebar list. */
  onOpenTask?: (taskId: string) => void;
  /** Open the task's owning trunk from the plain trunk row and breadcrumb. */
  onBack?: () => void;
  onHistoryBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  collapsed?: boolean;
  onToggleCollapse?: () => void;
  /** Navigate to this task's brain page — the live plan's Plan node. */
  onOpenBrain?: () => void;
  trunkLabel?: string;
  workspaceName?: string;
  workspaceRepository?: string;
  onNavigateGlobal?: (destination: WsNavKey) => void;
  onSwitchWorkspace?: () => void;
  /** Open the PR-owned full-review destination at the selected round. */
  onOpenAgentReview?: (target: AgentReviewNavTarget) => void;
}) {
  const { data, error: stageError, refresh } = useStageDetailData(stageId);
  const conversationThreadId = data?.conversationThreadId ?? threadId;
  const { proposal: shipProposal, refresh: refreshShipProposal } = usePendingShipProposal(threadId, taskId);
  const { data: brain, error: brainError, pollFast } = useBrainViewData(taskId);
  const conversationRef = useRef<HTMLDivElement | null>(null);
  // The plan is the task's; surface it on every stage via the reminder pill +
  // zoomed overlay (the plan no longer sits in the stage's tab strip).
  const plan = brain.rightRail.plan;
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [prZoomed, setPrZoomed] = useState(false);
  const {
    bundle: localPrBundle,
    refresh: refreshLocalPr,
    error: prError,
    deleteLocalComment,
    confirmPush, pushOpen, setPushOpen, prBusy,
  } = useLocalPrActions(taskId, { onAfterTransition: pollFast });
  const pendingLocalReviewRoots = useMemo(() => {
    const submitted = activelySubmittedCommentIds(localPrBundle?.timeline ?? []);
    return (localPrBundle?.comments ?? [])
      .filter(comment => isPublishableReviewDraft(comment) && !submitted.has(comment.id));
  }, [localPrBundle]);

  // Submits the selected private review comments to Development. Task-owned
  // comments never publish to GitHub.
  const [submittingReview, setSubmittingReview] = useState(false);
  const onSubmitReview = useCallback(async (body: string, verdict: ReviewVerdict) => {
    setSubmittingReview(true);
    try {
      await window.bridge.submitReview(taskId, {
        body, verdict, commentIds: pendingLocalReviewRoots.map(comment => comment.id),
      });
      pollFast();
    } finally {
      setSubmittingReview(false);
    }
  }, [taskId, pendingLocalReviewRoots, pollFast]);
  const submitAgentFindingsToTask = useCallback(async (verdict: ReviewVerdict, comments: Array<{ id: string }>) => {
    await window.bridge.submitReview(taskId, {
      commentIds: comments.map(comment => comment.id), verdict,
    });
    pollFast();
  }, [pollFast, taskId]);
  const agentReview = useAgentReviewState(localPrBundle, refreshLocalPr, submitAgentFindingsToTask);
  const canSubmitLocalReview = localPrBundle?.pr.origin === 'task'
    && localPrBundle.pr.status === 'local-open';
  const openAgentRound = useCallback((roundId?: string) => {
    const selected = roundId ?? agentReview.latestRound?.id;
    const review = agentReview.data?.review;
    const pr = localPrBundle?.pr;
    if (selected !== undefined && onOpenAgentReview !== undefined
        && review?.workspace_id != null && pr !== undefined) {
      onOpenAgentReview({
        threadId: review.owner_thread_id,
        taskId: review.owner_task_id,
        roundId: selected,
        workspaceId: review.workspace_id,
        prId: pr.id,
        repo: pr.repo ?? workspaceRepository ?? '',
        prNumber: pr.remotePrNumber,
      });
    }
  }, [agentReview.data?.review, agentReview.latestRound?.id, localPrBundle?.pr,
    onOpenAgentReview, workspaceRepository]);
  const pendingReviewComments = useMemo(
    () => pendingLocalReviewRoots.map(diffInlineCommentFromLocalPr),
    [pendingLocalReviewRoots],
  );

  // Force-opens the right-pane PR tab from task actions. A fresh token
  // re-fires even for a repeat click on the tab that's already open.
  const [openTabRequest, setOpenTabRequest] = useState<{ tab: 'pr' | 'ci'; token: number } | undefined>(
    undefined);
  const [openChangesToken, setOpenChangesToken] = useState<number>();
  const [openOverviewToken, setOpenOverviewToken] = useState<number>();
  const openTab = useCallback((tab: 'pr', subTab?: 'overview' | 'checks' | 'changes') => {
    refreshLocalPr();
    setOpenTabRequest(prev => ({ tab, token: (prev?.token ?? 0) + 1 }));
    if (subTab === 'overview') {
      setOpenChangesToken(undefined);
      setOpenOverviewToken(token => (token ?? 0) + 1);
    }
    if (subTab === 'changes') {
      setOpenOverviewToken(undefined);
      setOpenChangesToken(token => (token ?? 0) + 1);
    }
  }, [refreshLocalPr]);
  const openChanges = useCallback(() => openTab('pr', 'changes'), [openTab]);

  // The ready-for-review callout's inline gate — same semantics as the
  // task page: approve ships the parked proposal exactly as drafted.
  const [shipBusy, setShipBusy] = useState(false);
  const [shipNote, setShipNote] = useState<string | null>(null);
  const approveShip = useCallback(async () => {
    if (shipBusy || shipProposal === null) return;
    setShipBusy(true);
    setShipNote(null);
    try {
      const result = await window.bridge.approveNotification(
        shipProposal.id, null, proposalAction(shipProposal) ?? 'ship_task');
      if (result.resolution !== 'approved') setShipNote(result.message);
      pollFast();
      refresh();
      void refreshShipProposal();
    }
    catch (e) { setShipNote(e instanceof Error ? e.message : String(e)); }
    finally { setShipBusy(false); }
  }, [shipBusy, shipProposal, pollFast, refresh, refreshShipProposal]);
  const discardProposal = useCallback(async () => {
    if (shipBusy || shipProposal === null) return;
    setShipBusy(true);
    setShipNote(null);
    try {
      const action = proposalAction(shipProposal);
      if (action === null) throw new Error('This proposal has no supported action.');
      const result = await window.bridge.discardNotification(shipProposal.id, action);
      if (!result.ok) setShipNote(result.message);
      pollFast();
      refresh();
      void refreshShipProposal();
    }
    catch (e) { setShipNote(e instanceof Error ? e.message : String(e)); }
    finally { setShipBusy(false); }
  }, [shipBusy, shipProposal, pollFast, refresh, refreshShipProposal]);

  const stageKind: StageKind = data ? KIND[data.stage.type] ?? 'dev' : 'dev';
  const state = data?.stage.state;

  // ── Run / round episodes (plan-rail-runs.md Phase 5) ────────────────────
  // The Dev feed folds its `ci_fix` runs (not tied to a review round) into
  // episodes; the Comments feed replaces the flat transcript with the round
  // list entirely. Both read from the same task-wide runs fetch.
  const { runs: taskRuns, error: runsError } = useTaskRuns(taskId);
  const { rounds, error: roundsError, refresh: refreshRounds } = useTaskRounds(taskId);
  const devRunEpisodes = useMemo(
    () => taskRuns
      .filter(r => r.kind === 'ci_fix' && r.reviewRoundId === null)
      .sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt)),
    [taskRuns],
  );
  // A round can cycle through more than one ci_fix run over its lifetime (a
  // re-run per fix attempt) — keep the live one over a finished one so the
  // round's expanded body always shows what's actually happening now.
  const runsByRoundId = useMemo(() => {
    const map = new Map<string, AgentRunDto>();
    for (const r of taskRuns) {
      if (r.reviewRoundId === null) continue;
      const existing = map.get(r.reviewRoundId);
      const rLive = r.status === 'queued' || r.status === 'running'
        || r.status === 'paused' || r.status === 'awaiting_gate';
      if (existing === undefined || rLive) map.set(r.reviewRoundId, r);
    }
    return map;
  }, [taskRuns]);
  const [approvingRoundId, setApprovingRoundId] = useState<string | null>(null);
  const approveRound = useCallback((roundId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge?.approveRound) return;
    setActionError(null);
    setApprovingRoundId(roundId);
    void bridge.approveRound(roundId)
      .then(() => { refreshRounds(); refresh(); })
      .catch((reason: unknown) => setActionError(
        reason instanceof Error ? reason.message : 'Could not approve the review round'))
      .finally(() => setApprovingRoundId(null));
  }, [refreshRounds, refresh]);
  // Local-PR interactivity is gated on the TASK lifecycle, not the viewed
  // stage: the local-review moment arrives exactly when Dev closes, so a
  // closed stage must not disable commenting on a still-local PR.
  // Changes / PR tabs only apply to the work stages — the Plan stage is a
  // read-only conversation artifact with no diff of its own.
  const hasDiff = stageKind !== 'plan';

  // ── Right-pane data: the task's cumulative diff ─────────────────────────
  // Seed from the per-task cache so a stage switch shows the diff instantly
  // (stale-while-revalidate) rather than flashing the "Loading diff…" state.
  // Keyed by thread+task, not just thread — a thread can carry more than one
  // task, and each task's cumulative diff is its own.
  const diffCacheKey = `${threadId}:${taskId}`;
  const [files, setFiles] = useState<DiffFileDto[] | null>(() => diffCache.get(diffCacheKey) ?? null);
  const [commitCount, setCommitCount] = useState<number>();

  useEffect(() => {
    if (!hasDiff) return;
    setCommitCount(undefined);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTaskCumulativeDiff === undefined) return;
    let cancelled = false;
    void bridge.getTaskCumulativeDiff(threadId, taskId)
      .then(list => {
        if (cancelled) return;
        diffCache.set(diffCacheKey, list);
        setFiles(list);
      })
      .catch(() => { if (!cancelled) setFiles(prev => prev ?? []); });
    void bridge.listTaskCommits(threadId, taskId)
      .then(commits => { if (!cancelled) setCommitCount(commits.length); })
      .catch(() => { /* omit the count when git history is unavailable */ });
    return () => { cancelled = true; };
  }, [threadId, taskId, diffCacheKey, hasDiff]);

  const approvePlan = () => {
    if (plan === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.approvePlan(plan.planStageId)
      .then(result => { pollFast(); onOpenStage?.(result.devStageId); })
      .catch(() => { /* poll reconciles */ });
  };
  // The plan reminder pill (above the composer) opens the zoomed plan card in
  // an overlay — same affordance as the brain view, on every stage.
  const [planOpen, setPlanOpen] = useState(false);
  const closePlan = useCallback(() => setPlanOpen(false), []);
  const approvedAt = brain.brainFeed.find(r => r.type === 'PLAN_APPROVED')?.ts;
  const planCard = plan !== null ? (
    <PlanCard
      plan={plan}
      approvedAt={approvedAt}
      onApprove={plan.state === 'awaiting' ? approvePlan : undefined}
      onCommentStep={ord => { setText(`Re: step ${ord} — `); setPlanOpen(false); }}
      stepComments={planStepComments(brain.brainFeed)}
    />
  ) : null;

  const sendNow = useCallback((body: string, sendImages: string[] = []) => {
    setBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.steerStage(stageId, body, sendImages)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setBusy(false));
  }, [stageId, refresh]);

  // Interrupt the running turn — stops the agent doing more and stops it
  // waiting for further steering. Shared by the working-row Stop and the
  // composer's Stop button.
  const stopAgent = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.interruptTask(threadId).then(refresh).catch(() => { /* poll reconciles */ });
  }, [threadId, refresh]);

  // Live stream of the agent working this stage: its text appears
  // token-by-token (and a non-delta event refreshes the canonical
  // transcript, which clears the live buffer). This is what makes the stage
  // feel alive between the periodic poll snapshots.
  const { liveText, liveActivities } = useThreadStream(
    conversationThreadId, state === 'CLOSED' ? 'COMPLETED' : 'RUNNING', refresh);

  // Poll the thread's run state. This is the signal that stays true through a
  // long, quiet tool call (e.g. a multi-minute build) where no text streams
  // and the stage-state poll lags — so the working indicator doesn't blink
  // off mid-turn. Stops once the stage is closed.
  const [threadRunning, setThreadRunning] = useState(false);
  useEffect(() => {
    if (state === 'CLOSED') { setThreadRunning(false); return; }
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.getTask === undefined) return;
    let cancelled = false;
    const poll = () => {
      void bridge.getTask(threadId)
        .then(t => { if (!cancelled) setThreadRunning(t.status === 'RUNNING'); })
        .catch(() => { /* transient; next tick retries */ });
    };
    poll();
    const id = window.setInterval(poll, 3000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [threadId, state]);

  // Show the working indicator whenever a turn is executing — the thread is
  // RUNNING, the stage is ACTIVE, the user just steered, or text is streaming
  // in. Track when the working period began so the indicator can tick an
  // elapsed counter (a long, quiet turn shouldn't read as dead).
  const working = busy || threadRunning || liveText.length > 0;

  // Messages typed while the stage agent is working queue up and auto-send
  // when it goes idle; click one to pull it back into the composer to edit.
  const { queue, enqueue, takeForEdit, remove } = useMessageQueue(working, sendNow);
  const submit = () => {
    const body = text.trim();
    if (body.length === 0 && images.length === 0) return;
    // Queued (while-busy) sends are text-only, same tradeoff as the trunk
    // composer — an image attachment just waits for the composer to free up
    // instead of queueing.
    if (working && images.length > 0) return;
    setText('');
    if (working) enqueue(body);
    else {
      sendNow(body, images);
      setImages([]);
    }
  };

  // Surface the CLI agent's current activity — the latest tool call and (for
  // a shell command) the command itself — so a long stage turn shows what's
  // running rather than a bare "Agent is working…". Full command on hover.
  const lastTool = data?.conversation.filter(r => r.kind === 'tool_call').at(-1);
  const toolName = lastTool !== undefined ? (lastTool.toolTag?.trim() || 'Tool') : null;
  const toolArg = lastTool?.toolDetail?.trim() || null;
  // Elapsed ticks from the current activity's SERVER timestamp — the running
  // tool call, or the last transcript row — not a local mount time. Deriving
  // it from `data` (which is cached + polled) means leaving the tab and
  // coming back keeps counting instead of restarting at 0s.
  const currentLiveActivity = [...liveActivities].reverse().find(item => !item.done);
  const workingSince = currentLiveActivity?.startedAt
    ?? epochOrNull((lastTool ?? data?.conversation.at(-1))?.ts);
  const liveVerb = currentLiveActivity?.label
    ?? (toolName === null ? null : `Running ${toolName}`);
  const liveArg = currentLiveActivity?.detail ?? toolArg;
  // Read/Write args are file paths — split them off as a head-truncated tail
  // so the filename survives the worktree prefix. Bash/MCP args are commands:
  // leave them in the label so the head (the command itself) stays visible.
  const isPathArg = liveArg !== null && (currentLiveActivity !== undefined
    ? /read|writ|edit/i.test(currentLiveActivity.label)
    : toolName === 'Read' || toolName === 'Write');
  const workingTail = isPathArg ? liveArg : undefined;
  const workingLabel = liveVerb === null ? 'Agent is working…'
    : workingTail !== undefined ? `${liveVerb}:`
    : liveArg !== null ? `${liveVerb}: ${liveArg}`
    : `${liveVerb}…`;
  const workingDetail = currentLiveActivity?.detail ?? toolArg ?? undefined;

  // Answer a pending permission prompt that the agent raised on this stage
  // (e.g. a run_shell command), then refresh so the resolved card drops out.
  const onDecide = useCallback<PermissionDecideHandler>((callId, decision, preApprove) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge) {
      return;
    }
    void bridge.decideTaskPermission(conversationThreadId, callId, decision, preApprove)
      .then(() => refresh())
      .catch(() => { /* the next data refresh re-reflects the gate state */ });
  }, [conversationThreadId, refresh]);

  // Memoize the canonical transcript rows (each renders markdown) on the
  // conversation data alone — NOT on the composer's `text` or the streaming
  // `liveText`. Without this, every keystroke re-maps + re-renders the whole
  // feed, which makes typing crawl on a long conversation. Split before the
  // first follow-up turn so the changed-files artifact remains where the
  // initial work finished instead of moving to the bottom after every turn.
  const transcriptSegments = useMemo(() => {
    if (data === null) return undefined;
    const firstAgent = data.conversation.findIndex(row => row.kind === 'agent');
    const firstFollowUp = firstAgent < 0 ? -1 : data.conversation.findIndex(
      (row, index) => index > firstAgent && row.kind === 'user');
    const splitAt = firstFollowUp < 0 ? data.conversation.length : firstFollowUp;
    return [
      stageFeed(data.conversation.slice(0, splitAt), onDecide, conversationThreadId, false, true),
      stageFeed(data.conversation.slice(splitAt), onDecide, conversationThreadId, false, true),
    ] as const;
  }, [data, onDecide, conversationThreadId]);
  // Dev feed: the flat transcript, with any ci_fix runs folded in as episodes
  // (a live one flashes). Comments feed: the round list replaces the flat
  // transcript outright — a round IS the unit of work here, not a raw turn
  // sequence — with the live round's nested re-run + posting gate expanded.
  const feedRows = stageKind === 'comments'
    ? rounds.map(round => (
        <RoundEpisode
          key={round.id}
          round={round}
          nestedRun={runsByRoundId.get(round.id)}
          onOpenRun={onOpenRun}
          onApprove={approveRound}
          approveBusy={approvingRoundId === round.id}
        />
      ))
    : [
        ...devRunEpisodes.map(run => (
          <RunEpisode key={run.id} run={run} onOpen={() => onOpenRun?.(run.id)} />
        )),
        ...(transcriptSegments?.[0] ?? []),
        ...(files !== null && files.length > 0
          ? [<TaskChangedFilesCard
              key="changed-files"
              files={files}
              commitCount={commitCount}
              onReview={() => openTab('pr', 'changes')}
            />]
          : []),
        ...(transcriptSegments?.[1] ?? []),
      ];
  // The rail's entries come straight from the loaded transcript — a
  // stage's messages live in their own per-stage store whose seqs don't
  // exist in the task thread, so the thread-wide backend index can't
  // describe them (it used to drop prompts and preview the wrong text).
  const stageIndexEntries = useMemo(
    () => (data?.conversation ?? [])
      .flatMap(r => r.kind === 'user' && typeof r.messageSeq === 'number' && (r.text ?? '').trim().length > 0
        ? [{ seq: r.messageSeq, preview: (r.text ?? '').trim().slice(0, 80), tsMs: Date.parse(r.ts) }]
        : []),
    [data?.conversation]);
  const currentPhase = data?.task.currentPhase ?? brain.task.currentPhase;
  const localReviewGate = deriveLocalReviewGate(currentPhase, data?.devPhases ?? brain.devPhases);
  const localReviewApproval = deriveLocalReviewApproval(localPrBundle, localReviewGate);
  const canonicalLocalReviewPrompt = localReviewApproval === null ? null : (
    <ShipReviewPrompt
      onReview={openChanges}
      onApprove={() => setPushOpen(true)}
      // The human is the final authority: once the task is parked at the push
      // gate, Approve & ship stays enabled even with open findings or a failing
      // test — those surface as a warning note, not a hard block. Only mid-flight
      // (agent still working) keeps it disabled.
      approveDisabled={currentPhase !== 'AWAITING_PUSH'}
      onAskAgent={currentPhase === 'AWAITING_PUSH' && !localReviewApproval.enabled ? () => {
        const body = 'Address the remaining review comments and fix the failing local test, then I\'ll ship.';
        if (working) enqueue(body); else sendNow(body);
      } : undefined}
      onReviewChanges={() => openTab('pr', 'changes')}
      note={!localReviewApproval.enabled || localReviewGate.brainReview.state === 'unresolved'
        ? localReviewApproval.reason
        : null}
    />
  );
  const shipAction = proposalAction(shipProposal);
  const shipGatePrompt = shipAction === 'mark_ready'
    ? <StaleMarkReadyGatePrompt
        onDiscard={() => { void discardProposal(); }}
        busy={shipBusy}
        note={shipNote}
      />
    : shipAction === 'merge_pr' && shipProposal !== null
      ? <PublishGatePane
          notification={shipProposal}
          prTitle={localPrBundle?.pr.title ?? data?.task.title ?? brain.task.title}
          approveLabelOverride={localPrBundle?.pr.syncedMergeQueueEnabled === true ? 'Merge when ready' : undefined}
          onResolved={() => { pollFast(); refreshLocalPr(); void refreshShipProposal(); }}
          onViewPr={() => openTab('pr', 'overview')}
        />
    : shipAction === 'ship_task' && localPrBundle !== undefined
      ? localPrBundle === null && currentPhase === 'AWAITING_PUSH'
        ? <ShipReviewPrompt
            onReview={openChanges}
            onApprove={() => { void approveShip(); }}
            onDiscard={() => { void discardProposal(); }}
            onReviewChanges={() => openTab('pr', 'changes')}
            busy={shipBusy}
            note={shipNote}
          />
        : <StaleShipGatePrompt
            onDiscard={() => { void discardProposal(); }}
            busy={shipBusy}
            note={shipNote}
          />
      : canonicalLocalReviewPrompt;
  const conversation = (
    <Conv scrollRef={conversationRef}>
      <Spine>
        {data !== null && (
          <div className="workspace-task-stage-log__stamp">
            {stageKindLabel(stageKind)} · <EventTimestamp iso={data.stage.openedAt} />
          </div>
        )}
        {feedRows}
        {stageKind === 'comments' && files !== null && files.length > 0 && (
          <TaskChangedFilesCard
            files={files}
            commitCount={commitCount}
            onReview={() => openTab('pr', 'changes')}
          />
        )}
        {liveText.length > 0 && <EventRow kind="agent" who="Agent" markdown={liveText} />}
        {shipGatePrompt}
        <QueuedMessages
          messages={queue}
          onEdit={id => setText(takeForEdit(id))}
          onRemove={remove}
        />
        {working && liveText.length === 0 && (
          <Working
            label={workingLabel}
            tail={workingTail}
            detail={workingDetail}
            since={workingSince ?? undefined}
            activities={liveActivities}
            onStop={stopAgent}
          />
        )}
      </Spine>
      <PlanOverlay open={planOpen} card={planCard} onClose={closePlan} />
      {pushOpen && localPrBundle != null && (
        <PushDialog
          bundle={localPrBundle}
          repoLabel={workspaceRepository ?? data?.task.repoFullName ?? brain.task.repoFullName}
          busy={prBusy}
          onPush={confirmPush}
          onCancel={() => setPushOpen(false)}
        />
      )}
    </Conv>
  );

  // Normal stage pages reuse the locked Pull Requests detail body. Prefer the
  // local bundle's remote number, but fall back to the task linkage while that
  // aggregate catches up after a background push.
  const pr = data?.pr ?? null;
  const taskCompleted = data?.task.currentPhase === 'COMPLETED';
  const displayedLocalPrBundle = agentReview.displayedBundle ?? localPrBundle;
  const totalAdds = files?.reduce((n, f) => n + f.additions, 0) ?? 0;
  const totalDels = files?.reduce((n, f) => n + f.deletions, 0) ?? 0;
  const stageRemotePrNumber = displayedLocalPrBundle?.pr.remotePrNumber
    ?? data?.task.prNumber
    ?? brain.task.prNumber;
  const stagePrNumber = stageRemotePrNumber ?? 0;
  const stagePullRow = displayedLocalPrBundle !== null && displayedLocalPrBundle !== undefined
    ? ((): PullRow => {
        const base = pullRowFromLocal(
          displayedLocalPrBundle.pr,
          data?.task.repoFullName ?? brain.task.repoFullName,
          stagePrNumber,
        );
        const reviewState = agentReview.headerState === 'never' ? 'none' : agentReview.headerState;
        const additions = displayedLocalPrBundle.pr.syncedAdditions ?? totalAdds;
        const deletions = displayedLocalPrBundle.pr.syncedDeletions ?? totalDels;
        return {
          ...base,
          add: additions,
          del: deletions,
          hasAgent: reviewState !== 'none',
          dto: { ...base.dto, additions, deletions, reviewState },
        };
      })()
    : null;
  const onStagePrComment = async (body: string) => {
    if (displayedLocalPrBundle === null || displayedLocalPrBundle === undefined) return;
    const localPr = displayedLocalPrBundle.pr;
    if (derivePRCapabilities(localPr, 'details').postRemoteComment) {
      await window.bridge.postRemotePrComment(localPr.id, body);
    }
    else {
      await window.bridge.addLocalPrComment(localPr.id, { scope: 'pr', body });
    }
    refreshLocalPr();
  };
  const stagePullDetail = stagePullRow !== null && displayedLocalPrBundle !== null
      && displayedLocalPrBundle !== undefined ? (
    <PullDetailHost
      zoomed={prZoomed}
      onClose={() => setPrZoomed(false)}
      normalStyle={{ display: 'flex', flex: 1, minWidth: 0, minHeight: 0 }}
    >
      <PullDetailBody
        key={stagePullRow.id}
        row={stagePullRow}
        bundle={displayedLocalPrBundle}
        refresh={refreshLocalPr}
        openOverviewToken={openOverviewToken}
        openChangesToken={openChangesToken}
        changesFiles={displayedLocalPrBundle.pr.remotePrNumber === null ? files : undefined}
        fetchChangesBlob={displayedLocalPrBundle.pr.remotePrNumber === null
          ? (path) => window.bridge.fetchTaskFileBlob(threadId, taskId, path)
          : undefined}
        onClosePullRequest={displayedLocalPrBundle.pr.remotePrNumber !== null
            && displayedLocalPrBundle.pr.repo !== null
            && displayedLocalPrBundle.pr.status !== 'merged'
            && displayedLocalPrBundle.pr.status !== 'closed' ? async () => {
              await window.bridge.commentPr(
                Number(stagePullRow.dto.id) || 0,
                displayedLocalPrBundle.pr.repo!,
                displayedLocalPrBundle.pr.remotePrNumber!,
                '',
                true,
              );
              refreshLocalPr();
              pollFast();
            } : undefined}
        onComment={onStagePrComment}
        onAssignAgent={() => { void agentReview.startReview(); }}
        onWorkWithAgent={() => openAgentRound()}
        onOpenInWorkspace={onOpenBrain}
        zoomed={prZoomed}
        onToggleZoom={() => setPrZoomed(value => !value)}
      />
    </PullDetailHost>
  ) : null;
  const planStages = data?.allStages ?? brain.stages;
  const planSubStages = data?.subStages ?? brain.subStages;
  const planLiveRuns = data?.liveRuns ?? brain.liveRuns;
  const planGuard = data?.guard ?? brain.guard;
  const planLiveRound = data?.liveRound ?? brain.liveRound;
  const planDevPhases = data?.devPhases ?? brain.devPhases;
  const sidebarPhase = (data?.task.currentPhase ?? brain.task.currentPhase) as TaskPhase;
  const livePlanNodes = useMemo(() => buildLivePlan({
    stages: planStages,
    subStages: planSubStages,
    liveRuns: planLiveRuns,
    guard: planGuard,
    liveRound: planLiveRound,
    task: {
      prNumber: data?.task.prNumber ?? brain.task.prNumber,
      currentPhase: sidebarPhase,
      paused: brain.task.paused,
      terminal: brain.task.terminal,
    },
    prStatus: pr?.status ?? null,
    mergeReady: proposalAction(shipProposal) === 'merge_pr',
    viewedStageId: stageId,
    working,
    devPhases: planDevPhases,
    ciStatus: brain.rightRail.linkedPr?.ciStatus ?? null,
    ciSummary: brain.rightRail.linkedPr?.ciSummary ?? null,
  }), [
    planStages, planSubStages, planLiveRuns, planGuard, planLiveRound, planDevPhases,
    data?.task.prNumber, brain.task.prNumber, sidebarPhase, brain.task.paused, brain.task.terminal,
    pr, shipProposal, stageId, working, brain.rightRail.linkedPr,
  ]);
  const activeStageLabel = livePlanNodes.find(node => node.activeView)?.label
    ?? (stageKind === 'plan' ? 'Planning'
      : stageKind === 'remote-dev' ? 'Remote Development'
        : stageKind === 'ci-fix' ? 'CI Fixing'
          : stageKind === 'comments' ? 'Review Comments'
            : stageKind === 'cleanup' ? 'Cleanup'
              : 'Local Development');
  const topLevelStages = planStages.filter(stage => stage.callerStageId === null);
  const stagePosition = Math.max(1, topLevelStages.findIndex(stage => stage.id === stageId) + 1);
  const stageDurationSec = data?.stage.metrics.activeTimeSec
    ?? data?.stage.metrics.wallTimeSec
    ?? (data === null ? 0 : Math.max(0, Math.round((Date.parse(data.stage.closedAt ?? new Date().toISOString())
      - Date.parse(data.stage.openedAt)) / 1000)));
  const stageContext = data?.context ?? brain.rightRail.context;
  const retryingExhaustedCi = brain.task.paused
    && brain.task.currentPhase === 'NEEDS_ATTENTION'
    && ['ci fix attempts exhausted', 'ci fix no changes'].some(
      reason => brain.task.statusLabel.toLowerCase().startsWith(reason));
  const embeddedPr = stagePullDetail !== null && stageRemotePrNumber !== null ? {
    number: stageRemotePrNumber,
    status: taskCompleted ? 'merged' : (pr?.status ?? displayedLocalPrBundle?.pr.status ?? 'open'),
    onOpen: () => openTab('pr', 'overview'),
    onOpenRemote: () => {
      void window.bridge.openInAppBrowser(
        prUrl(data?.task.repoFullName ?? brain.task.repoFullName, stageRemotePrNumber));
    },
  } : undefined;
  const sidebar = (
    <TaskSidebar
      task={{
        title: data?.task.title ?? brain.task.title,
        branch: data?.task.branch ?? brain.task.branch,
        taskNumber: data?.task.taskNumber ?? brain.task.taskNumber,
        repository: workspaceRepository ?? data?.task.repoFullName ?? brain.task.repoFullName,
        workspaceName,
        metaLine: sidebarPhase.replace(/_/g, ' ').toLowerCase(),
        finished: taskCompleted || (data === null && brain.task.terminal),
      }}
      threadLabel={trunkLabel}
      threadId={threadId}
      currentTaskId={taskId}
      onOpenTask={onOpenTask}
      nodes={livePlanNodes}
      onBack={onHistoryBack}
      onForward={onForward}
      backEnabled={backEnabled}
      forwardEnabled={forwardEnabled}
      onToggleCollapse={onToggleCollapse}
      onOpenTrunk={onBack}
      onOpenStage={onOpenStage}
      onOpenPr={pr !== null ? () => openTab('pr', 'overview') : undefined}
      onOpenTab={openTab}
      onOpenBrain={onOpenBrain}
      onOpenRun={onOpenRun}
      onNavigateGlobal={onNavigateGlobal}
      onSwitchWorkspace={onSwitchWorkspace}
    />
  );

  return (
    <StageDetailPage
      stageKind={stageKind}
      sidebar={collapsed ? undefined : sidebar}
      openTabRequest={openTabRequest}
      stage={{ title: activeStageLabel, branch: data?.task.branch ?? brain.task.branch }}
      taskTitle={data?.task.title ?? brain.task.title}
      taskNumber={data?.task.taskNumber ?? brain.task.taskNumber}
      trunkLabel={trunkLabel}
      onOpenTrunk={onBack}
      onOpenTask={onOpenBrain}
      pr={embeddedPr}
      conversation={conversation}
      conversationIndex={data?.conversationThreadId != null ? (
        <ConvIndex
          threadId={data.conversationThreadId}
          scrollContainerRef={conversationRef}
          localEntries={stageIndexEntries}
        />
      ) : undefined}
      composer={{
        value: text,
        onChange: setText,
        onSubmit: submit,
        busy: working,
        queueWhenBusy: true,
        onStop: stopAgent,
        placeholder: 'Steer this stage…',
        closedNote: state === 'CLOSED'
          ? 'This stage is closed — ask about what happened here…'
          : undefined,
        images,
        onImagesChange: setImages,
        modePill: <WorkModelPill variant="workspace-v2" scope={{ kind: 'stage', stageId }}
          agentLockPending={working} />,
        usage: {
          contextPercent: stageContext.tokensLimit > 0
            ? Math.round((stageContext.tokensUsed / stageContext.tokensLimit) * 100)
            : 0,
          sessionLabel: `${stageContext.tokensUsed.toLocaleString('en-US')} tokens`,
        },
        meta: `Stage ${stagePosition} of ${Math.max(1, topLevelStages.length)} · ${formatDuration(stageDurationSec)}`,
      }}
      run={{
        paused: brain.task.paused,
        terminal: brain.task.terminal,
        statusLabel: brain.task.paused ? brain.task.statusLabel : state ?? 'Running',
        statusDetail: brain.task.paused
          ? brain.rightRail.approval?.reasonShort
            ?? brain.devPhases.find(phase => /failed|attention|unresolved/i.test(phase.meta ?? ''))?.meta
          : undefined,
        onPause: () => {
          setActionError(null);
          void window.bridge.pauseTask(threadId, taskId)
            .then(() => { pollFast(); refresh(); })
            .catch((reason: unknown) => setActionError(
              reason instanceof Error ? reason.message : 'Could not pause the task'));
        },
        onResume: () => {
          setActionError(null);
          const action = retryingExhaustedCi
            ? window.bridge.retryFailedCi(threadId, taskId)
            : window.bridge.resumePausedTask(threadId, taskId);
          void action
            .then(() => { pollFast(); refresh(); })
            .catch((reason: unknown) => setActionError(
              reason instanceof Error ? reason.message
                : retryingExhaustedCi ? 'Could not retry CI' : 'Could not resume the task'));
        },
        resumeLabel: retryingExhaustedCi ? 'Retry CI' : undefined,
        resumeConfirmation: retryingExhaustedCi ? {
          title: 'Retry failed CI?',
          body: `This asks GitHub Actions to rerun the failed checks for PR #${brain.task.prNumber ?? ''}. No code will be changed unless a later CI-fix turn creates a commit.`,
          confirmLabel: 'Retry CI',
        } : undefined,
      }}
      error={actionError ?? prError ?? stageError ?? brainError ?? roundsError ?? runsError}
      tabs={{
        pr: stagePullDetail ?? undefined,
      }}
      changes={hasDiff ? {
        additions: totalAdds,
        deletions: totalDels,
        onOpen: () => openTab('pr', 'changes'),
      } : undefined}
      onSubmitReview={canSubmitLocalReview ? onSubmitReview : undefined}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={deleteLocalComment}
      planReminder={plan === null ? undefined
        : plan.state === 'awaiting' ? 'awaiting'
        : plan.state === 'locked' ? 'locked'
        : undefined}
      onRevealPlan={plan !== null ? () => setPlanOpen(true) : undefined}
    />
  );
}
