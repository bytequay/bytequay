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
        { id: 'm1', threadId: 't1', taskId: null, seq: 1, role: 'user', type: 'text', contentJson: JSON.stringify({ text: 'plan the cleanup' }), durationMs: null, tokensIn: null },
      ],
    }),
    listTasksForThread: vi.fn().mockResolvedValue([
      { id: 'task-1', seq: 1, name: 'Add meter', status: 'RUNNING', branchName: 'feat/x' },
      { id: 'task-2', seq: 2, name: 'Later', status: 'PENDING', branchName: null },
    ]),
    sendTrunkMessage: vi.fn().mockResolvedValue(undefined),
    listBacklog: vi.fn().mockResolvedValue([]),
    listThreadSignals: vi.fn().mockResolvedValue([]),
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
    // Active task card on top, PENDING task in the Queued folder.
    expect(await screen.findByText('Task 1 · Add meter')).toBeTruthy();
    expect(screen.getByText('Queued')).toBeTruthy();
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
    fireEvent.click(await screen.findByText('Task 1 · Add meter'));
    expect(onOpenTask).toHaveBeenCalledWith('task-1');
  });
});
