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
import type { ThreadDto, ThreadResourceLaneDto, ThreadStatusDto, ThreadTurnDto } from '../types';

export type SchedulerDisplayStatus = ThreadStatusDto | 'QUEUED';

export type ActiveTurnSummary = {
  status: 'RUNNING' | 'QUEUED';
  lane: ThreadResourceLaneDto;
  queued: number;
  input: string;
};

export function buildActiveTurnSummaries(turns: ThreadTurnDto[]): Map<string, ActiveTurnSummary> {
  const queuedCounts = new Map<string, number>();
  const summaries = new Map<string, ActiveTurnSummary>();
  for (const turn of turns) {
    if (turn.status === 'QUEUED') {
      queuedCounts.set(turn.threadId, (queuedCounts.get(turn.threadId) ?? 0) + 1);
      if (!summaries.has(turn.threadId)) {
        summaries.set(turn.threadId, {
          status: 'QUEUED',
          lane: turn.lane,
          queued: 0,
          input: turn.input,
        });
      }
      continue;
    }
    if (turn.status === 'RUNNING') {
      summaries.set(turn.threadId, {
        status: 'RUNNING',
        lane: turn.lane,
        queued: 0,
        input: turn.input,
      });
    }
  }
  for (const [threadId, queued] of queuedCounts) {
    const current = summaries.get(threadId);
    if (current !== undefined) {
      summaries.set(threadId, { ...current, queued });
    }
  }
  return summaries;
}

export function displayStatusForTask(
  thread: ThreadDto,
  summary: ActiveTurnSummary | undefined,
): SchedulerDisplayStatus {
  if (summary?.status === 'RUNNING') {
    return 'RUNNING';
  }
  if (summary?.status === 'QUEUED' && thread.status !== 'COMPLETED' && thread.status !== 'ARCHIVED' && thread.status !== 'ERRORED') {
    return 'QUEUED';
  }
  return thread.status;
}

export function threadActivityRank(
  thread: ThreadDto,
  summary: ActiveTurnSummary | undefined,
): number {
  switch (displayStatusForTask(thread, summary)) {
    case 'RUNNING':
      return 0;
    case 'AWAITING':
      return 1;
    case 'QUEUED':
    case 'PENDING':
      return 2;
    case 'IDLE':
      return 3;
    case 'COMPLETED':
      return 4;
    case 'ERRORED':
      return 5;
  }
}
