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
import { useCallback, useEffect, useState } from 'react';
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { usePendingShipProposal, proposalAction } from '../threads/usePendingShipProposal';
import { useMessageQueue } from '../threads/useMessageQueue';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import { MarkReadyPrompt } from '../threads/MarkReadyPrompt';
import type { TaskPhase } from '../types/brainView';
import { Conv, DecisionNode, DensityToggle, QueuedMessages, Working } from '../ui/conv';
import { BrainFeed } from '../threads/brain/BrainFeed';
import { DetailsTabContent } from '../ui/pane';
import { TaskSidebar } from '../ui/shell/TaskSidebar';
import { usePersistentToggle } from '../ui/shell';
import { buildLivePlan } from '../ui/shell/livePlanModel';
import { planTab } from './planTab';
import { TaskBrainPage } from './TaskBrainPage';

/**
 * Data adapter that mounts the V3 {@link TaskBrainPage} on the live brain
 * data. Wires the brain feed → conversation, the composer → the brain
 * agent, the lifecycle Run menu, and the task-scoped sidebar's live-plan
 * diagram (which replaces the old stage-chip strip as the stage-navigation
 * surface). Details shows the task's status for now.
 */
export function TaskBrainRoute({
  threadId, taskId, onOpenStage, onOpenCode, onClosed, onBack,
}: {
  threadId: string;
  taskId: string;
  onOpenStage: (stageId: string) => void;
  onOpenCode: () => void;
  /** Closing a task seals it terminal + reaps its worktree, so the page is
   *  a dead end afterwards — navigate away (back to the thread trunk). */
  onClosed: () => void;
  /** Navigate back to the thread trunk (the task sidebar's back button). */
  onBack?: () => void;
}) {
  const { data, pollFast } = useBrainViewData(taskId);
  const { task, brainFeed, stages, subStages } = data;
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  // Per-task auto-approve mode (default off); loaded once, toggled from the
  // brain page's top bar.
  const [autoApprove, setAutoApprove] = useState(false);
  useEffect(() => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.getTaskAutoApprove?.(threadId, taskId)
      .then(r => setAutoApprove(r.enabled))
      .catch(() => { /* default off */ });
  }, [threadId, taskId]);
  const toggleAutoApprove = () => {
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    const next = !autoApprove;
    setAutoApprove(next);
    bridge?.setTaskAutoApprove?.(threadId, taskId, next)
      .then(r => setAutoApprove(r.enabled))
      .catch(() => setAutoApprove(!next));
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

  const sendNow = useCallback((body: string) => {
    setBusy(true);
    setAwaitedAt(responseCount);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.sendBrainMessage(taskId, body)
      .then(() => pollFast())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
  }, [taskId, pollFast, responseCount]);
  // Messages typed while the brain is thinking queue up and auto-send when it
  // goes idle; click one to pull it back into the composer to edit.
  const { queue, enqueue, takeForEdit, remove } = useMessageQueue(working, sendNow);
  const submit = () => {
    const body = text.trim();
    if (body.length === 0) return;
    setText('');
    if (working) enqueue(body);
    else sendNow(body);
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

  // Conversation density (Focused default / Full), persisted per user.
  const { value: fullDensity, setValue: setFullDensity } = usePersistentToggle('bq.convDensityFull');
  const density = fullDensity ? 'full' : 'focused';

  const conversation = (
    <Conv>
      <div className="sp-controls">
        <DensityToggle value={density} onChange={d => setFullDensity(d === 'full')} />
      </div>
      <BrainFeed
        feed={brainFeed}
        stages={stages}
        density={density}
        trailer={(
          <>
            {data.rightRail.approval !== null && (
              <DecisionNode tone="approve">
                <div className="sp-appr">
                  <div className="sp-appr__head">
                    <span className="sp-appr__lbl">⚑ {data.rightRail.approval.stageTitle}</span>
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
              : <ShipReviewPrompt onReview={onOpenCode} />)}
            <QueuedMessages
              messages={queue}
              onEdit={id => setText(takeForEdit(id))}
              onRemove={remove}
            />
            {working && <Working label="Brain is thinking…" />}
          </>
        )}
      />
    </Conv>
  );

  const allStages = [...stages, ...subStages];
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
    task: { prNumber: task.prNumber, currentPhase: task.currentPhase as TaskPhase, terminal: task.terminal },
    prStatus: task.prNumber === null ? null : task.prDraft ? 'draft' : 'open',
    mergeReady: proposalAction(shipProposal) === 'merge_pr',
    viewedStageId: null,
    // This IS the brain page, so the Root node is the active view.
    viewingBrain: true,
    // Pulse the Root node while the brain is thinking.
    working,
  });
  const sidebar = (
    <TaskSidebar
      task={{
        title: task.title, branch: task.branch,
        metaLine: task.statusLabel, finished,
      }}
      nodes={livePlanNodes}
      onBack={onBack}
      onOpenStage={onOpenStage}
      onOpenCode={onOpenCode}
      onOpenPr={pr?.onOpen}
    />
  );

  return (
    <TaskBrainPage
      task={{ pillLabel: `TASK #${task.taskNumber}`, title: task.title, branch: task.branch, finished }}
      pr={pr}
      autoApprove={autoApprove}
      onToggleAutoApprove={toggleAutoApprove}
      sidebar={sidebar}
      conversation={conversation}
      composer={{ value: text, onChange: setText, onSubmit: submit, busy: working, queueWhenBusy: true, placeholder: 'Ask the brain, or steer the task…' }}
      run={{
        statusLabel: task.statusLabel,
        paused: task.paused,
        terminal: task.terminal,
        onPause: runAction(bridge?.pauseTask),
        onResume: runAction(bridge?.resumePausedTask),
        onClose: closeTask,
      }}
      priorityTab={plan !== null && plan.state === 'awaiting' ? 'plan' : undefined}
      tabs={{
        plan: plan !== null ? planTab(plan, plan.state === 'awaiting' ? approvePlan : undefined) : undefined,
        details: (
          <DetailsTabContent sections={[{
            title: 'Task',
            rows: [
              { label: 'Status', value: task.statusLabel },
              { label: 'Branch', value: task.branch },
              { label: 'Stages', value: String(allStages.length) },
            ],
          }]}
          />
        ),
      }}
      onOpenChanges={onOpenCode}
    />
  );
}
