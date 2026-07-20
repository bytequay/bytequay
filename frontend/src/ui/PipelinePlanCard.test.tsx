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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PipelinePlanCard, type Plan } from './PipelinePlanCard';

afterEach(cleanup);

function plan(overrides: Partial<Plan> = {}): Plan {
  return {
    rev: 1,
    status: 'ready',
    goal: 'Add a guard for `maxSize` and rewire the cache.',
    risk: 'low',
    effort: 'low',
    confidence: 'high',
    why: ['Because the old length was stale.'],
    validation: 'Run the three cache test classes.',
    value: 'Unblocks a correctness fix.',
    steps: [
      { n: 1, short: 'Collect comments', phase: 'prepare' },
      { n: 2, short: 'Check out branch', code: 'jack/fix-cache', phase: 'prepare' },
      { n: 3, short: 'Address in code', code: 'MemoryFileSystemCache', phase: 'implement' },
      { n: 4, short: 'Format & inspect', phase: 'verify' },
      { n: 5, short: 'Push & watch CI', phase: 'ship' },
    ],
    policy: { minApprovals: 0, autoApprove: false, autoMerge: false },
    ...overrides,
  };
}

function noop() { /* callbacks not under test */ }

describe('PipelinePlanCard', () => {
  it('groups steps under the four phases with per-column counts', () => {
    render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={noop} onRequestRevision={noop} />);
    for (const name of ['Prepare', 'Implement', 'Verify', 'Ship & monitor']) {
      expect(screen.getByText(name)).toBeTruthy();
    }
    expect(screen.getByText('2 steps')).toBeTruthy(); // Prepare
    expect(screen.getAllByText('1 step').length).toBe(3); // Implement, Verify, Ship
  });

  it('renders backtick spans in the goal as mono chips', () => {
    render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={noop} onRequestRevision={noop} />);
    const chip = screen.getByText('maxSize');
    expect(chip.tagName).toBe('SPAN');
    expect(chip.style.fontFamily).toContain('SF Mono');
  });

  it('fires onPolicyChange with the full merged policy on a control change', () => {
    const onPolicyChange = vi.fn();
    render(<PipelinePlanCard plan={plan()} onPolicyChange={onPolicyChange} onApprove={noop} onRequestRevision={noop} />);
    fireEvent.click(screen.getByText('2', { selector: '.ppc-seg-cell' })); // Min approvals → 2
    expect(onPolicyChange).toHaveBeenCalledWith({ minApprovals: 2, autoApprove: false, autoMerge: false });
    fireEvent.click(screen.getAllByText('On', { selector: '.ppc-seg-cell' })[0]); // first "On" = auto-approve
    expect(onPolicyChange).toHaveBeenLastCalledWith({ minApprovals: 0, autoApprove: true, autoMerge: false });
  });

  it('fires onApprove from the primary button', () => {
    const onApprove = vi.fn();
    render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={onApprove} onRequestRevision={noop} />);
    fireEvent.click(screen.getByText('Approve & start dev'));
    expect(onApprove).toHaveBeenCalledOnce();
  });

  it('sends trimmed revision text and only enables send when non-empty', () => {
    const onRequestRevision = vi.fn();
    render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={noop} onRequestRevision={onRequestRevision} />);
    fireEvent.click(screen.getByText('Request revision'));
    const send = screen.getByText('Send revision request').closest('button') as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '  fix step 3  ' } });
    expect(send.disabled).toBe(false);
    fireEvent.click(send);
    expect(onRequestRevision).toHaveBeenCalledWith('fix step 3');
    // composer collapses back to the button row
    expect(screen.getByText('Approve & start dev')).toBeTruthy();
  });

  it('hides fold toggles when their content is absent', () => {
    render(
      <PipelinePlanCard
        plan={plan({ why: undefined, validation: undefined, value: '' })}
        onPolicyChange={noop} onApprove={noop} onRequestRevision={noop}
      />,
    );
    expect(screen.queryByText('Why this plan')).toBeNull();
    expect(screen.queryByText('Validation')).toBeNull();
    expect(screen.queryByText('Value')).toBeNull();
  });

  it('expands a fold panel on toggle', () => {
    render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={noop} onRequestRevision={noop} />);
    expect(screen.queryByText('Because the old length was stale.')).toBeNull();
    fireEvent.click(screen.getByText('Why this plan'));
    expect(screen.getByText('Because the old length was stale.')).toBeTruthy();
  });

  it('disables the policy toolbar and hides revision when handlers are omitted', () => {
    render(<PipelinePlanCard plan={plan()} onApprove={noop} />);
    // Policy cells render but are disabled (read-only display).
    expect((screen.getByText('2', { selector: '.ppc-seg-cell' }) as HTMLButtonElement).disabled).toBe(true);
    // No revision affordance without onRequestRevision.
    expect(screen.queryByText('Request revision')).toBeNull();
  });

  it('shows the approved footer instead of actions once the plan is approved', () => {
    const onApprove = vi.fn();
    render(
      <PipelinePlanCard
        plan={plan({ status: 'approved' })}
        approvedAt="2026-07-20T10:30:00Z"
        onApprove={onApprove} onRequestRevision={noop} onPolicyChange={noop}
      />,
    );
    expect(screen.queryByText('Approve & start dev')).toBeNull();
    expect(screen.getByText(/Plan approved at 2026-07-20/)).toBeTruthy();
  });

  it('collapses phases with no steps to a Skipped column', () => {
    render(
      <PipelinePlanCard
        plan={plan({ steps: [{ n: 1, short: 'Do the work', phase: 'implement' }] })}
        onApprove={noop}
      />,
    );
    // Prepare, Verify, Ship & monitor are all empty → each reads "Skipped".
    expect(screen.getAllByText('Skipped').length).toBe(3);
    expect(screen.getByText('Do the work')).toBeTruthy();
  });
});
