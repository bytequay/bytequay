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
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConvIndexEntryDto, TaskMessageDto } from '../types';

export type ConvIndexState = {
  /** All loaded user-prompt entries, oldest-first. Grows as the
   *  user clicks "↑ load earlier" or as new prompts stream in. */
  entries: ConvIndexEntryDto[];
  /** Task-wide user-prompt count, for the panel's "N of M" header. */
  total: number;
  /** Smallest seq currently in the loaded window. Used as the
   *  backfill cursor when the user pages older. */
  loadedFromSeq: number | null;
  /** Whether older entries exist that haven't been fetched yet. */
  canLoadMore: boolean;
  /** True during the initial fetch — null state vs loading state. */
  loading: boolean;
  /** True only during a backfill fetch so the panel can show a
   *  spinner / disable the load-more button. */
  loadingMore: boolean;
  /** Error from the last fetch, if any. Reset on success. */
  error: string | null;
  /** Full prompt text keyed by user-message seq. The ConvIndex rail
   *  uses this on hover to show the un-truncated prompt — server-side
   *  {@code entry.preview} is capped at 80 chars, which is fine for
   *  the row label but too short when the user is trying to find a
   *  specific past prompt. Populated from the {@code messages} array
   *  the backend already ships alongside the index entries, so we
   *  don't need a second fetch. */
  fullTextBySeq: Record<number, string>;
};

const INITIAL_LIMIT = 50;
const BACKFILL_LIMIT = 50;

/**
 * Reads the conversation index for a task and keeps it in sync.
 *
 * <p>Live updates piggyback on the existing SSE stream: the parent
 * already subscribes for the agent terminal, so we expose
 * {@link #onUpstreamEvent} that the parent calls on each event. We
 * refetch the tail window on {@code UserMessage} or
 * {@code TurnDone} — both indicate something that could change the
 * user-prompt count or preview. (We don't open a second SSE here to
 * avoid two parallel streams; the parent is the single subscriber.)
 *
 * <p>{@link #loadOlder} fires a backfill against the smallest seq
 * we currently have. Entries are prepended (older-first), keeping
 * oldest-first ordering across the whole list.
 */
export function useConvIndex(taskId: string): ConvIndexState & {
  loadOlder: () => Promise<void>;
  onUpstreamEvent: (eventName: string) => void;
} {
  const [entries, setEntries] = useState<ConvIndexEntryDto[]>([]);
  const [total, setTotal] = useState(0);
  const [loadedFromSeq, setLoadedFromSeq] = useState<number | null>(null);
  const [canLoadMore, setCanLoadMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fullTextBySeq, setFullTextBySeq] = useState<Record<number, string>>({});
  // Mirror state in refs so the SSE-driven refresh and the
  // user-triggered loadOlder don't race against React's state
  // batching — both read the up-to-date cursor synchronously.
  const loadedFromSeqRef = useRef<number | null>(null);
  const loadingMoreRef = useRef(false);
  const taskIdRef = useRef(taskId);

  useEffect(() => {
    taskIdRef.current = taskId;
  }, [taskId]);

  const fetchInitial = useCallback(async (id: string) => {
    setLoading(true);
    setError(null);
    try {
      const page = await window.bridge.getTaskIndex(id, { limit: INITIAL_LIMIT });
      if (taskIdRef.current !== id) return;
      setEntries(prev => mergeEntries(prev, page.entries));
      setTotal(page.totalUserMessages);
      setFullTextBySeq(prev => mergeFullText(prev, page.messages));
      const loadedFromSeq = mergeLoadedFromSeq(loadedFromSeqRef.current, page.loadedFromSeq);
      setLoadedFromSeq(loadedFromSeq);
      loadedFromSeqRef.current = loadedFromSeq;
      setCanLoadMore(loadedFromSeq !== null && loadedFromSeq > 1);
    }
    catch (e) {
      if (taskIdRef.current !== id) return;
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      if (taskIdRef.current === id) {
        setLoading(false);
      }
    }
  }, []);

  // Initial load on task switch. Resets state so a leftover index
  // from a previous task can't briefly render against the new task.
  useEffect(() => {
    setEntries([]);
    setTotal(0);
    setLoadedFromSeq(null);
    loadedFromSeqRef.current = null;
    setCanLoadMore(false);
    setFullTextBySeq({});
    void fetchInitial(taskId);
  }, [taskId, fetchInitial]);

  const loadOlder = useCallback(async () => {
    const cursor = loadedFromSeqRef.current;
    if (cursor === null || loadingMoreRef.current) {
      return;
    }
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const page = await window.bridge.getTaskIndex(taskIdRef.current, {
        cursor,
        limit: BACKFILL_LIMIT,
        direction: 'before',
      });
      // The backfill window is message-based, not prompt-based: a
      // page can contain zero user prompts but still advance the
      // cursor toward older prompt rows. Keep paging based on the
      // returned cursor, not on page.entries.length.
      setEntries(prev => mergeEntries(page.entries, prev));
      setTotal(page.totalUserMessages);
      setFullTextBySeq(prev => mergeFullText(prev, page.messages));
      setLoadedFromSeq(page.loadedFromSeq);
      loadedFromSeqRef.current = page.loadedFromSeq;
      setCanLoadMore(page.nextCursor !== null);
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    finally {
      loadingMoreRef.current = false;
      setLoadingMore(false);
    }
  }, []);

  // Parent SSE handler calls this for every event. We only react to
  // names that could shift the user-prompt count or preview text,
  // and we refetch the tail window so the panel header / current row
  // stay accurate. Keeping the trigger list small avoids re-fetching
  // on every text delta the model emits.
  const onUpstreamEvent = useCallback((eventName: string) => {
    if (eventName === 'UserMessage' || eventName === 'TurnDone') {
      void fetchInitial(taskIdRef.current);
    }
  }, [fetchInitial]);

  return {
    entries,
    total,
    loadedFromSeq,
    canLoadMore,
    loading,
    loadingMore,
    error,
    fullTextBySeq,
    loadOlder,
    onUpstreamEvent,
  };
}

/** Merge newly-arrived user-prompt rows into the existing full-text
 *  map. Only role=user / type=text messages are kept; the rest of the
 *  message stream is rendered by the transcript pane and doesn't
 *  belong on the index rail. */
function mergeFullText(
  current: Record<number, string>,
  messages: TaskMessageDto[],
): Record<number, string> {
  let mutated = false;
  let next: Record<number, string> | null = null;
  for (const m of messages) {
    if (m.role !== 'user' || m.type !== 'text') {
      continue;
    }
    if (current[m.seq] !== undefined) {
      continue;
    }
    const text = extractText(m.contentJson);
    if (text === null) {
      continue;
    }
    if (!mutated) {
      next = { ...current };
      mutated = true;
    }
    (next as Record<number, string>)[m.seq] = text;
  }
  return mutated && next !== null ? next : current;
}

function extractText(contentJson: string): string | null {
  if (!contentJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(contentJson) as { text?: unknown };
    if (typeof parsed.text === 'string') {
      return parsed.text;
    }
    return null;
  }
  catch {
    return null;
  }
}

function mergeEntries(
  left: ConvIndexEntryDto[],
  right: ConvIndexEntryDto[],
): ConvIndexEntryDto[] {
  if (left.length === 0) {
    return right;
  }
  if (right.length === 0) {
    return left;
  }
  const bySeq = new Map<number, ConvIndexEntryDto>();
  for (const entry of left) {
    bySeq.set(entry.seq, entry);
  }
  for (const entry of right) {
    bySeq.set(entry.seq, entry);
  }
  return Array.from(bySeq.values()).sort((a, b) => a.seq - b.seq);
}

function mergeLoadedFromSeq(
  current: number | null,
  incoming: number | null,
): number | null {
  if (current === null) {
    return incoming;
  }
  if (incoming === null) {
    return current;
  }
  return Math.min(current, incoming);
}
