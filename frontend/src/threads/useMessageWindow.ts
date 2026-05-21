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
import { useCallback, useMemo, useRef, useState } from 'react';
import type { ThreadMessageDto } from '../types';

/** Latest-N messages we render by default. A long-running thread can
 *  accumulate thousands; rendering all of them runs both grouping
 *  passes (groupAndPair / groupToolCalls) and React reconciliation
 *  on every 1s poll while RUNNING, which gets visibly slow past a
 *  few hundred. 150 covers ~15-30 recent turns. */
const WINDOW_DEFAULT = 150;

/** Additional messages revealed per "Load older history" click.
 *  Bigger than the default so a user who's actively scrolling back
 *  doesn't need to click many times. */
const WINDOW_INCREMENT = 200;

export type MessageWindow = {
  /** The slice of {@code messages} the renderer should actually map
   *  over — always the most recent {@code visible.length} items. */
  visible: ThreadMessageDto[];
  /** Total messages currently held in memory. */
  total: number;
  /** True when older messages are hidden behind a load-more click. */
  hasMore: boolean;
  /** How many more messages will be revealed on the next call. */
  nextChunk: number;
  /** Expand the window. Wrap in a scroll-preservation step so the
   *  user's current viewport stays anchored to the same content. */
  loadMore: () => void;
};

/**
 * Caps the rendered message list to the latest N items, with an
 * imperative {@link MessageWindow.loadMore} that bumps the window
 * by {@link WINDOW_INCREMENT}. The hook itself doesn't preserve
 * scroll — the caller wraps loadMore so it can capture scrollHeight
 * before and adjust scrollTop after the next layout pass.
 */
export function useMessageWindow(messages: ThreadMessageDto[]): MessageWindow {
  const [windowSize, setWindowSize] = useState(WINDOW_DEFAULT);
  const total = messages.length;
  const visible = useMemo(() => {
    if (windowSize >= total) return messages;
    return messages.slice(total - windowSize);
  }, [messages, windowSize, total]);
  const loadMore = useCallback(() => {
    setWindowSize(s => s + WINDOW_INCREMENT);
  }, []);
  return {
    visible,
    total,
    hasMore: windowSize < total,
    nextChunk: Math.min(WINDOW_INCREMENT, total - windowSize),
    loadMore,
  };
}

/**
 * Helper to wrap a {@link MessageWindow.loadMore} call so the user's
 * viewport stays anchored. Capture the scroller's current
 * {@code scrollHeight}, run the state bump, then on the next frame
 * shift {@code scrollTop} by the delta so the same content sits
 * under the cursor. Without this the visible messages would shift
 * down by hundreds of pixels when newly-revealed history appears
 * above them.
 */
export function useScrollAnchoredLoadMore(
  loadMore: () => void,
  getScroller: () => HTMLElement | null,
): () => void {
  const pendingRef = useRef<{ beforeHeight: number; beforeTop: number } | null>(null);
  return useCallback(() => {
    const el = getScroller();
    if (el) {
      pendingRef.current = { beforeHeight: el.scrollHeight, beforeTop: el.scrollTop };
    }
    loadMore();
    requestAnimationFrame(() => {
      const after = getScroller();
      const snap = pendingRef.current;
      pendingRef.current = null;
      if (!after || !snap) return;
      after.scrollTop = snap.beforeTop + (after.scrollHeight - snap.beforeHeight);
    });
  }, [loadMore, getScroller]);
}
