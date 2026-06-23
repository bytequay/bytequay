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
import { useEffect, useState } from 'react';
import type { BrainFeedRow, StageDto, StageType } from '../../types/brainView';
import { useBrainViewData } from './useBrainViewData';
import { buildStageLabels } from './stageMeta';
import { TaskIdentityBar } from './TaskIdentityBar';
import { AggregateMetricsStrip } from './AggregateMetricsStrip';
import { PendingApprovalToast } from '../PendingApprovalToast';
import { StageNavigatorRail } from './StageNavigatorRail';
import { BrainFeedColumn } from './BrainFeedColumn';
import { RightRail } from './RightRail';
import { ConfirmDialog } from '../../workspace/ConfirmDialog';
import PromptContextInspector from '../../inspector/PromptContextInspector';

type Props = {
  taskId: string;
  threadId: string;
  /** Thread title for the rail's "↑ Thread · …" up-link. */
  threadTitle?: string;
  onBack: () => void;
  onOpenThread: () => void;
  /** Drill into a stage's detail surface (the stage-detail page is a
   *  later milestone; until then this can no-op or redirect). */
  onOpenStage?: (stageId: string) => void;
  /** Open the linked PR in the in-app PR detail page. */
  onOpenPr?: (owner: string, repo: string, prNumber: number) => void;
  /** Open the multi-agent review panel for a freshly-spawned pass, keyed by
   *  its review thread id. */
  onOpenReviewThread?: (threadId: string) => void;
  /** Open the standalone code (commit/diff/files) page for this task. */
  onOpenCode?: () => void;
  /** Injectable clock for deterministic relative-time rendering in
   *  tests. Defaults to the real wall clock. */
  nowMs?: number;
};

function liveLabelFor(type: StageType): string {
  switch (type) {
    case 'CI_FIXING_STAGE': return 'CI FIX RUNNING';
    case 'REVIEW_MONITOR_STAGE': return 'REVIEW MONITOR RUNNING';
    case 'DEVELOPMENT_STAGE': return 'DEV RUNNING';
    case 'CLEANUP_STAGE': return 'CLEANUP RUNNING';
    case 'REVIEW_STAGE': return 'PANEL RUNNING';
  }
}

/**
 * Task brain view — the main per-task surface. Renders three stacked
 * zones (identity bar / aggregate strip / body) where the body is a
 * three-column grid: stage navigator, brain feed, action rail. Data
 * comes entirely through {@link useBrainViewData}, a mock fixture for
 * now; the real-data swap changes only that hook.
 */
export default function TaskBrainView({
  taskId, threadId, threadTitle = 'Cost & tokens',
  onBack, onOpenThread, onOpenStage, onOpenPr, onOpenReviewThread, onOpenCode, nowMs,
}: Props) {
  const { data, error: loadError, pollFast } = useBrainViewData(taskId);
  const { task, aggregate, stages, subStages, brainFeed, rightRail, scrubbers } = data;
  const clock = nowMs ?? Date.now();

  // Optimistic YOU bubbles: shown immediately on submit so the user sees
  // their message before the round-trip, then dropped once the persisted
  // row shows up in the polled feed (matched on body).
  const [optimistic, setOptimistic] = useState<BrainFeedRow[]>([]);
  useEffect(() => {
    setOptimistic(prev => prev.filter(
      o => !brainFeed.some(r => r.type === 'USER_MESSAGE' && r.body === o.body)));
  }, [brainFeed]);

  const submitMessage = (text: string) => {
    const optimisticRow: BrainFeedRow = {
      id: `optimistic-${Date.now()}`,
      type: 'USER_MESSAGE',
      stageId: null,
      stageType: null,
      ts: new Date(clock).toISOString(),
      body: text,
      referencedStageId: null,
    };
    setOptimistic(prev => [...prev, optimisticRow]);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.sendBrainMessage) {
      bridge.sendBrainMessage(taskId, text)
        .then(() => pollFast())
        .catch(() => { /* leave the optimistic bubble; the next poll reconciles */ });
    }
  };

  const feed: BrainFeedRow[] = optimistic.length === 0 ? brainFeed : [...brainFeed, ...optimistic];

  const allStages: StageDto[] = [...stages, ...subStages];
  const activeStageIds = new Set(allStages.filter(s => s.state === 'ACTIVE').map(s => s.id));
  const activeStage = allStages.find(s => s.state === 'ACTIVE');
  const liveLabel = activeStage !== undefined ? liveLabelFor(activeStage.type) : null;
  const stageLabels = buildStageLabels(stages, subStages);

  const openStage = (stageId: string) => {
    if (onOpenStage !== undefined) onOpenStage(stageId);
    else console.log('[brain view] open stage (stage detail not built yet):', stageId);
  };

  const openPr = () => {
    if (task.prNumber === null || onOpenPr === undefined) return;
    const [owner, repo] = task.repoFullName.split('/');
    if (owner !== undefined && repo !== undefined) onOpenPr(owner, repo, task.prNumber);
  };

  // Launch a panel review of the task's own PR from the current stage, then
  // open the seated panel. The rail only renders this affordance when the
  // server marks the task panelSpawnable, so parentStageId is set here.
  const spawnReview = async (): Promise<void> => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge?.spawnReview === undefined || rightRail.parentStageId === null) {
      throw new Error('Panel review is unavailable for this task right now.');
    }
    const result = await bridge.spawnReview(rightRail.parentStageId);
    pollFast();
    if (onOpenReviewThread !== undefined) onOpenReviewThread(result.reviewThreadId);
  };

  // Task lifecycle actions, wired to the existing per-task endpoints. Pause
  // is reversible (→ Resume); Close is destructive (reaps the worktree), so
  // it confirms first. A shared busy flag blocks a double-fire mid-request.
  const [taskActionBusy, setTaskActionBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmCloseOpen, setConfirmCloseOpen] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(false);

  const runTaskAction = (fn: () => Promise<unknown>, after?: () => void) => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (bridge === undefined || taskActionBusy) return;
    setTaskActionBusy(true);
    setActionError(null);
    fn()
      .then(() => { pollFast(); after?.(); })
      .catch((e: unknown) => setActionError(e instanceof Error ? e.message : 'Task action failed'))
      .finally(() => setTaskActionBusy(false));
  };

  const onPause = () => runTaskAction(() => window.bridge.pauseTask(threadId, task.id));
  const onResume = () => runTaskAction(() => window.bridge.resumePausedTask(threadId, task.id));

  // Plan card actions. Approving closes the PlanStage, opens the
  // DevelopmentStage, and auto-navigates to its detail page.
  const onApprovePlan = () => {
    const planStageId = rightRail.plan?.planStageId;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (planStageId === undefined || bridge?.approvePlan === undefined || taskActionBusy) return;
    setTaskActionBusy(true);
    setActionError(null);
    bridge.approvePlan(planStageId)
      .then(result => { if (onOpenStage !== undefined) onOpenStage(result.devStageId); })
      .catch((e: unknown) => setActionError(e instanceof Error ? e.message : 'Approve failed'))
      .finally(() => setTaskActionBusy(false));
  };
  const onResolveFollowup = (eventId: string, status: 'addressed' | 'dismissed') => {
    const planStageId = rightRail.plan?.planStageId;
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    if (planStageId === undefined || bridge?.updateFollowup === undefined) return;
    bridge.updateFollowup(planStageId, eventId, status).then(() => pollFast()).catch(() => { /* poll reconciles */ });
  };
  // "Request changes" just surfaces the composer the user types into; it's
  // already visible in the center column, so this is a no-op hook for now.
  const onRequestPlanChanges = () => { /* composer is always visible */ };
  const doClose = () => {
    setConfirmCloseOpen(false);
    runTaskAction(() => window.bridge.cancelTask(threadId, task.id), () => onBack());
  };

  return (
    <div className="task-brain">
      <div className="mesh-bg" aria-hidden />
      <div className="tbv-stack">
        <TaskIdentityBar
          task={task}
          onBack={onBack}
          onOpenPr={task.prNumber !== null && onOpenPr !== undefined ? openPr : undefined}
        />
        <AggregateMetricsStrip aggregate={aggregate} liveLabel={liveLabel} />
        <PendingApprovalToast threadId={threadId} onResolved={pollFast} />
        {loadError !== null && (
          <div className="tbv-load-error" role="alert">
            Couldn't refresh the brain view: {loadError}. Showing the last loaded state.
          </div>
        )}
        {/* Grid columns are set inline as well as in CSS: this is the
            load-bearing layout value (252 / fluid center / 308) and the
            minmax(0, 1fr) center column is what stops long unbreakable
            content from shoving the rail widths around. */}
        <div className="tbv-body" style={{ gridTemplateColumns: '252px minmax(0, 1fr) 308px' }}>
          <StageNavigatorRail
            stages={stages}
            subStages={subStages}
            threadTitle={threadTitle}
            brainCount={brainFeed.length}
            taskTerminal={task.terminal}
            onOpenThread={onOpenThread}
            onOpenStage={openStage}
          />
          <BrainFeedColumn
            feed={feed}
            scrubbers={scrubbers}
            stageLabels={stageLabels}
            activeStageIds={activeStageIds}
            nowMs={clock}
            taskNumber={task.taskNumber}
            taskBranch={task.branch}
            onOpenStage={openStage}
            onSubmitMessage={submitMessage}
          />
          <RightRail
            rail={rightRail}
            nowMs={clock}
            onApprove={approval => openStage(approval.stageId)}
            onMerge={openPr}
            onViewDiff={onOpenCode ?? openPr}
            onViewContext={() => setInspectorOpen(true)}
            onPause={onPause}
            onResume={onResume}
            onClose={() => setConfirmCloseOpen(true)}
            paused={task.paused}
            terminal={task.terminal}
            statusLabel={task.statusLabel}
            taskActionBusy={taskActionBusy}
            onSpawnReview={spawnReview}
            onApprovePlan={onApprovePlan}
            onRequestPlanChanges={onRequestPlanChanges}
            onResolveFollowup={onResolveFollowup}
          />
        </div>
      </div>
      {actionError !== null && (
        <div className="tbv-action-error" role="alert">{actionError}</div>
      )}
      {confirmCloseOpen && (
        <ConfirmDialog
          title={`Close “${task.title}”?`}
          body={'This stops the agent, marks the task canceled, and reaps its '
            + 'worktree and branch. Unpushed local work is lost. The PR on '
            + 'GitHub is not affected. This cannot be undone.'}
          confirmLabel="Close task"
          destructive
          busy={taskActionBusy}
          onConfirm={doClose}
          onCancel={() => setConfirmCloseOpen(false)}
        />
      )}
      {inspectorOpen && (
        <PromptContextInspector
          scope="TASK"
          threadId={threadId}
          taskId={taskId}
          onClose={() => setInspectorOpen(false)}
        />
      )}
    </div>
  );
}
