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
import { useBrainViewData } from '../threads/brain/useBrainViewData';
import { usePendingShipProposal } from '../threads/usePendingShipProposal';
import { ShipReviewPrompt } from '../threads/ShipReviewPrompt';
import type { BrainFeedRow, StageDto, StageType } from '../types/brainView';
import { Conv, EventRow, UserMsg, Working } from '../ui/conv';
import type { EventKind } from '../ui/conv';
import { DetailsTabContent } from '../ui/pane';
import type { StageChip } from '../ui/shell';
import { planTab } from './planTab';
import { TaskBrainPage } from './TaskBrainPage';

const SHORT_LABEL: Record<StageType, string> = {
  PLAN_STAGE: 'Plan',
  DEVELOPMENT_STAGE: 'Dev',
  CI_FIXING_STAGE: 'CI Fix',
  REVIEW_MONITOR_STAGE: 'Comments',
  CLEANUP_STAGE: 'Cleanup',
  REVIEW_STAGE: 'Review',
};

function feedKind(type: BrainFeedRow['type']): { kind: EventKind; who: string } {
  switch (type) {
    case 'USER_MESSAGE':
    case 'TRUNK_MESSAGE':
      return { kind: 'user', who: 'You' };
    case 'BRAIN_AGENT_RESPONSE':
      return { kind: 'brain', who: 'Brain' };
    default:
      return { kind: 'system', who: type.replace(/_/g, ' ').toLowerCase() };
  }
}

function stageDot(state: StageDto['state']): StageChip['dot'] | undefined {
  if (state === 'ACTIVE') return 'active';
  if (state === 'CLOSED') return 'done';
  return undefined;
}

/**
 * Data adapter that mounts the V3 {@link TaskBrainPage} on the live brain
 * data. Wires the brain feed → conversation, the composer → the brain
 * agent, the lifecycle Run menu, and the stage-chip strip. Plan/PR tabs
 * and the full sidebar tree are backfilled later; Details shows the task's
 * status for now.
 */
export function TaskBrainRoute({
  threadId, taskId, onOpenStage, onOpenCode, onClosed,
}: {
  threadId: string;
  taskId: string;
  onOpenStage: (stageId: string) => void;
  onOpenCode: () => void;
  /** Closing a task seals it terminal + reaps its worktree, so the page is
   *  a dead end afterwards — navigate away (back to the thread trunk). */
  onClosed: () => void;
}) {
  const { data, pollFast } = useBrainViewData(taskId);
  const { task, brainFeed, stages, subStages } = data;
  const shipProposal = usePendingShipProposal(threadId, taskId);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  // Track the brain's answer count so the "working" indicator clears only
  // when a new response actually lands (not when the user's own message
  // persists into the feed).
  const responseCount = brainFeed.filter(r => r.type === 'BRAIN_AGENT_RESPONSE').length;
  const [awaitedAt, setAwaitedAt] = useState<number | null>(null);
  useEffect(() => {
    if (awaitedAt !== null && responseCount > awaitedAt) setAwaitedAt(null);
  }, [responseCount, awaitedAt]);
  const working = busy || awaitedAt !== null;

  const submit = () => {
    const body = text.trim();
    if (body.length === 0 || busy) return;
    setText('');
    setBusy(true);
    setAwaitedAt(responseCount);
    const bridge = typeof window !== 'undefined' ? window.bridge : undefined;
    bridge?.sendBrainMessage(taskId, body)
      .then(() => pollFast())
      .catch(() => { setAwaitedAt(null); })
      .finally(() => setBusy(false));
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

  const conversation = (
    <Conv>
      {brainFeed.map(row => {
        const { kind, who } = feedKind(row.type);
        return kind === 'user'
          ? <UserMsg key={row.id} text={row.body} />
          : <EventRow key={row.id} kind={kind} who={who} markdown={row.body} />;
      })}
      {shipProposal !== null && <ShipReviewPrompt onReview={onOpenCode} />}
      {working && <Working label="Brain is thinking…" />}
    </Conv>
  );

  const allStages = [...stages, ...subStages];
  const stageChips: StageChip[] = stages.map(s => ({
    label: SHORT_LABEL[s.type],
    dot: stageDot(s.state),
    current: s.state === 'ACTIVE',
    onClick: () => onOpenStage(s.id),
  }));

  const bridge = typeof window !== 'undefined' ? window.bridge : undefined;

  return (
    <TaskBrainPage
      task={{ pillLabel: `TASK #${task.taskNumber}`, title: task.title, branch: task.branch }}
      conversation={conversation}
      stageChips={stageChips}
      composer={{ value: text, onChange: setText, onSubmit: submit, busy, placeholder: 'Ask the brain, or steer the task…' }}
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
