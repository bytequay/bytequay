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
    outOfScope: ['Changing the cache eviction policy.'],
    pushStrategy: 'await_approval',
    value: 'Unblocks a correctness fix.',
    steps: [
      { n: 1, short: 'Collect comments', detail: 'Confirm the failing behavior.', files: ['src/cache.ts', 'src/cache.test.ts'], risk: 'low' },
      { n: 2, short: 'Check out branch', risk: 'low' },
      { n: 3, short: 'Address in code', risk: 'med' },
      { n: 4, short: 'Format & inspect', risk: 'low' },
      { n: 5, short: 'Push & watch CI', risk: 'opt' },
    ],
    policy: { minApprovals: 0, autoApprove: false, autoMerge: false },
    ...overrides,
  };
}

function noop() { /* callbacks not under test */ }

describe('PipelinePlanCard', () => {
  it('renders the authored steps as one ordered list', () => {
    const { container } = render(<PipelinePlanCard plan={plan()} onPolicyChange={noop} onApprove={noop} onRequestRevision={noop} />);
    expect(Array.from(container.querySelectorAll('.ppc-step-title')).map(node => node.textContent)).toEqual([
      'Collect comments', 'Check out branch', 'Address in code', 'Format & inspect', 'Push & watch CI',
    ]);
    expect(screen.queryByText('Prepare')).toBeNull();
    expect(screen.queryByText('Ship & monitor')).toBeNull();
  });

  it('expands each step accessibly to show its rationale and every file path', () => {
    render(<PipelinePlanCard plan={plan()} onApprove={noop} />);
    const toggle = screen.getByRole('button', { name: 'Expand step 1: Collect comments. Low risk' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('Confirm the failing behavior.')).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByRole('button', { name: 'Collapse step 1: Collect comments. Low risk' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('Confirm the failing behavior.')).toBeTruthy();
    expect(screen.getByText('src/cache.ts')).toBeTruthy();
    expect(screen.getByText('src/cache.test.ts')).toBeTruthy();
    expect(screen.getAllByText('Low risk').length).toBeGreaterThan(0);
  });

  it('shows validation, scope boundaries, and push scope without expanding a fold', () => {
    render(<PipelinePlanCard plan={plan()} onApprove={noop} />);
    expect(screen.getByText('Run the three cache test classes.')).toBeTruthy();
    expect(screen.getByText('Changing the cache eviction policy.')).toBeTruthy();
    expect(screen.getByText('Wait for approval before pushing.')).toBeTruthy();
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

  it('explains why Approve is disabled while the plan is still drafting', () => {
    render(<PipelinePlanCard plan={plan({ status: 'draft' })} onPolicyChange={noop} onRequestRevision={noop} />);
    expect(screen.getByText('Plan drafting')).toBeTruthy();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
    expect(screen.getByText(/Still drafting/)).toBeTruthy();
  });

  it('explains that a finalized plan stays locked during Brain self-review', () => {
    render(<PipelinePlanCard plan={plan({ status: 'running' })} onPolicyChange={noop} onRequestRevision={noop} />);
    expect(screen.getByText('Brain reviewing plan')).toBeTruthy();
    expect(screen.getByText(/Approval unlocks when self-review finishes/)).toBeTruthy();
    expect(screen.queryByText(/Still drafting/)).toBeNull();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
  });

  it('does not show the drafting note for a finalized plan awaiting approval', () => {
    render(<PipelinePlanCard plan={plan({ status: 'ready' })} onPolicyChange={noop} onApprove={noop} onRequestRevision={noop} />);
    expect(screen.getByText('Plan ready')).toBeTruthy();
    expect(screen.queryByText(/Still drafting/)).toBeNull();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(false);
  });

});
