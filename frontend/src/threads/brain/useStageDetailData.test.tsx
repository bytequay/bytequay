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
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StageDetailData } from '../../types/brainView';
import { useStageDetailData } from './useStageDetailData';

afterEach(() => {
  vi.useRealTimers();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function stageData(stageId: string): StageDetailData {
  return { stage: { id: stageId } } as unknown as StageDetailData;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

describe('useStageDetailData', () => {
  it('seeds from the per-id cache on a later mount instead of blanking', async () => {
    const getStageDetail = vi.fn(async (id: string): Promise<StageDetailData> => stageData(id));
    window.bridge = { getStageDetail } as unknown as typeof window.bridge;

    // First mount populates the cache for stage-A.
    const first = renderHook(() => useStageDetailData('stage-A'));
    await waitFor(() => expect(first.result.current.data).not.toBeNull());
    first.unmount();

    // A fresh mount for the same stage paints the cached snapshot immediately
    // (synchronously on first render) — no null frame.
    const second = renderHook(() => useStageDetailData('stage-A'));
    expect(second.result.current.data).not.toBeNull();
    expect((second.result.current.data as unknown as { stage: { id: string } }).stage.id).toBe('stage-A');
  });

  it('swaps to the new stage on an in-place id change rather than showing the old one', async () => {
    const getStageDetail = vi.fn(async (id: string): Promise<StageDetailData> => stageData(id));
    window.bridge = { getStageDetail } as unknown as typeof window.bridge;

    const { result, rerender } = renderHook(({ id }) => useStageDetailData(id), {
      initialProps: { id: 'stage-A' },
    });
    await waitFor(() => expect(result.current.data).not.toBeNull());

    // Switch to a never-seen stage: must not keep stage-A's snapshot under the
    // new header — it resets, then the fetch fills stage-B in.
    rerender({ id: 'stage-B' });
    await waitFor(() =>
      expect((result.current.data as unknown as { stage: { id: string } } | null)?.stage.id).toBe('stage-B'));
  });

  it('ignores a previous stage request that resolves after the new stage', async () => {
    const oldRequest = deferred<StageDetailData>();
    const getStageDetail = vi.fn((id: string) => id === 'stage-race-old'
      ? oldRequest.promise : Promise.resolve(stageData(id)));
    window.bridge = { getStageDetail } as unknown as typeof window.bridge;

    const { result, rerender } = renderHook(({ id }) => useStageDetailData(id), {
      initialProps: { id: 'stage-race-old' },
    });
    rerender({ id: 'stage-race-new' });
    await waitFor(() => expect(result.current.data?.stage.id).toBe('stage-race-new'));

    await act(async () => {
      oldRequest.resolve(stageData('stage-race-old'));
      await oldRequest.promise;
    });
    expect(result.current.data?.stage.id).toBe('stage-race-new');
  });
});
