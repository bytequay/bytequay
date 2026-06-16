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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FlowStepper } from './FlowStepper';
import type { MilestoneSummaryDto, TaskTraceDto } from '../types';

afterEach(() => {
  cleanup();
  (window as { bridge?: unknown }).bridge = undefined;
  localStorage.clear();
  vi.restoreAllMocks();
});

function milestone(over: Partial<MilestoneSummaryDto>): MilestoneSummaryDto {
  return { milestone: 'IMPLEMENT', label: 'Implement', visits: 1, active: false,
    skipped: false, position: 1, ...over };
}

const SUMMARY: MilestoneSummaryDto[] = [
  milestone({ milestone: 'IMPLEMENT', label: 'Implement', visits: 2, position: 1 }),
  milestone({ milestone: 'VALIDATE', label: 'Validate', visits: 0, skipped: true, position: 2 }),
  milestone({ milestone: 'REVIEW', label: 'Review', visits: 1, position: 3 }),
  milestone({ milestone: 'PUSH', label: 'Push', visits: 1, position: 4 }),
  milestone({ milestone: 'WAIT_ON_PR', label: 'Wait on PR', visits: 2, active: true, position: 5 }),
  milestone({ milestone: 'MERGE', label: 'Merge', visits: 0, position: 6 }),
];

const TRACE: TaskTraceDto = {
  taskId: 't1.k1',
  currentPhase: 'PUSHED_AWAITING_CI',
  currentMilestone: 'WAIT_ON_PR',
  events: [
    { n: 1, fromPhase: 'AWAITING_PUSH', toPhase: 'PUSHED_AWAITING_CI', fromMilestone: 'PUSH',
      toMilestone: 'WAIT_ON_PR', actor: 'HUMAN', reason: 'approved push · waiting on CI',
      transitionedAt: new Date().toISOString(), label: 'Wait CI' },
  ],
  milestoneSummary: SUMMARY,
  nextPossible: [
    { trigger: 'AWAITING_REMOTE_REVIEW', label: 'Remote review', cond: 'on CI green / ready' },
    { trigger: 'COMPLETED', label: 'Merged', cond: 'PR merged externally' },
  ],
  linkedActivePr: null,
};

function stubBridge(trace: TaskTraceDto) {
  const getTaskTrace = vi.fn().mockResolvedValue(trace);
  // @ts-expect-error partial bridge for the test
  window.bridge = { getTaskTrace };
  return getTaskTrace;
}

describe('FlowStepper compact strip (collapsed default)', () => {
  it('renders the six mini-stepper buckets with derived states', async () => {
    stubBridge(TRACE);
    const { container } = render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByTestId('phase-strip')).toBeTruthy());

    const buckets = container.querySelectorAll('[data-milestone]');
    expect(buckets).toHaveLength(6);
    const byName = (m: string) =>
      container.querySelector(`[data-milestone="${m}"]`)?.getAttribute('data-state');
    expect(byName('IMPLEMENT')).toBe('reached');
    expect(byName('VALIDATE')).toBe('skipped');
    expect(byName('WAIT_ON_PR')).toBe('active');
    expect(byName('MERGE')).toBe('future');
  });

  it('shows a ×N badge for looped buckets and a one-line context + Timeline toggle', async () => {
    stubBridge(TRACE);
    render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByTestId('phase-strip')).toBeTruthy());
    expect(screen.getAllByText('×2')).toHaveLength(2);
    // The strip is one compact row: context line + the Timeline toggle.
    // No multi-row head pill / sub-status / next-line in the collapsed strip.
    expect(screen.getByText(/approved push/)).toBeTruthy();
    expect(screen.getByText('▾ Timeline')).toBeTruthy();
    expect(screen.queryByText(/precise:/)).toBeNull();
    expect(screen.queryByText('Next node will be:')).toBeNull();
  });

  it('collapses a COMPLETED task to a text trail with no stepper', async () => {
    stubBridge({
      ...TRACE,
      currentPhase: 'COMPLETED',
      currentMilestone: 'MERGE',
      milestoneSummary: SUMMARY.map(m =>
        m.milestone === 'MERGE' ? { ...m, visits: 1, active: true }
          : m.milestone === 'WAIT_ON_PR' ? { ...m, active: false } : m),
    });
    const { container } = render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByText('✓ Done')).toBeTruthy());
    // Journey shown as text; the mini-stepper is hidden when settled.
    expect(screen.getByText(/Implement → Review → Push → Wait on PR → Merge/)).toBeTruthy();
    expect(container.querySelector('[data-milestone]')).toBeNull();
    expect(screen.getByText('▾ Timeline')).toBeTruthy();
  });
});

describe('FlowStepper expanded view', () => {
  function renderExpanded(trace: TaskTraceDto = TRACE) {
    localStorage.setItem('flowStepperMode:t1.k1', 'expanded');
    stubBridge(trace);
    return render(<FlowStepper taskId="t1.k1" />);
  }

  it('shows the head pill, the next-line, and the collapse toggle when expanded', async () => {
    renderExpanded();
    await waitFor(() => expect(screen.getByText(/precise: PUSHED_AWAITING_CI/)).toBeTruthy());
    expect(screen.getByText('Next node will be:')).toBeTruthy();
    expect(screen.getByText('Collapse to milestones')).toBeTruthy();
  });

  it('renders the parallel sub-status axes (expanded, wait-state, linked PR)', async () => {
    renderExpanded({
      ...TRACE,
      linkedActivePr: {
        prNumber: 29897, ciStatus: 'PENDING', draft: true, approvalCount: 0,
        changesRequestedCount: 0, pendingReviewerCount: 1, requestedReviewers: ['alice'],
      },
    });
    await waitFor(() => expect(screen.getByTestId('parallel-status')).toBeTruthy());
    expect(screen.getByText('running')).toBeTruthy();
    expect(screen.getByText(/@alice/)).toBeTruthy();
  });
});

describe('FlowStepper Timeline toggle persistence', () => {
  it('expands on ▾ Timeline and persists per task', async () => {
    stubBridge(TRACE);
    const { unmount } = render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByText('▾ Timeline')).toBeTruthy());

    fireEvent.click(screen.getByText('▾ Timeline'));
    await waitFor(() => expect(screen.getByText('Collapse to milestones')).toBeTruthy());
    expect(localStorage.getItem('flowStepperMode:t1.k1')).toBe('expanded');

    unmount();
    stubBridge(TRACE);
    render(<FlowStepper taskId="t1.k1" />);
    await waitFor(() => expect(screen.getByText('Collapse to milestones')).toBeTruthy());
  });

  it('does not leak one task’s mode onto another', async () => {
    localStorage.setItem('flowStepperMode:t1.k1', 'expanded');
    stubBridge(TRACE);
    render(<FlowStepper taskId="t1.k2" />);
    await waitFor(() => expect(screen.getByText('▾ Timeline')).toBeTruthy());
  });
});
