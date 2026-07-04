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
afterEach(() => { cleanup(); vi.restoreAllMocks(); Reflect.deleteProperty(window, 'bridge'); });

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
    listTasksForThread: vi.fn().mockResolvedValue([
      { id: 'task-1', seq: 1, name: 'Add meter', status: 'RUNNING', branchName: 'feat/x', workingDir: '/repo/web' },
      { id: 'task-2', seq: 2, name: 'Later', status: 'COMPLETED', branchName: null, workingDir: '/repo/web' },
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
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    expect(screen.getByText('THREAD')).toBeTruthy();
    // The thread title shows in both the top bar and the sidebar's
    // current-thread row.
    expect((await screen.findAllByText('Backend cleanup')).length).toBeGreaterThanOrEqual(1);
    // Planning message rendered into the conversation.
    expect(await screen.findByText('plan the cleanup')).toBeTruthy();
    // Active task card shows in the conversation AND the Tasks tab;
    // the COMPLETED task lives in the Closed folder.
    expect((await screen.findAllByText('Add meter')).length).toBeGreaterThanOrEqual(2);
    // "Closed" shows as both the sub-tab label and the folder header.
    expect(screen.getAllByText('Closed').length).toBeGreaterThanOrEqual(1);
  });

  it('renders task cuts as milestone nodes carrying the task card', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findByText('plan the cleanup');
    // Each cut is a milestone peak (purple ◆ + "Task cut" kicker) embedding
    // the existing task card — the same card used in the Tasks tab.
    const conv = container.querySelector('.conv') as HTMLElement;
    expect(conv).toBeTruthy();
    const cut = conv.querySelector('.sp-ms--purple');
    expect(cut).toBeTruthy();
    expect(cut?.textContent).toContain('Task cut');
    expect(conv.querySelector('.sp-ms .task-card')).toBeTruthy();
    expect(conv.textContent).toContain('Add meter');
  });

  it('lists each task cut in the outline strip', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findByText('plan the cleanup');
    const outline = container.querySelector('.sp-outline') as HTMLElement;
    expect(outline).toBeTruthy();
    // One chip per cut task.
    expect(outline.querySelectorAll('.sp-ochip--task').length).toBe(2);
  });

  it('posts a trunk message from the composer', async () => {
    const bridge = mockBridge();
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findAllByText('Backend cleanup');
    const box = screen.getByRole('textbox');
    fireEvent.change(box, { target: { value: 'cut a task' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(bridge.sendTrunkMessage).toHaveBeenCalledWith('t1', 'cut a task'));
  });

  it('opening a task-cut card fires onOpenTask', async () => {
    mockBridge();
    const onOpenTask = vi.fn();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={onOpenTask} />);
    await screen.findByText('plan the cleanup');
    const card = container.querySelector('.sp-ms .task-card') as HTMLElement;
    expect(card).toBeTruthy();
    fireEvent.click(card);
    expect(onOpenTask).toHaveBeenCalledWith('task-1');
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
    // The last assistant text is the headline — always visible.
    expect(await screen.findByText('Here is the plan.')).toBeTruthy();
    // The thinking row folds into the round's work disclosure (hidden in
    // Focused density).
    expect(screen.queryByText('weighing the approach')).toBeNull();
    const work = container.querySelector('.sp-work__bar') as HTMLElement;
    expect(work).toBeTruthy();
    fireEvent.click(work);
    expect(screen.getByText('weighing the approach')).toBeTruthy();
  });
});
