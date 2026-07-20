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
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { useLocalPrActions } from '../pr/localpr/useLocalPrActions';
import { PRView } from '../pr/localpr/PRView';
import { LocalPrReviewScreen } from '../pr/localpr/LocalPrReviewScreen';
import { PushDialog } from '../pr/localpr/PushDialog';
import type { DiffFileDto, UserProfileDto } from '../types';
import { getCached } from '../dataCache';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useMessageQueue } from '../threads/useMessageQueue';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import { MarkReadyPrompt } from '../threads/MarkReadyPrompt';
import { MarkReadyPanel, markReadyPrRef } from '../threads/MarkReadyPanel';
import type { TaskPhase } from '../types/brainView';
import { Conv, DecisionNode, EventTimestamp, NodeCard, QueuedMessages, Working } from '../ui/conv';
import { SparkIcon } from '../ui/TaskBrainDesignIcons';
import { BrainFeed } from '../threads/brain/BrainFeed';
import { PlanCard, PlanningSeed, planStepComments } from '../threads/brain/TaskRootNode';
import { buildTaskAgentReviewTrack, TaskSidebar } from '../ui/shell/TaskSidebar';
import { buildGuardChip, buildLivePlan } from '../ui/shell/livePlanModel';
import { TaskBrainPage } from './TaskBrainPage';
import { WorkModelPill } from '../workspace/WorkModelPill';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import { diffInlineCommentFromLocalPr, isPendingLocalComment } from '../diff/DiffInlineComments';
import { PlanOverlay } from './PlanOverlay';
import { ConvIndex } from '../threads/ConvIndex';
import { AgentReviewHeaderAction } from '../review/AgentReviewHeaderAction';
import { AgentReviewRoundPage } from '../review/AgentReviewRoundPage';
import { useAgentReviewState } from '../review/useAgentReviewState';
import { PullDetailBody } from '../pulls/PullDetailPane';
import { pullRowFromLocal } from '../pulls/localRow';
import type { PullRow } from '../pulls/model';
import { derivePRCapabilities } from '../pr/prCapabilities';
import type { AgentReviewNavTarget } from '../pr/localpr/PrDetailsView';
import { formatCost, formatDuration } from '../threads/brain/format';
import { TaskChangedFilesCard } from './TaskChangedFilesCard';

/**
 * Data adapter that mounts the V3 {@link TaskBrainPage} on the live brain
 * data. Wires the brain feed → conversation, the composer → the brain
 * agent, the lifecycle Run menu, and the task-scoped sidebar's live-plan
 * diagram (which replaces the old stage-chip strip as the stage-navigation
 * surface).
 */
export function TaskBrainRoute({
  threadId, taskId, onOpenStage, onOpenRun, onClosed,
  onBack, onHistoryBack, onForward, backEnabled, forwardEnabled, onToggleCollapse,
  trunkLabel, workspaceName, workspaceRepository,
  onNavigateGlobal, onSwitchWorkspace, onNotifications, notificationCount,
  initialReviewRoundId, initialPrSubTab, onOpenAgentReview,
}: {
  threadId: string;
  taskId: string;
  onOpenStage: (stageId: string) => void;
  onOpenRun?: (runId: string) => void;
  /** Closing a task seals it terminal + reaps its worktree, so the page is
   *  a dead end afterwards — navigate away (back to the thread trunk). */
  onClosed: () => void;
  /** Open the task's owning trunk from the plain trunk row and breadcrumb. */
  onBack?: () => void;
  /** Browser-style history back for the traffic-light row. */
  onHistoryBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
  trunkLabel?: string;
  workspaceName?: string;
  workspaceRepository?: string;
  onNavigateGlobal?: (destination: 'home' | 'workspaces') => void;
  onSwitchWorkspace?: () => void;
  onNotifications?: () => void;
  notificationCount?: number;
  initialReviewRoundId?: string;
  initialPrSubTab?: 'changes';
  /** Opens the PR-owned AgentColumn destination instead of an inline round page. */
  onOpenAgentReview?: (target: AgentReviewNavTarget) => void;
}) {
  const { data, pollFast } = useBrainViewData(taskId);
  const { task, brainFeed, stages, subStages } = data;
  const conversationRef = useRef<HTMLDivElement | null>(null);
  const { proposal: shipProposal, refresh: refreshShipProposal } = usePendingShipProposal(threadId, taskId);
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  // Force-opens the PR tab's own Checks sub-tab — the Remote CI row's
  // click target. Declared ahead of the <PRView> construction below, which
  // reads it.
  const [prSubTabRequest, setPrSubTabRequest] = useState<{ subTab: 'conversation' | 'checks' | 'changes'; token: number } | undefined>(undefined);
  const [openOverviewToken, setOpenOverviewToken] = useState<number>();
  const [reviewTabRequest, setReviewTabRequest] = useState<{ tab: 'files' | 'review'; token: number }>();
  // The task's local PR — rendered in the right pane's PR tab through the
  // same unified <PRView> + user-gated actions the stage pages use.
  const {
    bundle: localPrBundle, refresh: refreshLocalPr, syncing: prSyncing, capabilities: prCapabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch,
    addLocalLineComment, replyLocalLineComment, replyLocalPrComment, resolveLocalComment, deleteLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy,
    runLocalTests, testsBusy,
  } = useLocalPrActions(taskId, { onAfterTransition: pollFast });

  // Publishes the Submit-review drawer's body/verdict and this task's
  // unresolved diff comments to GitHub.
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
  const [agentRoundId, setAgentRoundId] = useState<string | null>(null);
  const [selectedAgentFinding, setSelectedAgentFinding] = useState<string | null>(null);
  const [selectedAgentFile, setSelectedAgentFile] = useState<string | null>(null);
  const [selectedAgentLine, setSelectedAgentLine] = useState<number | null>(null);
  const openAgentRound = (roundId?: string) => {
    const selected = roundId ?? agentReview.latestRound?.id;
    if (selected === undefined) return;
    const review = agentReview.data?.review;
    const pr = localPrBundle?.pr;
    if (onOpenAgentReview !== undefined && review?.workspace_id != null && pr !== undefined) {
      onOpenAgentReview({
        threadId: review.owner_thread_id,
        taskId: review.owner_task_id,
        roundId: selected,
        workspaceId: review.workspace_id,
        prId: pr.id,
        repo: pr.repo ?? workspaceRepository ?? '',
        prNumber: pr.remotePrNumber,
      });
      return;
    }
    setReviewOpen(true);
    setAgentRoundId(selected);
  };
  const pendingReviewComments = useMemo(
    () => (localPrBundle?.comments ?? []).filter(isPendingLocalComment).map(diffInlineCommentFromLocalPr),
    [localPrBundle],
  );

  // Force-opens the right-pane PR tab from the rail's gate nodes (Local
  // review / Remote pull request / Merge-Close, R27) and the review
  // callout's View PR — a fresh token re-fires even for a repeat click on
  // the tab that's already open.
  const [openTabRequest, setOpenTabRequest] = useState<{ tab: 'pr'; token: number } | undefined>(undefined);
  const openTab = useCallback((tab: 'pr', subTab?: 'overview' | 'checks' | 'changes') => {
    refreshLocalPr();
    setOpenTabRequest(prev => ({ tab, token: (prev?.token ?? 0) + 1 }));
    if (subTab === 'overview') {
      setPrSubTabRequest(undefined);
      setOpenOverviewToken(token => (token ?? 0) + 1);
    }
    else if (subTab !== undefined) {
      setOpenOverviewToken(undefined);
      setPrSubTabRequest(prev => ({ subTab, token: (prev?.token ?? 0) + 1 }));
    }
  }, [refreshLocalPr]);
  const openChanges = useCallback(() => openTab('pr', 'changes'), [openTab]);

  // The ready-for-review callout's inline gate. Approve ships the parked
  // proposal exactly as drafted (the agent's stored PR title + body).
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
      void refreshShipProposal();
    }
    catch (e) { setShipNote(e instanceof Error ? e.message : String(e)); }
    finally { setShipBusy(false); }
  }, [shipBusy, shipProposal, pollFast, refreshShipProposal]);

  const askAgentToAddress = useCallback(() => {
    setText('Please address my review comments on the PR, then I\'ll push. ');
    // Land the cursor in the composer so the user can elaborate and send.
    requestAnimationFrame(() => {
      document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus();
    });
  }, []);

  // One task-wide diff backs both the locked timeline artifact and the local
  // PR's Changes tab before a remote PR number exists.
  const [reviewFiles, setReviewFiles] = useState<DiffFileDto[] | null>(null);
  const [reviewCommitCount, setReviewCommitCount] = useState<number>();
  useEffect(() => {
    setAgentRoundId(null);
    setSelectedAgentFinding(null);
    setSelectedAgentFile(null);
    setSelectedAgentLine(null);
    setPrSubTabRequest(initialPrSubTab === 'changes'
      ? previous => ({ subTab: 'changes', token: (previous?.token ?? 0) + 1 })
      : undefined);
    setOpenTabRequest(initialPrSubTab === 'changes'
      ? previous => ({ tab: 'pr', token: (previous?.token ?? 0) + 1 })
      : undefined);
    setReviewTabRequest(undefined);
    setReviewOpen(false);
    if (initialReviewRoundId !== undefined) {
      setReviewOpen(true);
      setAgentRoundId(initialReviewRoundId);
    }
  }, [threadId, taskId, initialReviewRoundId, initialPrSubTab, setReviewOpen]);
  useEffect(() => {
    setReviewFiles(null);
    setReviewCommitCount(undefined);
    const b = typeof window !== 'undefined' ? window.bridge : undefined;
    if (b?.getTaskCumulativeDiff === undefined) return;
    let cancelled = false;
    void b.getTaskCumulativeDiff(threadId, taskId)
      .then(list => { if (!cancelled) setReviewFiles(list); })
      .catch(() => { if (!cancelled) setReviewFiles([]); });
    void b.listTaskCommits(threadId, taskId)
      .then(commits => { if (!cancelled) setReviewCommitCount(commits.length); })
      .catch(() => { /* omit the count when git history is unavailable */ });
    return () => { cancelled = true; };
  }, [threadId, taskId]);
  // Auto-approve mode. The backend persists it per-task; a per-thread default
  // (localStorage) lets new tasks inherit the user's latest choice, with the
  // per-task toggle overriding (A4.3, defaulted). Toggling updates both.
  const [autoApprove, setAutoApprove] = useState(false);
  const threadDefaultKey = `bq.autoApprove.thread.${threadId}`;
  const readThreadDefault = () => {
    try { return typeof localStorage !== 'undefined' && localStorage.getItem(threadDefaultKey) === 'true'; }
    catch { return false; }
  };
  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    const threadDefault = readThreadDefault();
    bridge?.getTaskAutoApprove?.(threadId, taskId)
      .then(r => {
        const eff = r.enabled || threadDefault;
        setAutoApprove(eff);
        // A new task whose backend value is still off inherits the thread
        // default — persist it so the backend matches what the UI shows.
        if (eff && !r.enabled) bridge?.setTaskAutoApprove?.(threadId, taskId, true).catch(() => { /* poll reconciles */ });
      })
      .catch(() => setAutoApprove(threadDefault));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId, taskId]);
  const toggleAutoApprove = () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    const next = !autoApprove;
    setAutoApprove(next);
    try { if (typeof localStorage !== 'undefined') localStorage.setItem(threadDefaultKey, String(next)); }
    catch { /* storage unavailable */ }
    bridge?.setTaskAutoApprove?.(threadId, taskId, next)
      .then(r => setAutoApprove(r.enabled))
      .catch(() => setAutoApprove(!next));
  };
  // Auto-merge mode: on top of auto-approve, the final merge gate also
  // approves automatically. Only settable while the plan reads low-risk /
  // small-effort (backend re-checks and 409s; the UI already disables the
  // switch via PlanCard's own eligibility check). Enabling it flips
  // auto-approve on too, so reflect that locally rather than waiting on a
  // second poll.
  const [autoMerge, setAutoMerge] = useState(false);
  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.getTaskAutoMerge?.(threadId, taskId)
      .then(r => setAutoMerge(r.enabled))
      .catch(() => { /* poll reconciles */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId, taskId]);
  const toggleAutoMerge = () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    const next = !autoMerge;
    setAutoMerge(next);
    if (next) setAutoApprove(true);
    bridge?.setTaskAutoMerge?.(threadId, taskId, next)
      .then(r => { setAutoMerge(r.enabled); if (r.enabled) setAutoApprove(true); })
      .catch(() => setAutoMerge(!next));
  };
  // Minimum write-permission approvals a shipped PR needs before it counts as
  // merge-ready (0/1/2). Per-task, persisted; chosen on the plan card.
  const [minApprovals, setMinApprovalsState] = useState(0);
  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.getTaskMinApprovals?.(threadId, taskId)
      .then(r => setMinApprovalsState(r.minApprovals))
      .catch(() => { /* poll reconciles */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [threadId, taskId]);
  const setMinApprovals = (n: number) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    setMinApprovalsState(n);
    bridge?.setTaskMinApprovals?.(threadId, taskId, n)
      .then(r => setMinApprovalsState(r.minApprovals))
      .catch(() => { /* leave the optimistic value; a reload reconciles */ });
  };
  // Track the brain's answer count so the "working" indicator clears only
  // when a new response actually lands (not when the user's own message
  // persists into the feed).
  const responseCount = brainFeed.filter(r => r.type === 'BRAIN_AGENT_RESPONSE').length;
  const [awaitedAt, setAwaitedAt] = useState<number | null>(null);
  useEffect(() => {
    if (awaitedAt !== null && responseCount > awaitedAt) setAwaitedAt(null);
  }, [responseCount, awaitedAt]);
  const working = busy || awaitedAt !== null;

  const sendNow = useCallback((body: string, sendImages: string[] = []) => {
    setBusy(true);
    setAwaitedAt(responseCount);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.sendBrainMessage(taskId, body, sendImages)
      .then(() => pollFast())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
  }, [taskId, pollFast, responseCount]);
  // Messages typed while the brain is thinking queue up and auto-send when it
  // goes idle; click one to pull it back into the composer to edit.
  const { queue, enqueue, takeForEdit, remove } = useMessageQueue(working, sendNow);
  const submit = () => {
    const body = text.trim();
    if (body.length === 0 && images.length === 0) return;
    // See TrunkRoute's identical comment: an image attachment waits for the
    // composer to free up rather than queueing behind an in-flight turn.
    if (working && images.length > 0) return;
    setText('');
    if (working) enqueue(body);
    else {
      sendNow(body, images);
      setImages([]);
    }
  };

  const runAction = (fn?: (threadId: string, taskId: string) => Promise<unknown>) => () => {
    fn?.(threadId, task.id).then(() => pollFast()).catch(() => { /* poll reconciles */ });
  };

  // Close seals the task terminal and reaps its worktree; once it resolves
  // the page has nothing live to show, so leave for the thread trunk.
  const closeTask = () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.cancelTask(threadId, task.id)
      .then(() => onClosed())
      .catch(() => pollFast());
  };

  // The plan card (draft / awaiting / locked). When finalized and awaiting
  // the user, approving closes the PlanStage, opens the DevelopmentStage,
  // and navigates there.
  const plan = data.rightRail.plan;
  const approvePlan = () => {
    if (plan === null) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.approvePlan(plan.planStageId)
      .then(result => onOpenStage(result.devStageId))
      .catch(() => { /* poll reconciles */ });
  };
  // Ask the brain to revise — the typed feedback goes to the brain as a
  // message, which supersedes the draft with a fresh planning turn that has the
  // user's concerns in hand (richer than a blind replan).
  const requestRevision = (feedback: string) => {
    sendNow(feedback);
  };
  // The reminder pill opens the original execution plan card in an overlay.
  const [planOpen, setPlanOpen] = useState(false);
  const closePlan = useCallback(() => setPlanOpen(false), []);
  const [planInlineOpenOverride, setPlanInlineOpenOverride] = useState<boolean | null>(null);
  useEffect(() => setPlanInlineOpenOverride(null), [plan?.planStageId, plan?.state]);

  // While planning is live (draft / awaiting), the planning seed anchors the
  // top of the conversation as opening context and the typed plan card + review
  // bar render at the BOTTOM of the feed — where the eye lands when the view
  // scrolls to the latest message — rather than pinned above the feed. Once
  // locked the plan moves to the right-pane reference tab, so it never dupes.
  const showRoot = plan !== null && (plan.state === 'draft' || plan.state === 'awaiting');
  // The seed is the prose that opened the PlanStage (the trunk handoff) — the
  // brain view carries no dedicated seed field (DISCOVERY-FINDINGS #8).
  const seed = brainFeed.find(r => r.type === 'STAGE_OPENED' && r.stageType === 'PLAN_STAGE')?.body;
  const planConfidenceHigh = (plan?.signals.confidence ?? null) === 'high';
  const approvedAt = brainFeed.find(r => r.type === 'PLAN_APPROVED')?.ts;

  // The original execution plan card (steps + signals + review bar). The new
  // brain redesign keeps it inline even after approval; the reminder pill still
  // gives a one-click zoomed view from the composer row.
  // Suppressed until the brain has actually written something — a freshly
  // opened PlanStage's draft row is otherwise all empty placeholders (no
  // steps, no summary, "0 steps in scope"), which reads as broken rather
  // than "still thinking".
  const planHasContent = plan !== null
    && (plan.steps.length > 0 || plan.understandingSummary.trim().length > 0);
  const planCard = planHasContent && plan !== null ? (
    <PlanCard
      plan={plan}
      autoApprove={autoApprove}
      autoMerge={autoMerge}
      autoConfidenceHigh={planConfidenceHigh}
      approvedAt={approvedAt}
      onApprove={plan.state === 'awaiting' ? approvePlan : undefined}
      onRequestRevision={requestRevision}
      onCommentStep={ord => { setText(`Re: step ${ord} — `); setPlanOpen(false); }}
      onHoldAuto={toggleAutoApprove}
      onToggleAutoApprove={toggleAutoApprove}
      onToggleAutoMerge={toggleAutoMerge}
      minApprovals={minApprovals}
      onSetMinApprovals={setMinApprovals}
      stepComments={planStepComments(brainFeed)}
    />
  ) : null;
  const defaultInlinePlanOpen = plan?.state !== 'locked';
  const inlinePlanOpen = planInlineOpenOverride ?? defaultInlinePlanOpen;
  const planTimelineNode = planCard !== null && plan !== null ? (
    <NodeCard color="purple" mark={<SparkIcon />}>
      <div className="plan-feed-event">
        <div className="plan-feed-event__summary">
          <div className="plan-feed-event__copy">
            <strong>{plan.state === 'locked' ? 'Plan finalized' : 'Plan ready'}</strong>
            <span>
              rev {plan.revisionCount}
              {' · '}
              {plan.steps.length} {plan.steps.length === 1 ? 'step' : 'steps'}
              {' · '}
              {plan.signals.riskLevel} risk
              {' · '}
              {plan.signals.confidence ?? 'medium'} confidence
            </span>
            {approvedAt !== undefined && (
              <span className="plan-feed-event__time"><EventTimestamp iso={approvedAt} /></span>
            )}
          </div>
          <button
            type="button"
            className="plan-feed-event__toggle"
            onClick={() => setPlanInlineOpenOverride(!inlinePlanOpen)}
          >
            {inlinePlanOpen ? 'Hide plan' : 'View plan'}
          </button>
        </div>
        {inlinePlanOpen && <div className="plan-feed-event__card">{planCard}</div>}
      </div>
    </NodeCard>
  ) : null;
  const brainPromptSeqs = useMemo(
    () => new Set(
      brainFeed
        .flatMap(r => (r.type === 'USER_MESSAGE' || r.type === 'TRUNK_MESSAGE')
          && typeof r.messageSeq === 'number'
          ? [r.messageSeq]
          : [])),
    [brainFeed]);

  const conversation = (
    <Conv scrollRef={conversationRef}>
      {showRoot && seed !== undefined && seed.trim().length > 0 && (
        <PlanningSeed seed={seed} />
      )}
      <BrainFeed
        feed={brainFeed}
        stages={stages}
        density="focused"
        foldClosedStages={false}
        onOpenStage={onOpenStage}
        threadId={threadId}
        developmentArtifact={reviewFiles !== null && reviewFiles.length > 0
          ? <TaskChangedFilesCard
              files={reviewFiles}
              commitCount={reviewCommitCount}
              onReview={() => openTab('pr', 'changes')}
            />
          : undefined}
        spineTrailer={planTimelineNode}
        trailer={(
          <>
            {data.rightRail.approval !== null && (
              <DecisionNode tone="approve">
                <div className="sp-appr">
                  <div className="sp-appr__head">
                    <span className="sp-appr__lbl">{data.rightRail.approval.stageTitle}</span>
                  </div>
                  <div className="sp-appr__why">
                    {data.rightRail.approval.reasonShort} — {data.rightRail.approval.pendingArtifact}
                  </div>
                  <div className="sp-appr__actions">
                    <button type="button" className="sp-ab sp-ab--ok" onClick={openChanges}>
                      {data.rightRail.approval.primaryAction.label}
                    </button>
                  </div>
                </div>
              </DecisionNode>
            )}
            {shipProposal !== null && (proposalAction(shipProposal) === 'mark_ready'
              ? <MarkReadyPrompt onReview={openChanges} />
              : (
                <ShipReviewPrompt
                  onReview={openChanges}
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
            {working && <Working label="Brain is thinking…" />}
          </>
        )}
      />
      <PlanOverlay open={planOpen} card={planCard} onClose={closePlan} />
      {pushOpen && localPrBundle != null && (
        <PushDialog
          bundle={localPrBundle}
          repoLabel={task.repoFullName}
          busy={prBusy}
          onPush={confirmPush}
          onCancel={() => setPushOpen(false)}
        />
      )}
    </Conv>
  );

  const bridge = typeof window !== 'undefined' ? window.bridge : undefined;

  // Once shipped, the linked PR shows as a clickable chip that opens the
  // right-panel overview.
  // A completed task's PR has landed (merged, or the queue merged it) — show
  // it merged + flag the task finished, rather than the stale "open"/draft.
  const finished = task.currentPhase === 'COMPLETED';
  const linkedPr = data.rightRail.linkedPr;
  const pr = task.prNumber !== null
    ? {
        number: task.prNumber,
        status: finished ? 'merged' : (linkedPr?.status ?? (task.prDraft ? 'draft' : 'open')),
        onOpen: () => openTab('pr', 'overview'),
      }
    : undefined;

  // The task-scoped sidebar with the live-plan diagram — the stage-navigation
  // surface that replaces the top-bar chip strip (design decision #17).
  const livePlanNodes = buildLivePlan({
    stages,
    subStages,
    liveRuns: data.liveRuns,
    guard: data.guard,
    liveRound: data.liveRound,
    task: { prNumber: task.prNumber, currentPhase: task.currentPhase as TaskPhase, terminal: task.terminal },
    prStatus: task.prNumber === null ? null : task.prDraft ? 'draft' : 'open',
    mergeReady: proposalAction(shipProposal) === 'merge_pr',
    viewedStageId: null,
    // This IS the brain page, so the Plan node is the active view.
    viewingBrain: true,
    // Pulse the Plan node while the brain is thinking.
    working,
    // Light the parked stage orange when a gate is awaiting the user's approval.
    awaitingApprovalStageId: data.rightRail.approval?.stageId ?? null,
    devPhases: data.devPhases,
    ciStatus: linkedPr?.ciStatus ?? null,
    ciSummary: linkedPr?.ciSummary ?? null,
  });
  const sidebar = (
    <TaskSidebar
      task={{
        title: task.title, branch: task.branch,
        taskNumber: task.taskNumber,
        repository: workspaceRepository ?? task.repoFullName,
        workspaceName,
        metaLine: task.statusLabel, finished,
      }}
      nodes={livePlanNodes}
      guard={buildGuardChip(data.guard, task.terminal)}
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
      defaultExpandPhases
      highlightActiveStage={false}
      onOpenStage={onOpenStage}
      onOpenPr={pr?.onOpen}
      onOpenTab={openTab}
      onOpenRun={onOpenRun}
      agentReview={agentReview.data === null
        ? undefined
        : buildTaskAgentReviewTrack(agentReview.data, openAgentRound)}
      onToggleGuard={enabled => {
        void window.bridge.updateTaskGuard(taskId, { enabled }).then(pollFast).catch(() => { /* poll reconciles */ });
      }}
    />
  );

  const displayedTaskBundle = agentReview.displayedBundle ?? localPrBundle;
  const markReadyPr = proposalAction(shipProposal) === 'mark_ready' ? markReadyPrRef(shipProposal) : null;
  // Normal task pages reuse the locked Pull Requests detail body. Keep the
  // legacy PRView below exclusively for the review-round drill-in, whose
  // finding APIs do not exist on PullDetailBody.
  const taskPrNumber = displayedTaskBundle?.pr.remotePrNumber ?? task.prNumber ?? 0;
  const taskPullRow = displayedTaskBundle !== null && displayedTaskBundle !== undefined
    ? ((): PullRow => {
        const base = pullRowFromLocal(displayedTaskBundle.pr, task.repoFullName, taskPrNumber);
        const reviewState = agentReview.headerState === 'never' ? 'none' : agentReview.headerState;
        const additions = displayedTaskBundle.pr.syncedAdditions
          ?? reviewFiles?.reduce((sum, file) => sum + file.additions, 0) ?? 0;
        const deletions = displayedTaskBundle.pr.syncedDeletions
          ?? reviewFiles?.reduce((sum, file) => sum + file.deletions, 0) ?? 0;
        return {
          ...base,
          add: additions,
          del: deletions,
          hasAgent: reviewState !== 'none',
          dto: { ...base.dto, additions, deletions, reviewState },
        };
      })()
    : null;
  const onTaskPrComment = async (body: string) => {
    if (displayedTaskBundle === null || displayedTaskBundle === undefined) return;
    const localPr = displayedTaskBundle.pr;
    if (derivePRCapabilities(localPr, 'details').postRemoteComment) {
      await window.bridge.postRemotePrComment(localPr.id, body);
    }
    else {
      await window.bridge.addLocalPrComment(localPr.id, { scope: 'pr', body });
    }
    refreshLocalPr();
  };
  const taskPullDetail = taskPullRow !== null && displayedTaskBundle !== null
      && displayedTaskBundle !== undefined ? (
    <PullDetailBody
      key={taskPullRow.id}
      row={taskPullRow}
      bundle={displayedTaskBundle}
      refresh={refreshLocalPr}
      openOverviewToken={openOverviewToken}
      openChangesToken={prSubTabRequest?.subTab === 'changes' ? prSubTabRequest.token : undefined}
      changesFiles={displayedTaskBundle.pr.remotePrNumber === null ? reviewFiles : undefined}
      fetchChangesBlob={displayedTaskBundle.pr.remotePrNumber === null
        ? (path) => window.bridge.fetchTaskFileBlob(threadId, taskId, path)
        : undefined}
      changesBanner={shipProposal !== null && markReadyPr !== null ? (
        <MarkReadyPanel
          notificationId={shipProposal.id}
          pr={markReadyPr}
          onMarked={() => {
            pollFast();
            refreshLocalPr();
            void refreshShipProposal();
          }}
        />
      ) : undefined}
      onComment={onTaskPrComment}
      onAssignAgent={() => { void agentReview.startReview(); }}
      onWorkWithAgent={() => openAgentRound()}
      onOpenInWorkspace={openChanges}
    />
  ) : null;
  const openAgentFinding = (
    findingId: string,
    filePath: string | null = null,
    lineNumber: number | null = null,
  ) => {
    setSelectedAgentFinding(findingId);
    setSelectedAgentFile(filePath);
    setSelectedAgentLine(lineNumber);
    setPrSubTabRequest(prev => ({
      subTab: filePath === null ? 'conversation' : 'changes',
      token: (prev?.token ?? 0) + 1,
    }));
    if (filePath !== null) {
      setReviewTabRequest(current => ({ tab: 'files', token: (current?.token ?? 0) + 1 }));
    }
  };
  const openAgentReviewList = (findingId: string) => {
    setSelectedAgentFinding(findingId);
    setSelectedAgentFile(null);
    setSelectedAgentLine(null);
    setPrSubTabRequest(prev => ({
      subTab: 'changes',
      token: (prev?.token ?? 0) + 1,
    }));
    setReviewTabRequest(current => ({ tab: 'review', token: (current?.token ?? 0) + 1 }));
  };
  const removeReviewComment = (commentId: string) => {
    if (agentReview.hasAgentComment(commentId)) agentReview.dismissComment(commentId);
    else deleteLocalComment(commentId);
  };
  const agentHeader = (
    <AgentReviewHeaderAction
      state={agentReview.headerState}
      round={agentReview.latestRoundNumber}
      spendCents={agentReview.latestRound?.cost_cents ?? 0}
      comments={agentReview.pendingComments}
      excluded={agentReview.excludedFindings}
      error={agentReview.error}
      onStart={agentReview.startReview}
      onOpenRound={() => openAgentRound()}
      onToggle={agentReview.toggleFinding}
      onEdit={agentReview.updateComment}
      onRemove={removeReviewComment}
      onSubmit={agentReview.submitReview}
    />
  );
  const taskPrView = displayedTaskBundle != null && prCapabilities !== null ? (
    <PRView
      bundle={displayedTaskBundle}
      capabilities={prCapabilities}
      commentValue={localComment}
      onCommentChange={setLocalComment}
      onAddComment={task.terminal && !prCapabilities.postRemoteComment ? undefined : submitLocalComment}
      onPush={() => setPushOpen(true)}
      onAskAgent={task.terminal ? undefined : askAgentToAddress}
      onMerge={confirmMerge}
      onDequeue={dequeuePr}
      onDeleteBranch={deleteBranch}
      onReviewChanges={() => setReviewOpen(true)}
      changesContent={(
        <LocalPrReviewScreen
          embedded
          title={`Review · ${displayedTaskBundle.pr.title}`}
          files={reviewOpen ? reviewFiles : null}
          comments={displayedTaskBundle.comments}
          commits={displayedTaskBundle.commits}
          allowLocalComments={prCapabilities.draftLocalComments && !task.terminal}
          fetchFileBlob={(path) => window.bridge.fetchTaskFileBlob(threadId, taskId, path)}
          onAddComment={addLocalLineComment}
          onReplyComment={replyLocalLineComment}
          onResolveComment={resolveLocalComment}
          onDismissComment={agentReview.hasAgentComment ? (commentId) => {
            if (agentReview.hasAgentComment(commentId)) agentReview.dismissComment(commentId);
            else deleteLocalComment(commentId);
          } : deleteLocalComment}
          onAnswerFinding={agentReview.answerFinding}
          onSetFindingResolved={agentReview.setFindingResolved}
          onBack={() => setReviewOpen(false)}
          onSubmitReview={onSubmitReview}
          submittingReview={submittingReview}
          reviewData={agentReview.data ?? undefined}
          selectedFindingId={selectedAgentFinding}
          selectedFindingRequestToken={reviewTabRequest?.token}
          selectedFindingFilePath={selectedAgentFile}
          selectedFindingLineNumber={selectedAgentLine}
          onSelectFinding={(findingId, filePath, lineNumber) => openAgentFinding(findingId, filePath, lineNumber)}
          onStartAgentReview={agentReview.startReview}
          openTabRequest={reviewTabRequest}
        />
      )}
      onRunTests={runLocalTests}
      runTestsBusy={testsBusy}
      onResolveThread={task.terminal ? undefined : resolveLocalComment}
      onDismissThread={task.terminal ? undefined : (commentId) => {
        if (agentReview.hasAgentComment(commentId)) agentReview.dismissComment(commentId);
        else deleteLocalComment(commentId);
      }}
      onReplyThread={task.terminal ? undefined : replyLocalPrComment}
      onReplyLineThread={task.terminal ? undefined : replyLocalLineComment}
      onOpenStage={onOpenStage}
      syncedAt={displayedTaskBundle.pr.syncedAt}
      syncing={prSyncing}
      onRefresh={refreshLocalPr}
      openSubTabRequest={prSubTabRequest}
      headerAction={agentHeader}
      reviewData={agentReview.data ?? undefined}
      onOpenReviewRound={openAgentRound}
      onAnswerFinding={agentReview.answerFinding}
      onReviewRoundAction={agentReview.roundAction}
      onSetFindingResolved={agentReview.setFindingResolved}
      onToggleFindingPromotion={agentReview.toggleFinding}
    />
  ) : null;

  if (agentRoundId !== null && agentReview.data !== null && taskPrView !== null) {
    return (
      <AgentReviewRoundPage
        data={agentReview.data}
        roundId={agentRoundId}
        prView={taskPrView}
        prTitle={`${displayedTaskBundle?.pr.title ?? 'Pull request'} · #${displayedTaskBundle?.pr.remotePrNumber ?? task.prNumber ?? ''}`}
        onBack={() => {
          setAgentRoundId(null);
          setReviewOpen(false);
          setPrSubTabRequest(undefined);
          setReviewTabRequest(undefined);
          setSelectedAgentFinding(null);
          setSelectedAgentFile(null);
          setSelectedAgentLine(null);
        }}
        onSelectRound={setAgentRoundId}
        onOpenFinding={openAgentFinding}
        onOpenReviewList={openAgentReviewList}
        onReopenFinding={agentReview.reopenFinding}
        onStopRound={agentReview.cancelRound}
        onStartRound={agentReview.startRound}
        onSendMessage={agentReview.sendRoundMessage}
        onUpdateBudget={agentReview.updateRoundBudget}
        busy={agentReview.loading}
        error={agentReview.error}
      />
    );
  }

  return (
    <TaskBrainPage
      task={{
        pillLabel: `TASK #${task.taskNumber}`,
        taskNumber: task.taskNumber,
        title: task.title,
        branch: task.branch,
        finished,
        trunkLabel,
      }}
      pr={taskPullDetail !== null ? pr : undefined}
      sidebar={sidebar}
      onOpenTrunk={onBack}
      openTabRequest={openTabRequest}
      conversation={conversation}
      conversationIndex={data.brainThreadId !== null ? (
        <ConvIndex
          threadId={data.brainThreadId}
          scrollContainerRef={conversationRef}
          restrictToSeqs={brainPromptSeqs}
        />
      ) : undefined}
      composer={{
        value: text, onChange: setText, onSubmit: submit, busy: working, queueWhenBusy: true,
        placeholder: 'Ask the brain, or steer the task…',
        images, onImagesChange: setImages,
        closedNote: task.terminal
          ? 'This task is closed — ask the brain, or reopen to continue…'
          : undefined,
        modePill: <WorkModelPill variant="workspace-v2" scope={{ kind: 'task', threadId, taskId }}
          agentLockPending={working} />,
        usage: {
          contextPercent: data.rightRail.context.tokensLimit > 0
            ? Math.round((data.rightRail.context.tokensUsed / data.rightRail.context.tokensLimit) * 100)
            : 0,
          sessionLabel: `${data.rightRail.context.tokensUsed.toLocaleString('en-US')} tokens`,
        },
        meta: `Task #${task.taskNumber} · ${formatDuration(data.aggregate.activeTimeSec)} · ${formatCost(data.aggregate.costCents)}`,
      }}
      run={{
        statusLabel: task.statusLabel,
        paused: task.paused,
        terminal: task.terminal,
        onPause: runAction(bridge?.pauseTask),
        onResume: runAction(bridge?.resumePausedTask),
        onClose: closeTask,
      }}
      planReminder={plan === null ? undefined
        : plan.state === 'awaiting' ? 'awaiting'
        : plan.state === 'locked' ? 'locked'
        : undefined}
      onRevealPlan={plan !== null ? () => setPlanOpen(true) : undefined}
      markReadyReminder={proposalAction(shipProposal) === 'mark_ready'}
      onOpenMarkReady={openChanges}
      tabs={{
        pr: taskPullDetail ?? undefined,
      }}
      changes={taskPullDetail !== null && displayedTaskBundle !== null && displayedTaskBundle !== undefined ? {
        additions: displayedTaskBundle.pr.syncedAdditions
          ?? displayedTaskBundle.commits.reduce((sum, commit) => sum + commit.additions, 0),
        deletions: displayedTaskBundle.pr.syncedDeletions
          ?? displayedTaskBundle.commits.reduce((sum, commit) => sum + commit.deletions, 0),
        onOpen: () => openTab('pr', 'changes'),
      } : undefined}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={deleteLocalComment}
    />
  );
}
