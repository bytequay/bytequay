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
import type { AgentRunDto } from '../../types/brainView';
import { useTaskRuns } from './useTaskRuns';

afterEach(() => {
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function run(id: string): AgentRunDto {
  return {
    id, taskId: 't', kind: 'ci_fix', source: 'remote', parentStageId: null,
    reviewRoundId: null, stageId: `${id}-stage`, status: 'running', iterations: 1,
    budget: null, headline: null, startedAt: '2026-01-01T00:00:00Z', finishedAt: null,
  };
}

describe('useTaskRuns', () => {
  it('fetches a task\'s runs via the bridge', async () => {
    const getTaskRuns = vi.fn(async () => [run('r1'), run('r2')]);
    window.bridge = { getTaskRuns } as unknown as typeof window.bridge;

    const { result } = renderHook(() => useTaskRuns('t'));
    await waitFor(() => expect(result.current).toHaveLength(2));
    expect(getTaskRuns).toHaveBeenCalledWith('t');
  });

  it('stays empty without a bridge instead of throwing', () => {
    const { result } = renderHook(() => useTaskRuns('t'));
    expect(result.current).toEqual([]);
  });
});
