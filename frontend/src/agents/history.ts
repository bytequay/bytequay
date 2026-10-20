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

import type { AgentHistoryPageDto } from '../types';
import type { AgentEventEnvelope } from './events';

const MAX_EVENTS = 1000;
const MAX_BYTES = 12 * 1024 * 1024;
const encoder = new TextEncoder();

type Entry = { event: AgentEventEnvelope; bytes: number; textBytes: number };

function entry(event: AgentEventEnvelope): Entry {
  const text = event.type === 'assistant_message' ? event.payload.text : event.type === 'command' ? event.payload.output ?? '' : '';
  return { event, bytes: encoder.encode(JSON.stringify(event)).byteLength, textBytes: encoder.encode(JSON.stringify(text)).byteLength - 2 };
}

function itemKey(event: AgentEventEnvelope): string | null {
  if (event.source.providerItemId === null || (event.type !== 'command' && event.type !== 'assistant_message')) return null;
  return JSON.stringify([event.taskId, event.turnId, event.source.providerThreadId, event.source.providerTurnId,
    event.source.providerItemId, event.type, event.type === 'assistant_message' ? event.payload.messageKind : null]);
}

function commandMetadata(previous: Extract<AgentEventEnvelope, { type: 'command' }>, next: Extract<AgentEventEnvelope, { type: 'command' }>) {
  return {
    ...next.payload,
    argv: next.payload.argv.length > 0 ? next.payload.argv : previous.payload.argv,
    exitCode: next.payload.exitCode ?? previous.payload.exitCode,
    cwd: next.payload.cwd ?? previous.payload.cwd,
    durationMs: next.payload.durationMs ?? previous.payload.durationMs,
    actions: next.payload.actions.length > 0 ? next.payload.actions : previous.payload.actions,
  };
}

function merge(previous: Entry, next: Entry): Entry | null {
  const left = previous.event;
  const right = next.event;
  if (left.type === 'command' && right.type === 'command') {
    const payload = commandMetadata(left, right);
    // A null terminal output means retain the accumulated output, not replace it.
    if ((right.payload.state === 'COMPLETED' || right.payload.state === 'FAILED') && right.payload.output !== null) {
      return entry({ ...right, payload });
    }
    if (left.payload.state !== 'PROGRESS' || right.payload.state !== 'PROGRESS') return null;
    const merged = { ...right, payload: { ...payload, output: '', truncated: left.payload.truncated || right.payload.truncated } };
    const textBytes = previous.textBytes + next.textBytes;
    const bytes = encoder.encode(JSON.stringify(merged)).byteLength + textBytes;
    if (bytes + 2 > MAX_BYTES) return null;
    merged.payload.output = (left.payload.output ?? '') + (right.payload.output ?? '');
    return { event: merged, bytes, textBytes };
  }
  if (left.type === 'assistant_message' && right.type === 'assistant_message') {
    if (right.source.providerEventId !== null || (right.payload.messageKind === 'ANSWER' && right.payload.phase === 'FINAL')) return next;
    if (left.source.providerEventId !== null || left.payload.phase !== 'PARTIAL' || right.payload.phase !== 'PARTIAL') return null;
    const merged = { ...right, payload: { ...right.payload, text: '' } };
    const textBytes = previous.textBytes + next.textBytes;
    const bytes = encoder.encode(JSON.stringify(merged)).byteLength + textBytes;
    if (bytes + 2 > MAX_BYTES) return null;
    merged.payload.text = left.payload.text + right.payload.text;
    return { event: merged, bytes, textBytes };
  }
  return null;
}

function compactEvents(events: AgentEventEnvelope[]): { events: AgentEventEnvelope[]; bytes: number } {
  const entries: Array<Entry | null> = [];
  const items = new Map<string, { first: number; last: number }>();
  for (const event of events) {
    const current = entry(event);
    const key = itemKey(event);
    const item = key === null ? undefined : items.get(key);
    const previous = item === undefined ? null : entries[item.last];
    // Keep the first occurrence in place so tools interleaved with commentary never reorder.
    const merged = item !== undefined && item.first !== item.last && previous !== null ? merge(previous, current) : null;
    if (merged !== null && item !== undefined) entries[item.last] = null;
    const index = entries.push(merged ?? current) - 1;
    if (key !== null) items.set(key, { first: item?.first ?? index, last: index });
  }
  const retained = entries.filter((value): value is Entry => value !== null);
  return { events: retained.map(value => value.event), bytes: 2 + retained.reduce((sum, value) => sum + value.bytes, 0) + Math.max(0, retained.length - 1) };
}

/** Join bounded pages, keeping raw deltas only for the overlapping transport-page cache. */
export function compactHistoryPages(pages: AgentHistoryPageDto[], compactDeltas = true): AgentHistoryPageDto[] {
  const result: AgentHistoryPageDto[] = [];
  const contents = (events: AgentEventEnvelope[]) => compactDeltas ? compactEvents(events)
    : { events, bytes: encoder.encode(JSON.stringify(events)).byteLength };
  for (const page of pages) {
    const compact = contents(page.events);
    const current = { ...page, events: compact.events };
    const previous = result.at(-1);
    const adjacent = previous !== undefined && previous.afterCursor !== null && previous.afterCursor === current.beforeCursor;
    if (adjacent) {
      const combined = contents([...previous.events, ...current.events]);
      if (combined.events.length <= MAX_EVENTS && combined.bytes <= MAX_BYTES) {
        result[result.length - 1] = {
          events: combined.events,
          beforeCursor: previous.beforeCursor,
          afterCursor: current.afterCursor,
          latestSequence: Math.max(previous.latestSequence, current.latestSequence),
        };
        continue;
      }
    }
    result.push(current);
  }
  return result;
}
