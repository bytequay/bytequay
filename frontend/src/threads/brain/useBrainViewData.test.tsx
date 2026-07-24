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
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TaskBrainViewData } from '../../types/brainView';
import { buildEmptyBrainView } from './brainViewFixture';
import { useBrainViewData } from './useBrainViewData';

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function view(taskId: string): TaskBrainViewData {
  const empty = buildEmptyBrainView(taskId);
  return { ...empty, task: { ...empty.task, id: taskId, title: taskId } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('useBrainViewData', () => {
  it('keeps a late previous-task response out of the current task', async () => {
    const oldRequest = deferred<TaskBrainViewData>();
    const getBrainView = vi.fn((taskId: string) => taskId === 'brain-race-old'
      ? oldRequest.promise : Promise.resolve(view(taskId)));
    window.bridge = { getBrainView } as unknown as typeof window.bridge;

    const { result, rerender } = renderHook(({ id }) => useBrainViewData(id), {
      initialProps: { id: 'brain-race-old' },
    });
    rerender({ id: 'brain-race-new' });
    await waitFor(() => expect(result.current.data.task.id).toBe('brain-race-new'));

    await act(async () => {
      oldRequest.resolve(view('brain-race-old'));
      await oldRequest.promise;
    });
    expect(result.current.data.task.id).toBe('brain-race-new');
  });
});
