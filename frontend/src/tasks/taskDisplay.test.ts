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
import { isWorktreeBackedTask, taskAgentCwd, taskDisplayBranch, taskModelLabel } from './taskDisplay';

describe('taskDisplay', () => {
  it('prefers the worktree path for the agent cwd', () => {
    expect(taskAgentCwd({
      workingDir: '/repo/main',
      worktreePath: '/repo/main/.bytequay/worktrees/dev/task-1',
    })).toBe('/repo/main/.bytequay/worktrees/dev/task-1');
  });

  it('falls back to the original working dir for legacy tasks', () => {
    expect(taskAgentCwd({
      workingDir: '/repo/main',
      worktreePath: null,
    })).toBe('/repo/main');
  });

  it('prefers the local worktree branch over the original branch', () => {
    expect(taskDisplayBranch({
      branchName: 'main',
      localBranch: 'dev/task-1',
    })).toBe('dev/task-1');
  });

  it('falls back to the original branch when no worktree branch exists', () => {
    expect(taskDisplayBranch({
      branchName: 'main',
      localBranch: ' ',
    })).toBe('main');
  });

  it('detects worktree-backed tasks', () => {
    expect(isWorktreeBackedTask({ worktreePath: '/repo/.bytequay/worktrees/dev/task-1' })).toBe(true);
    expect(isWorktreeBackedTask({ worktreePath: '' })).toBe(false);
    expect(isWorktreeBackedTask({ worktreePath: null })).toBe(false);
  });

  it('formats pending model labels', () => {
    expect(taskModelLabel('claude-sonnet-4.6')).toBe('claude-sonnet-4.6');
    expect(taskModelLabel('  ')).toBe('model pending');
    expect(taskModelLabel(null)).toBe('model pending');
  });
});
