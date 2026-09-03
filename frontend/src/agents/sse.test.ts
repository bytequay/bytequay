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

import { describe, expect, it, vi } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { AgentEventEnvelope } from './events';
import {
  AgentStreamStatus,
  SseFrameParser,
  belongsToSubscription,
  isTerminalStatus,
  readAgentStream,
} from './sse';

const events = fixture.events as unknown[];

function frame(event: unknown): string {
  const payload = event as { sequence: number; type: string };
  return `id: ${payload.sequence}\nevent: ${payload.type}\ndata: ${JSON.stringify(event)}\n\n`;
}

/** A body that yields exactly the given chunks, so a test controls every split point. */
function body(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
}

function streaming(chunks: string[]): Response {
  return new Response(body(chunks), { status: 200 });
}

interface Run {
  statuses: { status: AgentStreamStatus; detail?: string }[];
  delivered: AgentEventEnvelope[];
  cursors: number[];
}

async function run(
  responses: (after: number) => Promise<Response>,
  options: { after?: number; abortAfter?: number } = {},
): Promise<Run> {
  const result: Run = { statuses: [], delivered: [], cursors: [] };
  const controller = new AbortController();
  await readAgentStream({
    after: options.after,
    signal: controller.signal,
    sleep: async () => {},
    request: async (after) => {
      result.cursors.push(after);
      if (options.abortAfter !== undefined && result.cursors.length > options.abortAfter) {
        controller.abort();
      }
      return responses(after);
    },
    onStatus: (status, detail) => result.statuses.push({ status, detail }),
    onEvents: (batch) => result.delivered.push(...batch),
  });
  return result;
}

describe('SseFrameParser', () => {
  it('reassembles a frame split across chunks', () => {
    const parser = new SseFrameParser();
    const whole = frame(events[0]);

    // A chunk boundary is a property of the network, so every split must be invisible.
    const first = parser.push(whole.slice(0, 12));
    const second = parser.push(whole.slice(12, 40));
    const third = parser.push(whole.slice(40));

    expect(first).toEqual([]);
    expect(second).toEqual([]);
    expect(third).toHaveLength(1);
    expect(JSON.parse(third[0].data).sequence).toBe(1);
  });

  it('splits two frames delivered in one chunk', () => {
    const parser = new SseFrameParser();

    const frames = parser.push(frame(events[0]) + frame(events[1]));

    expect(frames.map((f) => JSON.parse(f.data).sequence)).toEqual([1, 2]);
  });

  it('ignores comments and keeps carriage returns out of values', () => {
    const parser = new SseFrameParser();

    const frames = parser.push(`: keep-alive\r\nid: 7\r\nevent: lifecycle\r\ndata: {"a":1}\r\n\r\n`);

    expect(frames).toEqual([{ id: '7', event: 'lifecycle', data: '{"a":1}' }]);
  });
});

describe('isTerminalStatus', () => {
  it('treats an answer as terminal and an outage as retryable', () => {
    // Authentication, a missing task, and a workspace that does not own it are answers;
    // reconnecting through them would spin forever while looking healthy.
    expect([401, 403, 404, 400].map(isTerminalStatus)).toEqual([true, true, true, true]);
    expect([500, 502, 503, 408, 429].map(isTerminalStatus)).toEqual([false, false, false, false, false]);
  });
});

describe('readAgentStream', () => {
  it('delivers ordered events and reports connecting then open', async () => {
    const result = await run(async () => streaming([frame(events[0]), frame(events[1])]), { abortAfter: 1 });

    expect(result.delivered.map((event) => event.sequence)).toEqual([1, 2]);
    expect(result.statuses.map((entry) => entry.status)).toEqual(['connecting', 'open', 'reconnecting']);
  });

  it("ends without reconnecting once the task's own closing event has been delivered", async () => {
    const lifecycle = events.find((event) => (event as { type: string }).type === 'lifecycle') as Record<string, unknown>;
    const last = (events[events.length - 1] as { sequence: number }).sequence;
    const closing: Record<string, unknown> = {
      ...lifecycle,
      eventId: '00000000-0000-4000-8000-00000000fffe',
      sequence: last + 1,
      turnId: null,
      payload: { phase: 'TASK_COMPLETED', message: 'Task completed' },
    };

    const result = await run(async () => streaming([frame(events[events.length - 1]), frame(closing)]), { after: last - 1 });

    // One request, one open, and no reconnect after the clean close that follows the closing event.
    expect(result.cursors).toEqual([last - 1]);
    expect(result.statuses.map((entry) => entry.status)).toEqual(['connecting', 'open']);
    expect(result.delivered.map((event) => event.sequence)).toEqual([last, last + 1]);
  });

  it('reconnects from the last delivered sequence, not from where the request began', async () => {
    let attempt = 0;
    const result = await run(async () => {
      attempt += 1;
      // The first attempt drops after one event, so the second must resume after it.
      return attempt === 1
        ? streaming([frame(events[0])])
        : streaming([frame(events[1])]);
    }, { abortAfter: 2 });

    expect(result.cursors).toEqual([0, 1, 2]);
    expect(result.delivered.map((event) => event.sequence)).toEqual([1, 2]);
  });

  it('drops a replayed event at or below the cursor instead of failing', async () => {
    let attempt = 0;
    const result = await run(async () => {
      attempt += 1;
      // A backend replaying inclusively would resend event 1; it is a duplicate, not a gap.
      return attempt === 1
        ? streaming([frame(events[0])])
        : streaming([frame(events[0]), frame(events[1])]);
    }, { abortAfter: 2 });

    expect(result.delivered.map((event) => event.sequence)).toEqual([1, 2]);
    expect(result.statuses.some((entry) => entry.status === 'failed')).toBe(false);
  });

  it('fails terminally when the stream is refused', async () => {
    const result = await run(async () => new Response('no', { status: 404 }));

    expect(result.statuses.at(-1)).toEqual({
      status: 'failed',
      detail: 'stream refused with status 404',
    });
    // One attempt only: a refusal is an answer, so it is never retried.
    expect(result.cursors).toEqual([0]);
  });

  it('retries a server outage rather than giving up', async () => {
    let attempt = 0;
    const result = await run(async () => {
      attempt += 1;
      return attempt === 1 ? new Response('down', { status: 503 }) : streaming([frame(events[0])]);
    }, { abortAfter: 2 });

    expect(result.statuses.map((entry) => entry.status)).toContain('reconnecting');
    expect(result.delivered.map((event) => event.sequence)).toEqual([1]);
  });

  it('fails terminally on a payload the renderer cannot represent', async () => {
    const wrongSchema = { ...(events[0] as Record<string, unknown>), schemaVersion: 99 };
    const result = await run(async () => streaming([frame(wrongSchema)]));

    // A schema the contract cannot read will not become readable by asking again.
    expect(result.statuses.at(-1)?.status).toBe('failed');
    expect(result.cursors).toEqual([0]);
  });

  it('stops immediately once aborted', async () => {
    const controller = new AbortController();
    const request = vi.fn(async () => streaming([frame(events[0])]));
    controller.abort();

    await readAgentStream({
      signal: controller.signal,
      request,
      sleep: async () => {},
      onStatus: () => {},
      onEvents: () => {},
    });

    expect(request).not.toHaveBeenCalled();
  });
});

describe('belongsToSubscription', () => {
  it('ignores events still arriving for a screen the renderer has left', () => {
    const current = { subscriptionId: 'sub-2', taskId: 'task-2' };
    const stale = { subscriptionId: 'sub-1', taskId: 'task-1' };

    // Main pushes every task on one channel, so a late event from the previous screen would
    // otherwise render into the new one.
    expect(belongsToSubscription('sub-2', current)).toBe(true);
    expect(belongsToSubscription('sub-2', stale)).toBe(false);
  });
});

describe('a renderer consuming the bridge', () => {
  it('receives ordered events without ever seeing a URL or a session token', async () => {
    // Stands in for window.bridge: the renderer only knows this shape.
    const pushes: AgentEventEnvelope[] = [];
    const bridge = {
      subscribeAgentTask: async (
        _taskId: string,
        after: number,
        listener: (message: { subscriptionId: string; taskId: string; kind: string; events?: AgentEventEnvelope[] }) => void,
      ) => {
        const parsed = await run(async () => streaming([frame(events[0]), frame(events[1])]), { abortAfter: 1, after });
        listener({ subscriptionId: 'sub-1', taskId: 'task-1', kind: 'events', events: parsed.delivered });
        return () => {};
      },
    };

    const unsubscribe = await bridge.subscribeAgentTask('task-1', 0, (message) => {
      if (message.kind === 'events' && message.events) pushes.push(...message.events);
    });
    unsubscribe();

    expect(pushes.map((event) => event.sequence)).toEqual([1, 2]);
    // Nothing the renderer touched carries a base URL, a port, or the session header.
    const seen = JSON.stringify({ bridgeKeys: Object.keys(bridge), pushes });
    expect(seen).not.toContain('X-ByteQuay-Session');
    expect(seen).not.toContain('127.0.0.1');
  });
});
