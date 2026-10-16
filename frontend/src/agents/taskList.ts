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

import { completedExecutionItems, taskState } from '../commits/model';
import type { AttentionLink, AttentionTone } from '../home/attention';
import { reviewStatus } from '../pulls/agent-review/model';
import type { AgentReviewDto } from '../pulls/agent-review/contracts';
import { laneLabel } from '../settings/model';
import type { AgentTaskActivityDto, AgentTaskChangesDto, AgentTaskDto, CherryPickExecution, CiAutofixRunDto, ModelProviderDto } from '../types';
import { changesLabel, taskArchived } from './AgentTasksView';
import { taskCardState, taskLastMessage, taskLinks, type TaskCardState } from './attention';

/** Every kind of work the Tasks page lists, each with its own colour and icon. */
export type TaskType = 'dev' | 'review' | 'cherry' | 'ci';

export const TASK_TYPES: Record<TaskType, { label: string; newLabel: string }> = {
  dev: { label: 'Development', newLabel: 'New task' },
  review: { label: 'Review', newLabel: 'Review a pull request' },
  cherry: { label: 'Cherry-pick', newLabel: 'New cherry-pick' },
  ci: { label: 'CI fix', newLabel: 'Fix a failing check' },
};

export type TaskListMessage = { who: string; text: string; at: string };

/** One task of any type as a card or row draws it. */
export type TaskListItem = {
  key: string;
  type: TaskType;
  title: string;
  state: TaskCardState;
  /** When the item last moved: it picks the day group and the age. */
  at: string;
  message: TaskListMessage | null;
  links: AttentionLink[];
  change: string | null;
  /** How far a cherry-pick has come through its commits. */
  progress: { done: number; total: number } | null;
  /** The agent task behind a Development item: the only type that archives, deletes and selects. */
  task: AgentTaskDto | null;
  review: AgentReviewDto | null;
  execution: CherryPickExecution | null;
  run: CiAutofixRunDto | null;
};

const EMPTY: Pick<TaskListItem, 'task' | 'review' | 'execution' | 'run'> = { task: null, review: null, execution: null, run: null };

/** Words for an enum value the backend sends, such as a pause reason. */
function words(value: string): string {
  const spaced = value.toLowerCase().replace(/_/g, ' ');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function pullRequestLink(number: number, title: string, state: string | null, draft: boolean): AttentionLink {
  const status = state === 'merged' ? { status: 'Merged', tone: 'purple' as AttentionTone }
    : state === 'closed' ? { status: 'Closed', tone: 'neutral' as AttentionTone }
      : draft ? { status: 'Draft', tone: 'neutral' as AttentionTone }
        : state === null ? { status: null, tone: 'neutral' as AttentionTone } : { status: 'Open', tone: 'green' as AttentionTone };
  return { source: 'pr', key: `#${number}`, title, ...status };
}

export function developmentItem(
  task: AgentTaskDto,
  activity: AgentTaskActivityDto | null,
  changes: AgentTaskChangesDto | null,
  providers: ModelProviderDto[] = [],
): TaskListItem {
  const message = taskLastMessage(task, activity);
  const lane = providers.find((provider) => provider.id === task.laneId)?.label ?? laneLabel(task.laneId);
  return {
    ...EMPTY,
    key: `dev:${task.id}`,
    type: 'dev',
    title: task.title,
    state: taskCardState(task, activity),
    at: task.updatedAt,
    message: message === null ? null : { who: message.author === 'USER' ? 'You' : lane, text: message.text, at: message.at },
    links: taskLinks(task),
    // A pull request is already among the links; otherwise the card names what the checkout holds.
    change: task.pullRequest == null && changes !== null ? changesLabel(changes) : null,
    progress: null,
    task,
  };
}

/** What a review found, or that its pull request moved on; the state pill already says the rest. */
const REVIEW_CHANGES = new Set(['ready', 'nofindings', 'newcommits', 'ended']);

export function reviewItem(review: AgentReviewDto): TaskListItem {
  const status = reviewStatus(review);
  const state: TaskCardState = status.status === 'approval' || status.status === 'recovery'
    ? { label: 'Needs you', tone: 'orange', spinning: false, reason: status.status === 'approval' ? 'Approval needed' : 'Could not be shown to have stopped' }
    : status.busy ? { label: 'Running', tone: 'blue', spinning: true, reason: null }
      : status.status === 'failed' ? { label: 'Failed', tone: 'red', spinning: false, reason: null }
        : status.status === 'ended' || status.status === 'ready' || status.status === 'nofindings'
          ? { label: 'Finished', tone: 'neutral', spinning: false, reason: null }
          : { label: 'Idle', tone: 'green', spinning: false, reason: null };
  const last = review.messages.at(-1) ?? null;
  const at = [review.createdAt, review.closedAt, last?.createdAt ?? null]
    .filter((value): value is string => value !== null)
    .reduce((latest, value) => (Date.parse(value) > Date.parse(latest) ? value : latest));
  const title = review.number === null ? `${review.repo} · Local changes`
    : review.pullRequest === null ? `${review.repo} #${review.number}` : `#${review.number} ${review.pullRequest.title}`;
  return {
    ...EMPTY,
    key: `review:${review.id}`,
    type: 'review',
    title,
    state,
    at,
    message: last === null ? null
      : { who: last.author === 'USER' ? 'You' : last.developerTaskId != null ? 'Developer' : review.task.laneLabel, text: last.body, at: last.createdAt },
    links: review.number === null ? []
      : [pullRequestLink(review.number, review.pullRequest?.title ?? review.repo, review.pullRequest?.state ?? null, review.pullRequest?.draft ?? false)],
    change: REVIEW_CHANGES.has(status.status) ? status.chip : null,
    progress: null,
    review,
  };
}

function executionTitle(execution: CherryPickExecution): string {
  return execution.pullRequestDraft?.title || `Cherry-pick ${execution.items.length} commits into ${execution.localTargetBranch}`;
}

export function cherryPickItem(execution: CherryPickExecution): TaskListItem {
  const current = taskState(execution);
  const state: TaskCardState = current === 'running' ? { label: 'Running', tone: 'blue', spinning: true, reason: null }
    : current === 'paused' ? { label: 'Needs you', tone: 'orange', spinning: false, reason: execution.pauseReason ? `Paused: ${words(execution.pauseReason)}` : 'Paused' }
      // A canceled execution stopped short of its commits, so it never reads as completed.
      : { label: current === 'done' ? 'Completed' : 'Finished', tone: 'neutral', spinning: false, reason: null };
  const pullRequest = execution.delivery?.pullRequestNumber ?? null;
  return {
    ...EMPTY,
    key: `cherry:${execution.executionId}`,
    type: 'cherry',
    title: executionTitle(execution),
    state,
    at: execution.updatedAt,
    message: execution.summary ? { who: 'Cherry-pick', text: execution.summary, at: execution.updatedAt } : null,
    links: pullRequest === null ? [] : [pullRequestLink(pullRequest, executionTitle(execution), null, false)],
    change: null,
    progress: { done: completedExecutionItems(execution.items), total: execution.items.length },
    execution,
  };
}

export function ciFixItem(run: CiAutofixRunDto): TaskListItem {
  const state: TaskCardState = run.status === 'ACTIVE' ? { label: 'Running', tone: 'blue', spinning: true, reason: null }
    : run.status === 'PAUSED' ? { label: 'Needs you', tone: 'orange', spinning: false, reason: run.pauseReason ? `Paused: ${words(run.pauseReason)}` : 'Paused' }
      : run.finishReason === 'GREEN' ? { label: 'Completed', tone: 'neutral', spinning: false, reason: null }
        : run.finishReason === 'MAX_ATTEMPTS' || run.finishReason === 'ERROR' ? { label: 'Failed', tone: 'red', spinning: false, reason: null }
          : { label: 'Finished', tone: 'neutral', spinning: false, reason: null };
  return {
    ...EMPTY,
    key: `ci:${run.id}`,
    type: 'ci',
    title: `Get CI green on #${run.pullRequestNumber}`,
    state,
    at: run.updatedAt,
    message: run.summary ? { who: 'CI fix', text: run.summary, at: run.updatedAt } : null,
    links: [pullRequestLink(run.pullRequestNumber, run.headRef, null, false)],
    change: `attempt ${Math.max(1, run.attemptsUsed)} of ${run.authorization.maxAttempts}`,
    progress: null,
    run,
  };
}

/** Whether an item belongs on the Archived shelf; only agent tasks can be shelved. */
export function itemArchived(item: TaskListItem): boolean {
  return item.task !== null && taskArchived(item.task);
}

const STATE_ORDER: Record<TaskCardState['label'], number> = { 'Needs you': 0, Running: 1, Idle: 2, Failed: 3, Completed: 3, Finished: 3 };

export type TaskListGroup = { key: 'yesterday' | 'earlier' | 'older'; label: string; date: string; items: TaskListItem[] };

function dayStart(at: Date): number {
  return new Date(at.getFullYear(), at.getMonth(), at.getDate()).getTime();
}

/** "Sep 21 – 25", or "Aug 30 – Sep 2" across a month, or one date; nothing when no time reads. */
function dateRange(items: TaskListItem[]): string {
  const days = items.map((item) => dayStart(new Date(item.at))).filter(Number.isFinite);
  if (days.length === 0) return '';
  const first = Math.min(...days);
  const last = Math.max(...days);
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).formatRange(first, last);
}

/**
 * Today's items first, as cards, ordered by what they need: waiting on the user, running, idle,
 * then finished. Older items follow as rows in Yesterday, the rest of this week (from Monday), and
 * anything before it, newest first. An item whose time does not read lands in Older.
 */
export function groupTaskList(items: TaskListItem[], now: number): { today: TaskListItem[]; todayDate: string; groups: TaskListGroup[] } {
  // Local calendar days; a daylight-saving change must not move an item across a group boundary.
  const clock = new Date(now);
  const day = (offset: number) => new Date(clock.getFullYear(), clock.getMonth(), clock.getDate() - offset).getTime();
  const today = day(0);
  const yesterday = day(1);
  const weekStart = day((clock.getDay() + 6) % 7);
  const todays: TaskListItem[] = [];
  const yesterdays: TaskListItem[] = [];
  const thisWeek: TaskListItem[] = [];
  const older: TaskListItem[] = [];
  for (const item of items) {
    const at = dayStart(new Date(item.at));
    (at >= today ? todays : at >= yesterday ? yesterdays : at >= weekStart ? thisWeek : older).push(item);
  }
  const newest = (left: TaskListItem, right: TaskListItem) => (Date.parse(right.at) || 0) - (Date.parse(left.at) || 0);
  todays.sort((left, right) => STATE_ORDER[left.state.label] - STATE_ORDER[right.state.label] || newest(left, right));
  [yesterdays, thisWeek, older].forEach((group) => group.sort(newest));
  const groups: TaskListGroup[] = [
    { key: 'yesterday', label: 'Yesterday', date: new Date(yesterday).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }), items: yesterdays },
    { key: 'earlier', label: 'Earlier this week', date: dateRange(thisWeek), items: thisWeek },
    { key: 'older', label: 'Older', date: dateRange(older), items: older },
  ];
  return {
    today: todays,
    todayDate: new Date(today).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }),
    groups: groups.filter((group) => group.items.length > 0),
  };
}
