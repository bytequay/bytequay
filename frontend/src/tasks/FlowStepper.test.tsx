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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FlowStepper } from './FlowStepper';
import type { MilestoneSummaryDto, TaskTraceDto } from '../types';

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
  vi.restoreAllMocks();
});

function milestone(over: Partial<MilestoneSummaryDto>): MilestoneSummaryDto {
  return { milestone: 'IMPLEMENT', label: 'Implement', visits: 1, active: false,
    skipped: false, position: 1, ...over };
}

const TRACE: TaskTraceDto = {
  taskId: 't1.k1',
  currentPhase: 'PUSHED_AWAITING_CI',
  currentMilestone: 'WAIT_ON_PR',
  events: [
    { n: 1, fromPhase: null, toPhase: 'IMPLEMENTING', fromMilestone: null,
      toMilestone: 'IMPLEMENT', actor: 'SCHEDULER', reason: 'queued', transitionedAt:
      new Date().toISOString(), label: 'Implement' },
  ],
  milestoneSummary: [
    milestone({ milestone: 'IMPLEMENT', label: 'Implement', visits: 2, position: 1 }),
    milestone({ milestone: 'VALIDATE', label: 'Validate', visits: 0, skipped: true, position: 2 }),
    milestone({ milestone: 'REVIEW', label: 'Review', visits: 1, position: 3 }),
    milestone({ milestone: 'PUSH', label: 'Push', visits: 1, position: 4 }),
    milestone({ milestone: 'WAIT_ON_PR', label: 'Wait on PR', visits: 2, active: true, position: 5 }),
    milestone({ milestone: 'MERGE', label: 'Merge', visits: 0, position: 6 }),
  ],
  nextPossible: [],
};

function stubBridge(trace: TaskTraceDto) {
  const getTaskTrace = vi.fn().mockResolvedValue(trace);
  // @ts-expect-error partial bridge for the test
  window.bridge = { getTaskTrace };
  return getTaskTrace;
}

describe('FlowStepper collapsed view', () => {
  it('renders the six milestone buckets with derived states', async () => {
    stubBridge(TRACE);
    const { container } = render(<FlowStepper taskId="t1.k1" />);

    await waitFor(() => expect(screen.getByText('Wait on PR')).toBeTruthy());

    const buckets = container.querySelectorAll('[data-milestone]');
    expect(buckets).toHaveLength(6);
    const byName = (m: string) =>
      container.querySelector(`[data-milestone="${m}"]`)?.getAttribute('data-state');
    expect(byName('IMPLEMENT')).toBe('reached');
    expect(byName('VALIDATE')).toBe('skipped');
    expect(byName('WAIT_ON_PR')).toBe('active');
    expect(byName('MERGE')).toBe('future');
  });

  it('shows a ×N loop badge only when a bucket was visited more than once', async () => {
    stubBridge(TRACE);
    render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByText('Wait on PR')).toBeTruthy());
    // IMPLEMENT (×2) and WAIT_ON_PR (×2) carry badges; single-visit ones don't.
    expect(screen.getAllByText('×2')).toHaveLength(2);
    expect(screen.queryByText('×1')).toBeNull();
  });

  it('surfaces the precise phase in the head pill', async () => {
    stubBridge(TRACE);
    render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() =>
      expect(screen.getByText(/precise: PUSHED_AWAITING_CI/)).toBeTruthy());
  });
});
