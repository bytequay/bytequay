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
import type { TaskPhase } from '../types/brainView';
import { Conv, DecisionNode, EventTimestamp, NodeCard, QueuedMessages, Working } from '../ui/conv';
import { SparkIcon } from '../ui/TaskBrainDesignIcons';
import { BrainFeed } from '../threads/brain/BrainFeed';
import { PlanCard, PlanningSeed, planStepComments } from '../threads/brain/TaskRootNode';
import { TaskSidebar } from '../ui/shell/TaskSidebar';
import { buildGuardChip, buildLivePlan } from '../ui/shell/livePlanModel';
import { TaskBrainPage } from './TaskBrainPage';
import { WorkModelPill } from '../workspace/WorkModelPill';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import { diffInlineCommentFromLocalPr, isPendingLocalComment } from '../diff/DiffInlineComments';
import { PlanOverlay } from './PlanOverlay';
import TaskCodePage from '../threads/TaskCodePage';
import { ConvIndex } from '../threads/ConvIndex';
import { AgentReviewHeaderAction } from '../review/AgentReviewHeaderAction';
import { AgentReviewRoundPage } from '../review/AgentReviewRoundPage';
import { useAgentReviewState } from '../review/useAgentReviewState';

/**
 * Data adapter that mounts the V3 {@link TaskBrainPage} on the live brain
 * data. Wires the brain feed → conversation, the composer → the brain
 * agent, the lifecycle Run menu, and the task-scoped sidebar's live-plan
 * diagram (which replaces the old stage-chip strip as the stage-navigation
 * surface).
 */
export function TaskBrainRoute({
  threadId, taskId, onOpenStage, onOpenCode, onOpenRun, onClosed,
  onBack, onForward, backEnabled, forwardEnabled, onToggleCollapse,
}: {
  threadId: string;
  taskId: string;
  onOpenStage: (stageId: string) => void;
  onOpenCode: () => void;
  /** Navigate to a live run's own log — the rail's Remote CI / comments rows use this. */
  onOpenRun?: (runId: string) => void;
  /** Closing a task seals it terminal + reaps its worktree, so the page is
   *  a dead end afterwards — navigate away (back to the thread trunk). */
  onClosed: () => void;
  /** Global nav-history back/forward, forwarded to the task sidebar's
   *  TrafficLights — same as the main Sidebar uses. */
  onBack?: () => void;
  onForward?: () => void;
  backEnabled?: boolean;
  forwardEnabled?: boolean;
  onToggleCollapse?: () => void;
}) {
  const { data, pollFast } = useBrainViewData(taskId);
  const { task, brainFeed, stages, subStages } = data;
  const conversationRef = useRef<HTMLDivElement | null>(null);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  // Force-opens the PR tab's own Checks sub-tab — the Remote CI row's
  // click target. Declared ahead of the <PRView> construction below, which
  // reads it.
  const [prSubTabRequest, setPrSubTabRequest] = useState<{ subTab: 'conversation' | 'checks' | 'changes'; token: number } | undefined>(undefined);
  // The task's local PR — rendered in the right pane's PR tab through the
  // same unified <PRView> + user-gated actions the stage pages use.
  const {
    bundle: localPrBundle, refresh: refreshLocalPr, syncing: prSyncing, capabilities: prCapabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch,
    addLocalLineComment, replyLocalLineComment, resolveLocalComment, deleteLocalComment,
    pushOpen, setPushOpen,
    reviewOpen, setReviewOpen, prBusy,
    runLocalTests, testsBusy,
  } = useLocalPrActions(taskId, { onAfterTransition: pollFast });

  // Bundles the Submit-review drawer's body/verdict, plus this task's
  // unresolved diff comments, into a steering turn for the dev agent — same
  // action as TaskCodePage's embedded "Submit review" button, surfaced here
  // in the top bar too.
  const [submittingReview, setSubmittingReview] = useState(false);
  const onSubmitReview = useCallback((body: string, verdict: ReviewVerdict) => {
    setSubmittingReview(true);
    window.bridge.submitReview(taskId, { body, verdict })
      .then(() => pollFast())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setSubmittingReview(false));
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
  const pendingReviewComments = useMemo(
    () => (localPrBundle?.comments ?? []).filter(isPendingLocalComment).map(diffInlineCommentFromLocalPr),
    [localPrBundle],
  );

  // Force-opens the right-pane PR tab from the rail's gate nodes (Local
  // review / Remote pull request / Merge-Close, R27) and the review
  // callout's View PR — a fresh token re-fires even for a repeat click on
  // the tab that's already open.
  const [openTabRequest, setOpenTabRequest] = useState<{ tab: 'pr'; token: number } | undefined>(undefined);
  const openTab = useCallback((tab: 'pr', subTab?: 'checks' | 'changes') => {
    setOpenTabRequest(prev => ({ tab, token: (prev?.token ?? 0) + 1 }));
    if (subTab !== undefined) {
      setPrSubTabRequest(prev => ({ subTab, token: (prev?.token ?? 0) + 1 }));
    }
  }, []);

  // The ready-for-review callout's inline gate. Approve ships the parked
  // proposal exactly as drafted (the agent's stored PR title + body); the
  // full editable surface stays on TaskCodePage via the callout's link.
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
    }
    catch (e) { setShipNote(e instanceof Error ? e.message : String(e)); }
    finally { setShipBusy(false); }
  }, [shipBusy, shipProposal, pollFast]);

  const askAgentToAddress = useCallback(() => {
    setText('Please address my review comments on the PR, then I\'ll push. ');
    // Land the cursor in the composer so the user can elaborate and send.
    requestAnimationFrame(() => {
      document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus();
    });
  }, []);

  // The cumulative diff backing the full-page review takeover — fetched
  // lazily, only once the user opens the review.
  const [reviewFiles, setReviewFiles] = useState<DiffFileDto[] | null>(null);
  useEffect(() => {
    if (!reviewOpen) return;
    const b = typeof window !== 'undefined' ? window.bridge : undefined;
    if (b?.getTaskCumulativeDiff === undefined) return;
    let cancelled = false;
    void b.getTaskCumulativeDiff(threadId, taskId)
      .then(list => { if (!cancelled) setReviewFiles(list); })
      .catch(() => { if (!cancelled) setReviewFiles([]); });
    return () => { cancelled = true; };
  }, [reviewOpen, threadId, taskId]);
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
  // Ask the brain to revise — a fresh planning turn that supersedes the draft.
  const requestRevision = () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.replan?.(taskId).then(() => pollFast()).catch(() => { /* poll reconciles */ });
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
        onOpenStage={onOpenStage}
        threadId={threadId}
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
                    <button type="button" className="sp-ab sp-ab--ok" onClick={onOpenCode}>
                      {data.rightRail.approval.primaryAction.label}
                    </button>
                  </div>
                </div>
              </DecisionNode>
            )}
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

  // Once shipped, the linked PR shows as a clickable chip that opens it on
  // GitHub. The brain view doesn't carry the PR's html URL, so build the
  // canonical one from the repo + number.
  // A completed task's PR has landed (merged, or the queue merged it) — show
  // it merged + flag the task finished, rather than the stale "open"/draft.
  const finished = task.currentPhase === 'COMPLETED';
  const linkedPr = data.rightRail.linkedPr;
  const pr = task.prNumber !== null
    ? {
        number: task.prNumber,
        status: finished ? 'merged' : (linkedPr?.status ?? (task.prDraft ? 'draft' : 'open')),
        onOpen: () => {
          void bridge?.openExternal(
            `https://github.com/${task.repoFullName}/pull/${task.prNumber}`);
        },
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
        metaLine: task.statusLabel, finished,
      }}
      nodes={livePlanNodes}
      guard={buildGuardChip(data.guard, task.terminal)}
      onBack={onBack}
      onForward={onForward}
      backEnabled={backEnabled}
      forwardEnabled={forwardEnabled}
      onToggleCollapse={onToggleCollapse}
      threadLabel="Back to thread"
      user={getCached<UserProfileDto>('home:profile')?.login}
      defaultExpandPhases
      onOpenStage={onOpenStage}
      onOpenCode={onOpenCode}
      onOpenPr={pr?.onOpen}
      onOpenTab={openTab}
      onOpenRun={onOpenRun}
      onToggleGuard={enabled => {
        void window.bridge.updateTaskGuard(taskId, { enabled }).then(pollFast).catch(() => { /* poll reconciles */ });
      }}
    />
  );

  const displayedTaskBundle = agentReview.displayedBundle ?? localPrBundle;
  const openAgentRound = (roundId?: string) => {
    const selected = roundId ?? agentReview.latestRound?.id;
    if (selected === undefined) return;
    setReviewOpen(true);
    setAgentRoundId(selected);
  };
  const openAgentFinding = (findingId: string, filePath: string | null = null) => {
    setSelectedAgentFinding(findingId);
    setPrSubTabRequest(prev => ({
      subTab: filePath === null ? 'conversation' : 'changes',
      token: (prev?.token ?? 0) + 1,
    }));
  };
  const openAgentReviewList = (findingId: string) => {
    setSelectedAgentFinding(findingId);
    setPrSubTabRequest(prev => ({
      subTab: 'changes',
      token: (prev?.token ?? 0) + 1,
    }));
  };
  const agentHeader = (
    <AgentReviewHeaderAction
      state={agentReview.headerState}
      round={agentReview.data?.rounds.length ?? 1}
      spendCents={agentReview.latestRound?.cost_cents ?? 0}
      comments={agentReview.pendingComments}
      excluded={agentReview.excludedFindings}
      error={agentReview.error}
      onStart={agentReview.startReview}
      onOpenRound={() => openAgentRound()}
      onToggle={agentReview.toggleFinding}
      onEdit={agentReview.updateComment}
      onRemove={agentReview.dismissComment}
      onSubmit={agentReview.submitReview}
    />
  );
  const taskPrView = displayedTaskBundle != null && prCapabilities !== null ? (
    <PRView
      bundle={displayedTaskBundle}
      capabilities={prCapabilities}
      commentValue={localComment}
      onCommentChange={setLocalComment}
      onAddComment={task.terminal ? undefined : submitLocalComment}
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
          onBack={() => setReviewOpen(false)}
          onSubmitReview={onSubmitReview}
          submittingReview={submittingReview}
          reviewData={agentReview.data ?? undefined}
          selectedFindingId={selectedAgentFinding}
          onSelectFinding={(findingId) => openAgentFinding(findingId)}
          onStartAgentReview={agentReview.startReview}
        />
      )}
      onRunTests={runLocalTests}
      runTestsBusy={testsBusy}
      onResolveThread={task.terminal ? undefined : resolveLocalComment}
      onDismissThread={task.terminal ? undefined : (commentId) => {
        if (agentReview.hasAgentComment(commentId)) agentReview.dismissComment(commentId);
        else deleteLocalComment(commentId);
      }}
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
    />
  ) : null;

  if (agentRoundId !== null && agentReview.data !== null && taskPrView !== null) {
    return (
      <AgentReviewRoundPage
        data={agentReview.data}
        roundId={agentRoundId}
        prView={taskPrView}
        onBack={() => { setAgentRoundId(null); setReviewOpen(false); }}
        onOpenFinding={(findingId, filePath) => openAgentFinding(findingId, filePath)}
        onOpenReviewList={openAgentReviewList}
        onReopenFinding={agentReview.reopenFinding}
        onStopRound={agentReview.cancelRound}
      />
    );
  }

  return (
    <TaskBrainPage
      task={{ pillLabel: `TASK #${task.taskNumber}`, title: task.title, branch: task.branch, finished }}
      pr={pr}
      sidebar={sidebar}
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
        closedNote: task.terminal ? 'This task is closed.' : undefined,
        modePill: <WorkModelPill scope={{ kind: 'task', threadId, taskId }} />,
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
      onOpenMarkReady={onOpenCode}
      tabs={{
        pr: taskPrView ?? undefined,
        // Gated on having a PR (like StageDetailRoute's `hasDiff`) — otherwise
        // this is the only tab, so it becomes the default and its
        // paneExpanded behavior hides the conversation column, burying the
        // plan-approval UI before there's even anything to review yet.
        code: task.prNumber !== null ? <TaskCodePage embedded threadId={threadId} taskId={taskId} /> : undefined,
      }}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={deleteLocalComment}
    />
  );
}
