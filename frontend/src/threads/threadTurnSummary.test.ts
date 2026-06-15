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
import type { ThreadDto, ThreadTurnDto } from '../types';
import {
  buildActiveTurnSummaries,
  displayStatusForTask,
  threadActivityRank,
} from './threadTurnSummary';

describe('threadTurnSummary', () => {
  it('lets running turns win and preserves queued count', () => {
    const summaries = buildActiveTurnSummaries([
      turn('queued-1', 'thread-1', 'QUEUED', 'first'),
      turn('running-1', 'thread-1', 'RUNNING', 'running'),
      turn('queued-2', 'thread-1', 'QUEUED', 'second'),
      turn('done', 'thread-2', 'COMPLETED', 'done'),
    ]);

    expect(summaries.get('thread-1')).toEqual({
      status: 'RUNNING',
      lane: 'CLI',
      queued: 2,
      input: 'running',
    });
    expect(summaries.has('thread-2')).toBe(false);
  });

  it('displays queued work before idle thread state', () => {
    const thread = threadRow('IDLE');
    const summary = buildActiveTurnSummaries([
      turn('queued-1', thread.id, 'QUEUED', 'next'),
    ]).get(thread.id);

    expect(displayStatusForTask(thread, summary)).toBe('QUEUED');
    expect(threadActivityRank(thread, summary)).toBeLessThan(threadActivityRank(threadRow('IDLE'), undefined));
  });
});

function turn(id: string, threadId: string, status: ThreadTurnDto['status'], input: string): ThreadTurnDto {
  return {
    id,
    threadId,
    taskId: null,
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

function threadRow(status: ThreadDto['status']): ThreadDto {
  return {
    id: `thread-${status}`,
    kind: 'CLI_AGENT',
    provider: 'claude-code',
    agentSessionId: null,
    title: 'Thread',
    status,
    flow: 'build',
    model: 'claude-sonnet-4.6',
    costUsdMilli: 0,
    tokensIn: 0,
    tokensOut: 0,
    createdAt: '2026-05-18T12:00:00Z',
    updatedAt: '2026-05-18T12:00:00Z',
    endedAt: null,
    errorMessage: null,
    workspaceId: 'ws-default',
    workModel: null,
    activeTask: null,
    queue: [],
    parallelSlots: 1,
  };
}
