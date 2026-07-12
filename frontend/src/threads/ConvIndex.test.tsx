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
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, ConvIndexEntryDto, ConvIndexPageDto, ThreadMessageDto, WorkUnitTaskDto } from '../types';
import { ConvIndex } from './ConvIndex';

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

function entry(seq: number, preview: string): ConvIndexEntryDto {
  return { seq, preview, tsMs: 1_765_000_000_000 + seq };
}

function installBridge(entries: ConvIndexEntryDto[], messages: ThreadMessageDto[] = []) {
  const pageDto: ConvIndexPageDto = {
    threadId: 't1',
    totalUserMessages: entries.length,
    entries,
    messages,
    loadedFromSeq: entries.length > 0 ? entries[0].seq : null,
    nextCursor: null,
  };
  (window as unknown as { bridge: Pick<Bridge, 'getTaskIndex' | 'listTasksForThread'> }).bridge = {
    getTaskIndex: vi.fn(async () => pageDto) as Bridge['getTaskIndex'],
    listTasksForThread: vi.fn(
      async (): Promise<WorkUnitTaskDto[]> => []) as Bridge['listTasksForThread'],
  };
}

const ALL = [
  entry(10, 'trunk planning prompt'),
  entry(20, 'task prompt one'),
  entry(30, 'task prompt two'),
];

describe('ConvIndex scoping', () => {
  it('restricts the rail to the pane’s own prompt seqs', async () => {
    installBridge(ALL);
    render(
      <ConvIndex
        threadId="t1"
        scrollContainerRef={createRef<HTMLElement>()}
        restrictToSeqs={new Set([20, 30])}
      />,
    );

    // Only the task's own prompts render — the trunk prompt (seq 10) is
    // dropped because it has no row to scroll to in this pane.
    await waitFor(() => {
      expect(screen.getByLabelText('Jump to: task prompt one')).toBeTruthy();
    });
    expect(screen.getByLabelText('Jump to: task prompt two')).toBeTruthy();
    expect(screen.queryByLabelText('Jump to: trunk planning prompt')).toBeNull();
  });

  it('shows the full thread-wide list when unrestricted', async () => {
    installBridge(ALL);
    render(
      <ConvIndex
        threadId="t1"
        scrollContainerRef={createRef<HTMLElement>()}
      />,
    );

    await waitFor(() => {
      expect(screen.getByLabelText('Jump to: trunk planning prompt')).toBeTruthy();
    });
    expect(screen.getByLabelText('Jump to: task prompt one')).toBeTruthy();
    expect(screen.getByLabelText('Jump to: task prompt two')).toBeTruthy();
  });

  it('renders nothing when no pane prompt matches the index', async () => {
    installBridge(ALL);
    const { container } = render(
      <ConvIndex
        threadId="t1"
        scrollContainerRef={createRef<HTMLElement>()}
        restrictToSeqs={new Set([999])}
      />,
    );

    // Give the fetch a tick to resolve, then assert the rail stayed empty.
    await waitFor(() => {
      expect(screen.queryByLabelText(/Jump to:/)).toBeNull();
    });
    expect(container.querySelector('aside')).toBeNull();
  });

  it('keeps long previews collapsed to the short index text on hover', async () => {
    const preview = 'Remove dead endpoints from TaskController...';
    installBridge([entry(40, preview)], [
      message(40, 'Remove dead endpoints from TaskController\n\nUNIQUE FULL PROMPT TAIL'),
    ]);
    const { container } = render(
      <ConvIndex
        threadId="t1"
        scrollContainerRef={createRef<HTMLElement>()}
      />,
    );

    const aside = await waitFor(() => {
      const node = container.querySelector('aside');
      expect(node).toBeTruthy();
      return node as HTMLElement;
    });
    fireEvent.mouseEnter(aside);
    const row = await screen.findByText(preview);
    fireEvent.mouseEnter(row);

    expect(screen.getByText(preview)).toBeTruthy();
    expect(screen.queryByText(/UNIQUE FULL PROMPT TAIL/)).toBeNull();
  });
});

function message(seq: number, text: string): ThreadMessageDto {
  return {
    id: `m-${seq}`,
    threadId: 't1',
    taskId: null,
    seq,
    role: 'user',
    type: 'text',
    contentJson: JSON.stringify({ text }),
    durationMs: null,
    tokensIn: null,
    tokensOut: null,
    costUsdMilli: null,
    ts: '2026-01-01T00:00:00Z',
  };
}
