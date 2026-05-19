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
import { describe, expect, it } from 'vitest';
import type { TaskDto, TaskTurnDto } from '../types';
import {
  buildActiveTurnSummaries,
  displayStatusForTask,
  taskActivityRank,
} from './taskTurnSummary';

describe('taskTurnSummary', () => {
  it('lets running turns win and preserves queued count', () => {
    const summaries = buildActiveTurnSummaries([
      turn('queued-1', 'task-1', 'QUEUED', 'first'),
      turn('running-1', 'task-1', 'RUNNING', 'running'),
      turn('queued-2', 'task-1', 'QUEUED', 'second'),
      turn('done', 'task-2', 'COMPLETED', 'done'),
    ]);

    expect(summaries.get('task-1')).toEqual({
      status: 'RUNNING',
      lane: 'CLI',
      queued: 2,
      input: 'running',
    });
    expect(summaries.has('task-2')).toBe(false);
  });

  it('displays queued work before idle task state', () => {
    const task = taskRow('IDLE');
    const summary = buildActiveTurnSummaries([
      turn('queued-1', task.id, 'QUEUED', 'next'),
    ]).get(task.id);

    expect(displayStatusForTask(task, summary)).toBe('QUEUED');
    expect(taskActivityRank(task, summary)).toBeLessThan(taskActivityRank(taskRow('IDLE'), undefined));
  });
});

function turn(id: string, taskId: string, status: TaskTurnDto['status'], input: string): TaskTurnDto {
  return {
    id,
    taskId,
    lane: 'CLI',
    status,
    input,
    createdAt: '2026-05-18T12:00:00Z',
    updatedAt: '2026-05-18T12:00:00Z',
    startedAt: null,
    finishedAt: null,
    errorMessage: null,
  };
}

function taskRow(status: TaskDto['status']): TaskDto {
  return {
    id: `task-${status}`,
    kind: 'CLI_AGENT',
    provider: 'claude-code',
    agentSessionId: null,
    title: 'Task',
    status,
    workingDir: '/tmp/repo',
    branchName: null,
    model: 'claude-sonnet-4.6',
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    processPid: null,
    logPath: null,
    createdAt: '2026-05-18T12:00:00Z',
    updatedAt: '2026-05-18T12:00:00Z',
    endedAt: null,
    errorMessage: null,
    metadataJson: '{}',
    taskType: 'DEVELOP',
    linkedPrNumber: null,
    linkedIssueNumber: null,
  };
}
