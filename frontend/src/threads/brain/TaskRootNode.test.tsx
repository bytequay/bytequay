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
import { TaskRootNode } from './TaskRootNode';

afterEach(cleanup);

function plan(over: Partial<PlanCardDto> = {}): PlanCardDto {
  return {
    planStageId: 'p1', state: 'awaiting', status: 'finalized', source: 'brain',
    goal: 'Add one canonical parse() and route all sites through it',
    understandingSummary: 'eight copies disagree on validation', intentSummary: 'fullName already renders the string',
    steps: [
      { ordinal: 1, action: 'Add parse(String ref) to PullRequestRef: null-tolerant validation.' },
      { ordinal: 2, action: 'Replace 8 sites, preserving each bail value.' },
    ],
    validationStrategy: 'Round-trip parse(fullName()) + mvn verify',
    pushStrategy: 'await_approval',
    signals: { riskLevel: 'low', estimatedComplexity: 'medium', componentsCount: 5, expectedGain: 'High', confidence: 'high' },
    revisionCount: 1, followups: [],
    ...over,
  };
}

describe('TaskRootNode', () => {
  it('renders the typed plan: goal, steps, signals, confidence', () => {
    const { container } = render(<TaskRootNode plan={plan()} />);
    expect(screen.getByText(/route all sites through it/)).toBeTruthy();
    expect(container.querySelectorAll('.plan-step').length).toBe(2);
    expect(container.querySelector('.plan-card__rev')?.textContent).toBe('rev 1');
    expect(container.querySelector('.plan-conf--high')).toBeTruthy();
    // Risk/Effort/Value signals row.
    expect(container.querySelectorAll('.plan-sig').length).toBe(3);
  });

  it('shows the review bar with scope guard + trigger note + actions', () => {
    const onApprove = vi.fn();
    const onRequestRevision = vi.fn();
    const { container } = render(<TaskRootNode plan={plan()} onApprove={onApprove} onRequestRevision={onRequestRevision} />);
    expect(container.querySelector('.scope-guard')?.textContent).toContain('2 steps in scope');
    expect(container.querySelector('.trigger-note')?.textContent).toContain('Development → Review → Push');
    fireEvent.click(screen.getByText(/Approve & start dev/));
    expect(onApprove).toHaveBeenCalled();
    fireEvent.click(screen.getByText('Request revision'));
    expect(onRequestRevision).toHaveBeenCalled();
  });

  it('extracts seed chips and reveals the raw markdown on expand', () => {
    const seed = 'Refactor: collapse the parsers. Gate before committing: mvn verify. Do not push.';
    const { container } = render(<TaskRootNode plan={plan()} seed={seed} />);
    expect(container.querySelector('.seed-chip')).toBeTruthy();
    expect(container.querySelector('.seed__full')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Planning seed/ }));
    expect(container.querySelector('.seed__full')?.textContent).toContain('collapse the parsers');
  });

  it('swaps the review bar for an auto-state banner when auto-approve + high confidence', () => {
    const { container } = render(<TaskRootNode plan={plan()} autoApprove autoConfidenceHigh onApprove={() => {}} />);
    expect(container.querySelector('.auto-banner')).toBeTruthy();
    expect(container.querySelector('.actions-row')).toBeNull();
  });

  it('a low-confidence plan still shows the manual actions despite auto-approve', () => {
    const { container } = render(
      <TaskRootNode plan={plan({ signals: { riskLevel: 'high', estimatedComplexity: 'large', componentsCount: 9, expectedGain: 'High', confidence: 'low' } })}
        autoApprove autoConfidenceHigh={false} onApprove={() => {}} />,
    );
    expect(container.querySelector('.auto-banner')).toBeNull();
    expect(container.querySelector('.actions-row')).toBeTruthy();
  });

  it('a locked plan is read-only (no review bar)', () => {
    const { container } = render(<TaskRootNode plan={plan({ state: 'locked' })} />);
    expect(container.querySelector('.review-bar')).toBeNull();
    expect(container.querySelector('.review-locked')).toBeTruthy();
  });
});
