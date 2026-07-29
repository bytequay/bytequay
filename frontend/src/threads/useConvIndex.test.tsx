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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Bridge, ConvIndexEntryDto, ConvIndexPageDto } from '../types';
import { useConvIndex } from './useConvIndex';

// React 19 enforces this flag before async act() works.
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

type GetTaskIndex = Bridge['getTaskIndex'];
type GetTaskIndexOptions = Parameters<GetTaskIndex>[1];

afterEach(() => {
  cleanup();
  delete (window as unknown as { bridge?: unknown }).bridge;
});

describe('useConvIndex', () => {
  it('keeps paging through message-only backfill windows', async () => {
    const getTaskIndex = installBridge(async (_id, opts) => {
      if (opts?.direction === 'before' && opts.cursor === 100) {
        return page({
          entries: [],
          loadedFromSeq: 50,
          nextCursor: 50,
          totalUserMessages: 2,
        });
      }
      if (opts?.direction === 'before' && opts.cursor === 50) {
        return page({
          entries: [entry(10, 'first prompt')],
          loadedFromSeq: 1,
          nextCursor: null,
          totalUserMessages: 2,
        });
      }
      return page({
        entries: [entry(120, 'latest prompt')],
        loadedFromSeq: 100,
        nextCursor: 100,
        totalUserMessages: 2,
      });
    });

    render(<Harness />);

    await waitFor(() => {
      expect(text('entries')).toBe('latest prompt');
    });
    expect(text('can-load-more')).toBe('true');

    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('loaded-from-seq')).toBe('50');
    });
    expect(text('entries')).toBe('latest prompt');
    expect(text('can-load-more')).toBe('true');

    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('entries')).toBe('first prompt|latest prompt');
    });
    expect(text('loaded-from-seq')).toBe('1');
    expect(text('can-load-more')).toBe('false');
    expect(getTaskIndex).toHaveBeenCalledWith('thread-1', { limit: 50 });
    expect(getTaskIndex).toHaveBeenCalledWith('thread-1', {
      cursor: 100,
      limit: 50,
      direction: 'before',
    });
    expect(getTaskIndex).toHaveBeenCalledWith('thread-1', {
      cursor: 50,
      limit: 50,
      direction: 'before',
    });
  });

  it('preserves backfilled prompts when the tail refreshes from SSE', async () => {
    let tailVersion = 0;
    installBridge(async (_id, opts) => {
      if (opts?.direction === 'before') {
        return page({
          entries: [entry(10, 'older prompt')],
          loadedFromSeq: 1,
          nextCursor: null,
          totalUserMessages: 3,
        });
      }
      if (tailVersion++ === 0) {
        return page({
          entries: [entry(100, 'original tail')],
          loadedFromSeq: 90,
          nextCursor: 90,
          totalUserMessages: 2,
        });
      }
      return page({
        entries: [entry(101, 'new tail')],
        loadedFromSeq: 91,
        nextCursor: 91,
        totalUserMessages: 3,
      });
    });

    render(<Harness />);

    await waitFor(() => {
      expect(text('entries')).toBe('original tail');
    });

    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('entries')).toBe('older prompt|original tail');
    });

    fireEvent.click(screen.getByText('turn done'));
    await waitFor(() => {
      expect(text('entries')).toBe('older prompt|original tail|new tail');
    });
    expect(text('loaded-from-seq')).toBe('1');
    expect(text('can-load-more')).toBe('false');
  });

  it('keeps legacy prompts before negative typed seqs across paging and refresh', async () => {
    let tailVersion = 0;
    const getTaskIndex = installBridge(async (_id, opts) => {
      if (opts?.direction === 'before') {
        return page({
          entries: [entry(10, 'legacy one'), entry(20, 'legacy two')],
          loadedFromSeq: 10,
          nextCursor: null,
          totalUserMessages: 4,
        });
      }
      const entries = [entry(-3, 'typed one'), entry(-5, 'typed two')];
      if (tailVersion++ > 0) entries.push(entry(-7, 'typed three'));
      return page({
        entries,
        loadedFromSeq: -3,
        nextCursor: -3,
        totalUserMessages: tailVersion > 1 ? 5 : 4,
      });
    });

    render(<Harness />);

    await waitFor(() => {
      expect(text('entries')).toBe('typed one|typed two');
    });
    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('entries')).toBe(
        'legacy one|legacy two|typed one|typed two');
    });
    expect(text('loaded-from-seq')).toBe('10');
    expect(getTaskIndex).toHaveBeenCalledWith('thread-1', {
      cursor: -3,
      limit: 50,
      direction: 'before',
    });

    fireEvent.click(screen.getByText('turn done'));
    await waitFor(() => {
      expect(text('entries')).toBe(
        'legacy one|legacy two|typed one|typed two|typed three');
    });
    expect(text('loaded-from-seq')).toBe('10');
  });

  it('restarts backfill from the tail when a late legacy prefix row is missing', async () => {
    let tailCalls = 0;
    const getTaskIndex = installBridge(async (_id, opts) => {
      if (opts?.direction === 'before') {
        const late = tailCalls > 1;
        return page({
          entries: late
            ? [entry(1, 'legacy first'), entry(101, 'legacy late')]
            : [entry(1, 'legacy first')],
          loadedFromSeq: 1,
          nextCursor: null,
          totalUserMessages: late ? 3 : 2,
        });
      }
      tailCalls++;
      return page({
        entries: [entry(-3, 'typed tail')],
        loadedFromSeq: -3,
        nextCursor: -3,
        totalUserMessages: tailCalls > 1 ? 3 : 2,
      });
    });

    render(<Harness />);
    await waitFor(() => expect(text('entries')).toBe('typed tail'));

    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('entries')).toBe('legacy first|typed tail');
    });
    expect(text('loaded-from-seq')).toBe('1');
    expect(text('can-load-more')).toBe('false');

    fireEvent.click(screen.getByText('turn done'));
    await waitFor(() => {
      expect(text('loaded-from-seq')).toBe('-3');
      expect(text('can-load-more')).toBe('true');
    });
    fireEvent.click(screen.getByText('load older'));
    await waitFor(() => {
      expect(text('entries')).toBe('legacy first|legacy late|typed tail');
    });
    expect(getTaskIndex).toHaveBeenLastCalledWith('thread-1', {
      cursor: -3,
      limit: 50,
      direction: 'before',
    });
  });
});

function Harness() {
  const index = useConvIndex('thread-1');
  return (
    <div>
      <div data-testid="entries">{index.entries.map(e => e.preview).join('|')}</div>
      <div data-testid="loaded-from-seq">{index.loadedFromSeq ?? 'null'}</div>
      <div data-testid="can-load-more">{String(index.canLoadMore)}</div>
      <button type="button" onClick={() => { void index.loadOlder(); }}>
        load older
      </button>
      <button type="button" onClick={() => index.onUpstreamEvent('TurnDone')}>
        turn done
      </button>
    </div>
  );
}

function installBridge(handler: GetTaskIndex) {
  const getTaskIndex = vi.fn((id: string, opts?: GetTaskIndexOptions) => handler(id, opts));
  (window as unknown as { bridge: Pick<Bridge, 'getTaskIndex'> }).bridge = {
    getTaskIndex: getTaskIndex as GetTaskIndex,
  };
  return getTaskIndex;
}

function text(testId: string): string {
  return screen.getByTestId(testId).textContent ?? '';
}

function page({
  entries,
  loadedFromSeq,
  nextCursor,
  totalUserMessages,
}: {
  entries: ConvIndexEntryDto[];
  loadedFromSeq: number | null;
  nextCursor: number | null;
  totalUserMessages: number;
}): ConvIndexPageDto {
  return {
    threadId: 'thread-1',
    totalUserMessages,
    entries,
    messages: [],
    loadedFromSeq,
    nextCursor,
  };
}

function entry(seq: number, preview: string): ConvIndexEntryDto {
  return {
    seq,
    preview,
    tsMs: 1_765_000_000_000 + seq,
  };
}
