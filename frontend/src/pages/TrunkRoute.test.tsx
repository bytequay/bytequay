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
    // Every cut task folds, including the latest one — seq just orders them.
    // createdAt matters: it's what buildTrunkTimeline sorts on, so both get
    // a real value bracketing the planning round below (task-2 before it,
    // task-1 after).
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
    expect(screen.getByText('THREAD')).toBeTruthy();
    // The thread title shows in both the top bar and the sidebar's
    // current-thread row.
    expect((await screen.findAllByText('Backend cleanup')).length).toBeGreaterThanOrEqual(1);
    // Both cut tasks fold by default — open them to reach the planning
    // message underneath.
    await screen.findAllByText('Add meter'); // wait for the mount-time load to settle
    container.querySelectorAll('.sp-taskfold .sp-work__bar').forEach(bar => fireEvent.click(bar));
    expect(screen.getByText('plan the cleanup')).toBeTruthy();
    // Active task card shows in the (now-open) conversation fold AND the
    // Tasks tab; its own fold bar label is a third occurrence.
    expect(screen.getAllByText('Add meter').length).toBeGreaterThanOrEqual(2);
    // "Closed" shows as both the sub-tab label and the folder header.
    expect(screen.getAllByText('Closed').length).toBeGreaterThanOrEqual(1);
  });

  it('renders task cuts as milestone nodes carrying the task card', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    const conv = container.querySelector('.conv') as HTMLElement;
    expect(conv).toBeTruthy();
    // Every cut task folds by default now — open both folds to reach the
    // milestone peak (purple ◆ + "Task cut" kicker) embedding the existing
    // task card, the same card used in the Tasks tab.
    await screen.findAllByText('Add meter');
    conv.querySelectorAll('.sp-taskfold .sp-work__bar').forEach(bar => fireEvent.click(bar));
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

  it('opening a task-cut card fires onOpenTask', async () => {
    mockBridge();
    const onOpenTask = vi.fn();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={onOpenTask} />);
    // Every cut task folds by default now — open the folds to reach the
    // task card underneath.
    await screen.findAllByText('Add meter');
    container.querySelectorAll('.sp-taskfold .sp-work__bar').forEach(bar => fireEvent.click(bar));
    // Both task folds are now open, each with its own card — pick task-1's
    // by its title, since the two folds carry different cards.
    const card = Array.from(container.querySelectorAll('.sp-ms .task-card'))
      .find(c => c.textContent?.includes('Add meter')) as HTMLElement;
    expect(card).toBeTruthy();
    fireEvent.click(card);
    expect(onOpenTask).toHaveBeenCalledWith('task-1');
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
    // The planning round now lands inside the task fold it led up to — every
    // cut task folds by default, including the latest. Open both folds to
    // reach it.
    await screen.findAllByText('Add meter');
    container.querySelectorAll('.sp-taskfold .sp-work__bar').forEach(bar => fireEvent.click(bar));
    // The last assistant text is the headline — visible once its fold opens.
    expect(screen.getByText('Here is the plan.')).toBeTruthy();
    // The thinking row folds into the round's own work disclosure (hidden in
    // Focused density), nested one level deeper than the task fold.
    expect(screen.queryByText('weighing the approach')).toBeNull();
    // '.sp-work__bar' is shared with the task fold's own bar (a TaskFold is
    // a '.sp-work.sp-taskfold'), so scope to the round's own — the one whose
    // immediate parent is a plain WorkFold, not a TaskFold.
    const bars = Array.from(container.querySelectorAll('.sp-work__bar'));
    const work = bars.find(b => !b.parentElement?.classList.contains('sp-taskfold')) as HTMLElement;
    expect(work).toBeTruthy();
    fireEvent.click(work);
    expect(screen.getByText('weighing the approach')).toBeTruthy();
  });
});
