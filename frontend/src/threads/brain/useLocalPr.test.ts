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
import { useLocalPr } from './useLocalPr';
import type { LocalPRBundle } from '../../types/localPr';

afterEach(() => {
  cleanup();
  delete (globalThis as { bridge?: unknown }).bridge;
});

function bundleFor(taskId: string): LocalPRBundle {
  return {
    pr: {
      id: `pr-${taskId}`, taskId, branchName: 'feat/x', baseBranch: 'main', title: 'T',
      description: '', status: 'local-open', createdAt: 1, pushedAt: null, remotePrNumber: null,
      remotePrUrl: null, mergedAt: null, closedAt: null,
    },
    commits: [], timeline: [], checks: [], comments: [], pendingStripCount: 0,
  };
}

describe('useLocalPr', () => {
  it('resolves the task bundle from the bridge', async () => {
    const bundle = bundleFor('t1');
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundle);
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t1'));
    await waitFor(() => expect(result.current.bundle).toEqual(bundle));
    expect(getLocalPrBundle).toHaveBeenCalledWith('t1');
  });

  it('resolves null when the task has no local PR yet (404 → null)', async () => {
    const getLocalPrBundle = vi.fn().mockResolvedValue(null);
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t2'));
    await waitFor(() => expect(result.current.bundle).toBeNull());
  });

  it('refresh() re-fetches on demand', async () => {
    const getLocalPrBundle = vi.fn().mockResolvedValue(bundleFor('t3'));
    (globalThis as { bridge?: unknown }).bridge = { getLocalPrBundle };

    const { result } = renderHook(() => useLocalPr('t3'));
    await waitFor(() => expect(result.current.bundle).not.toBeUndefined());
    const before = getLocalPrBundle.mock.calls.length;
    result.current.refresh();
    await waitFor(() => expect(getLocalPrBundle.mock.calls.length).toBeGreaterThan(before));
  });
});
