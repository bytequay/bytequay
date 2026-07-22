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
import type { WorkUnitTaskDto } from '../../types';
import { isTaskActive, selectSidebarTasks } from './TaskSidebar';

function task(overrides: Partial<WorkUnitTaskDto>): WorkUnitTaskDto {
  return {
    id: 't1', threadId: 'th', seq: 1, status: 'RUNNING', branchName: 'dev/x',
    worktreePath: null, baseBranch: null, workingDir: null, prNumber: null,
    prState: null, ciState: null, taskType: 'dev', linkedPrNumber: null,
    linkedIssueNumber: null, origin: 'user' as never, pushedAt: null,
    phase: 'IMPLEMENTING' as never, agendaJson: null, consecutiveAutoPushes: 0,
    linkedPrRef: null, openingPrompt: null, costUsdMilli: 0, tokensIn: 0,
    tokensOut: 0, createdAt: '', name: null, workModel: null,
    ...overrides,
  };
}

const ids = (r: { visible: WorkUnitTaskDto[] }) => r.visible.map(t => t.id);

describe('isTaskActive', () => {
  it('treats completed/errored and a COMPLETED phase as terminal, IN_REVIEW as active', () => {
    expect(isTaskActive(task({ status: 'RUNNING' }))).toBe(true);
    expect(isTaskActive(task({ status: 'IN_REVIEW' }))).toBe(true);
    expect(isTaskActive(task({ status: 'COMPLETED' }))).toBe(false);
    expect(isTaskActive(task({ status: 'ERRORED' }))).toBe(false);
    expect(isTaskActive(task({ status: 'RUNNING', phase: 'COMPLETED' as never }))).toBe(false);
  });
});

describe('selectSidebarTasks', () => {
  it('drops terminal tasks but always keeps the current one', () => {
    const tasks = [
      task({ id: 'a', seq: 1, status: 'RUNNING' }),
      task({ id: 'b', seq: 2, status: 'COMPLETED' }),
      task({ id: 'c', seq: 3, status: 'IDLE' }),
    ];
    expect(ids(selectSidebarTasks(tasks, 'b', 3, false))).toEqual(['a', 'b', 'c']);
    expect(ids(selectSidebarTasks(tasks, undefined, 3, false))).toEqual(['a', 'c']);
  });

  it('caps at three collapsed and reports the hidden count', () => {
    const tasks = [1, 2, 3, 4, 5].map(n => task({ id: `t${n}`, seq: n }));
    const r = selectSidebarTasks(tasks, 't1', 3, false);
    expect(ids(r)).toEqual(['t1', 't2', 't3']);
    expect(r.hiddenCount).toBe(2);
    expect(selectSidebarTasks(tasks, 't1', 3, true).hiddenCount).toBe(0);
  });

  it('pins the current task into view when the cap would hide it', () => {
    const tasks = [1, 2, 3, 4, 5].map(n => task({ id: `t${n}`, seq: n }));
    const r = selectSidebarTasks(tasks, 't5', 3, false);
    expect(ids(r)).toEqual(['t1', 't2', 't5']);
    expect(r.hiddenCount).toBe(2);
  });
});
