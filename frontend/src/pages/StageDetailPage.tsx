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
  type TaskPagePr,
} from './TaskPageFrame';

/** Work-stage variants sharing the locked detail frame. */
export type StageKind = 'plan' | 'dev' | 'remote-dev' | 'ci-fix' | 'comments' | 'cleanup';
type StageTab = 'pr' | 'ci';

const STAGE_KEY: Record<StageKind, string> = {
  plan: 'plan',
  dev: 'dev',
  'remote-dev': 'remote dev',
  'ci-fix': 'ci fix',
  comments: 'comments',
  cleanup: 'cleanup',
};

/** Locked stage/agent page. Its work folds are supplied expanded by the route. */
export function StageDetailPage({
  stageKind, stage, sidebar, conversation, composer, run = {}, tabs, changes, pr,
  planReminder, onRevealPlan,
  onSubmitReview, submittingReview = false,
  openTabRequest, pendingReviewComments = [], onRemovePendingReviewComment,
  conversationIndex, taskNumber, trunkLabel, taskTitle, onOpenTrunk, onOpenTask,
}: {
  stageKind: StageKind;
  stage: { title: string; branch?: string; pillLabel?: string };
  sidebar?: ReactNode;
  conversation: ReactNode;
  conversationIndex?: ReactNode;
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
  tabs: { pr?: ReactNode; ci?: ReactNode };
  pr?: TaskPagePr;
  changes?: TaskPageChanges;
  tabCounts?: Partial<Record<StageTab, { count?: number; countColor?: 'red' | 'acc' | 'muted' }>>;
  paneMeta?: { left?: ReactNode; right?: ReactNode };
  onOpenCi?: () => void;
  planReminder?: 'awaiting' | 'locked';
  onRevealPlan?: () => void;
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  pendingReviewComments?: DiffInlineComment[];
  onRemovePendingReviewComment?: (commentId: string) => void;
  openTabRequest?: { tab: StageTab; token: number };
  taskNumber?: number;
  trunkLabel?: string;
  taskTitle?: string;
  onOpenTrunk?: () => void;
  onOpenTask?: () => void;
}) {
  const reminder = planReminder !== undefined && onRevealPlan !== undefined
    ? <PlanReminderTab state={planReminder} onClick={onRevealPlan} /> : null;
  const reminders = reminder ?? undefined;

  return (
    <TaskPageFrame
      surface="stage"
      pageTitle={stage.title}
      taskTitle={taskTitle ?? stage.title}
      taskNumber={taskNumber}
      trunkLabel={trunkLabel}
      branch={stage.branch}
      run={{ ...run, statusLabel: run.statusLabel?.toUpperCase() }}
      sidebar={sidebar}
      conversation={conversation}
      conversationIndex={conversationIndex}
      composer={composer}
      pr={pr}
      prPane={tabs.pr}
      changes={changes}
      stageKey={STAGE_KEY[stageKind]}
      onOpenTrunk={onOpenTrunk}
      onOpenTask={onOpenTask}
      onSubmitReview={onSubmitReview}
      submittingReview={submittingReview}
      pendingReviewComments={pendingReviewComments}
      onRemovePendingReviewComment={onRemovePendingReviewComment}
      openPrToken={openTabRequest?.tab === 'pr' ? openTabRequest.token : undefined}
      leadingToolbar={reminders}
    />
  );
}
