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
  task: { prNumber: 145, currentPhase: 'CI_FIXING' as TaskPhase, terminal: false },
});

describe('TaskSidebar', () => {
  it('renders the task identity and the live-plan diagram', () => {
    render(
      <TaskSidebar
        task={{ taskNumber: 4, title: 'Add cost-meter card', branch: 'feat/cost-meter' }}
        nodes={nodes}
      />,
    );
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.getByText('feat/cost-meter')).toBeTruthy();
    expect(screen.getByText(/TASK #4/)).toBeTruthy();
    expect(screen.getByText('Live plan')).toBeTruthy();
    // The live plan leads with the Root node (brain/root conversation),
    // not a "Plan" stage.
    expect(screen.getByText('Root')).toBeTruthy();
    expect(screen.getByText('Cleanup')).toBeTruthy();
  });

  it('fires onBack from the back-to-thread button', () => {
    const onBack = vi.fn();
    render(
      <TaskSidebar
        task={{ taskNumber: 4, title: 'x', branch: 'b' }}
        nodes={nodes}
        onBack={onBack}
        threadLabel="Backend cleanup review"
      />,
    );
    fireEvent.click(screen.getByText('Backend cleanup review'));
    expect(onBack).toHaveBeenCalledOnce();
  });

  it('routes a live-plan node click through to the stage handler', () => {
    const onOpenStage = vi.fn();
    render(
      <TaskSidebar
        task={{ taskNumber: 4, title: 'x', branch: 'b' }}
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
    fireEvent.click(screen.getByText('Development'));
    expect(onOpenStage).toHaveBeenCalledWith('dev-1');
  });
});
