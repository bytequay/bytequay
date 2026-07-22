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
    sendTrunkMessage: vi.fn().mockResolvedValue(undefined),
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
    listNotificationsForThread: vi.fn().mockResolvedValue([]),
    ...over,
  };
  (window as unknown as { bridge: unknown }).bridge = bridge;
  return bridge;
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

  it('clears a stuck Working indicator once a turn ends without ever replying', async () => {
    vi.useFakeTimers();
    let getTaskCalls = 0;
    const bridge = mockBridge({
      // 1st call: initial mount (IDLE). 2nd: sendNow's own immediate reload
      // once sendTrunkMessage resolves — the backend has picked up the turn
      // (RUNNING). 3rd: the next poll tick, still RUNNING. 4th+: its tool
      // calls got denied/cancelled and it ends WITHOUT ever sending a
      // closing reply — back to IDLE with no new assistant message ever
      // landing.
      getTask: vi.fn(() => {
        getTaskCalls += 1;
        const status = getTaskCalls <= 1 ? 'IDLE' : getTaskCalls <= 3 ? 'RUNNING' : 'IDLE';
        return Promise.resolve({
          id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z',
          workspaceId: 'ws-1', status,
        });
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
    expect(bridge.getTask).toHaveBeenCalledTimes(1);

    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'go' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await vi.advanceTimersByTimeAsync(0); // flush sendTrunkMessage's promise chain

    // Poll tick #3 lands RUNNING (call #2 already did, via sendNow's own
    // reload) — the Working banner shows.
    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.getByRole('status')).toBeTruthy();

    // Poll tick #4 lands back at IDLE with still no new assistant reply —
    // the turn is over; Working must clear instead of sticking forever.
    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.queryByRole('status')).toBeNull();
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
