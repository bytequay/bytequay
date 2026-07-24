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
import { useLocalPr } from './useLocalPr';
import type { LocalPR, LocalPRBundle } from '../../types/localPr';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

function prFor(taskId: string): LocalPR {
  return {
    id: `pr-${taskId}`, taskId, branchName: 'feat/x', baseBranch: 'main', title: 'T',
    description: '', status: 'local-open', createdAt: 1, pushedAt: null, remotePrNumber: null,
    remotePrUrl: null, mergedAt: null, closedAt: null,
    origin: 'task', repo: null, author: null, syncedAt: null,
    syncedAdditions: null, syncedDeletions: null,
  syncedMergeable: null, syncedMergeableState: null, syncedMergeQueueEnabled: false, syncedMergeQueueState: null, branchDeletedAt: null,
  };
}

function bundleFor(taskId: string): LocalPRBundle {
  return {
    pr: prFor(taskId),
    commits: [], timeline: [], checks: [], comments: [], pendingStripCount: 0,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('useLocalPr', () => {
  it('resolves the task id to a PR id, then fetches its bundle', async () => {
    const bundle = bundleFor('t1');
    const getPrForTask = vi.fn().mockResolvedValue(prFor('t1'));
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundle);
    (globalThis as { bridge?: unknown }).bridge = { getPrForTask, getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t1'));
    await waitFor(() => expect(result.current.bundle).toEqual(bundle));
    expect(getPrForTask).toHaveBeenCalledWith('t1');
    expect(getLocalPrBundle).toHaveBeenCalledWith('pr-t1');
  });

  it('resolves null when the task has no PR yet', async () => {
    const getPrForTask = vi.fn().mockResolvedValue(null);
    const getLocalPrBundle = vi.fn();
    (globalThis as { bridge?: unknown }).bridge = { getPrForTask, getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t2'));
    await waitFor(() => expect(result.current.bundle).toBeNull());
    expect(getLocalPrBundle).not.toHaveBeenCalled();
  });

  it('refresh() re-fetches the bundle on demand', async () => {
    const getPrForTask = vi.fn().mockResolvedValue(prFor('t3'));
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundleFor('t3'));
    (globalThis as { bridge?: unknown }).bridge = { getPrForTask, getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t3'));
    await waitFor(() => expect(result.current.bundle).not.toBeUndefined());
    const before = getLocalPrBundle.mock.calls.length;
    result.current.refresh();
    await waitFor(() => expect(getLocalPrBundle.mock.calls.length).toBeGreaterThan(before));
  });

  it('refresh() discovers a PR created after the task initially had none', async () => {
    const pr = prFor('t4');
    const bundle = bundleFor('t4');
    const getPrForTask = vi.fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValue(pr);
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundle);
    (globalThis as { bridge?: unknown }).bridge = { getPrForTask, getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t4'));
    await waitFor(() => expect(result.current.bundle).toBeNull());

    act(() => result.current.refresh());

    await waitFor(() => expect(result.current.bundle).toEqual(bundle));
    expect(getPrForTask).toHaveBeenCalledTimes(2);
    expect(getLocalPrBundle).toHaveBeenCalledWith('pr-t4');
  });

  it('does not attach a late PR resolution to a different task', async () => {
    const oldRequest = deferred<LocalPR | null>();
    const getPrForTask = vi.fn((taskId: string) => taskId === 'task-race-old'
      ? oldRequest.promise : Promise.resolve(prFor(taskId)));
    const getLocalPrBundle = vi.fn((prId: string) =>
      Promise.resolve(bundleFor(prId.replace(/^pr-/, ''))));
    (globalThis as { bridge?: unknown }).bridge = { getPrForTask, getLocalPrBundle };

    const { result, rerender } = renderHook(({ id }) => useLocalPr(id), {
      initialProps: { id: 'task-race-old' },
    });
    rerender({ id: 'task-race-new' });
    await waitFor(() => expect(result.current.bundle?.pr.taskId).toBe('task-race-new'));

    await act(async () => {
      oldRequest.resolve(prFor('task-race-old'));
      await oldRequest.promise;
    });
    expect(result.current.bundle?.pr.taskId).toBe('task-race-new');
  });
});
