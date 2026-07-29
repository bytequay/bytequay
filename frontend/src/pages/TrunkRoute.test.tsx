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
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { TrunkRoute } from './TrunkRoute';

beforeAll(() => { Element.prototype.scrollIntoView = vi.fn(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); Reflect.deleteProperty(window, 'bridge'); });

function mockBridge(over: Record<string, unknown> = {}) {
  const bridge = {
    getTask: vi.fn().mockResolvedValue({
      id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z', workspaceId: 'ws-1',
    }),
    getTaskIndex: vi.fn().mockResolvedValue({
      threadId: 't1', totalUserMessages: 1, entries: [], loadedFromSeq: null, nextCursor: null,
      messages: [
        { id: 'm1', threadId: 't1', taskId: null, seq: 1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'plan the cleanup' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:00Z' },
        { id: 'm2', threadId: 't1', taskId: null, seq: 2, role: 'assistant', type: 'thinking', contentJson: JSON.stringify({ summary: 'weighing the approach' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:03Z' },
        { id: 'm3', threadId: 't1', taskId: null, seq: 3, role: 'assistant', type: 'text', contentJson: JSON.stringify({ text: 'Here is the plan.' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:05Z' },
      ],
    }),
    cutTaskNow: vi.fn().mockResolvedValue({ id: 'task-3', seq: 3, name: 'Cut now', status: 'RUNNING', branchName: null }),
    // createdAt matters: it's what buildTrunkTimeline sorts on, so both get
    // a real value bracketing the planning round below (task-2 before it,
    // task-1 after). The newest task remains expanded in locked frame 1b.
    listTasksForThread: vi.fn().mockResolvedValue([
      {
        id: 'task-1', seq: 2, name: 'Add meter', status: 'RUNNING', branchName: 'feat/x', workingDir: '/repo/web',
        createdAt: '2026-06-24T10:00:10Z',
      },
      {
        id: 'task-2', seq: 1, name: 'Later', status: 'COMPLETED', branchName: null, workingDir: '/repo/web',
        createdAt: '2026-06-24T09:00:00Z',
      },
    ]),
    sendTrunkMessage: vi.fn().mockResolvedValue({ status: 'queued', turnId: 'turn-new' }),
    getTaskTurns: vi.fn().mockResolvedValue([]),
    getTrunkTraceEvents: vi.fn().mockResolvedValue([]),
    getTypedPermissions: vi.fn().mockResolvedValue([]),
    decideTaskPermission: vi.fn().mockResolvedValue({ status: 'recorded' }),
    interruptTask: vi.fn().mockResolvedValue(undefined),
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
    listNotificationsForThread: vi.fn().mockResolvedValue([]),
    ...over,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
}

function mockAssistantQuestion(text: string) {
  return mockBridge({
    getTaskIndex: vi.fn().mockResolvedValue({
      threadId: 't1', totalUserMessages: 1, entries: [], loadedFromSeq: null, nextCursor: null,
      messages: [
        { id: 'm1', threadId: 't1', taskId: null, seq: 1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'What is next?' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:00Z' },
        { id: 'm2', threadId: 't1', taskId: null, seq: 2, role: 'assistant', type: 'text', contentJson: JSON.stringify({ text }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:05Z' },
        { id: 'm3', threadId: 't1', taskId: null, seq: 3, role: 'system', type: 'turn_done', contentJson: '{}', durationMs: 1000, tokensIn: null, ts: '2026-06-24T10:00:06Z' },
      ],
    }),
  });
}

describe('TrunkRoute', () => {
  it('mounts the V3 trunk on live thread data', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    // The thread title shows in both the top bar and the sidebar's
    // current-thread row.
    expect((await screen.findAllByText('Backend cleanup')).length).toBeGreaterThanOrEqual(1);
    await screen.findAllByText('Add meter'); // wait for the mount-time load to settle
    expect(screen.getByText('plan the cleanup')).toBeTruthy();
    // Active task detail stays visible on the branch rail; the locked
    // workspace-level overview replaces the old Activity pane.
    expect(container.querySelector('.trunk-page-v2__branch-row--cut')).toBeTruthy();
    expect(screen.getAllByText('Add meter').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('OPEN PRS')).toBeTruthy();
  });

  it('renders task cuts as milestone nodes carrying the task card', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    const conv = container.querySelector('.conv') as HTMLElement;
    expect(conv).toBeTruthy();
    await screen.findAllByText('Add meter');
    const cut = conv.querySelector('.sp-ms--purple');
    expect(cut).toBeTruthy();
    expect(cut?.textContent).toContain('Task cut');
    expect(conv.querySelector('.sp-ms .task-card')).toBeTruthy();
    expect(conv.textContent).toContain('Add meter');
  });

  it('posts a trunk message from the composer', async () => {
    const bridge = mockBridge();
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findAllByText('Backend cleanup');
    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'cut a task' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(bridge.sendTrunkMessage).toHaveBeenCalledWith('t1', 'cut a task', []));
  });

  it.each([
    'Phase 2 is unblocked. Want me to put up the plan for approval?',
    'Cut this as the Phase 2 task?',
    'Next task: Cut this as the Phase 2 task?',
  ])('offers go ahead for the direct continuation question: %s', async question => {
    const bridge = mockAssistantQuestion(question);
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);

    await screen.findByText('go ahead');
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });

    await waitFor(() => expect(bridge.sendTrunkMessage).toHaveBeenCalledWith('t1', 'go ahead', []));
  });

  it.each([
    'Which branch should I use?',
    'Cut this from the Phase 2 task?',
    'Which task should I cut?',
    'Cut this as the Phase 2 or Phase 3 task?',
    'Should I use main or release/2.x?',
  ])('does not offer go ahead for a question needing a real choice: %s', async question => {
    mockAssistantQuestion(question);
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);

    await screen.findByText(question);
    expect(screen.queryByText('go ahead')).toBeNull();
  });

  it('revives an errored trunk from the visible recovery action', async () => {
    const resumeTask = vi.fn().mockResolvedValue(undefined);
    mockBridge({
      getTask: vi.fn().mockResolvedValue({
        id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z', workspaceId: 'ws-1',
        status: 'ERRORED', errorMessage: 'permission MCP unavailable',
      }),
      resumeTask,
    });
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Resume thread' }));

    await waitFor(() => expect(resumeTask).toHaveBeenCalledWith('t1'));
  });

  it('opening a task-cut card fires onOpenTask', async () => {
    mockBridge();
    const onOpenTask = vi.fn();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={onOpenTask} />);
    await screen.findAllByText('Add meter');
    // The newest cut's existing task card is directly operable on the rail.
    const card = Array.from(container.querySelectorAll('.sp-ms .task-card'))
      .find(c => c.textContent?.includes('Add meter')) as HTMLElement;
    expect(card).toBeTruthy();
    fireEvent.click(card);
    expect(onOpenTask).toHaveBeenCalledWith('task-1');
  });

  it('loads the newest task artifacts and routes their actions to the diff page', async () => {
    const getTaskCumulativeDiff = vi.fn().mockResolvedValue([{
        filename: 'frontend/src/App.tsx', status: 'modified', additions: 8, deletions: 3, patch: null,
      }]);
    const listTaskCommits = vi.fn().mockResolvedValue([{
        sha: 'abc123def456', shortSha: 'abc123de', authorName: 'Jack', authorEmail: 'jack@example.com',
        authoredAt: '2026-06-24T10:02:00Z', subject: 'Tighten workspace routes',
      }]);
    mockBridge({
      getTaskCumulativeDiff,
      listTaskCommits,
    });
    const onReviewTask = vi.fn();
    render(
      <TrunkRoute threadId="t1" onOpenTask={() => {}} onReviewTask={onReviewTask} />,
    );

    await screen.findByText('Edited 1 file');
    expect(getTaskCumulativeDiff).toHaveBeenCalledWith('t1', 'task-1');
    expect(listTaskCommits).toHaveBeenCalledWith('t1', 'task-1');
    expect(screen.getByText('abc123de')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }));
    expect(onReviewTask).toHaveBeenNthCalledWith(1, 'task-1');
    expect(onReviewTask).toHaveBeenNthCalledWith(2, 'task-1');
  });

  it.each(['FAILED', 'CANCELLED'] as const)(
    'clears Working when its typed turn ends %s without ever replying', async terminalStatus => {
    vi.useFakeTimers();
    let turnCalls = 0;
    const bridge = mockBridge({
      // V2 owns the turn state and deliberately leaves this legacy row IDLE.
      getTask: vi.fn().mockResolvedValue({
        id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z',
        workspaceId: 'ws-1', status: 'IDLE',
      }),
      getTaskTurns: vi.fn(() => {
        turnCalls += 1;
        if (turnCalls === 1) return Promise.resolve([]);
        const status = turnCalls <= 3 ? (turnCalls === 2 ? 'QUEUED' : 'RUNNING') : terminalStatus;
        return Promise.resolve([{
          id: 'turn-new', threadId: 't1', taskId: null, lane: 'CLI', status,
          input: 'go', createdAt: '2026-06-24T10:01:00Z', updatedAt: '2026-06-24T10:01:01Z',
          startedAt: status === 'QUEUED' ? null : '2026-06-24T10:01:01Z',
          finishedAt: status === terminalStatus ? '2026-06-24T10:01:02Z' : null,
          errorMessage: status === terminalStatus ? 'turn ended without reply' : null,
        }]);
      }),
      getTaskIndex: vi.fn().mockResolvedValue({
        threadId: 't1', totalUserMessages: 1, entries: [], loadedFromSeq: null, nextCursor: null,
        messages: [
          { id: 'm1', threadId: 't1', taskId: null, seq: 1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'clean this up' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:00Z' },
        ],
      }),
    });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await vi.advanceTimersByTimeAsync(0); // flush the mount-time load()
    expect(bridge.getTaskTurns).toHaveBeenCalledTimes(1);

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'go' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await vi.advanceTimersByTimeAsync(0); // flush sendTrunkMessage's promise chain

    // The send reload observes QUEUED; the next poll observes RUNNING.
    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.getByRole('status')).toBeTruthy();

    // The exact V2 turn becomes terminal with no assistant message. The
    // legacy Thread.status never changed, but Working still clears.
    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('shows Working for a typed Trunk turn while the legacy thread row stays idle', async () => {
    mockBridge({
      getTask: vi.fn().mockResolvedValue({
        id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z',
        workspaceId: 'ws-1', status: 'IDLE',
      }),
      getTaskTurns: vi.fn().mockResolvedValue([{
        id: 'typed-running', threadId: 't1', taskId: null, lane: 'CLI', status: 'RUNNING',
        input: 'plan it', createdAt: '2026-06-24T10:01:00Z', updatedAt: '2026-06-24T10:01:01Z',
        startedAt: '2026-06-24T10:01:01Z', finishedAt: null, errorMessage: null,
      }]),
    });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    expect(await screen.findByRole('status')).toBeTruthy();
  });

  it('stops only the exact visible typed Trunk turn', async () => {
    const interruptTask = vi.fn().mockResolvedValue(undefined);
    mockBridge({
      interruptTask,
      getTaskTurns: vi.fn().mockResolvedValue([{
        id: 'typed-running', threadId: 't1', taskId: null, lane: 'CLI', status: 'RUNNING',
        input: 'plan it', createdAt: '2026-06-24T10:01:00Z', updatedAt: '2026-06-24T10:01:01Z',
        startedAt: '2026-06-24T10:01:01Z', finishedAt: null, errorMessage: null,
      }]),
    });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));

    await waitFor(() => expect(interruptTask)
      .toHaveBeenCalledWith('t1', 'typed-running'));
  });

  it('stops an older running Trunk turn before a newer queued turn', async () => {
    const interruptTask = vi.fn().mockResolvedValue(undefined);
    mockBridge({
      interruptTask,
      getTaskTurns: vi.fn().mockResolvedValue([
        {
          id: 'typed-queued', threadId: 't1', taskId: null, lane: 'CLI', status: 'QUEUED',
          input: 'queued next', createdAt: '2026-06-24T10:02:00Z', updatedAt: '2026-06-24T10:02:00Z',
          startedAt: null, finishedAt: null, errorMessage: null,
        },
        {
          id: 'typed-running', threadId: 't1', taskId: null, lane: 'CLI', status: 'RUNNING',
          input: 'running now', createdAt: '2026-06-24T10:01:00Z', updatedAt: '2026-06-24T10:01:01Z',
          startedAt: '2026-06-24T10:01:01Z', finishedAt: null, errorMessage: null,
        },
      ]),
    });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));

    await waitFor(() => expect(interruptTask)
      .toHaveBeenCalledWith('t1', 'typed-running'));
  });

  it('reloads trace only for the visible typed request without duplicating rows', async () => {
    vi.useFakeTimers();
    const getTrunkTraceEvents = vi.fn().mockResolvedValue([
      {
        id: 'trace:execution-1:0:0', trunkId: 't1', turnId: 'turn-1',
        requestMessageId: 'turn-1:request', executionId: 'execution-1', logSeq: 0,
        eventIndex: 0, type: 'thinking', contentJson: JSON.stringify({ text: 'provider trace thought' }),
        ts: '2026-06-24T10:00:01Z',
      },
      {
        id: 'trace:execution-1:1:0', trunkId: 't1', turnId: 'turn-1',
        requestMessageId: 'turn-1:request', executionId: 'execution-1', logSeq: 1,
        eventIndex: 0, type: 'error', contentJson: JSON.stringify({ text: 'recoverable trace error' }),
        ts: '2026-06-24T10:00:02Z',
      },
    ]);
    mockBridge({
      getTaskIndex: vi.fn().mockResolvedValue({
        threadId: 't1', totalUserMessages: 2, entries: [], loadedFromSeq: 7, nextCursor: 7,
        messages: [
          { id: 'legacy-request', threadId: 't1', taskId: null, seq: 7, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'legacy' }), durationMs: null, tokensIn: null, ts: '2026-06-24T09:00:00Z' },
          { id: 'turn-1:request', threadId: 't1', taskId: null, seq: -1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'typed' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:00Z' },
          { id: 'turn-1:result', threadId: 't1', taskId: null, seq: -2, role: 'assistant', type: 'text', contentJson: JSON.stringify({ text: 'done' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:03Z' },
        ],
      }),
      getTrunkTraceEvents,
    });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);

    expect(getTrunkTraceEvents).toHaveBeenLastCalledWith('t1', ['turn-1:request']);
    expect(screen.getAllByText('provider trace thought')).toHaveLength(1);
    expect(screen.getAllByText('recoverable trace error')).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(3000);
    await vi.advanceTimersByTimeAsync(0);
    expect(getTrunkTraceEvents).toHaveBeenCalledTimes(2);
    expect(screen.getAllByText('provider trace thought')).toHaveLength(1);
    expect(screen.getAllByText('recoverable trace error')).toHaveLength(1);
  });

  it('renders and answers its typed permission with the exact revision', async () => {
    const permission = {
      id: 'permission-1', callId: 'call-1', ownerKind: 'THREAD_TURN' as const,
      turnId: 'typed-running', operationId: 'operation-1', capability: 'process.execute',
      toolName: 'Bash', parametersJson: JSON.stringify({ command: 'npm test' }),
      state: 'OPEN', answerRevision: 7, requestedAt: 1_782_297_600_000,
    };
    const taskPermission = {
      ...permission, id: 'permission-task', callId: 'call-task', ownerKind: 'TASK_TURN' as const,
      parametersJson: JSON.stringify({ command: 'task-only-command' }),
    };
    const getTypedPermissions = vi.fn()
      .mockResolvedValueOnce([taskPermission, permission])
      .mockResolvedValue([]);
    const bridge = mockBridge({ getTypedPermissions });

    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    const approve = await screen.findByRole('button', { name: 'Approve once' });
    expect(screen.queryByText('task-only-command')).toBeNull();
    fireEvent.click(approve);

    await waitFor(() => expect(bridge.decideTaskPermission).toHaveBeenCalledWith(
      't1', 'call-1', 'ALLOW', undefined, 7));
  });

  it('keeps the Working banner hidden when only a cut task\'s stage is running', async () => {
    // The trunk turn already ended (its last trunk-scope row is a turn_done),
    // but the cut task's dev stage is still running — and since the task shares
    // the trunk's threads row, thread.status reads RUNNING. The banner must NOT
    // treat that stage work as the trunk working.
    mockBridge({
      getTask: vi.fn().mockResolvedValue({
        id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z',
        workspaceId: 'ws-1', status: 'RUNNING',
      }),
      getTaskIndex: vi.fn().mockResolvedValue({
        threadId: 't1', totalUserMessages: 1, entries: [], loadedFromSeq: null, nextCursor: null,
        messages: [
          { id: 'm1', threadId: 't1', taskId: null, seq: 1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'cut the task' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:00Z' },
          { id: 'm2', threadId: 't1', taskId: null, seq: 2, role: 'assistant', type: 'text', contentJson: JSON.stringify({ text: 'Task cut.' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:00:05Z' },
          { id: 'm3', threadId: 't1', taskId: null, seq: 3, role: 'system', type: 'turn_done', contentJson: '{}', durationMs: 1000, tokensIn: null, ts: '2026-06-24T10:00:06Z' },
          // The running stage appends only task-scoped rows.
          { id: 'm4', threadId: 't1', taskId: 'task-1', seq: 4, role: 'assistant', type: 'text', contentJson: JSON.stringify({ text: 'editing files' }), durationMs: null, tokensIn: null, ts: '2026-06-24T10:05:00Z' },
        ],
      }),
      getTaskTurns: vi.fn().mockResolvedValue([{
        id: 'stage-running', threadId: 't1', taskId: 'task-1', lane: 'CLI', status: 'RUNNING',
        input: 'develop', createdAt: '2026-06-24T10:05:00Z', updatedAt: '2026-06-24T10:05:01Z',
        startedAt: '2026-06-24T10:05:01Z', finishedAt: null, errorMessage: null,
      }]),
    });
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findAllByText('Add meter');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('reports the loaded thread\'s own workspace id', async () => {
    const onWorkspaceResolved = vi.fn();
    mockBridge();
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} onWorkspaceResolved={onWorkspaceResolved} />);
    await waitFor(() => expect(onWorkspaceResolved).toHaveBeenCalledWith('ws-1'));
  });

  it('does not report a workspace id for a slow fetch that resolves after unmount', async () => {
    // The parent renders <TrunkRoute> only while nav.view === 'thread-detail';
    // navigating anywhere else (a different workspace, the landing grid)
    // unmounts it entirely rather than switching threadId. onWorkspaceResolved
    // is a parent callback (App.tsx's sidebar-workspace state), so firing it
    // post-unmount would silently pin the sidebar to this stale thread's
    // workspace with nothing left to ever reset it.
    let resolveTask: (v: unknown) => void = () => {};
    const taskPromise = new Promise(resolve => { resolveTask = resolve; });
    const onWorkspaceResolved = vi.fn();
    mockBridge({ getTask: vi.fn(() => taskPromise) });

    const { unmount } = render(
      <TrunkRoute threadId="t1" onOpenTask={() => {}} onWorkspaceResolved={onWorkspaceResolved} />,
    );
    unmount();
    resolveTask({ id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z', workspaceId: 'ws-1' });
    await new Promise(r => setTimeout(r, 0));
    expect(onWorkspaceResolved).not.toHaveBeenCalled();
  });

  it('discards a slow response for a thread the user has since navigated away from', async () => {
    let resolveT1Task: (v: unknown) => void = () => {};
    const t1TaskPromise = new Promise(resolve => { resolveT1Task = resolve; });
    mockBridge({
      getTask: vi.fn((id: string) => (id === 't1'
        ? t1TaskPromise
        : Promise.resolve({ id: 't2', title: 'Other thread', createdAt: '2026-06-24T00:00:00Z' }))),
    });

    const { rerender } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    // Switch to a different thread before t1's slow getTask resolves —
    // mirrors navigating home and clicking a different sidebar thread while
    // the previous page's fetch is still in flight.
    rerender(<TrunkRoute threadId="t2" onOpenTask={() => {}} />);
    await screen.findAllByText('Other thread');

    // t1's fetch finally resolves — it must not clobber t2's content, which
    // is what's now on screen and what the sidebar highlight points at.
    resolveT1Task({ id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z' });
    await new Promise(r => setTimeout(r, 0));
    expect(screen.queryByText('Backend cleanup')).toBeNull();
    expect((await screen.findAllByText('Other thread')).length).toBeGreaterThanOrEqual(1);
  });

  it('folds the round work and surfaces the headline reply', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findAllByText('Add meter');
    // The last assistant text stays visible on the trunk rail.
    expect(screen.getByText('Here is the plan.')).toBeTruthy();
    // The thinking row folds into the round's own work disclosure (hidden in
    // Focused density), with no outer task fold around the newest cut.
    expect(screen.queryByText('weighing the approach')).toBeNull();
    const work = container.querySelector('.sp-work__bar') as HTMLElement;
    expect(work).toBeTruthy();
    fireEvent.click(work);
    expect(screen.getByText('weighing the approach')).toBeTruthy();
  });
});
