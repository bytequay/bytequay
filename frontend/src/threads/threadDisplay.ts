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
import type { ThreadDto } from '../types';

type ThreadActiveTaskFields = Pick<ThreadDto, 'activeTask'>;

/** Directory the agent process would be spawned in for this thread —
 *  delegates to the active task's worktree (or its workingDir if no
 *  worktree). Empty string when the thread has no active task; that's
 *  a 0-Task brainstorm thread that no agent can attach to. */
export function threadAgentCwd(thread: ThreadActiveTaskFields): string {
  const active = thread.activeTask;
  if (active === null) return '';
  return nonBlank(active.worktreePath) ?? active.workingDir ?? '';
}

export function threadDisplayBranch(thread: ThreadActiveTaskFields): string | null {
  return nonBlank(thread.activeTask?.branchName ?? null);
}

export function isWorktreeBackedTask(thread: ThreadActiveTaskFields): boolean {
  return nonBlank(thread.activeTask?.worktreePath ?? null) !== null;
}

export function threadModelLabel(model: string | null | undefined): string {
  return nonBlank(model) ?? 'model pending';
}

export function threadTokenLabel(tokens: number): string {
  return `${threadCompactNumber(tokens)} ${tokens === 1 ? 'token' : 'tokens'}`;
}

export function threadCompactNumber(value: number): string {
  if (value < 1_000) {
    return String(value);
  }
  if (value < 1_000_000) {
    return trimFixed(value / 1_000) + 'k';
  }
  return trimFixed(value / 1_000_000) + 'M';
}

function trimFixed(value: number): string {
  return value.toFixed(1).replace(/\.0$/, '');
}

function nonBlank(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}
