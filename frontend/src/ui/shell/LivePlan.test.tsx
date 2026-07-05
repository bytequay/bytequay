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
    reviewRoundId: null, stageId: `${kind}-stage`, status: 'running', iterations: 1, budget: null,
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
    liveRuns: [run('ci_fix', { id: 'local-fix', iterations: 2 })],
    devPhases: [
      { key: 'implementing', status: 'done', meta: null, badgeRunId: null },
      { key: 'validation', status: 'running', meta: null, badgeRunId: 'local-fix' },
      { key: 'brainReview', status: 'future', meta: 'next', badgeRunId: null },
    ],
    task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false },
    viewedStageId,
  });
}

describe('LivePlan', () => {
  it('renders every lifecycle node with its status class, plus the nested phase ladder', () => {
    const { container } = render(<LivePlan nodes={model()} />);
    // The first node is Plan (the brain/root conversation). A closed
    // PlanStage reads as 'done'.
    expect(screen.getByText('Plan').closest('.plan-node')?.className).toContain('done');
    expect(screen.getByText('Validation').closest('.plan-phase-row')?.className).toContain('running');
    expect(screen.getByText('Comments').closest('.plan-node')?.className).toContain('future');
    // A live run badges its phase row inline.
    expect(screen.getByText('iter 2')).toBeTruthy();
    // Review (callable, not-invoked, Development still open) renders as a
    // sub-row; the phase ladder renders as its own nested rows.
    expect(container.querySelectorAll('.plan-sub-row').length).toBe(1);
    expect(container.querySelectorAll('.plan-phase-row').length).toBe(3);
  });

  it('navigates to a stage when its node is clicked', () => {
    const onOpenStage = vi.fn();
    render(<LivePlan nodes={model()} onOpenStage={onOpenStage} />);
    fireEvent.click(screen.getByText('Development'));
    expect(onOpenStage).toHaveBeenCalledWith('DEVELOPMENT_STAGE-id');
  });

  it('navigates to the brain page when the Plan node is clicked', () => {
    const onOpenBrain = vi.fn();
    render(<LivePlan nodes={model()} onOpenBrain={onOpenBrain} />);
    fireEvent.click(screen.getByText('Plan'));
    expect(onOpenBrain).toHaveBeenCalledOnce();
  });

  it('force-opens the PR tab for a gate node click', () => {
    const onOpenTab = vi.fn();
    render(<LivePlan nodes={model()} onOpenTab={onOpenTab} />);
    fireEvent.click(screen.getByText('Local review'));
    expect(onOpenTab).toHaveBeenCalledWith('pr');
  });

  it('disables future nodes that have nowhere to navigate', () => {
    render(<LivePlan nodes={model()} />);
    expect((screen.getByText('Cleanup').closest('button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('highlights the currently-viewed stage', () => {
    render(<LivePlan nodes={model('DEVELOPMENT_STAGE-id')} />);
    expect(screen.getByText('Development').closest('.plan-node')?.className).toContain('active-view');
  });

  it('renders the guard chip once a guard row exists, hides it before the task has ever pushed', () => {
    const { rerender } = render(
      <LivePlan nodes={model()} guard={buildGuardChip({
        taskId: 't', enabled: true, schedule: 'nightly', state: 'drifting',
        health: { behindBy: 3, mergeable: true, checksGreen: true },
        lastRunId: null, lastCheckedAt: null,
      })}
      />,
    );
    expect(screen.getByText('drifting from main')).toBeTruthy();

    rerender(<LivePlan nodes={model()} guard={buildGuardChip(null)} />);
    expect(screen.queryByText('drifting from main')).toBeNull();
  });

  it('shows the bold "Guard" label and the last-checked meta inline (plan-spine-options.html), not just in a tooltip', () => {
    render(
      <LivePlan nodes={model()} guard={buildGuardChip({
        taskId: 't', enabled: true, schedule: 'nightly', state: 'healthy',
        health: { behindBy: 0, mergeable: true, checksGreen: true },
        lastRunId: null, lastCheckedAt: '2026-07-05T00:00:00Z',
      })}
      />,
    );
    expect(screen.getByText('Guard')).toBeTruthy();
    expect(screen.getByText(new Date('2026-07-05T00:00:00Z').toLocaleTimeString())).toBeTruthy();
  });

  it('shows a disabled guard as "off" with a toggle, and fires onToggleGuard when flipped', () => {
    const onToggleGuard = vi.fn();
    render(
      <LivePlan
        nodes={model()}
        guard={buildGuardChip({
          taskId: 't', enabled: false, schedule: 'nightly', state: 'healthy',
          health: { behindBy: 0, mergeable: true, checksGreen: true },
          lastRunId: null, lastCheckedAt: null,
        })}
        onToggleGuard={onToggleGuard}
      />,
    );
    expect(screen.getByText('guard off')).toBeTruthy();
    const toggle = screen.getByRole('switch', { name: 'Enable branch guard' });
    expect(toggle.getAttribute('aria-checked')).toBe('false');

    fireEvent.click(toggle);
    expect(onToggleGuard).toHaveBeenCalledWith(true);
  });
});
