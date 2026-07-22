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

const activeDevelopmentNodes = buildLivePlan({
  stages: [{
    id: 'dev-1', taskId: 't', type: 'DEVELOPMENT_STAGE', state: 'ACTIVE',
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0,
  }],
  subStages: [],
  task: { prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, terminal: false },
});

describe('TaskSidebar', () => {
  it('renders task context and stages as flat navigation rows', () => {
    render(
      <TaskSidebar
        task={{ title: 'Add cost-meter card', branch: 'feat/cost-meter' }}
        nodes={nodes}
      />,
    );
    expect(screen.getByText('Add cost-meter card')).toBeTruthy();
    expect(screen.queryByText(/feat\/cost-meter/)).toBeNull();
    expect(screen.getByText('STAGES')).toBeTruthy();
    expect(screen.getByText('Plan')).toBeTruthy();
    expect(screen.getByText('Development')).toBeTruthy();
    expect(screen.getByText('Local review')).toBeTruthy();
    expect(screen.getByText('CI validation')).toBeTruthy();
    expect(screen.getByText('Comments')).toBeTruthy();
    expect(screen.getByText('Cleanup')).toBeTruthy();
  });

  it('shows Development phases while active and lets the user fold them', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={activeDevelopmentNodes}
      />,
    );
    expect(document.querySelector('.workspace-task-stage__plan')).not.toBeNull();
    expect(screen.getByText('Implementing')).toBeTruthy();
    expect(screen.getByText('Validation')).toBeTruthy();
    expect(screen.getByText('Brain review')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Collapse Development' }));
    expect(document.querySelector('.workspace-task-stage__plan')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Expand Development' }));
    expect(document.querySelector('.workspace-task-stage__plan')).not.toBeNull();
  });

  it('shows the done-count with the same bottom navigation as Home', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
      />,
    );
    expect(screen.getByText(/^\d+ of 8 done$/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Report a bug' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Settings' })).toBeTruthy();
    expect(screen.queryByText('chenjian2664')).toBeNull();
  });

  it('keeps canonical owner glyphs visible independently of status', () => {
    const { container } = render(<TaskSidebar task={{ title: 'x', branch: 'b' }} nodes={nodes} />);
    expect(Array.from(container.querySelectorAll('[data-node-type="stage"]')).map(node => node.textContent))
      .toEqual(['🤖', '🤖', '🤖']);
    expect(Array.from(container.querySelectorAll('[data-node-type="gate"]')).map(node => node.textContent))
      .toEqual(['◆', '◆']);
    expect(Array.from(container.querySelectorAll('[data-node-type="auto"]')).map(node => node.textContent))
      .toEqual(['○', '○', '○']);
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
    fireEvent.click(screen.getByText('Development'));
    expect(onOpenStage).toHaveBeenCalledWith('dev-1');
  });

  it('keeps Cleanup disabled while CI validation remains actionable', () => {
    const onOpenTab = vi.fn();
    const { container } = render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
        onOpenTab={onOpenTab}
      />,
    );
    fireEvent.click(screen.getByText('CI validation'));
    expect(onOpenTab).toHaveBeenCalledWith('pr', 'checks');
    const cleanupButton = screen.getByText('Cleanup').closest('button') as HTMLButtonElement;
    expect(cleanupButton.disabled).toBe(true);
    expect(cleanupButton.title).toBe('Runs automatically — no agent page');
    expect(cleanupButton.classList.contains('is-automatic')).toBe(true);
    expect(cleanupButton.querySelector(':scope > svg')).toBeNull();
    expect(container.querySelector('.workspace-task-sidebar-v2__trunk')).toBeTruthy();
  });

  it('uses real workspace/trunk identity and keeps history navigation distinct', () => {
    const onHistoryBack = vi.fn();
    const onOpenTrunk = vi.fn();
    const onNavigateGlobal = vi.fn();
    const onSwitchWorkspace = vi.fn();
    render(
      <TaskSidebar
        task={{
          title: 'x', branch: 'b', workspaceName: 'ByteQuay', repository: 'owner/bytequay',
        }}
        threadLabel="Clean code v2"
        nodes={nodes}
        onBack={onHistoryBack}
        onOpenTrunk={onOpenTrunk}
        onNavigateGlobal={onNavigateGlobal}
        onSwitchWorkspace={onSwitchWorkspace}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    fireEvent.click(screen.getByText('Clean code v2'));
    fireEvent.click(screen.getByRole('button', { name: 'Home' }));
    fireEvent.click(screen.getByRole('button', { name: 'Pull requests' }));
    fireEvent.click(screen.getByTitle('Open ByteQuay Today'));
    fireEvent.click(screen.getByRole('button', { name: /Notifications/ }));
    expect(onHistoryBack).toHaveBeenCalledOnce();
    expect(onOpenTrunk).toHaveBeenCalledOnce();
    expect(onNavigateGlobal).toHaveBeenCalledWith('home');
    expect(onNavigateGlobal).toHaveBeenCalledWith('pulls');
    expect(onSwitchWorkspace).toHaveBeenCalledOnce();
    expect(onNavigateGlobal).not.toHaveBeenCalledWith('notifications');
    expect(screen.getByText('owner/bytequay')).toBeTruthy();
  });
});
