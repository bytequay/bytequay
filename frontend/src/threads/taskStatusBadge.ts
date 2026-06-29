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

/** Colour family for an at-a-glance task/thread status dot. */
export type StatusTone = 'running' | 'review' | 'attention' | 'done' | 'idle';

export type StatusBadge = { label: string; tone: StatusTone };

/**
 * Maps a task status string (backend {@code TaskStatus}) to a human label +
 * a colour tone, so a thread's "running vs finished" state is legible at a
 * glance. Notably {@code COMPLETED} reads as **Done** (the PR merged), not
 * the older "shipped", which was easily confused with "PR opened".
 */
export function taskStatusBadge(status: string): StatusBadge {
  switch (status) {
    case 'RUNNING': return { label: 'Running', tone: 'running' };
    case 'NEEDS_ATTENTION': return { label: 'Needs attention', tone: 'attention' };
    case 'ERRORED': return { label: 'Errored', tone: 'attention' };
    case 'AWAITING': return { label: 'Awaiting approval', tone: 'review' };
    case 'AWAITING_REVIEW': return { label: 'Awaiting your review', tone: 'review' };
    case 'IN_REVIEW': return { label: 'In review', tone: 'review' };
    case 'COMPLETED': return { label: 'Done', tone: 'done' };
    case 'REMOTE_CLOSED': return { label: 'Remote closed', tone: 'done' };
    case 'IDLE':
    case 'PENDING': return { label: 'Idle', tone: 'idle' };
    default: return { label: status.toLowerCase().replace(/_/g, ' '), tone: 'idle' };
  }
}

/** Priority order for picking a thread's headline status from its tasks —
 *  attention/running surface over a finished or idle sibling. */
const PRIORITY = [
  'NEEDS_ATTENTION', 'ERRORED', 'RUNNING', 'AWAITING_REVIEW', 'AWAITING',
  'IN_REVIEW', 'COMPLETED', 'REMOTE_CLOSED', 'IDLE', 'PENDING',
];

/** The single most salient status across a thread's tasks, or null when it
 *  has none. Drives the thread page's status chip. */
export function headlineStatus(statuses: string[]): string | null {
  if (statuses.length === 0) return null;
  for (const s of PRIORITY) {
    if (statuses.includes(s)) return s;
  }
  return statuses[0];
}
