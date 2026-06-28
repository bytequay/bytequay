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
import { LivePlan } from './LivePlan';
import { buildLivePlan } from './livePlanModel';
import type { StageDto, StageState, StageType, TaskPhase } from '../../types/brainView';

afterEach(cleanup);

function stage(type: StageType, state: StageState, over: Partial<StageDto> = {}): StageDto {
  return {
    id: `${type}-id`, taskId: 't', type, state,
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0, ...over,
  };
}

function model(viewedStageId?: string) {
  return buildLivePlan({
    stages: [
      stage('PLAN_STAGE', 'CLOSED'),
      stage('DEVELOPMENT_STAGE', 'CLOSED'),
      stage('CI_FIXING_STAGE', 'ACTIVE', { loopIteration: 2 }),
    ],
    subStages: [],
    task: { prNumber: 145, currentPhase: 'CI_FIXING' as TaskPhase, terminal: false },
    viewedStageId,
  });
}

describe('LivePlan', () => {
  it('renders every lifecycle node with its status class', () => {
    const { container } = render(<LivePlan nodes={model()} />);
    expect(screen.getByText('Plan').closest('.plan-node')?.className).toContain('done');
    expect(screen.getByText('CI Fix').closest('.plan-node')?.className).toContain('running');
    expect(screen.getByText('Comments').closest('.plan-node')?.className).toContain('future');
    // Push milestone shows the PR number.
    expect(screen.getByText('PR #145')).toBeTruthy();
    // The split renders both branches inside one container.
    expect(container.querySelector('.plan-split .split-row')?.children.length).toBe(2);
  });

  it('navigates to a stage when its node is clicked', () => {
    const onOpenStage = vi.fn();
    render(<LivePlan nodes={model()} onOpenStage={onOpenStage} />);
    fireEvent.click(screen.getByText('CI Fix'));
    expect(onOpenStage).toHaveBeenCalledWith('CI_FIXING_STAGE-id');
  });

  it('routes the Push sub-node to the Development conversation', () => {
    const onOpenStage = vi.fn();
    render(<LivePlan nodes={model()} onOpenStage={onOpenStage} />);
    fireEvent.click(screen.getByText('Push'));
    expect(onOpenStage).toHaveBeenCalledWith('DEVELOPMENT_STAGE-id');
  });

  it('disables future nodes that have nowhere to navigate', () => {
    render(<LivePlan nodes={model()} />);
    expect((screen.getByText('Cleanup').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('highlights the currently-viewed stage', () => {
    render(<LivePlan nodes={model('CI_FIXING_STAGE-id')} />);
    expect(screen.getByText('CI Fix').closest('.plan-node')?.className).toContain('active-view');
  });
});
