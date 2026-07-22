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
import type {
  TrunkActivityDto,
  WorkspaceBacklogItemDto,
} from '../workspace/workspaceApi';
import { TrunkPage } from './TrunkPage';

const EMPTY_ACTIVITY: TrunkActivityDto = {
  trunkId: 't1',
  pinned: [],
  timeline: [],
  generatedAt: 0,
};
const OVERVIEW_WIDTH_KEY = 'bq.trunkOverviewWidth';

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

function backlogItem(overrides: Partial<WorkspaceBacklogItemDto> = {}): WorkspaceBacklogItemDto {
  return {
    id: 'b1',
    threadId: 't1',
    workspaceId: 'ws-1',
    key: 'BQ-1',
    title: 'Remove legacy endpoint',
    body: 'The endpoint has no callers.',
    summary: 'The endpoint has no callers.',
    detail: null,
    impactRisk: null,
    links: [],
    tags: ['cleanup'],
    priority: 'medium',
    source: 'agent',
    status: 'open',
    createdBy: 'trunk-agent',
    origin: 'agent',
    createdAt: 1,
    inProgressAt: null,
    startedAt: null,
    resolvedAt: null,
    rejectedAt: null,
    rejectionReason: null,
    linkedTaskId: null,
    relatedBacklogIds: [],
    ...overrides,
  };
}

function mockBridge({
  trunkActivity = EMPTY_ACTIVITY,
  questions = [],
  backlog = [],
}: {
  trunkActivity?: TrunkActivityDto;
  questions?: AgentQuestionDto[];
  backlog?: WorkspaceBacklogItemDto[];
} = {}) {
  const bridge = {
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
    listThreadQuestions: vi.fn().mockResolvedValue(questions),
    workspaceApi: vi.fn(({ path }: { path: string; method?: string }) => {
      if (path.endsWith('/activity')) return Promise.resolve(trunkActivity);
      if (path.endsWith('/overview')) {
        return Promise.resolve({
          workspace: { id: 'ws-1', name: 'ByteQuay' },
          repository: { fullName: 'acme/bytequay' },
          sidebarCounts: { pullRequests: 0 },
          today: { needsYou: [], running: [], spendTodayMilliUsd: 1840 },
        });
      }
      if (path.endsWith('/backlog')) return Promise.resolve(backlog);
      return Promise.resolve([]);
    }),
    answerQuestion: vi.fn().mockResolvedValue(undefined),
    startBacklogDevelopment: vi.fn().mockResolvedValue({ item: null, taskId: null }),
    skipBacklogItem: vi.fn().mockResolvedValue(undefined),
    openInAppBrowser: vi.fn().mockResolvedValue(undefined),
    markNotificationRead: vi.fn().mockResolvedValue({ id: 'n1', status: 'read' }),
    dismissNotification: vi.fn().mockResolvedValue({ id: 'n1', status: 'dismissed' }),
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
  localStorage.removeItem(OVERVIEW_WIDTH_KEY);
  Reflect.deleteProperty(window, 'bridge');
});
beforeEach(() => { mockBridge(); });

describe('TrunkPage', () => {
  it('renders the locked trunk shell, conversation, and workspace overview', async () => {
    renderTrunk();

    expect(screen.getByTestId('sidebar')).toBeTruthy();
    expect(screen.getByTestId('conv')).toBeTruthy();
    expect(screen.getByText('Backend cleanup')).toBeTruthy();
    expect(await screen.findByText('Active task')).toBeTruthy();
    expect(screen.getByText('RUNNING NOW')).toBeTruthy();
    expect(screen.getByText('OPEN PRS')).toBeTruthy();
    expect(screen.getByText('BACKLOG')).toBeTruthy();
    expect(screen.queryByText('USAGE')).toBeNull();
  });

  it('surfaces a terminal agent error and offers recovery', () => {
    const onResume = vi.fn();
    render(
      <TrunkPage
        threadId="t1"
        thread={{
          title: 'Backend cleanup', status: 'ERRORED', workspaceId: 'ws-1',
          errorMessage: 'Claude permission bridge was unavailable.',
        }}
        conversation={<div>conversation</div>}
        composer={{ value: '', onChange: () => {}, onSubmit: () => {} }}
        tasks={{ active: [], closed: [] }}
        onResume={onResume}
      />,
    );

    expect(screen.getByRole('alert').textContent).toContain('Claude permission bridge was unavailable.');
    fireEvent.click(screen.getByRole('button', { name: 'Resume thread' }));
    expect(onResume).toHaveBeenCalledOnce();
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

  it('opens actionable backlog items from the workspace overview', async () => {
    window.location.hash = '#/workspace/ws-1/trunks/t1';
    mockBridge({
      backlog: [
        backlogItem(),
        backlogItem({ id: 'b2', key: 'BQ-2', title: 'Already shipped', status: 'resolved' }),
        backlogItem({ id: 'b3', key: 'BQ-3', title: 'Already underway', status: 'in-progress' }),
      ],
    });
    renderTrunk();

    const item = await screen.findByRole('button', { name: 'Open backlog item Remove legacy endpoint' });
    expect(screen.queryByText('Already shipped')).toBeNull();
    expect(screen.queryByText('Already underway')).toBeNull();

    fireEvent.click(item);
    expect(window.location.hash).toBe('#/workspace/ws-1/backlog/BQ-1');
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

  it('dismisses a pinned notification when the user jumps into it', async () => {
    const bridge = mockBridge({
      trunkActivity: activity({
        pinned: [{
          id: 'notification:n1',
          kind: 'attention',
          title: 'Session paused at budget cap',
          summary: 'Review the usage, then resume or restart.',
          status: 'unread',
          itemPath: '#/workspace/ws-1/sessions/run-1',
          taskId: 'task-9',
          sessionId: null,
          occurredAt: 9,
          actionable: true,
        }],
      }),
    });
    const { onOpenTask } = renderTrunk();

    await screen.findByText('Session paused at budget cap');
    fireEvent.click(screen.getByRole('button', { name: 'Jump' }));

    // The card must clear on return, so jumping dismisses the notification
    // (id stripped of its "notification:" prefix) and still opens the task.
    // markRead is a no-op for NEEDS_ATTENTION rows, so dismiss is what clears it.
    expect(bridge.dismissNotification).toHaveBeenCalledWith('n1');
    expect(bridge.markNotificationRead).not.toHaveBeenCalled();
    expect(onOpenTask).toHaveBeenCalledWith('task-9');
  });

  it('collapses and reopens the workspace overview', async () => {
    renderTrunk();
    await screen.findByText('Active task');

    fireEvent.click(screen.getByRole('button', { name: 'Toggle workspace panel' }));
    expect(screen.queryByText('Active task')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Toggle workspace panel' }));
    expect(await screen.findByText('Active task')).toBeTruthy();
  });

  it('resizes the workspace overview and restores its width', () => {
    const { container, unmount } = renderTrunk();
    const body = container.querySelector('.trunk-page-v2__body') as HTMLElement;
    vi.spyOn(body, 'getBoundingClientRect').mockReturnValue({ right: 1200 } as DOMRect);

    const handle = screen.getByRole('separator', { name: 'Resize workspace panel' });
    fireEvent.mouseDown(handle, { clientX: 882 });
    fireEvent.mouseMove(window, { clientX: 800 });
    fireEvent.mouseUp(window);

    expect((container.querySelector('.trunk-page-v2__overview') as HTMLElement).style.width)
      .toBe('400px');
    expect(localStorage.getItem(OVERVIEW_WIDTH_KEY)).toBe('400');

    unmount();
    const restored = renderTrunk();
    expect((restored.container.querySelector('.trunk-page-v2__overview') as HTMLElement).style.width)
      .toBe('400px');
  });

  it('omits provider and token usage from the trunk surface', async () => {
    const bridge = mockBridge();
    renderTrunk();

    await waitFor(() => expect(bridge.workspaceApi).toHaveBeenCalledWith({
      path: '/api/workspaces/ws-1/overview',
    }));

    expect(screen.queryByText('USAGE')).toBeNull();
    expect(screen.queryByText('MODEL ACTIVITY')).toBeNull();
    expect(screen.queryByText('Codex CLI')).toBeNull();
    expect(screen.queryByText('Claude CLI')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Usage' })).toBeNull();
    expect(bridge.workspaceApi).not.toHaveBeenCalledWith({
      path: '/api/workspaces/ws-1/sessions',
    });
    expect(bridge.workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/plan-usage' });
    expect(bridge.workspaceApi).not.toHaveBeenCalledWith({
      path: '/api/ai/plan-usage/claude/refresh',
      method: 'POST',
    });
    expect(bridge.workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/api-usage' });
    expect(bridge.workspaceApi).not.toHaveBeenCalledWith({ path: '/api/ai/deepseek/balance' });
  });
});
