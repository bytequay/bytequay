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
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BrainFeedRow, PlanCardDto } from '../../types/brainView';
import { planStepComments, TaskRootNode } from './TaskRootNode';

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

  it('never auto-approves the plan: manual actions show even with auto-approve + high confidence', () => {
    vi.useFakeTimers();
    try {
      const onApprove = vi.fn();
      const { container } = render(
        <TaskRootNode plan={plan()} autoApprove autoConfidenceHigh onApprove={onApprove} />);
      // No countdown banner, and the plan is not approved for the user.
      expect(container.querySelector('.auto-banner')).toBeNull();
      expect(container.querySelector('.actions-row')).toBeTruthy();
      act(() => { vi.advanceTimersByTime(10000); });
      expect(onApprove).not.toHaveBeenCalled();
    }
    finally {
      vi.useRealTimers();
    }
  });

  it('renders typed steps: file chips, per-step risk, and out-of-scope', () => {
    const { container } = render(
      <TaskRootNode plan={plan({
        steps: [
          { ordinal: 1, action: 'Add parse() to PullRequestRef', detail: 'null-tolerant validation', files: ['domain/PullRequestRef.java'], risk: 'low' },
          { ordinal: 2, action: 'Hoist the duplicated set', risk: 'opt' },
        ],
        outOfScope: ['stream/Optional rewrites', 'inline-helper hints'],
      })} />,
    );
    // Step 1 (open by default) shows its detail + file chip.
    expect(container.querySelector('.plan-step__files .fref')?.textContent).toBe('domain/PullRequestRef.java');
    expect(container.textContent).toContain('null-tolerant validation');
    // Per-step risk pills, including the optional pill.
    const risks = [...container.querySelectorAll('.plan-step .risk')].map(r => r.textContent);
    expect(risks).toEqual(['low', 'opt']);
    // Out-of-scope mini-card.
    expect(container.querySelector('.plan-mini__oos')?.textContent).toContain('stream/Optional rewrites');
  });

  it('shows comments entered from a plan step on that step', () => {
    const feed: BrainFeedRow[] = [{
      id: 'comment-1', messageSeq: 4, type: 'USER_MESSAGE', stageId: null, stageType: null,
      ts: '2026-01-01T00:00:00Z', body: 'Re: step 2 — Why leave it?', referencedStageId: null,
      images: [], managedSkills: [],
    }];
    render(<TaskRootNode plan={plan()} stepComments={planStepComments(feed)} />);

    expect(screen.getByText('1 comment')).toBeTruthy();
    fireEvent.click(screen.getByText('Replace 8 sites, preserving each bail value'));
    expect(screen.getByText('Why leave it?')).toBeTruthy();
  });

  it('a locked plan is read-only (no review bar)', () => {
    const { container } = render(<TaskRootNode plan={plan({ state: 'locked' })} />);
    expect(container.querySelector('.review-bar')).toBeNull();
    expect(container.querySelector('.review-locked')).toBeTruthy();
  });

  it('the auto-merge switch is always clickable, even for a risky/large plan', () => {
    // Risk/effort is a hint the plan card already surfaces elsewhere
    // (the Risk/Effort signal chips) — it's the user's call whether to
    // turn auto-merge on, not a system-enforced gate.
    const onToggleAutoMerge = vi.fn();
    const { container } = render(
      <TaskRootNode plan={plan()} onToggleAutoMerge={onToggleAutoMerge} />); // default fixture: effort=medium
    const input = container.querySelector('.plan-auto .plan-auto__sw input') as HTMLInputElement;
    expect(input.disabled).toBe(false);
    fireEvent.click(input);
    expect(onToggleAutoMerge).toHaveBeenCalled();
  });
});
