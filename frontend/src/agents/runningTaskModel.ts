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
export type RunningTask = {
  id: string;
  workspaceId: string;
  title: string;
  repository: string;
  kind: 'TASK' | 'REVIEW' | 'CHERRY_PICK' | 'CI_FIX';
  targetId: string;
  status: 'RUNNING' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'STOPPED' | 'BLOCKED';
  startedAt: string;
  expiresAt: string | null;
  pullRequestNumber: number | null;
  branch: string | null;
};

/** The task-type icon each kind shows, as the task lists draw it. */
export const RUNNING_TASK_TYPES = { TASK: 'dev', REVIEW: 'review', CHERRY_PICK: 'cherry', CI_FIX: 'ci' } as const;

export const RUNNING_TASK_LABELS: Record<RunningTask['status'], string> = {
  RUNNING: 'Running', WAITING: 'Needs you', COMPLETED: 'Finished', FAILED: 'Failed', STOPPED: 'Stopped', BLOCKED: 'Needs recovery',
};

/** Copy only sidebar fields across IPC, never runtime paths or provider data. */
export function parseRunningTasks(value: unknown): RunningTask[] {
  if (!Array.isArray(value)) throw new Error('Invalid running tasks');
  return value.map(raw => {
    if (raw === null || typeof raw !== 'object') throw new Error('Invalid running task');
    const item = raw as Record<string, unknown>;
    for (const key of ['id', 'workspaceId', 'title', 'repository', 'targetId', 'startedAt']) {
      if (typeof item[key] !== 'string' || !(item[key] as string).trim()) throw new Error('Invalid running task field');
    }
    if (!['TASK', 'REVIEW', 'CHERRY_PICK', 'CI_FIX'].includes(String(item.kind))
      || !Object.hasOwn(RUNNING_TASK_LABELS, String(item.status))
      || item.kind === 'CI_FIX' && (!Number.isSafeInteger(Number(item.targetId)) || Number(item.targetId) < 1)
      || !Number.isFinite(Date.parse(item.startedAt as string))
      || item.expiresAt !== null && (typeof item.expiresAt !== 'string' || !Number.isFinite(Date.parse(item.expiresAt)))
      || item.pullRequestNumber !== null && (!Number.isSafeInteger(item.pullRequestNumber) || Number(item.pullRequestNumber) < 1)
      || item.branch !== null && (typeof item.branch !== 'string' || !item.branch.trim())) {
      throw new Error('Invalid running task state');
    }
    const { id, workspaceId, title, repository, kind, targetId, status, startedAt, expiresAt, pullRequestNumber, branch } = item;
    return { id, workspaceId, title, repository, kind, targetId, status, startedAt, expiresAt, pullRequestNumber, branch } as RunningTask;
  });
}

export function visibleRunningTasks(tasks: RunningTask[], now: number): RunningTask[] {
  return tasks.filter(task => task.expiresAt === null || Date.parse(task.expiresAt) > now)
    .sort((left, right) => Number(left.expiresAt !== null) - Number(right.expiresAt !== null)
      || Date.parse(right.startedAt) - Date.parse(left.startedAt) || left.id.localeCompare(right.id));
}
