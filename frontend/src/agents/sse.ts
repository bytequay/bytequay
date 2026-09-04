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

import type { AgentStreamMessage } from '../types';
import { AgentEventEnvelope, parseAgentEventSequence } from './events';

/** What the renderer is told about the stream itself, as opposed to its events. */
export type AgentStreamStatus = 'connecting' | 'open' | 'reconnecting' | 'failed';

/** One complete Server-Sent Events frame. */
export interface SseFrame {
  id?: string;
  event?: string;
  data: string;
}

/**
 * Reassembles SSE frames from chunks that split anywhere.
 *
 * A chunk boundary is a property of the network, not the protocol, so a frame can arrive in
 * pieces and two frames can arrive in one piece. Feeding text through here rather than parsing
 * each chunk is what makes that invisible to the caller.
 */
export class SseFrameParser {
  private buffer = '';

  push(chunk: string): SseFrame[] {
    this.buffer += chunk;
    const frames: SseFrame[] = [];
    for (;;) {
      const end = this.nextBoundary();
      if (end === null) {
        return frames;
      }
      const raw = this.buffer.slice(0, end.index);
      this.buffer = this.buffer.slice(end.index + end.length);
      const frame = parseFrame(raw);
      if (frame) {
        frames.push(frame);
      }
    }
  }

  private nextBoundary(): { index: number; length: number } | null {
    const lf = this.buffer.indexOf('\n\n');
    const crlf = this.buffer.indexOf('\r\n\r\n');
    if (lf < 0 && crlf < 0) {
      return null;
    }
    if (crlf >= 0 && (lf < 0 || crlf < lf)) {
      return { index: crlf, length: 4 };
    }
    return { index: lf, length: 2 };
  }
}

function parseFrame(raw: string): SseFrame | null {
  let id: string | undefined;
  let event: string | undefined;
  const data: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    // A line starting with a colon is a comment, which servers send to keep a connection warm.
    if (line.length === 0 || line.startsWith(':')) {
      continue;
    }
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    const rest = separator < 0 ? '' : line.slice(separator + 1);
    const value = rest.startsWith(' ') ? rest.slice(1) : rest;
    if (field === 'id') {
      id = value;
    } else if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      data.push(value);
    }
  }
  return data.length === 0 ? null : { id, event, data: data.join('\n') };
}

/**
 * Whether a response status means retrying can never help.
 *
 * Authentication, a task that does not exist, a workspace that does not own it, and a request the
 * backend rejects outright are all answers, not outages. Reconnecting through them would spin
 * forever while looking healthy.
 */
export function isTerminalStatus(status: number): boolean {
  if (status === 408 || status === 429) {
    return false;
  }
  return status >= 400 && status < 500;
}

export interface AgentStreamOptions {
  /** Opens the stream from an exclusive sequence cursor. */
  request: (after: number, signal: AbortSignal) => Promise<Response>;
  after?: number;
  onStatus: (status: AgentStreamStatus, detail?: string) => void;
  onEvents: (events: AgentEventEnvelope[]) => void;
  signal: AbortSignal;
  /** Overridable so tests do not wait in real time. */
  sleep?: (ms: number) => Promise<void>;
  retryDelayMs?: number;
}

/**
 * Streams one task's events until it is aborted or fails terminally.
 *
 * Reconnects from the last sequence actually delivered rather than from where the request began,
 * so a drop mid-page neither repeats nor skips. Each frame is validated against that cursor by the
 * same parser the replay path uses, so a gap or a schema the renderer cannot read is caught here
 * instead of reaching the UI.
 */
export async function readAgentStream(options: AgentStreamOptions): Promise<void> {
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const retryDelayMs = options.retryDelayMs ?? 1000;
  let cursor = options.after ?? 0;
  let reconnecting = false;
  // The backend completes a terminal task's stream once its closing event is out; after that a
  // clean close is the end, not a drop, and reconnecting would only replay the same close forever.
  let terminal = false;

  for (;;) {
    if (options.signal.aborted) {
      return;
    }
    options.onStatus(reconnecting ? 'reconnecting' : 'connecting');
    let response: Response;
    try {
      response = await options.request(cursor, options.signal);
    } catch (error) {
      if (options.signal.aborted) {
        return;
      }
      reconnecting = true;
      await sleep(retryDelayMs);
      continue;
    }

    if (options.signal.aborted) {
      // Aborted while the request was in flight; announcing an open stream now would be a lie.
      return;
    }
    if (!response.ok) {
      if (isTerminalStatus(response.status)) {
        options.onStatus('failed', `stream refused with status ${response.status}`);
        return;
      }
      reconnecting = true;
      await sleep(retryDelayMs);
      continue;
    }
    if (!response.body) {
      options.onStatus('failed', 'stream response carried no body');
      return;
    }

    options.onStatus('open');
    const parser = new SseFrameParser();
    const decoder = new TextDecoder();
    const reader = response.body.getReader();
    let dropped = false;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          if (terminal) {
            return;
          }
          // A stream that ends without an error before the task has ended is a drop, not a
          // completion: the task is still live and the renderer still wants its events.
          dropped = true;
          break;
        }
        const frames = parser.push(decoder.decode(value, { stream: true }));
        for (const frame of frames) {
          const events = parseFrameEvents(frame, cursor);
          if (events.length === 0) {
            continue;
          }
          cursor = events[events.length - 1].sequence;
          terminal ||= events.some(isTaskTerminalEvent);
          options.onEvents(events);
        }
      }
    } catch (error) {
      if (options.signal.aborted) {
        return;
      }
      // A payload the renderer cannot read will not become readable by asking again.
      if (error instanceof AgentStreamSchemaError) {
        options.onStatus('failed', error.message);
        return;
      }
      dropped = true;
    } finally {
      reader.cancel().catch(() => {});
    }

    if (options.signal.aborted) {
      return;
    }
    if (dropped) {
      reconnecting = true;
      await sleep(retryDelayMs);
    }
  }
}

/** The task's own closing event: a lifecycle event outside any turn that says the task ended. */
export function isTaskTerminalEvent(event: AgentEventEnvelope): boolean {
  return event.type === 'lifecycle'
    && event.turnId === null
    && (event.payload.phase === 'TASK_COMPLETED' || event.payload.phase === 'TASK_FAILED');
}

/**
 * Whether a pushed message belongs to the subscription that is listening.
 *
 * Main pushes every task on one channel, so a screen that has moved on keeps receiving events for
 * the one it left. Extracted from preload, which cannot be imported under the test runner.
 */
export function belongsToSubscription(
  subscriptionId: string,
  message: { subscriptionId: string },
): boolean {
  return message.subscriptionId === subscriptionId;
}

/** Holds pushes that arrive before Electron returns the subscription id that identifies them. */
export function createAgentStreamReceiver(
  listener: (message: AgentStreamMessage) => void,
): { receive: (message: AgentStreamMessage) => void; activate: (subscriptionId: string) => void } {
  let subscriptionId: string | null = null;
  const pending: AgentStreamMessage[] = [];
  const deliver = (message: AgentStreamMessage): void => {
    if (subscriptionId !== null && belongsToSubscription(subscriptionId, message)) listener(message);
  };
  return {
    receive: (message) => {
      if (subscriptionId === null) pending.push(message);
      else deliver(message);
    },
    activate: (id) => {
      subscriptionId = id;
      pending.splice(0).forEach(deliver);
    },
  };
}

/** A frame the renderer's contract cannot represent, which reconnecting cannot fix. */
export class AgentStreamSchemaError extends Error {}

function parseFrameEvents(frame: SseFrame, cursor: number): AgentEventEnvelope[] {
  let payload: unknown;
  try {
    payload = JSON.parse(frame.data);
  } catch {
    throw new AgentStreamSchemaError('stream frame was not JSON');
  }
  // The backend replays from the cursor, so a frame at or below it is a duplicate rather than a
  // gap: drop it instead of failing the stream.
  if (isBelowCursor(payload, cursor)) {
    return [];
  }
  try {
    return parseAgentEventSequence([payload], cursor);
  } catch (error) {
    throw new AgentStreamSchemaError(error instanceof Error ? error.message : 'stream frame was unreadable');
  }
}

function isBelowCursor(payload: unknown, cursor: number): boolean {
  if (typeof payload !== 'object' || payload === null) {
    return false;
  }
  const sequence = (payload as { sequence?: unknown }).sequence;
  return typeof sequence === 'number' && sequence <= cursor;
}
