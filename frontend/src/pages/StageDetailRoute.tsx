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
import { useLocalPrActions } from '../pr/localpr/useLocalPrActions';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useThreadStream } from '../threads/useThreadStream';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import { MarkReadyPrompt } from '../threads/MarkReadyPrompt';
import type { DiffFileDto, UserProfileDto } from '../types';
import { getCached } from '../dataCache';
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
import { diffInlineCommentFromLocalPr, isPendingLocalComment } from '../diff/DiffInlineComments';
import { PlanCard, planStepComments } from '../threads/brain/TaskRootNode';
import { PlanOverlay } from './PlanOverlay';
import { buildTaskAgentReviewTrack, TaskSidebar } from '../ui/shell/TaskSidebar';
import { buildGuardChip, buildLivePlan } from '../ui/shell/livePlanModel';
import { makeIdCache } from '../threads/brain/idCache';
import { useAgentReviewState } from '../review/useAgentReviewState';
import { ConvIndex } from '../threads/ConvIndex';
import { PullDetailBody } from '../pulls/PullDetailPane';
import { pullRowFromLocal } from '../pulls/localRow';
import type { PullRow } from '../pulls/model';
import { derivePRCapabilities } from '../pr/prCapabilities';
import { formatDuration } from '../threads/brain/format';
import { TaskChangedFilesCard } from './TaskChangedFilesCard';

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
  threadId, taskId, stageId, onOpenCode, onOpenStage, onOpenRun,
  onBack, onHistoryBack, onForward, backEnabled, forwardEnabled, onToggleCollapse, onOpenBrain,
  trunkLabel, workspaceName, workspaceRepository,
  onNavigateGlobal, onSwitchWorkspace, onNotifications, notificationCount,
  onOpenAgentReview,
}: {
  threadId: string;
  taskId: string;
  stageId: string;
  onOpenCode: () => void;
  /** Jump to another stage — used after approving the plan, which closes
   *  this Plan stage and opens the Development stage, and by the live-plan
   *  diagram in the task sidebar. */
  onOpenStage?: (stageId: string) => void;
  /** Navigate to a live run's own log — the rail's Remote CI / comments rows
   *  and the stage feed's run/round episodes use this. */
  onOpenRun?: (runId: string) => void;
  /** Open the task's owning trunk from the plain trunk row and breadcrumb. */
  onBack?: () => void;
  /** Browser-style history back for the traffic-light row. */
  onHistoryBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
  /** Navigate to this task's brain page — the live plan's Plan node. */
  onOpenBrain?: () => void;
  trunkLabel?: string;
  workspaceName?: string;
  workspaceRepository?: string;
  onNavigateGlobal?: (destination: 'home' | 'workspaces') => void;
  onSwitchWorkspace?: () => void;
  onNotifications?: () => void;
  notificationCount?: number;
  /** Open the task-owned full review-round surface at the selected round. */
  onOpenAgentReview?: (roundId: string) => void;
}) {
  const { data, refresh } = useStageDetailData(stageId);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const { data: brain, pollFast } = useBrainViewData(taskId);
  const conversationRef = useRef<HTMLDivElement | null>(null);
  // The plan is the task's; surface it on every stage via the reminder pill +
  // zoomed overlay (the plan no longer sits in the stage's tab strip).
  const plan = brain.rightRail.plan;
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const {
    bundle: localPrBundle,
    refresh: refreshLocalPr,
    deleteLocalComment,
  } = useLocalPrActions(taskId, { onAfterTransition: pollFast });

  // Publishes the Submit-review drawer's body/verdict and this task's
  // unresolved diff comments to GitHub — the same action as TaskCodePage's
  // embedded "Submit review" button, surfaced here in the top bar too.
  const [submittingReview, setSubmittingReview] = useState(false);
  const onSubmitReview = useCallback(async (body: string, verdict: ReviewVerdict) => {
    setSubmittingReview(true);
    try {
      await window.bridge.submitReview(taskId, { body, verdict });
      pollFast();
    } finally {
      setSubmittingReview(false);
    }
  }, [taskId, pollFast]);
  const submitAgentFindingsToTask = useCallback(async (verdict: ReviewVerdict, comments: Array<{ body: string }>) => {
    await window.bridge.submitReview(taskId, {
      body: comments.map(comment => comment.body).join('\n\n'), verdict,
    });
    pollFast();
  }, [pollFast, taskId]);
  const agentReview = useAgentReviewState(localPrBundle, refreshLocalPr, submitAgentFindingsToTask);
  const openAgentRound = useCallback((roundId?: string) => {
    const selected = roundId ?? agentReview.latestRound?.id;
    if (selected !== undefined && onOpenAgentReview !== undefined) {
      onOpenAgentReview(selected);
    }
  }, [agentReview.latestRound?.id, onOpenAgentReview]);
  const pendingReviewComments = useMemo(
    () => (localPrBundle?.comments ?? []).filter(isPendingLocalComment).map(diffInlineCommentFromLocalPr),
    [localPrBundle],
  );

  // Force-opens the right-pane PR tab from the rail's gate nodes (Local
  // review / Remote pull request / Merge-Close, R27) and the review
  // callout's View PR — a fresh token re-fires even for a repeat click on
  // the tab that's already open.
  const [openTabRequest, setOpenTabRequest] = useState<{ tab: 'pr' | 'ci'; token: number } | undefined>(
    undefined);
  const openTab = useCallback((tab: 'pr', _subTab?: 'checks' | 'changes') => {
    setOpenTabRequest(prev => ({ tab, token: (prev?.token ?? 0) + 1 }));
  }, []);

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
    }
    catch (e) { setShipNote(e instanceof Error ? e.message : String(e)); }
    finally { setShipBusy(false); }
  }, [shipBusy, shipProposal, pollFast, refresh]);

  const stageKind: StageKind = data ? KIND[data.stage.type] ?? 'dev' : 'dev';
  const state = data?.stage.state;

  // ── Run / round episodes (plan-rail-runs.md Phase 5) ────────────────────
  // The Dev feed folds its `ci_fix` runs (not tied to a review round) into
  // episodes; the Comments feed replaces the flat transcript with the round
  // list entirely. Both read from the same task-wide runs fetch.
  const taskRuns = useTaskRuns(taskId);
  const { rounds, refresh: refreshRounds } = useTaskRounds(taskId);
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
      const rLive = r.status === 'running' || r.status === 'awaiting_gate';
      if (existing === undefined || rLive) map.set(r.reviewRoundId, r);
    }
    return map;
  }, [taskRuns]);
  const [approvingRoundId, setApprovingRoundId] = useState<string | null>(null);
  const approveRound = useCallback((roundId: string) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    setApprovingRoundId(roundId);
    void bridge?.approveRound(roundId)
      .then(() => { refreshRounds(); refresh(); })
      .catch(() => { /* poll reconciles */ })
      .finally(() => setApprovingRoundId(null));
  }, [refreshRounds, refresh]);
  // Local-PR interactivity is gated on the TASK lifecycle, not the viewed
  // stage: the local-review moment arrives exactly when Dev closes, so a
  // closed stage must not disable commenting on a still-local PR.
  const taskTerminal = brain.task.terminal;
  const realtimeCi = data?.realtimeCi ?? null;
  const prNumber = data?.task.prNumber ?? null;
  const repoFullName = data?.task.repoFullName;

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

  useEffect(() => {
    if (!hasDiff) return;
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
    return () => { cancelled = true; };
  }, [threadId, taskId, diffCacheKey, hasDiff]);

  const openPr = useCallback(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (realtimeCi !== null) { void bridge?.openInAppBrowser(realtimeCi.prUrl); return; }
    if (repoFullName != null && prNumber !== null) {
      void bridge?.openInAppBrowser(`https://github.com/${repoFullName}/pull/${prNumber}`);
    }
  }, [realtimeCi, repoFullName, prNumber]);

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

  // Live stream of the agent working this stage: its text appears
  // token-by-token (and a non-delta event refreshes the canonical
  // transcript, which clears the live buffer). This is what makes the stage
  // feel alive between the periodic poll snapshots.
  const { liveText, liveActivities } = useThreadStream(
    threadId, state === 'CLOSED' ? 'COMPLETED' : 'RUNNING', refresh);

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
  const working = busy || threadRunning || state === 'ACTIVE' || liveText.length > 0;

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
  const workingLabel = currentLiveActivity !== undefined
    ? `${currentLiveActivity.label}${currentLiveActivity.detail === null ? '…' : `: ${currentLiveActivity.detail}`}`
    : toolName === null
    ? 'Agent is working…'
    : toolArg !== null ? `Running ${toolName}: ${toolArg}` : `Running ${toolName}…`;
  const workingDetail = currentLiveActivity?.detail ?? toolArg ?? undefined;

  // Answer a pending permission prompt that the agent raised on this stage
  // (e.g. a run_shell command), then refresh so the resolved card drops out.
  const onDecide = useCallback<PermissionDecideHandler>((callId, decision, preApprove) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (!bridge) {
      return;
    }
    void bridge.decideTaskPermission(threadId, callId, decision, preApprove)
      .then(() => refresh())
      .catch(() => { /* the next data refresh re-reflects the gate state */ });
  }, [threadId, refresh]);

  // Memoize the canonical transcript rows (each renders markdown) on the
  // conversation data alone — NOT on the composer's `text` or the streaming
  // `liveText`. Without this, every keystroke re-maps + re-renders the whole
  // feed, which makes typing crawl on a long conversation.
  const transcriptRows = useMemo(
    () => (data !== null ? stageFeed(data.conversation, onDecide, threadId, false, true) : undefined),
    [data, onDecide, threadId],
  );
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
        ...(transcriptRows ?? []),
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
  const conversation = (
    <Conv scrollRef={conversationRef}>
      <Spine>
        {data !== null && (
          <div className="workspace-task-stage-log__stamp">
            {stageKindLabel(stageKind)} · <EventTimestamp iso={data.stage.openedAt} />
          </div>
        )}
        {feedRows}
        {files !== null && files.length > 0 && (
          <TaskChangedFilesCard files={files} onReview={onOpenCode} />
        )}
        {liveText.length > 0 && <EventRow kind="agent" who="Agent" markdown={liveText} />}
        {shipProposal !== null && (proposalAction(shipProposal) === 'mark_ready'
          ? <MarkReadyPrompt onReview={onOpenCode} />
          : (
            <ShipReviewPrompt
              onReview={onOpenCode}
              onApprove={() => { void approveShip(); }}
              onReviewChanges={() => openTab('pr', 'changes')}
              busy={shipBusy}
              note={shipNote}
            />
          ))}
        <QueuedMessages
          messages={queue}
          onEdit={id => setText(takeForEdit(id))}
          onRemove={remove}
        />
        {working && liveText.length === 0 && (
          <Working
            label={workingLabel}
            detail={workingDetail}
            since={workingSince ?? undefined}
            activities={liveActivities}
            onStop={() => {
              const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
              void bridge?.interruptTask(threadId).then(refresh).catch(() => { /* poll reconciles */ });
            }}
          />
        )}
      </Spine>
      <PlanOverlay open={planOpen} card={planCard} onClose={closePlan} />
    </Conv>
  );

  // Normal stage pages reuse the locked Pull Requests detail body. The local
  // bundle is the source of truth; before it has a remote number there is no
  // real PR page to embed, so the fixed column is omitted.
  const pr = data?.pr ?? null;
  const taskCompleted = data?.task.currentPhase === 'COMPLETED';
  const displayedLocalPrBundle = agentReview.displayedBundle ?? localPrBundle;
  const stageRemotePrNumber = displayedLocalPrBundle?.pr.remotePrNumber ?? null;
  const stagePullRow = displayedLocalPrBundle !== null && displayedLocalPrBundle !== undefined
      && stageRemotePrNumber !== null
    ? ((): PullRow => {
        const base = pullRowFromLocal(
          displayedLocalPrBundle.pr,
          data?.task.repoFullName ?? brain.task.repoFullName,
          stageRemotePrNumber,
        );
        const reviewState = agentReview.headerState === 'never' ? 'none' : agentReview.headerState;
        return {
          ...base,
          hasAgent: reviewState !== 'none',
          dto: { ...base.dto, reviewState },
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
    <PullDetailBody
      key={stagePullRow.id}
      row={stagePullRow}
      bundle={displayedLocalPrBundle}
      refresh={refreshLocalPr}
      onComment={onStagePrComment}
      onAssignAgent={() => { void agentReview.startReview(); }}
      onWorkWithAgent={() => openAgentRound()}
      onOpenInWorkspace={onOpenBrain}
    />
  ) : null;

  const totalAdds = files?.reduce((n, f) => n + f.additions, 0) ?? 0;
  const totalDels = files?.reduce((n, f) => n + f.deletions, 0) ?? 0;

  // The task-scoped sidebar with the live-plan lifecycle diagram, replacing
  // the global rail while inside a task. The plan diagram is task-level — the
  // same set of stages regardless of which one you're viewing — so it derives
  // from the stage detail when present and otherwise from the brain view
  // (which is keyed by taskId and stays loaded across stage switches). That
  // keeps the rail on screen when hopping to a not-yet-loaded stage instead of
  // collapsing the layout for a frame.
  const planStages = data?.allStages ?? brain.stages;
  const planSubStages = data?.subStages ?? brain.subStages;
  const planLiveRuns = data?.liveRuns ?? brain.liveRuns;
  const planGuard = data?.guard ?? brain.guard;
  const planLiveRound = data?.liveRound ?? brain.liveRound;
  const planDevPhases = data?.devPhases ?? brain.devPhases;
  const sidebarTitle = data?.task.title ?? brain.task.title;
  const sidebarBranch = data?.task.branch ?? brain.task.branch;
  const sidebarPhase = (data?.task.currentPhase ?? brain.task.currentPhase) as TaskPhase;
  const sidebarFinished = taskCompleted || (data === null && brain.task.terminal);
  const livePlanNodes = useMemo(() => buildLivePlan({
    stages: planStages,
    subStages: planSubStages,
    liveRuns: planLiveRuns,
    guard: planGuard,
    liveRound: planLiveRound,
    task: {
      prNumber,
      currentPhase: sidebarPhase,
      terminal: taskTerminal,
    },
    prStatus: pr?.status ?? null,
    mergeReady: proposalAction(shipProposal) === 'merge_pr',
    viewedStageId: stageId,
    // Pulse this stage's node while its agent is mid-turn.
    working,
    devPhases: planDevPhases,
    ciStatus: brain.rightRail.linkedPr?.ciStatus ?? null,
    ciSummary: brain.rightRail.linkedPr?.ciSummary ?? null,
  }), [
    planStages, planSubStages, planLiveRuns, planGuard, planLiveRound, planDevPhases, sidebarPhase, prNumber, pr,
    stageId, shipProposal, working, taskTerminal, brain.rightRail.linkedPr,
  ]);
  // Render once we have any stage data — the stage detail, or the task-level
  // brain stages — so the rail persists across stage switches.
  const sidebar = planStages.length === 0 ? undefined : (
    <TaskSidebar
      task={{
        title: sidebarTitle,
        branch: sidebarBranch,
        taskNumber: data?.task.taskNumber ?? brain.task.taskNumber,
        repository: workspaceRepository ?? data?.task.repoFullName ?? brain.task.repoFullName,
        workspaceName,
        metaLine: sidebarPhase.replace(/_/g, ' ').toLowerCase(),
        finished: sidebarFinished,
      }}
      nodes={livePlanNodes}
      guard={buildGuardChip(planGuard, taskTerminal)}
      onBack={onHistoryBack}
      onOpenTrunk={onBack}
      onForward={onForward}
      backEnabled={backEnabled}
      forwardEnabled={forwardEnabled}
      onToggleCollapse={onToggleCollapse}
      threadLabel={trunkLabel}
      user={getCached<UserProfileDto>('home:profile')?.login}
      onNavigateGlobal={onNavigateGlobal}
      onSwitchWorkspace={onSwitchWorkspace}
      onNotifications={onNotifications}
      notificationCount={notificationCount}
      onOpenStage={onOpenStage}
      onOpenCode={onOpenCode}
      onOpenPr={pr !== null ? openPr : undefined}
      onOpenTab={openTab}
      onOpenBrain={onOpenBrain}
      onOpenRun={onOpenRun}
      agentReview={agentReview.data === null
        ? undefined
        : buildTaskAgentReviewTrack(agentReview.data, openAgentRound)}
      onToggleGuard={enabled => {
        void window.bridge.updateTaskGuard(taskId, { enabled })
          .then(() => { pollFast(); refresh(); })
          .catch(() => { /* poll reconciles */ });
      }}
    />
  );

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
  const embeddedPr = stagePullDetail !== null && stageRemotePrNumber !== null ? {
    number: stageRemotePrNumber,
    status: taskCompleted ? 'merged' : (pr?.status ?? displayedLocalPrBundle?.pr.status ?? 'open'),
    onOpen: openPr,
  } : undefined;

  return (
    <StageDetailPage
      stageKind={stageKind}
      sidebar={sidebar}
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
        placeholder: 'Steer this stage…',
        closedNote: state === 'CLOSED'
          ? 'This stage is closed — ask about what happened here…'
          : undefined,
        images,
        onImagesChange: setImages,
        modePill: <WorkModelPill variant="workspace-v2" scope={{ kind: 'stage', stageId }} />,
        usage: {
          planPercent: stageContext.tokensLimit > 0
            ? Math.round((stageContext.tokensUsed / stageContext.tokensLimit) * 100)
            : 0,
          sessionLabel: `${stageContext.tokensUsed.toLocaleString('en-US')} AI credits`,
        },
        meta: `Stage ${stagePosition} of ${Math.max(1, topLevelStages.length)} · ${formatDuration(stageDurationSec)}`,
      }}
      run={{ paused: state === 'PAUSED', terminal: state === 'CLOSED', statusLabel: state ?? 'Running' }}
      tabs={{
        pr: stagePullDetail ?? undefined,
      }}
      changes={hasDiff ? { additions: totalAdds, deletions: totalDels, onOpen: onOpenCode } : undefined}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={deleteLocalComment}
      planReminder={plan === null ? undefined
        : plan.state === 'awaiting' ? 'awaiting'
        : plan.state === 'locked' ? 'locked'
        : undefined}
      onRevealPlan={plan !== null ? () => setPlanOpen(true) : undefined}
      markReadyReminder={proposalAction(shipProposal) === 'mark_ready'}
    />
  );
}
