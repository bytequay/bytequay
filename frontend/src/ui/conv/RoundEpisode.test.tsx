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
import { RoundEpisode } from './RoundEpisode';
import type { AgentRunDto, ReviewRoundDto } from '../../types/brainView';

afterEach(cleanup);

function round(over: Partial<ReviewRoundDto> = {}): ReviewRoundDto {
  return {
    id: 'round-1', taskId: 't', idx: 3, reviewers: ['@alice'], status: 'addressing',
    stats: { fixed: 9, replied: 2, pushedBack: 1, open: 5 }, runId: 'run-round-1',
    openedAt: '2026-01-01T00:00:00Z', gatedAt: null, postedAt: null,
    origin: 'external', brainVerdict: null, iteration: 0, budget: 3, ...over,
  };
}

function ciFixRun(over: Partial<AgentRunDto> = {}): AgentRunDto {
  return {
    id: 'run-ci', taskId: 't', kind: 'ci_fix', source: 'remote', parentStageId: null,
    reviewRoundId: 'round-1', stageId: 'stage-ci', status: 'running', iterations: 1, budget: null,
    headline: null, startedAt: '2026-01-01T00:00:00Z', finishedAt: null, ...over,
  };
}

describe('RoundEpisode', () => {
  it('folds a posted round to a single done summary row, no nested body', () => {
    const { container } = render(<RoundEpisode round={round({ status: 'posted' })} />);
    expect(screen.getByText('Round 3')).toBeTruthy();
    expect(screen.getByText(/9 fixed · 2 replied · 1 pushed back/)).toBeTruthy();
    expect(container.querySelector('.round-episode__body')).toBeNull();
  });

  it('expands a live round with its nested run', () => {
    render(<RoundEpisode round={round()} nestedRun={ciFixRun()} />);
    expect(screen.getByText('CI fix run · remote')).toBeTruthy();
  });

  it('renders a paused round as needs attention without a live body', () => {
    const { container } = render(
      <RoundEpisode round={round({ origin: 'brain', status: 'paused' })} nestedRun={ciFixRun()} />,
    );
    expect(screen.getByText('needs attention')).toBeTruthy();
    expect(container.querySelector('.round-episode')?.classList.contains('live')).toBe(false);
    expect(container.querySelector('.round-episode__body')).toBeNull();
  });

  it('shows the gate bar and fires onApprove for an awaiting_gate round', () => {
    const onApprove = vi.fn();
    render(<RoundEpisode round={round({ status: 'awaiting_gate' })} onApprove={onApprove} />);
    fireEvent.click(screen.getByText('Approve & post'));
    expect(onApprove).toHaveBeenCalledWith('round-1');
  });

  it('opens the round\'s own run on header click', () => {
    const onOpenRun = vi.fn();
    render(<RoundEpisode round={round()} onOpenRun={onOpenRun} />);
    fireEvent.click(screen.getByText('Round 3'));
    expect(onOpenRun).toHaveBeenCalledWith('run-round-1');
  });

  it('renders a brain-origin round as "Brain review" with a BRAIN badge and no verdict yet', () => {
    render(<RoundEpisode round={round({ origin: 'brain', iteration: 1, budget: 3 })} />);
    expect(screen.getByText('Brain review')).toBeTruthy();
    expect(screen.getByText('BRAIN')).toBeTruthy();
    expect(screen.getByText('iter 1/3')).toBeTruthy();
    expect(screen.queryByText('APPROVED')).toBeNull();
    expect(screen.queryByText('CHANGES REQUESTED')).toBeNull();
  });

  it('shows the brain\'s verdict pill once it has reviewed', () => {
    const { rerender } = render(
      <RoundEpisode round={round({ origin: 'brain', brainVerdict: 'changes_requested' })} />,
    );
    expect(screen.getByText('CHANGES REQUESTED')).toBeTruthy();

    rerender(<RoundEpisode round={round({ origin: 'brain', status: 'closed', brainVerdict: 'approved' })} />);
    expect(screen.getByText('APPROVED')).toBeTruthy();
  });
});
