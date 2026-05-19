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
import type { TaskDto, TaskResourceLaneDto, TaskStatusDto, TaskTurnDto } from '../types';

export type SchedulerDisplayStatus = TaskStatusDto | 'QUEUED';

export type ActiveTurnSummary = {
  status: 'RUNNING' | 'QUEUED';
  lane: TaskResourceLaneDto;
  queued: number;
  input: string;
};

export function buildActiveTurnSummaries(turns: TaskTurnDto[]): Map<string, ActiveTurnSummary> {
  const queuedCounts = new Map<string, number>();
  const summaries = new Map<string, ActiveTurnSummary>();
  for (const turn of turns) {
    if (turn.status === 'QUEUED') {
      queuedCounts.set(turn.taskId, (queuedCounts.get(turn.taskId) ?? 0) + 1);
      if (!summaries.has(turn.taskId)) {
        summaries.set(turn.taskId, {
          status: 'QUEUED',
          lane: turn.lane,
          queued: 0,
          input: turn.input,
        });
      }
      continue;
    }
    if (turn.status === 'RUNNING') {
      summaries.set(turn.taskId, {
        status: 'RUNNING',
        lane: turn.lane,
        queued: 0,
        input: turn.input,
      });
    }
  }
  for (const [taskId, queued] of queuedCounts) {
    const current = summaries.get(taskId);
    if (current !== undefined) {
      summaries.set(taskId, { ...current, queued });
    }
  }
  return summaries;
}

export function displayStatusForTask(
  task: TaskDto,
  summary: ActiveTurnSummary | undefined,
): SchedulerDisplayStatus {
  if (summary?.status === 'RUNNING') {
    return 'RUNNING';
  }
  if (summary?.status === 'QUEUED' && task.status !== 'COMPLETED' && task.status !== 'ERRORED') {
    return 'QUEUED';
  }
  return task.status;
}

export function taskActivityRank(
  task: TaskDto,
  summary: ActiveTurnSummary | undefined,
): number {
  switch (displayStatusForTask(task, summary)) {
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
