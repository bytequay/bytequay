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
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentQuestionDto } from '../types';
import type { TrunkActivityDto } from '../workspace/workspaceApi';
import { TrunkPage } from './TrunkPage';

const EMPTY_ACTIVITY: TrunkActivityDto = {
  trunkId: 't1',
  pinned: [],
  timeline: [],
  generatedAt: 0,
};

function activity(overrides: Partial<TrunkActivityDto> = {}): TrunkActivityDto {
  return {
    ...EMPTY_ACTIVITY,
    generatedAt: 100,
    ...overrides,
  };
}

function question(): AgentQuestionDto {
  return {
    id: 'q1',
    threadId: 't1',
    taskId: null,
    question: 'Keep the legacy field order?',
    context: 'This affects serialized output.',
    options: [{ id: 'keep', label: 'Keep it', extra: null }],
    allowFreeForm: true,
    status: 'open',
    answerOptionId: null,
    answerFreeForm: null,
    createdAt: 1,
    answeredAt: null,
  };
}

function mockBridge({
  trunkActivity = EMPTY_ACTIVITY,
  questions = [],
}: {
  trunkActivity?: TrunkActivityDto;
  questions?: AgentQuestionDto[];
} = {}) {
  const bridge = {
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
    listThreadQuestions: vi.fn().mockResolvedValue(questions),
    workspaceApi: vi.fn(({ path }: { path: string }) => {
      if (path.endsWith('/activity')) return Promise.resolve(trunkActivity);
      if (path.endsWith('/overview')) {
        return Promise.resolve({
          workspace: { id: 'ws-1', name: 'ByteQuay' },
          repository: { fullName: 'acme/bytequay' },
          sidebarCounts: { pullRequests: 0 },
          today: { needsYou: [], running: [], spendTodayMilliUsd: 1840 },
        });
      }
      return Promise.resolve([]);
    }),
    answerQuestion: vi.fn().mockResolvedValue(undefined),
    startBacklogDevelopment: vi.fn().mockResolvedValue({ item: null, taskId: null }),
    skipBacklogItem: vi.fn().mockResolvedValue(undefined),
    openInAppBrowser: vi.fn().mockResolvedValue(undefined),
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

function renderTrunk(onOpenTask = vi.fn()) {
  return {
    onOpenTask,
    ...render(
      <TrunkPage
        threadId="t1"
        thread={{ title: 'Backend cleanup', createdLabel: '3d ago', workspaceId: 'ws-1' }}
        sidebar={<aside data-testid="sidebar" />}
        conversation={<div data-testid="conv">conversation</div>}
        composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
        tasks={{
          active: [{ id: 'ta', title: 'Active task', status: 'foreground' }],
          closed: [{ id: 'tc', title: 'Closed task', status: 'closed' }],
        }}
        onOpenTask={onOpenTask}
      />,
    ),
  };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Reflect.deleteProperty(window, 'bridge');
});
beforeEach(() => { mockBridge(); });

describe('TrunkPage', () => {
  it('renders the locked trunk shell, conversation, and workspace overview', async () => {
    renderTrunk();

    expect(screen.getByTestId('sidebar')).toBeTruthy();
    expect(screen.getByTestId('conv')).toBeTruthy();
    expect(screen.getByText('Backend cleanup')).toBeTruthy();
    expect(await screen.findByText('WORKSPACE')).toBeTruthy();
    expect(await screen.findByText('Active task')).toBeTruthy();
    expect(screen.getByText('RUNNING NOW')).toBeTruthy();
    expect(screen.getByText('OPEN PRS')).toBeTruthy();
    expect(screen.getByText('BACKLOG')).toBeTruthy();
    expect(screen.getByText('USAGE')).toBeTruthy();
  });

  it('pins actionable gates above the canonical timeline', async () => {
    mockBridge({
      trunkActivity: activity({
        pinned: [{
          id: 'gate:1',
          kind: 'approval',
          title: 'Plan ready — 4 steps',
          summary: 'Awaiting approval',
          status: 'open',
          itemPath: '#/workspace/ws-1/sessions/run-1',
          taskId: null,
          sessionId: 'run-1',
          occurredAt: 10,
          actionable: true,
        }],
        timeline: [{
          id: 'session:1',
          kind: 'session',
          title: 'Dev session',
          summary: 'Writing tests',
          status: 'running',
          itemPath: '#/workspace/ws-1/sessions/run-2',
          taskId: 'task-1',
          sessionId: 'run-2',
          occurredAt: 9,
          actionable: false,
        }],
      }),
    });
    renderTrunk();

    expect(await screen.findByText('NEEDS YOU')).toBeTruthy();
    expect(screen.getByText('Plan ready — 4 steps')).toBeTruthy();
    expect(screen.getByText('Dev session')).toBeTruthy();
  });

  it('opens a running task from the workspace overview', async () => {
    mockBridge({
      trunkActivity: activity({
        timeline: [{
          id: 'task:9',
          kind: 'task',
          title: 'Tests running',
          summary: null,
          status: 'running',
          itemPath: null,
          taskId: 'task-9',
          sessionId: null,
          occurredAt: 9,
          actionable: false,
        }],
      }),
    });
    const onOpenTask = vi.fn();
    renderTrunk(onOpenTask);

    await screen.findByText('Tests running');
    fireEvent.click(screen.getByRole('button', { name: 'Watch' }));
    expect(onOpenTask).toHaveBeenCalledWith('task-9');
  });

  it('keeps unresolved agent questions pinned in the conversation', async () => {
    const bridge = mockBridge({ questions: [question()] });
    renderTrunk();

    expect(await screen.findByText('Keep the legacy field order?')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Keep it' }));
    await waitFor(() =>
      expect(bridge.answerQuestion).toHaveBeenCalledWith('q1', 'keep', undefined));
  });

  it('navigates canonical activity deep links', async () => {
    window.location.hash = '#/home';
    mockBridge({
      trunkActivity: activity({
        pinned: [{
          id: 'review:4',
          kind: 'review',
          title: 'Review round complete',
          summary: 'Six drafts are ready',
          status: 'done',
          itemPath: '#/workspace/ws-1/prs/148',
          taskId: null,
          sessionId: null,
          occurredAt: 9,
          actionable: false,
        }],
      }),
    });
    renderTrunk();

    await screen.findByText('Review round complete');
    fireEvent.click(screen.getByRole('button', { name: 'Jump' }));
    expect(window.location.hash).toBe('#/workspace/ws-1/prs/148');
  });

  it('collapses and reopens the workspace overview', async () => {
    renderTrunk();
    await screen.findByText('Active task');

    fireEvent.click(screen.getByRole('button', { name: 'Collapse workspace panel' }));
    expect(screen.queryByText('Active task')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Toggle workspace panel' }));
    expect(await screen.findByText('Active task')).toBeTruthy();
  });

  it('uses the task-free trunk composer with the locked usage values', async () => {
    renderTrunk();

    expect(screen.getByRole('button', { name: 'Usage' })).toBeTruthy();
    expect(screen.queryByText(/Changes \+/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Usage' }));
    expect(screen.getByText('4% used')).toBeTruthy();
    expect(screen.getAllByText('827 AI credits').length).toBe(2);
  });
});
