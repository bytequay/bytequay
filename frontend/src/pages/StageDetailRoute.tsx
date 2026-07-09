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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useStageDetailData } from '../threads/brain/useStageDetailData';
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { useLocalPrActions } from '../pr/localpr/useLocalPrActions';
import { PRView } from '../pr/localpr/PRView';
import { LocalPrReviewScreen } from '../pr/localpr/LocalPrReviewScreen';
import { PushDialog } from '../pr/localpr/PushDialog';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useThreadStream } from '../threads/useThreadStream';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import { MarkReadyPrompt } from '../threads/MarkReadyPrompt';
import { CiStatusPanel } from './CiStatusPanel';
import { PRTabContent } from '../ui/pane/tabs';
import type { CommentThreadData, PRMetaChip } from '../ui/pane/tabs';
import type { DiffFileDto } from '../types';
import type { AgentRunDto, StageType } from '../types/brainView';
import { Conv, EventRow, QueuedMessages, RoundEpisode, RunEpisode, Working } from '../ui/conv';
import { useTaskRuns } from '../threads/brain/useTaskRuns';
import { useTaskRounds } from '../threads/brain/useTaskRounds';
import { useMessageQueue } from '../threads/useMessageQueue';
import { stageRow } from './stageConversationRow';
import type { PermissionDecideHandler } from '../threads/PermissionCard';
import { StageDetailPage } from './StageDetailPage';
import type { StageKind } from './StageDetailPage';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import TaskCodePage from '../threads/TaskCodePage';
import { PlanCard } from '../threads/brain/TaskRootNode';
import { PlanOverlay } from './PlanOverlay';
import { TaskSidebar } from '../ui/shell/TaskSidebar';
import { buildGuardChip, buildLivePlan } from '../ui/shell/livePlanModel';
import { makeIdCache } from '../threads/brain/idCache';
import type { TaskPhase } from '../types/brainView';

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
  threadId, taskId, stageId, onOpenCode, onOpenStage, onOpenRun, onBack, onOpenBrain,
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
  /** Navigate back to the thread trunk (the task sidebar's back button). */
  onBack?: () => void;
  /** Navigate to this task's brain page — the live plan's Plan node. */
  onOpenBrain?: () => void;
}) {
  const { data, refresh } = useStageDetailData(stageId);
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const { data: brain, pollFast } = useBrainViewData(taskId);
  // The plan is the task's; surface it on every stage via the reminder pill +
  // zoomed overlay (the plan no longer sits in the stage's tab strip).
  const plan = brain.rightRail.plan;
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  // The PR-tab Add-comment box (frame 7). Per the no-auto-post rule, a typed
  // comment is handed to the dev agent to post — it parks the publish for the
  // user's approval through the normal gate rather than posting directly.
  const [prComment, setPrComment] = useState('');
  // Force-opens the PR tab's own Checks sub-tab — the Remote CI row's
  // click target. Declared ahead of `localPrNode` below, which reads it.
  const [prSubTabRequest, setPrSubTabRequest] = useState<{ subTab: 'checks'; token: number } | undefined>(undefined);
  // The task's local PR — the primary artifact this milestone renders. Null
  // until Dev records its first commit; then the PR tab shows <PRView>
  // instead of the remote-GitHub PRTabContent. The bundle poll + the
  // user-gated push/merge/comment actions are shared with the brain page.
  const {
    bundle: localPrBundle, refresh: refreshLocalPr, syncing: prSyncing, localPr, capabilities: prCapabilities,
    localComment, setLocalComment, submitLocalComment,
    confirmPush, confirmMerge, dequeuePr, deleteBranch,
    addLocalLineComment, replyLocalLineComment, resolveLocalComment, dismissLocalComment,
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
  const branch = data?.task.branch;
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
    if (realtimeCi !== null) { void bridge?.openExternal(realtimeCi.prUrl); return; }
    if (repoFullName != null && prNumber !== null) {
      void bridge?.openExternal(`https://github.com/${repoFullName}/pull/${prNumber}`);
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
    />
  ) : null;

  const postPrComment = useCallback(() => {
    const body = prComment.trim();
    if (body.length === 0) return;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    void bridge?.steerStage(
      stageId,
      `Please post this comment on the pull request (park it for my approval as usual):\n\n${body}`)
      .then(() => { setPrComment(''); refresh(); })
      .catch(() => { /* poll reconciles */ });
  }, [prComment, stageId, refresh]);

  const sendNow = useCallback((body: string) => {
    setBusy(true);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.steerStage(stageId, body)
      .then(() => refresh())
      .catch(() => { /* poll reconciles */ })
      .finally(() => setBusy(false));
  }, [stageId, refresh]);

  // ── Local PR actions (user-gated; never auto-posted) ────────────────────
  const repoLabel = data?.task.repoFullName ?? undefined;

  const askAgentToAddress = useCallback(() => {
    setText('Please address my review comments on the PR, then I\'ll push. ');
    // Land the cursor in the composer so the user can elaborate and send.
    requestAnimationFrame(() => {
      document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus();
    });
  }, []);

  // Live stream of the agent working this stage: its text appears
  // token-by-token (and a non-delta event refreshes the canonical
  // transcript, which clears the live buffer). This is what makes the stage
  // feel alive between the periodic poll snapshots.
  const { liveText } = useThreadStream(
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
    if (body.length === 0) return;
    setText('');
    if (working) enqueue(body);
    else sendNow(body);
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
  const workingSince = epochOrNull((lastTool ?? data?.conversation.at(-1))?.ts);
  const workingLabel = toolName === null
    ? 'Agent is working…'
    : toolArg !== null ? `Running ${toolName}: ${toolArg}` : `Running ${toolName}…`;
  const workingDetail = toolArg ?? undefined;

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
    () => data?.conversation.map(r => stageRow(r, onDecide)),
    [data?.conversation, onDecide],
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
  const conversation = (
    <Conv>
      {feedRows}
      {liveText.length > 0 && <EventRow kind="agent" who="Agent" markdown={liveText} />}
      {shipProposal !== null && (proposalAction(shipProposal) === 'mark_ready'
        ? <MarkReadyPrompt onReview={onOpenCode} />
        : <ShipReviewPrompt onReview={onOpenCode} />)}
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
          onStop={() => {
            const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
            void bridge?.interruptTask(threadId).then(refresh).catch(() => { /* poll reconciles */ });
          }}
        />
      )}
      <PlanOverlay open={planOpen} card={planCard} onClose={closePlan} />
      {pushOpen && localPrBundle != null && (
        <PushDialog
          bundle={localPrBundle}
          repoLabel={repoLabel}
          busy={prBusy}
          onPush={confirmPush}
          onCancel={() => setPushOpen(false)}
        />
      )}
    </Conv>
  );

  // ── Right-pane tab nodes ────────────────────────────────────────────────
  // The CI-fix stage's own tab for the live CI run — separate from the
  // Changes tab so the stage keeps its checks focus without displacing the
  // diff.
  const ciNode = useMemo(() => (
    realtimeCi !== null
      ? <CiStatusPanel ci={realtimeCi} onOpenGitHub={openPr} />
      : <div className="pane-empty">No CI run yet.</div>
  ), [realtimeCi, openPr]);

  // PR tab content — built from the stage-detail `pr` block (status, branch
  // flow, reviewers, labels, CI check summary, and the per-line review
  // threads with the reviewer's root comment + the agent's reply).
  const pr = data?.pr ?? null;
  const threads: CommentThreadData[] = useMemo(() => (pr?.threads ?? []).map(t => {
    const root = t.messages[0];
    const reply = t.messages.length > 1 ? t.messages[t.messages.length - 1] : undefined;
    return {
      id: t.id,
      author: root?.author ?? 'reviewer',
      file: t.file === null ? undefined : (t.line !== null ? `${t.file}:${t.line}` : t.file),
      status: t.resolved ? 'resolved' as const : 'open' as const,
      body: root?.body ?? '',
      reply: reply !== undefined ? { src: reply.author, text: reply.body } : undefined,
    };
  }), [pr]);
  const openThreadCount = useMemo(() => threads.filter(t => t.status === 'open').length, [threads]);
  const prMetaChips: PRMetaChip[] = useMemo(() => {
    if (pr === null) return [];
    const chips: PRMetaChip[] = [];
    if (pr.reviewers.length > 0) chips.push({ icon: '👥', label: 'Reviewers', count: pr.reviewers.length });
    for (const label of pr.labels) chips.push({ label });
    return chips;
  }, [pr]);

  // A completed task's PR has landed — show it merged even if the cached PR
  // detail (no longer polled once terminal) still reads open/queued.
  const taskCompleted = data?.task.currentPhase === 'COMPLETED';
  const prStatus = taskCompleted ? 'merged' : pr?.status;
  const prNode = pr !== null ? (
    <PRTabContent
      title={data?.task.title}
      prNumber={pr.number}
      status={prStatus}
      statusLabel={prStatus === 'merged'
        ? 'Merged'
        : prStatus === 'queued'
          ? `Queued for merge${pr.queueState !== null ? ` · ${pr.queueState.toLowerCase().replace(/_/g, ' ')}` : ''}`
          : prStatus === 'draft'
            ? 'Draft'
            : 'Open · ready for review'}
      headBranch={pr.headRef ?? branch}
      baseBranch={pr.baseRef ?? undefined}
      metaChips={prMetaChips}
      checks={pr.checks.total > 0 ? pr.checks : undefined}
      threads={threads}
      threadsHeader={threads.length > 0 ? `Open threads · ${openThreadCount}` : undefined}
      commentValue={prComment}
      onCommentChange={setPrComment}
      onAddComment={state !== 'CLOSED' ? postPrComment : undefined}
    />
  ) : null;

  // Prefer the unified <PRView> once the task has a local PR; fall back to the
  // remote PRTabContent until then. Push/merge open their user-gated dialogs;
  // the action bar's secondary focuses the composer with an address-comments
  // starter (never auto-posts).
  const localPrNode = localPrBundle !== null && localPrBundle !== undefined && prCapabilities !== null ? (
    <PRView
      bundle={localPrBundle}
      capabilities={prCapabilities}
      commentValue={localComment}
      onCommentChange={setLocalComment}
      onAddComment={taskTerminal ? undefined : submitLocalComment}
      onPush={() => setPushOpen(true)}
      onAskAgent={taskTerminal ? undefined : askAgentToAddress}
      onMerge={confirmMerge}
      onDequeue={dequeuePr}
      onDeleteBranch={deleteBranch}
      onReviewChanges={() => setReviewOpen(true)}
      onRunTests={runLocalTests}
      runTestsBusy={testsBusy}
      onResolveThread={taskTerminal ? undefined : resolveLocalComment}
      onDismissThread={taskTerminal ? undefined : dismissLocalComment}
      syncedAt={localPrBundle.pr.syncedAt}
      syncing={prSyncing}
      onRefresh={refreshLocalPr}
      openSubTabRequest={prSubTabRequest}
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
      terminal: state === 'CLOSED' || (data === null && brain.task.terminal),
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
    state, stageId, shipProposal, working, data, brain.task.terminal, brain.rightRail.linkedPr,
  ]);
  // Force-opens the right-pane PR tab from the rail's gate nodes (Local
  // review / Remote pull request / Merge-Close, R27) — a fresh token
  // re-fires even for a repeat click on the tab that's already open.
  const [openTabRequest, setOpenTabRequest] = useState<{ tab: 'pr' | 'ci'; token: number } | undefined>(
    undefined);
  const openTab = useCallback((tab: 'pr', subTab?: 'checks') => {
    setOpenTabRequest(prev => ({ tab, token: (prev?.token ?? 0) + 1 }));
    if (subTab !== undefined) {
      setPrSubTabRequest(prev => ({ subTab, token: (prev?.token ?? 0) + 1 }));
    }
  }, []);
  // Render once we have any stage data — the stage detail, or the task-level
  // brain stages — so the rail persists across stage switches.
  const sidebar = planStages.length === 0 ? undefined : (
    <TaskSidebar
      task={{
        title: sidebarTitle,
        branch: sidebarBranch,
        metaLine: sidebarPhase.replace(/_/g, ' ').toLowerCase(),
        finished: sidebarFinished,
      }}
      nodes={livePlanNodes}
      guard={buildGuardChip(planGuard)}
      onBack={onBack}
      onOpenStage={onOpenStage}
      onOpenCode={onOpenCode}
      onOpenPr={pr !== null ? openPr : undefined}
      onOpenTab={openTab}
      onOpenBrain={onOpenBrain}
      onOpenRun={onOpenRun}
      onToggleGuard={enabled => {
        void window.bridge.updateTaskGuard(taskId, { enabled })
          .then(() => { pollFast(); refresh(); })
          .catch(() => { /* poll reconciles */ });
      }}
    />
  );

  // Full-page changed-files + diff review for the local PR, reached from the
  // PR tab's "Review changed files" button. A takeover (like the remote PR's
  // DiffViewerScreen), reusing the exact same file-tree + diff panels.
  if (reviewOpen && localPr !== null) {
    return (
      <LocalPrReviewScreen
        title={`Review · ${localPr.title}`}
        files={files}
        comments={localPrBundle?.comments ?? []}
        allowLocalComments={prCapabilities?.draftLocalComments === true && !taskTerminal}
        onAddComment={addLocalLineComment}
        onReplyComment={replyLocalLineComment}
        onResolveComment={resolveLocalComment}
        onDismissComment={dismissLocalComment}
        onBack={() => setReviewOpen(false)}
      />
    );
  }

  return (
    <StageDetailPage
      stageKind={stageKind}
      sidebar={sidebar}
      openTabRequest={openTabRequest}
      stage={{ title: data?.task.title ?? 'Stage', branch: data?.task.branch }}
      conversation={conversation}
      composer={{
        value: text,
        onChange: setText,
        onSubmit: submit,
        busy: working,
        queueWhenBusy: true,
        placeholder: state === 'CLOSED' ? 'This stage is closed.' : 'Steer this stage…',
      }}
      run={{ paused: state === 'PAUSED', terminal: state === 'CLOSED', statusLabel: state ?? 'Running' }}
      tabCounts={{
        code: files !== null && files.length > 0
          ? { count: files.length, countColor: 'acc' } : undefined,
        pr: prNumber !== null ? { count: prNumber, countColor: 'muted' } : undefined,
      }}
      paneMeta={stageKind === 'ci-fix' ? {
        left: `CI fix · iter ${data?.stage.iterationCount ?? 0}`
          + (data?.stage.config.autoPushBudget != null
            ? ` · auto-push ${data.stage.config.autoPushBudget.used}/${data.stage.config.autoPushBudget.limit}`
            : ''),
        right: (
          <>
            {`+${totalAdds} −${totalDels} · `}
            <span style={{ color: 'var(--accent)', cursor: 'pointer' }} onClick={openPr}>View on GitHub</span>
          </>
        ),
      } : undefined}
      tabs={{
        pr: localPrNode ?? prNode ?? undefined,
        ci: stageKind === 'ci-fix' ? ciNode : undefined,
        // Gated on hasDiff — otherwise on a Plan stage with no PR yet, this
        // ends up the only tab, becomes the default, and its paneExpanded
        // behavior hides the conversation column.
        code: hasDiff ? <TaskCodePage embedded threadId={threadId} taskId={taskId} stageId={stageId} /> : undefined,
      }}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      planReminder={plan === null ? undefined
        : plan.state === 'awaiting' ? 'awaiting'
        : plan.state === 'locked' ? 'locked'
        : undefined}
      onRevealPlan={plan !== null ? () => setPlanOpen(true) : undefined}
      markReadyReminder={proposalAction(shipProposal) === 'mark_ready'}
    />
  );
}
