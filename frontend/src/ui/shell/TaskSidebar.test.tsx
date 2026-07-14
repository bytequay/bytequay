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
import { TaskSidebar } from './TaskSidebar';
import { buildLivePlan } from './livePlanModel';
import type { TaskPhase } from '../../types/brainView';

afterEach(cleanup);

const nodes = buildLivePlan({
  stages: [], subStages: [],
  task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
});

describe('TaskSidebar', () => {
  it('renders the task identity and the live-plan diagram', () => {
    render(
      <TaskSidebar
        task={{ title: 'Add cost-meter card', branch: 'feat/cost-meter' }}
        nodes={nodes}
      />,
    );
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('feat/cost-meter')).toBeTruthy();
    expect(screen.getByText('Live plan')).toBeTruthy();
    expect(screen.getByText('Plan')).toBeTruthy();
    expect(screen.getByText('Remote Development')).toBeTruthy();
    expect(screen.getByText('Cleanup')).toBeTruthy();
  });

  it('shows the done-count over the plan leaves and the footer user', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
        user="chenjian2664"
      />,
    );
    expect(screen.getByText(/^\d+ of \d+ done$/)).toBeTruthy();
    expect(screen.getByText('chenjian2664')).toBeTruthy();
  });

  it('fires onBack from the traffic-lights back arrow', () => {
    const onBack = vi.fn();
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
        onBack={onBack}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onBack).toHaveBeenCalledOnce();
  });

  it('renders the traffic-lights window chrome unconditionally', () => {
    render(<TaskSidebar task={{ title: 'x', branch: 'b' }} nodes={nodes} />);
    expect(screen.getByRole('button', { name: 'Close window' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Toggle sidebar' })).toBeTruthy();
  });

  it('routes a live-plan node click through to the stage handler', () => {
    const onOpenStage = vi.fn();
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={buildLivePlan({
          stages: [{
            id: 'dev-1', taskId: 't', type: 'DEVELOPMENT_STAGE', state: 'ACTIVE',
            openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
            summary: '', loopIteration: 0,
          }],
          subStages: [],
          task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false },
        })}
        onOpenStage={onOpenStage}
      />,
    );
    fireEvent.click(screen.getByText('Local Development'));
    expect(onOpenStage).toHaveBeenCalledWith('dev-1');
  });

  it('renders AgentReview as a sibling track and opens its round', () => {
    const onOpenRound = vi.fn();
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
        agentReview={{
          status: 'questions',
          rounds: [{ id: 'round-1', status: 'questions', findings: 2 }],
          onOpenRound,
        }}
      />,
    );
    expect(screen.getByText('Agent review')).toBeTruthy();
    expect(screen.getByText('Review and verify fixes')).toBeTruthy();
    fireEvent.click(screen.getByText('Round 1'));
    expect(onOpenRound).toHaveBeenCalledWith('round-1');
  });
});
