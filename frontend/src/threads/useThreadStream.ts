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
import { useEffect, useRef, useState } from 'react';
import type { ThreadStreamEvent } from '../types';
import { type LiveActivity, updateLiveActivities } from './liveActivity';

/** Debounce before pulling canonical messages on a non-delta event —
 *  wide enough to coalesce a burst (tool call + result + turn done) into
 *  one refresh, short enough that the durable message lands promptly. */
const STREAM_REFRESH_DEBOUNCE_MS = 400;

/**
 * Subscribe to the per-thread SSE stream and accumulate the agent's
 * streaming assistant text into {@code liveText} — the in-flight bubble
 * that makes a response appear token-by-token (ChatGPT-style) instead of
 * landing all at once when a poll fires.
 *
 * <p>Deltas are not persisted, so any non-delta event (a tool call, the
 * turn finishing) schedules a debounced pull of the canonical messages
 * via {@code onCanonicalRefresh}; once that lands, the live buffer is
 * cleared so the durable assistant message renders in its place with no
 * duplicate. The channel only opens while the thread is live — a terminal
 * thread emits nothing — and stays open across the parent's polls because
 * it re-subscribes only when {@code threadId} or {@code status} changes.
 */
export type LiveUsage = { tokensIn: number; tokensOut: number };

export function useThreadStream(
  threadId: string,
  status: string | undefined,
  onCanonicalRefresh: () => void | Promise<void>,
): { liveText: string; liveThinking: string; liveUsage: LiveUsage | null; liveActivities: LiveActivity[] } {
  const [liveText, setLiveText] = useState('');
  const liveTextRef = useRef('');
  const [liveThinking, setLiveThinking] = useState('');
  const liveThinkingRef = useRef('');
  const [liveUsage, setLiveUsage] = useState<LiveUsage | null>(null);
  const liveUsageRef = useRef<LiveUsage | null>(null);
  const [liveActivities, setLiveActivities] = useState<LiveActivity[]>([]);
  // Keep the latest refresh callback in a ref so a new closure each render
  // doesn't tear down and re-open the SSE subscription.
  const refreshRef = useRef(onCanonicalRefresh);
  refreshRef.current = onCanonicalRefresh;

  useEffect(() => {
    setLiveActivities([]);
    if (!status || status === 'COMPLETED' || status === 'ARCHIVED' || status === 'ERRORED') return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const flush = () => {
      liveTextRef.current = '';
      setLiveText('');
      liveThinkingRef.current = '';
      setLiveThinking('');
      liveUsageRef.current = null;
      setLiveUsage(null);
    };
    const schedulePing = () => {
      if (timer !== null || disposed) return;
      timer = setTimeout(() => {
        timer = null;
        void Promise.resolve(refreshRef.current()).then(() => {
          if (!disposed) flush();
        });
      }, STREAM_REFRESH_DEBOUNCE_MS);
    };
    const onEvent = (event: ThreadStreamEvent) => {
      setLiveActivities(current => updateLiveActivities(current, event));
      if (event.name === 'AssistantTextDelta') {
        const chunk = typeof event.data.textChunk === 'string' ? event.data.textChunk : '';
        if (chunk.length === 0) return;
        liveTextRef.current += chunk;
        setLiveText(liveTextRef.current);
        return;
      }
      if (event.name === 'ThinkingTextDelta') {
        const chunk = typeof event.data.textChunk === 'string' ? event.data.textChunk : '';
        if (chunk.length === 0) return;
        liveThinkingRef.current += chunk;
        setLiveThinking(liveThinkingRef.current);
        return;
      }
      if (event.name === 'UsageUpdated') {
        // Anthropic splits usage across message_start (input-heavy) and
        // message_delta (growing output); take a running max per field so
        // a later delta that omits input_tokens doesn't drop the count.
        const tIn = typeof event.data.tokensIn === 'number' ? event.data.tokensIn : 0;
        const tOut = typeof event.data.tokensOut === 'number' ? event.data.tokensOut : 0;
        const prev = liveUsageRef.current;
        const merged = {
          tokensIn: Math.max(tIn, prev?.tokensIn ?? 0),
          tokensOut: Math.max(tOut, prev?.tokensOut ?? 0),
        };
        liveUsageRef.current = merged;
        setLiveUsage(merged);
        return;
      }
      schedulePing();
    };
    // The bridge may be absent (test mounts) or lack the stream method —
    // degrade to poll-only rather than throwing on mount.
    const subscribe = typeof window !== 'undefined' ? window.bridge?.subscribeTaskStream : undefined;
    const unsubscribe = typeof subscribe === 'function' ? subscribe(threadId, onEvent) : undefined;
    return () => {
      disposed = true;
      if (timer !== null) clearTimeout(timer);
      flush();
      unsubscribe?.();
    };
  }, [threadId, status]);

  return { liveText, liveThinking, liveUsage, liveActivities };
}
