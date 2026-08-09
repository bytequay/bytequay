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
import type { PlanCardDto } from '../../types/brainView';
import { PlanCard } from './TaskRootNode';

afterEach(cleanup);

function plan(over: Partial<PlanCardDto> = {}): PlanCardDto {
  return {
    planStageId: 'p1', state: 'awaiting', status: 'finalized', source: 'brain',
    goal: 'Add one canonical parse() and route all sites through it',
    understandingSummary: 'eight copies disagree on validation', intentSummary: 'fullName already renders the string',
    steps: [
      { ordinal: 1, action: 'Check out the PR branch', detail: 'Work from the reviewed branch.', files: ['domain/PullRequestRef.java', 'domain/PullRequestRefTest.java'], risk: 'low' },
      { ordinal: 2, action: 'Add parse(String ref) to PullRequestRef, route all sites through it', risk: 'med' },
      { ordinal: 3, action: 'Run mvn verify' },
      { ordinal: 4, action: 'Commit and push, watch CI' },
    ],
    outOfScope: ['Changing pull-request display names'],
    validationStrategy: 'Round-trip parse(fullName()) + mvn verify',
    pushStrategy: 'await_approval',
    signals: { riskLevel: 'low', estimatedComplexity: 'medium', componentsCount: 5, expectedGain: 'High', confidence: 'high' },
    revisionCount: 1, followups: [],
    ...over,
  };
}

function noop() { /* not under test */ }

describe('PlanCard (pipeline adapter)', () => {
  it('renders the goal and authored steps in ordinal order without inferred phases', () => {
    const { container } = render(<PlanCard plan={plan({ steps: [
      { ordinal: 3, action: 'Run mvn verify' },
      { ordinal: 1, action: 'Check out the PR branch' },
      { ordinal: 2, action: 'Implement the parser' },
    ] })} onApprove={noop} />);
    expect(screen.getByText(/one canonical parse/)).toBeTruthy();
    expect(Array.from(container.querySelectorAll('.ppc-step-title')).map(node => node.textContent)).toEqual([
      'Check out the PR branch', 'Implement the parser', 'Run mvn verify',
    ]);
    expect(screen.queryByText('Prepare')).toBeNull();
    expect(screen.queryByText(/Ensure the main branch is synced/)).toBeNull();
  });

  it('maps rev + effort onto the header pills', () => {
    render(<PlanCard plan={plan()} onApprove={noop} />);
    expect(screen.getByText('REV 1')).toBeTruthy();
    expect(screen.getByText('Medium effort')).toBeTruthy(); // estimatedComplexity=medium
  });

  it('decomposes a policy change into the task-specific handlers', () => {
    const onSetMinApprovals = vi.fn();
    const onToggleAutoApprove = vi.fn();
    render(
      <PlanCard
        plan={plan()} onApprove={noop}
        minApprovals={0} onSetMinApprovals={onSetMinApprovals}
        autoApprove={false} onToggleAutoApprove={onToggleAutoApprove}
      />,
    );
    fireEvent.click(screen.getByText('2', { selector: '.ppc-seg-cell' }));
    expect(onSetMinApprovals).toHaveBeenCalledWith(2);
    fireEvent.click(screen.getAllByText('On', { selector: '.ppc-seg-cell' })[0]);
    expect(onToggleAutoApprove).toHaveBeenCalledOnce();
  });

  it('approves and sends typed revision feedback', () => {
    const onApprove = vi.fn();
    const onRequestRevision = vi.fn();
    render(<PlanCard plan={plan()} onApprove={onApprove} onRequestRevision={onRequestRevision} />);
    fireEvent.click(screen.getByText('Approve & start dev'));
    expect(onApprove).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByText('Request revision'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'keep step 2, redo step 3' } });
    fireEvent.click(screen.getByText('Send revision request'));
    expect(onRequestRevision).toHaveBeenCalledWith('keep step 2, redo step 3');
  });

  it('preserves all step metadata and plan scope from the structured card', () => {
    render(<PlanCard plan={plan()} onApprove={noop} />);
    fireEvent.click(screen.getByRole('button', { name: /Expand step 1/ }));
    expect(screen.getByText('Work from the reviewed branch.')).toBeTruthy();
    expect(screen.getByText('domain/PullRequestRef.java')).toBeTruthy();
    expect(screen.getByText('domain/PullRequestRefTest.java')).toBeTruthy();
    expect(screen.getByText('Changing pull-request display names')).toBeTruthy();
    expect(screen.getByText('Round-trip parse(fullName()) + mvn verify')).toBeTruthy();
    expect(screen.getByText('Wait for approval before pushing.')).toBeTruthy();
  });

  it('a locked plan shows the approved footer, not the action row', () => {
    render(<PlanCard plan={plan({ state: 'locked' })} onApprove={noop} onRequestRevision={noop} />);
    expect(screen.queryByText('Approve & start dev')).toBeNull();
    expect(screen.getByText(/development under way/)).toBeTruthy();
  });

  it('a draft plan (not yet finalized) reads "Plan drafting", not "Plan ready"', () => {
    // The caller only wires onApprove once plan.state === 'awaiting' (draft
    // stays disabled), so the card must say so instead of claiming "ready".
    render(<PlanCard plan={plan({ state: 'draft', status: 'suggested' })} onRequestRevision={noop} />);
    expect(screen.getByText('Plan drafting')).toBeTruthy();
    expect(screen.queryByText('Plan ready')).toBeNull();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
  });

  it('maps a finalized plan awaiting mandatory self-review to the reviewing state', () => {
    render(<PlanCard plan={plan({ state: 'draft', status: 'finalized' })} onRequestRevision={noop} />);
    expect(screen.getByText('Brain reviewing plan')).toBeTruthy();
    expect(screen.queryByText('Plan drafting')).toBeNull();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);
  });

  it('keeps a changes-requested plan blocked with revision available', () => {
    const onRequestRevision = vi.fn();
    render(<PlanCard
      plan={plan({ state: 'revision_required', status: 'finalized' })}
      onRequestRevision={onRequestRevision}
    />);
    expect(screen.getByText('Plan needs revision')).toBeTruthy();
    expect(screen.getByText(/Brain requested changes to this plan/)).toBeTruthy();
    const approveButton = screen.getByText('Approve & start dev').closest('button') as HTMLButtonElement;
    expect(approveButton.disabled).toBe(true);

    fireEvent.click(screen.getByText('Request revision'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Address the review concerns' } });
    fireEvent.click(screen.getByText('Send revision request'));
    expect(onRequestRevision).toHaveBeenCalledWith('Address the review concerns');
  });

});
