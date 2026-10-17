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

import type { AgentTaskDto } from '../types';

/**
 * The list knows the durable task, not its events: a turn's claim on the checkout is what says a
 * task is running, and a completed task reads as done in grey, as the design draws it.
 */
export function taskStatePill(task: AgentTaskDto): { label: string; className: string; spinning: boolean } {
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'RECOVERY') {
    return { label: 'Recovery required', className: 'is-recovery', spinning: false };
  }
  if (task.state === 'OPEN' && task.mutationClaim?.kind === 'TURN') {
    return { label: 'Running', className: 'is-running', spinning: true };
  }
  if (task.state === 'OPEN' && task.developmentContinuationUnfinished) {
    return { label: 'Automation running', className: 'is-running', spinning: true };
  }
  switch (task.state) {
    case 'OPEN':
      return { label: 'Idle', className: 'is-idle', spinning: false };
    case 'COMPLETED':
      return { label: 'Completed', className: '', spinning: false };
    case 'FAILED':
      return { label: 'Failed', className: 'is-failed', spinning: false };
    default:
      return { label: task.state, className: '', spinning: false };
  }
}

/** Archived is a shelf, not a state: the task keeps everything and simply leaves the active list. */
export function taskArchived(task: Pick<AgentTaskDto, 'archivedAt'>): boolean {
  return task.archivedAt !== null && task.archivedAt !== undefined;
}

/**
 * A task something may still be writing to; deleting waits for it to stop. A recovery claim is not
 * one: it holds the worktree of a launch that could not be shown stopped, and deleting is how a
 * task leaves that state.
 */
export function taskBusy(task: Pick<AgentTaskDto, 'mutationClaim'>): boolean {
  const claim = task.mutationClaim ?? null;
  return claim !== null && claim.kind !== 'RECOVERY';
}
