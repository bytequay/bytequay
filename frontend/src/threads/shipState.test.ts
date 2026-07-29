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
import { describe, it, expect, vi } from 'vitest';
import type { LocalPR } from '../types/localPr';
import { approveAndShipTask, isTaskOwnedLocalOpenPr } from './shipState';

describe('approveAndShipTask', () => {
  it('enables Ship only for the exact Task-owned local-open PR', () => {
    const ready = {
      id: 'pr-1', taskId: 'task-1', origin: 'task', status: 'local-open',
    } as LocalPR;

    expect(isTaskOwnedLocalOpenPr(ready, 'task-1')).toBe(true);
    expect(isTaskOwnedLocalOpenPr(ready, 'task-2')).toBe(false);
    expect(isTaskOwnedLocalOpenPr({ ...ready, status: 'remote-drafted' }, 'task-1')).toBe(false);
    expect(isTaskOwnedLocalOpenPr(null, 'task-1')).toBe(false);
  });

  it('pushes only the exact Task-owned local PR', async () => {
    const pr = {
      id: 'pr-1', taskId: 'task-1', origin: 'task', status: 'local-open',
    } as LocalPR;
    const getPrForTask = vi.fn(async () => pr);
    const pushLocalPr = vi.fn(async () => pr);

    await approveAndShipTask({ getPrForTask, pushLocalPr }, 'task-1');

    expect(getPrForTask).toHaveBeenCalledWith('task-1');
    expect(pushLocalPr).toHaveBeenCalledWith('pr-1');
  });

  it('fails closed when the PR projection has another Task owner', async () => {
    const pr = {
      id: 'pr-2', taskId: 'task-2', origin: 'task', status: 'local-open',
    } as LocalPR;
    const pushLocalPr = vi.fn(async () => pr);

    await expect(approveAndShipTask({
      getPrForTask: async () => pr,
      pushLocalPr,
    }, 'task-1')).rejects.toThrow('does not belong to Task task-1');
    expect(pushLocalPr).not.toHaveBeenCalled();
  });

  it('rejects an already-pushed Task PR', async () => {
    const pr = {
      id: 'pr-1', taskId: 'task-1', origin: 'task', status: 'remote-drafted',
    } as LocalPR;
    const pushLocalPr = vi.fn(async () => pr);

    await expect(approveAndShipTask({
      getPrForTask: async () => pr,
      pushLocalPr,
    }, 'task-1')).rejects.toThrow('no local PR ready to ship');
    expect(pushLocalPr).not.toHaveBeenCalled();
  });
});
