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
import type { BrainFeedRow, PlanCardDto } from '../../types/brainView';
import { PlanCard, planStepComments, TaskRootNode } from './TaskRootNode';

afterEach(cleanup);

function plan(over: Partial<PlanCardDto> = {}): PlanCardDto {
  return {
    planStageId: 'p1', state: 'awaiting', status: 'finalized', source: 'brain',
    goal: 'Add one canonical parse() and route all sites through it',
    understandingSummary: 'eight copies disagree on validation', intentSummary: 'fullName already renders the string',
    steps: [
      { ordinal: 1, action: 'Check out the PR branch', files: ['domain/PullRequestRef.java'] },
      { ordinal: 2, action: 'Add parse(String ref) to PullRequestRef, route all sites through it' },
      { ordinal: 3, action: 'Run mvn verify' },
      { ordinal: 4, action: 'Commit and push, watch CI' },
    ],
    validationStrategy: 'Round-trip parse(fullName()) + mvn verify',
    pushStrategy: 'await_approval',
    signals: { riskLevel: 'low', estimatedComplexity: 'medium', componentsCount: 5, expectedGain: 'High', confidence: 'high' },
    revisionCount: 1, followups: [],
    ...over,
  };
}

function noop() { /* not under test */ }

describe('PlanCard (pipeline adapter)', () => {
  it('renders goal, the four phases, and buckets steps by keyword', () => {
    render(<PlanCard plan={plan()} onApprove={noop} />);
    expect(screen.getByText(/one canonical parse/)).toBeTruthy();
    for (const name of ['Prepare', 'Implement', 'Verify', 'Ship & monitor']) {
      expect(screen.getByText(name)).toBeTruthy();
    }
    // "Check out the PR branch" → Prepare; "Run mvn verify" → Verify;
    // "Commit and push, watch CI" → Ship; the parse step → Implement.
    expect(screen.getAllByText('1 step').length).toBe(4);
    // First file of a step becomes its mono code chip.
    expect(screen.getByText('domain/PullRequestRef.java')).toBeTruthy();
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

  it('seeds an empty Prepare phase with the default main-branch-sync step', () => {
    render(
      <PlanCard
        plan={plan({ steps: [
          { ordinal: 1, action: 'Rewrite the payloads to use ObjectMapper' }, // → implement
          { ordinal: 2, action: 'Run mvn verify and push if green' }, // → verify (not ship)
        ] })}
        onApprove={noop}
      />,
    );
    expect(screen.getByText(/Ensure the main branch is synced/)).toBeTruthy();
    expect(screen.getByText('Run mvn verify and push if green')).toBeTruthy();
    // Verify won the mvn-vs-push tie, so it is not the skipped column.
    expect(screen.getAllByText('Skipped').length).toBe(1); // only Ship & monitor
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

  it('does not surface per-step comment affordances (dropped in the pipeline design)', () => {
    const feed: BrainFeedRow[] = [{
      id: 'comment-1', messageSeq: 4, type: 'USER_MESSAGE', stageId: null, stageType: null,
      ts: '2026-01-01T00:00:00Z', body: 'Re: step 2 — Why leave it?', referencedStageId: null,
      images: [], managedSkills: [],
    }];
    render(<PlanCard plan={plan()} onApprove={noop} stepComments={planStepComments(feed)} onCommentStep={noop} />);
    expect(screen.queryByText('Comment on this step')).toBeNull();
  });
});

describe('TaskRootNode', () => {
  it('renders the planning seed chips above the plan card and expands on click', () => {
    const seed = 'Refactor: collapse the parsers. Gate before committing: mvn verify. Do not push.';
    const { container } = render(<TaskRootNode plan={plan()} seed={seed} onApprove={noop} />);
    expect(container.querySelector('.seed-chip')).toBeTruthy();
    expect(container.querySelector('.seed__full')).toBeNull();
    expect(container.querySelector('.plan-pipeline-card')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Planning seed/ }));
    expect(container.querySelector('.seed__full')?.textContent).toContain('collapse the parsers');
  });
});
