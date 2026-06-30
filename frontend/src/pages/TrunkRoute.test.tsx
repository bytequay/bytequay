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
    getTask: vi.fn().mockResolvedValue({ id: 't1', title: 'Backend cleanup', createdAt: '2026-06-24T00:00:00Z' }),
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
    expect((await screen.findAllByText('Task 1 · Add meter')).length).toBeGreaterThanOrEqual(2);
    // "Closed" shows as both the sub-tab label and the folder header.
    expect(screen.getAllByText('Closed').length).toBeGreaterThanOrEqual(1);
  });

  it('echoes the foreground task as a card in the conversation', async () => {
    mockBridge();
    const { container } = render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    await screen.findByText('plan the cleanup');
    // The conversation column carries the running task's card with a
    // "Running" pill — the completed task is not echoed here.
    const conv = container.querySelector('.conv') as HTMLElement;
    expect(conv).toBeTruthy();
    expect(conv.querySelector('.task-card')).toBeTruthy();
    expect(conv.textContent).toContain('Task 1 · Add meter');
    expect(conv.textContent).toContain('Running');
    expect(conv.textContent).not.toContain('Task 2 · Later');
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

  it('opening a task card fires onOpenTask', async () => {
    mockBridge();
    const onOpenTask = vi.fn();
    render(<TrunkRoute threadId="t1" onOpenTask={onOpenTask} />);
    fireEvent.click((await screen.findAllByText('Task 1 · Add meter'))[0]);
    expect(onOpenTask).toHaveBeenCalledWith('task-1');
  });

  it('renders thinking as a collapsible Thought block', async () => {
    mockBridge();
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    // 2s elapsed between the thinking row (:03) and the answer (:05).
    const thought = await screen.findByRole('button', { name: /Thought for 2s/ });
    expect(thought).toBeTruthy();
    // Default-open: the reasoning shows without a click (Copilot pattern).
    expect(screen.getByText('weighing the approach')).toBeTruthy();
    fireEvent.click(thought);
    expect(screen.queryByText('weighing the approach')).toBeNull();
    // The plan text renders inline.
    expect(screen.getByText('Here is the plan.')).toBeTruthy();
  });

  it('Cut task → creates a task seeded from the latest prompt in the thread repo', async () => {
    const bridge = mockBridge();
    render(<TrunkRoute threadId="t1" onOpenTask={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: /Cut task/ }));
    await waitFor(() => expect(bridge.cutTaskNow).toHaveBeenCalledWith('t1', 'CLI_AGENT', 'plan the cleanup', '/repo/web', 'plan the cleanup'));
  });
});
