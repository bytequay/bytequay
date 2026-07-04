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
import { buildGuardChip, buildLivePlan } from './livePlanModel';
import type {
  AgentRunDto, AgentRunKind, StageDto, StageState, StageType, TaskPhase,
} from '../../types/brainView';

afterEach(cleanup);

function stage(type: StageType, state: StageState, over: Partial<StageDto> = {}): StageDto {
  return {
    id: `${type}-id`, taskId: 't', type, state,
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0, ...over,
  };
}

function run(kind: AgentRunKind, over: Partial<AgentRunDto> = {}): AgentRunDto {
  return {
    id: `${kind}-run`, taskId: 't', kind, source: 'remote', parentStageId: null,
    reviewRoundId: null, status: 'running', iterations: 1, budget: null,
    headline: null, startedAt: '2026-01-01T00:00:00Z', finishedAt: null, ...over,
  };
}

function model(viewedStageId?: string) {
  return buildLivePlan({
    stages: [
      stage('PLAN_STAGE', 'CLOSED'),
      stage('DEVELOPMENT_STAGE', 'OPEN'),
    ],
    subStages: [],
    liveRuns: [run('ci_fix', { iterations: 2 })],
    task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    viewedStageId,
  });
}

describe('LivePlan', () => {
  it('renders every lifecycle node with its status class, plus a live Checks sub-row', () => {
    const { container } = render(<LivePlan nodes={model()} />);
    // The first node is Root (the brain/root conversation) — Plan as a
    // drill-in stage is gone. A closed PlanStage reads as 'done'.
    expect(screen.getByText('Root').closest('.plan-node')?.className).toContain('done');
    expect(screen.getByText('Checks').closest('.plan-node')?.className).toContain('running');
    expect(screen.getByText('Comments').closest('.plan-node')?.className).toContain('future');
    // Remote Push milestone shows the PR number.
    expect(screen.getByText('PR #145')).toBeTruthy();
    // Review (callable, not-invoked, Development still open) + the live
    // Checks run both render as sub-rows.
    expect(container.querySelectorAll('.plan-sub-row').length).toBe(2);
  });

  it('navigates to a stage when its node is clicked', () => {
    const onOpenStage = vi.fn();
    render(<LivePlan nodes={model()} onOpenStage={onOpenStage} />);
    fireEvent.click(screen.getByText('Development'));
    expect(onOpenStage).toHaveBeenCalledWith('DEVELOPMENT_STAGE-id');
  });

  it('navigates to the brain page when the Root node is clicked', () => {
    const onOpenBrain = vi.fn();
    render(<LivePlan nodes={model()} onOpenBrain={onOpenBrain} />);
    fireEvent.click(screen.getByText('Root'));
    expect(onOpenBrain).toHaveBeenCalledOnce();
  });

  it('routes the Remote Push node to the Development conversation', () => {
    const onOpenStage = vi.fn();
    render(<LivePlan nodes={model()} onOpenStage={onOpenStage} />);
    fireEvent.click(screen.getByText('Remote Push'));
    expect(onOpenStage).toHaveBeenCalledWith('DEVELOPMENT_STAGE-id');
  });

  it('disables future nodes that have nowhere to navigate', () => {
    render(<LivePlan nodes={model()} />);
    expect((screen.getByText('Cleanup').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('highlights the currently-viewed stage', () => {
    render(<LivePlan nodes={model('DEVELOPMENT_STAGE-id')} />);
    expect(screen.getByText('Development').closest('.plan-node')?.className).toContain('active-view');
  });

  it('renders the guard chip when the guard is enabled, hides it otherwise', () => {
    const { rerender } = render(
      <LivePlan nodes={model()} guard={buildGuardChip({
        taskId: 't', enabled: true, schedule: 'nightly', state: 'drifting',
        lastRunId: null, lastCheckedAt: null,
      })}
      />,
    );
    expect(screen.getByText('drifting from main')).toBeTruthy();

    rerender(<LivePlan nodes={model()} guard={buildGuardChip(null)} />);
    expect(screen.queryByText('drifting from main')).toBeNull();
  });
});
