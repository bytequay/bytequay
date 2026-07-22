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
import type { ReactNode } from 'react';
import type { DiffInlineComment } from '../diff/DiffInlineComments';
import { PlanReminderTab } from './PlanOverlay';
import type { ReviewVerdict } from './SubmitReviewDrawer';
import {
  TaskPageFrame,
  type TaskPageChanges,
  type TaskPageComposer,
} from './TaskPageFrame';

type BrainTab = 'pr';

/** Locked task-brain frame. Data and live actions remain owned by the route. */
export function TaskBrainPage({
  task, pr, sidebar, conversation, composer, run = {}, tabs, changes,
  planReminder, onRevealPlan,
  onSubmitReview, submittingReview = false, openTabRequest,
  pendingReviewComments = [], onRemovePendingReviewComment, conversationIndex,
  onOpenTrunk,
}: {
  task: {
    pillLabel: string;
    title: string;
    branch?: string;
    finished?: boolean;
    taskNumber?: number;
    trunkLabel?: string;
  };
  pr?: { number: number; status: string; onOpen: () => void };
  sidebar?: ReactNode;
  conversation: ReactNode;
  conversationIndex?: ReactNode;
  /** Retained for call-site compatibility; the locked sidebar is always 216px. */
  collapsed?: boolean;
  composer: TaskPageComposer;
  run?: {
    statusLabel?: string;
    paused?: boolean;
    terminal?: boolean;
    onPause?: () => void;
    onResume?: () => void;
    onClose?: () => void;
  };
  tabs: { pr?: ReactNode };
  changes?: TaskPageChanges;
  planReminder?: 'awaiting' | 'locked';
  onRevealPlan?: () => void;
  onOpenCi?: () => void;
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  pendingReviewComments?: DiffInlineComment[];
  onRemovePendingReviewComment?: (commentId: string) => void;
  openTabRequest?: { tab: BrainTab; token: number };
  onOpenTrunk?: () => void;
}) {
  const parsedTaskNumber = task.taskNumber
    ?? Number.parseInt(task.pillLabel.match(/\d+/)?.[0] ?? '1', 10);
  const revealPlan = () => {
    document.querySelector('.conv-col .plan-card')?.scrollIntoView({
      behavior: 'smooth', block: 'center',
    });
  };
  const reminder = (
    <>
      {planReminder !== undefined && (
        <PlanReminderTab state={planReminder} onClick={onRevealPlan ?? revealPlan} />
      )}
    </>
  );

  return (
    <TaskPageFrame
      surface="brain"
      pageTitle={task.title}
      taskTitle={task.title}
      taskNumber={parsedTaskNumber}
      trunkLabel={task.trunkLabel}
      branch={task.branch}
      run={{
        ...run,
        statusLabel: (run.statusLabel ?? (task.finished === true ? 'COMPLETED' : undefined))?.toUpperCase(),
        terminal: run.terminal ?? task.finished,
      }}
      sidebar={sidebar}
      conversation={conversation}
      conversationIndex={conversationIndex}
      composer={composer}
      pr={pr}
      prPane={tabs.pr}
      changes={changes}
      onOpenTrunk={onOpenTrunk}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={onRemovePendingReviewComment}
      openPrToken={openTabRequest?.tab === 'pr' ? openTabRequest.token : undefined}
      leadingToolbar={reminder}
    />
  );
}
