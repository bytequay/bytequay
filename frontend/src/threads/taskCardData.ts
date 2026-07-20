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
import type { WorkUnitTaskDto } from '../types';
import type { TaskCardData } from '../ui/pane';
import type { TaskStatus } from '../ui/conv';
import type { PrGlyphState } from '../ui/primitives';
import { taskLabel } from './taskLabel';
import { relativeTime } from '../notificationDisplay';

/** Terminal work-unit statuses — the task has landed (COMPLETED/merged) or been
 *  closed/reaped (CANCELED / ARCHIVED). A terminal task folds into the trunk's
 *  compact top history rather than staying live in the feed. IN_REVIEW is NOT
 *  terminal (a shipped task is still in-flight: CI-fixing / addressing comments
 *  / awaiting merge); PENDING is the Queued folder. */
export const TERMINAL_TASK_STATUSES = new Set(['COMPLETED', 'CANCELED', 'ARCHIVED']);

/** Map a work-unit status to the task card's status pill. */
export function cardStatus(status: string): TaskStatus {
  switch (status) {
    case 'COMPLETED': case 'IN_REVIEW': return 'shipped';
    case 'CANCELED': case 'ARCHIVED': return 'closed';
    case 'ERRORED': return 'errored';
    case 'AWAITING_REVIEW': return 'review';
    case 'PAUSED': return 'paused';
    case 'NEEDS_ATTENTION': return 'paused';
    case 'PENDING': return 'pending';
    default: return 'foreground';
  }
}

function cardStatusText(status: string): string | undefined {
  switch (status) {
    case 'AWAITING_REVIEW': return 'Awaiting review';
    case 'NEEDS_ATTENTION': return 'Needs attention';
    default: return undefined;
  }
}

/** The PR-state glyph before a task's name: merged once the work landed, a
 *  draft / open pull-request mark while it's in flight, the red closed mark
 *  when the task errored, and the in-progress mark for a running task that
 *  has no PR yet. */
export function cardPr(t: WorkUnitTaskDto): PrGlyphState | undefined {
  if (t.status === 'COMPLETED') return 'merged';
  if (t.status === 'ERRORED') return 'closed';
  if (t.prNumber == null) {
    return cardStatus(t.status) === 'foreground' ? 'progress' : undefined;
  }
  return typeof t.prState === 'string' && t.prState.toUpperCase() === 'DRAFT' ? 'draft' : 'open';
}

/**
 * The single builder for a task card's display fields, shared by the Tasks
 * tab (right pane) and the inline task-cut node in the conversation, so the
 * two stay in sync — same status pill, PR number, created time, merge-ready
 * badge, and PR glyph. `mergeReady` is supplied by the caller (it comes from
 * a live merge-gate lookup the DTO doesn't carry).
 */
export function toTaskCard(t: WorkUnitTaskDto, mergeReady: boolean): TaskCardData {
  return {
    id: t.id,
    title: taskLabel(t),
    status: cardStatus(t.status),
    statusText: cardStatusText(t.status),
    branch: t.branchName ?? undefined,
    createdLabel: t.createdAt !== null ? relativeTime(t.createdAt) : undefined,
    prNumber: t.prNumber ?? undefined,
    mergeReady,
    pr: cardPr(t),
  };
}
