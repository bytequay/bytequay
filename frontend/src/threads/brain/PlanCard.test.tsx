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
import { PlanCard } from './PlanCard';

afterEach(cleanup);

function plan(over: Partial<PlanCardDto> = {}): PlanCardDto {
  return {
    planStageId: 'plan-1',
    state: 'awaiting',
    status: 'finalized',
    source: 'brain',
    understandingSummary: 'Bump the retry default in RetryConfig',
    intentSummary: 'Change the default and add a regression test',
    steps: [{ ordinal: 1, action: 'edit RetryConfig' }, { ordinal: 2, action: 'add test' }],
    validationStrategy: 'unit tests',
    pushStrategy: 'await_approval',
    signals: { riskLevel: 'low', estimatedComplexity: 'small', componentsCount: 2, expectedGain: 'fewer flakes' },
    revisionCount: 2,
    followups: [],
    ...over,
  };
}

const noop = () => {};

describe('PlanCard', () => {
  it('awaiting state shows the structure, revision chain, and approve/request actions', () => {
    const onApprove = vi.fn();
    const onRequestChanges = vi.fn();
    render(
      <PlanCard plan={plan()} onApprove={onApprove} onRequestChanges={onRequestChanges}
        onResolveFollowup={noop} />,
    );
    expect(screen.getByText('AWAITING APPROVAL')).toBeTruthy();
    expect(screen.getByText('· rev 2')).toBeTruthy();
    expect(screen.getByText('edit RetryConfig')).toBeTruthy();
    expect(screen.getByText('risk: low')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Approve & start development/ }));
    expect(onApprove).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Request changes' }));
    expect(onRequestChanges).toHaveBeenCalledTimes(1);
  });

  it('draft state hides the step list and the approve action', () => {
    render(
      <PlanCard plan={plan({ state: 'draft' })} onApprove={noop} onRequestChanges={noop}
        onResolveFollowup={noop} />,
    );
    expect(screen.getByText('PLAN DRAFT')).toBeTruthy();
    expect(screen.queryByText('edit RetryConfig')).toBeNull();
    expect(screen.queryByRole('button', { name: /Approve/ })).toBeNull();
  });

  it('locked state shows the immutability note and resolves follow-up notes', () => {
    const onResolveFollowup = vi.fn();
    const locked = plan({
      state: 'locked',
      followups: [{
        eventId: 'fu-1', note: 'the retry default is still wrong', sourceAgent: 'dev',
        createdAt: '2026-06-21T10:00:00Z', status: 'open',
      }],
    });
    render(
      <PlanCard plan={locked} onApprove={noop} onRequestChanges={noop}
        onResolveFollowup={onResolveFollowup} />,
    );
    expect(screen.getByText('APPROVED & LOCKED')).toBeTruthy();
    expect(screen.getByText(/Approved plans are immutable/)).toBeTruthy();
    expect(screen.getByText('the retry default is still wrong')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Mark addressed' }));
    expect(onResolveFollowup).toHaveBeenCalledWith('fu-1', 'addressed');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(onResolveFollowup).toHaveBeenCalledWith('fu-1', 'dismissed');
  });

  it('surfaces a planning failure in an alert banner', () => {
    render(
      <PlanCard plan={plan({ state: 'draft', error: 'claude-code exited with code 1' })}
        onApprove={noop} onRequestChanges={noop} onResolveFollowup={noop} />,
    );
    expect(screen.getByRole('alert').textContent).toContain('claude-code exited with code 1');
    expect(screen.getByText(/Planning didn't complete/)).toBeTruthy();
  });

  it('addressed/dismissed follow-ups are not shown', () => {
    const locked = plan({
      state: 'locked',
      followups: [{
        eventId: 'fu-1', note: 'already handled', sourceAgent: 'dev',
        createdAt: '2026-06-21T10:00:00Z', status: 'addressed',
      }],
    });
    render(
      <PlanCard plan={locked} onApprove={noop} onRequestChanges={noop} onResolveFollowup={noop} />,
    );
    expect(screen.queryByText('already handled')).toBeNull();
  });
});
