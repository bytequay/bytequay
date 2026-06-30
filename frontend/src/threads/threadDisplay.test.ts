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
import type { WorkUnitTaskDto } from '../types';
import {
  isWorktreeBackedTask,
  threadAgentCwd,
  threadCompactNumber,
  threadDisplayBranch,
  threadModelLabel,
  threadTokenLabel,
} from './threadDisplay';

function task(overrides: Partial<WorkUnitTaskDto>): WorkUnitTaskDto {
  return {
    id: 't', threadId: 'thread-1', seq: 1, status: 'IDLE',
    branchName: null, worktreePath: null, baseBranch: 'main',
    workingDir: null,
    prNumber: null, prState: null, ciState: null,
    taskType: 'DEVELOP',
    linkedPrNumber: null, linkedIssueNumber: null,
    costUsdMilli: 0, tokensIn: 0, tokensOut: 0,
    createdAt: '2026-05-15T12:00:00Z',
    name: null,
    ...overrides,
  } as WorkUnitTaskDto;
}

describe('threadDisplay', () => {
  it('prefers the worktree path for the agent cwd', () => {
    expect(threadAgentCwd(task({ workingDir: '/repo/main',
      worktreePath: '/repo/main/.bytequay/worktrees/dev/thread-1' })))
      .toBe('/repo/main/.bytequay/worktrees/dev/thread-1');
  });

  it('falls back to the task working dir when no worktree', () => {
    expect(threadAgentCwd(task({ workingDir: '/repo/main', worktreePath: null })))
      .toBe('/repo/main');
  });

  it('returns empty string when there is no task', () => {
    expect(threadAgentCwd(null)).toBe('');
  });

  it('returns the task branch name as-is', () => {
    expect(threadDisplayBranch(task({ branchName: 'dev/thread-1' }))).toBe('dev/thread-1');
  });

  it('returns null when the task has no branch', () => {
    expect(threadDisplayBranch(task({ branchName: null }))).toBeNull();
    expect(threadDisplayBranch(null)).toBeNull();
  });

  it('detects worktree-backed tasks', () => {
    expect(isWorktreeBackedTask(task({ worktreePath: '/repo/.bytequay/worktrees/dev/thread-1' }))).toBe(true);
    expect(isWorktreeBackedTask(task({ worktreePath: '' }))).toBe(false);
    expect(isWorktreeBackedTask(task({ worktreePath: null }))).toBe(false);
    expect(isWorktreeBackedTask(null)).toBe(false);
  });

  it('formats pending model labels', () => {
    expect(threadModelLabel('claude-sonnet-4.6')).toBe('claude-sonnet-4.6');
    expect(threadModelLabel('  ')).toBe('model pending');
    expect(threadModelLabel(null)).toBe('model pending');
  });

  it('formats token labels', () => {
    expect(threadTokenLabel(0)).toBe('0 tokens');
    expect(threadTokenLabel(1)).toBe('1 token');
    expect(threadTokenLabel(462)).toBe('462 tokens');
    expect(threadTokenLabel(1_000)).toBe('1k tokens');
    expect(threadTokenLabel(25_500)).toBe('25.5k tokens');
    expect(threadTokenLabel(1_000_000)).toBe('1M tokens');
  });

  it('formats compact numbers', () => {
    expect(threadCompactNumber(999)).toBe('999');
    expect(threadCompactNumber(1_000)).toBe('1k');
    expect(threadCompactNumber(12_500)).toBe('12.5k');
    expect(threadCompactNumber(1_000_000)).toBe('1M');
  });
});
