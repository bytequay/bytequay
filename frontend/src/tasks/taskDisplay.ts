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
import type { TaskDto } from '../types';

type TaskCwdFields = Pick<TaskDto, 'workingDir' | 'worktreePath'>;
type TaskBranchFields = Pick<TaskDto, 'branchName' | 'localBranch'>;

export function taskAgentCwd(task: TaskCwdFields): string {
  return nonBlank(task.worktreePath) ?? task.workingDir;
}

export function taskDisplayBranch(task: TaskBranchFields): string | null {
  return nonBlank(task.localBranch) ?? nonBlank(task.branchName);
}

export function isWorktreeBackedTask(task: Pick<TaskDto, 'worktreePath'>): boolean {
  return nonBlank(task.worktreePath) !== null;
}

export function taskModelLabel(model: string | null | undefined): string {
  return nonBlank(model) ?? 'model pending';
}

function nonBlank(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null;
  }
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}
