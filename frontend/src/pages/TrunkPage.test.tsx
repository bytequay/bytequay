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
import type { PlanUsageDto, TrunkActivityDto, WorkspaceSessionDto } from '../workspace/workspaceApi';
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

function mockBridge({
  trunkActivity = EMPTY_ACTIVITY,
  questions = [],
  sessions = [],
  planUsage = { providers: [] },
  refreshedPlanUsage = planUsage,
}: {
  trunkActivity?: TrunkActivityDto;
  questions?: AgentQuestionDto[];
  sessions?: WorkspaceSessionDto[];
  planUsage?: PlanUsageDto;
  refreshedPlanUsage?: PlanUsageDto;
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
      if (path.endsWith('/sessions')) return Promise.resolve(sessions);
      if (path === '/api/ai/plan-usage') return Promise.resolve(planUsage);
      if (path === '/api/ai/plan-usage/claude/refresh') return Promise.resolve(refreshedPlanUsage);
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
    expect(await screen.findByText('WORKSPACE')).toBeTruthy();
    expect(await screen.findByText('Active task')).toBeTruthy();
    expect(screen.getByText('RUNNING NOW')).toBeTruthy();
    expect(screen.getByText('OPEN PRS')).toBeTruthy();
    expect(screen.getByText('BACKLOG')).toBeTruthy();
    expect(screen.getByText('USAGE')).toBeTruthy();
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

  it('shows provider-reported model usage instead of placeholder credits', async () => {
    mockBridge({
      sessions: [{
        id: 'run-usage', workspaceId: 'ws-1', trunkId: 't1', kind: 'dev', status: 'done',
        provider: 'openai', model: 'gpt-5', taskId: null, stageId: null, durableReview: false,
        costUsdMilli: 0, tokensIn: 1_234, tokensOut: 56, stepCursor: 1, budget: null,
        headline: null, durationMs: 100, launchInput: null, pauseReason: null, outcome: null,
        startedAt: Date.now(), finishedAt: Date.now(),
      }],
    });
    renderTrunk();

    expect(screen.getByRole('button', { name: 'Usage' })).toBeTruthy();
    expect(screen.queryByText(/Changes \+/)).toBeNull();
    expect(await screen.findByText('1,290 tokens · 1 run')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Usage' }));
    expect(screen.getAllByText('1,234 tokens').length).toBe(2);
    expect(screen.getAllByText('56 tokens').length).toBe(2);
    expect(screen.queryByText(/AI credits/)).toBeNull();
  });

  it('renders provider plan limits through one meter style', async () => {
    const now = Date.now();
    mockBridge({
      planUsage: {
        providers: [{
          provider: 'openai', label: 'Codex CLI', plan: 'prolite', updatedAt: now,
          source: 'Codex CLI app-server', message: null,
          limits: [{
            id: 'primary:10080', label: 'Weekly', usedPercent: 3, resetsAt: now + 7_200_000,
            model: null,
          }],
        }, {
          provider: 'anthropic', label: 'Claude CLI', plan: null, updatedAt: now,
          source: 'Claude CLI status line', message: null,
          limits: [{
            id: 'five_hour', label: '5-hour', usedPercent: 98, resetsAt: now + 5_400_000,
            model: null,
          }],
        }],
      },
    });
    renderTrunk();

    expect(await screen.findByText('Codex CLI')).toBeTruthy();
    expect(screen.getByText('Pro Lite')).toBeTruthy();
    expect(screen.getByText('3% used')).toBeTruthy();
    expect(screen.getByText('Claude CLI')).toBeTruthy();
    expect(screen.getByText('98% used')).toBeTruthy();
    expect(screen.getByLabelText('5-hour 98% used').firstElementChild?.classList.contains('is-critical'))
      .toBe(true);
    expect(screen.getByText('MODEL ACTIVITY')).toBeTruthy();
  });

  it('refreshes Claude Code interactive usage on demand', async () => {
    const now = Date.now();
    const bridge = mockBridge({
      planUsage: {
        providers: [{
          provider: 'anthropic', label: 'Claude CLI', plan: null, updatedAt: 0,
          source: null, message: 'Refresh Claude CLI usage to read plan limits.', limits: [],
        }],
      },
      refreshedPlanUsage: {
        providers: [{
          provider: 'anthropic', label: 'Claude CLI', plan: 'Max', updatedAt: now,
          source: 'Claude CLI /usage', message: null,
          limits: [{
            id: 'model:fable', label: 'Weekly', usedPercent: 98,
            resetsAt: now + 4 * 24 * 60 * 60_000, model: 'Fable',
          }],
        }],
      },
    });
    renderTrunk();

    fireEvent.click(await screen.findByRole('button', { name: 'Refresh Claude CLI usage' }));

    expect(await screen.findByText('Fable')).toBeTruthy();
    expect(screen.getByText('98% used')).toBeTruthy();
    expect(bridge.workspaceApi).toHaveBeenCalledWith({
      path: '/api/ai/plan-usage/claude/refresh',
      method: 'POST',
    });
  });
});
