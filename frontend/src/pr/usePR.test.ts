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
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePR } from './usePR';
import type { LocalPRBundle } from '../types/localPr';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

function bundleFor(prId: string): LocalPRBundle {
  return {
    pr: {
      id: prId, taskId: null, branchName: 'feat/x', baseBranch: 'main', title: 'T',
      description: '', status: 'remote-open', createdAt: 1, pushedAt: null, remotePrNumber: 7,
      remotePrUrl: null, mergedAt: null, closedAt: null,
      origin: 'external', repo: 'acme/widget', author: '@octocat', syncedAt: 1,
      syncedAdditions: null, syncedDeletions: null,
    },
    commits: [], timeline: [], checks: [], comments: [], pendingStripCount: 0,
  };
}

describe('usePR', () => {
  it('fetches the bundle by id', async () => {
    const bundle = bundleFor('pr-ext');
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundle);
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => usePR('pr-ext'));
    await waitFor(() => expect(result.current.bundle).toEqual(bundle));
    expect(getLocalPrBundle).toHaveBeenCalledWith('pr-ext');
  });

  it('idles without fetching when prId is null', async () => {
    const getLocalPrBundle = vi.fn();
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => usePR(null));
    expect(result.current.bundle).toBeNull();
    expect(getLocalPrBundle).not.toHaveBeenCalled();
  });

  it('resolves null on a 404', async () => {
    const getLocalPrBundle = vi.fn().mockResolvedValue(null);
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => usePR('missing'));
    await waitFor(() => expect(result.current.bundle).toBeNull());
  });

  it('refresh() re-fetches on demand', async () => {
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundleFor('pr-ext2'));
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => usePR('pr-ext2'));
    await waitFor(() => expect(result.current.bundle).not.toBeUndefined());
    const before = getLocalPrBundle.mock.calls.length;
    result.current.refresh();
    await waitFor(() => expect(getLocalPrBundle.mock.calls.length).toBeGreaterThan(before));
  });

  it('flips syncing true while a fetch is in flight', async () => {
    let resolveFetch: (b: LocalPRBundle) => void = () => {};
    const getLocalPrBundle = vi.fn().mockReturnValue(new Promise<LocalPRBundle>(res => { resolveFetch = res; }));
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => usePR('pr-ext3'));
    await waitFor(() => expect(result.current.syncing).toBe(true));
    resolveFetch(bundleFor('pr-ext3'));
    await waitFor(() => expect(result.current.syncing).toBe(false));
  });
});
