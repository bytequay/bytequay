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
  task: { prNumber: 145, currentPhase: 'PUSHED_AWAITING_CI' as TaskPhase, terminal: false, paused: false },
});

const activeDevelopmentNodes = buildLivePlan({
  stages: [{
    id: 'dev-1', taskId: 't', type: 'DEVELOPMENT_STAGE', state: 'ACTIVE',
    openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
    summary: '', loopIteration: 0,
  }],
  subStages: [],
  task: { prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, terminal: false, paused: false },
});

describe('TaskSidebar', () => {
  it('renders the four grouped stages without promoting nested checkpoints', () => {
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
    expect(screen.getByText('Local Development')).toBeTruthy();
    expect(screen.getByText('Remote Development')).toBeTruthy();
    expect(screen.getByText('Cleanup')).toBeTruthy();
    expect(screen.queryByText('Local review')).toBeNull();
    expect(screen.queryByText('CI validation')).toBeNull();
  });

  it('keeps grouped phases collapsed until the user opens them', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={activeDevelopmentNodes}
      />,
    );
    const localDevelopment = screen.getByText('Local Development').closest('button') as HTMLButtonElement;
    expect(localDevelopment.getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('.workspace-task-stage__plan')).toBeNull();
    fireEvent.click(localDevelopment);
    expect(localDevelopment.getAttribute('aria-expanded')).toBe('true');
    expect(document.querySelector('.workspace-task-stage__plan')).not.toBeNull();
    expect(screen.getByText('Implementing')).toBeTruthy();
    expect(screen.getByText('Validation')).toBeTruthy();
    expect(screen.getByText('Brain review')).toBeTruthy();
    expect(screen.getByText('Local review')).toBeTruthy();
    expect(screen.getByText('Push / PR')).toBeTruthy();
    fireEvent.click(localDevelopment);
    expect(localDevelopment.getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('.workspace-task-stage__plan')).toBeNull();
  });

  it('shows the done-count with the same bottom navigation as Home', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={nodes}
      />,
    );
    expect(screen.getByText(/^\d+ of 11 done$/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Report a bug' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Settings' })).toBeTruthy();
    expect(screen.queryByText('chenjian2664')).toBeNull();
  });

  it('does not add lifecycle-type glyphs beside the grouped stage labels', () => {
    const { container } = render(<TaskSidebar task={{ title: 'x', branch: 'b' }} nodes={nodes} />);
    expect(container.querySelectorAll('[data-node-type]').length).toBe(0);
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
          task: { prNumber: null, currentPhase: 'IMPLEMENTING' as TaskPhase, terminal: false, paused: false },
        })}
        onOpenStage={onOpenStage}
      />,
    );
    const localDevelopment = screen.getByText('Local Development').closest('button') as HTMLButtonElement;
    fireEvent.click(localDevelopment);
    expect(onOpenStage).toHaveBeenCalledWith('dev-1');
    expect(localDevelopment.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(localDevelopment);
    expect(localDevelopment.getAttribute('aria-expanded')).toBe('false');
    expect(onOpenStage).toHaveBeenCalledOnce();
  });

  it('renders a paused active phase with awaiting classes and no live-work classes', () => {
    const dev = {
      id: 'dev-paused', taskId: 't', type: 'DEVELOPMENT_STAGE' as const, state: 'ACTIVE' as const,
      openedAt: '2026-01-01T00:00:00Z', closedAt: null, callerStageId: null,
      summary: '', loopIteration: 0,
    };
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={buildLivePlan({
          stages: [dev], subStages: [],
          task: {
            prNumber: null, currentPhase: 'VALIDATING' as TaskPhase, paused: true, terminal: false,
          },
          viewedStageId: dev.id,
          working: true,
        })}
      />,
    );

    const localDevelopment = screen.getByText('Local Development').closest('button') as HTMLButtonElement;
    expect(localDevelopment.querySelector('.workspace-task-stage__status-icon.is-awaiting')).toBeTruthy();
    expect(localDevelopment.querySelector('.is-running, .is-planning, .is-monitoring')).toBeNull();

    fireEvent.click(localDevelopment);
    const validation = screen.getByText('Validation').closest('button') as HTMLButtonElement;
    expect(validation.querySelector('.workspace-task-stage__status-icon.is-awaiting')).toBeTruthy();
    expect(validation.querySelector('.is-running, .is-planning, .is-monitoring')).toBeNull();
  });

  it('labels a remotely closed terminal task as closed rather than merged', () => {
    render(
      <TaskSidebar
        task={{ title: 'x', branch: 'b' }}
        nodes={buildLivePlan({
          stages: [{
            id: 'remote-closed', taskId: 't', type: 'REMOTE_DEVELOPMENT_STAGE', state: 'CLOSED',
            openedAt: '2026-01-01T00:00:00Z', closedAt: '2026-01-01T01:00:00Z', callerStageId: null,
            summary: '', loopIteration: 0,
          }],
          subStages: [],
          task: { prNumber: 145, currentPhase: 'COMPLETED' as TaskPhase, paused: false, terminal: true },
          prStatus: 'closed',
        })}
      />,
    );

    const remote = screen.getByText('Remote Development').closest('button') as HTMLButtonElement;
    expect(remote.textContent?.toLowerCase()).toContain('closed');
    expect(remote.textContent?.toLowerCase()).not.toContain('merged');
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
    fireEvent.click(screen.getByText('Remote Development'));
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
