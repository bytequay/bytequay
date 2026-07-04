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
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReviewRoundDto } from '../../types/brainView';
import { useTaskRounds } from './useTaskRounds';

afterEach(() => {
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function round(id: string): ReviewRoundDto {
  return {
    id, taskId: 't', idx: 1, reviewers: ['@alice'], status: 'posted',
    stats: { fixed: 1, replied: 0, pushedBack: 0, open: 0 }, runId: null,
    openedAt: '2026-01-01T00:00:00Z', gatedAt: null, postedAt: '2026-01-01T00:05:00Z',
  };
}

describe('useTaskRounds', () => {
  it('fetches a task\'s rounds via the bridge', async () => {
    const getTaskRounds = vi.fn(async () => [round('round-1')]);
    window.bridge = { getTaskRounds } as unknown as typeof window.bridge;

    const { result } = renderHook(() => useTaskRounds('t'));
    await waitFor(() => expect(result.current.rounds).toHaveLength(1));
    expect(getTaskRounds).toHaveBeenCalledWith('t');
  });

  it('refetches on demand via refresh()', async () => {
    const getTaskRounds = vi.fn(async () => [round('round-1')]);
    window.bridge = { getTaskRounds } as unknown as typeof window.bridge;

    const { result } = renderHook(() => useTaskRounds('t'));
    await waitFor(() => expect(result.current.rounds).toHaveLength(1));
    result.current.refresh();
    await waitFor(() => expect(getTaskRounds).toHaveBeenCalledTimes(2));
  });
});
