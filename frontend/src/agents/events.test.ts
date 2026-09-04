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
import { describe, expect, it } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';

describe('agent event contract', () => {
  it('freezes the workspace-safe task-create request', () => {
    expect(fixture.createTaskRequest).toEqual({
      workspaceId: 'workspace-1',
      message: 'Review the authentication change',
      laneId: 'codex',
      defaults: {
        model: 'gpt-5.6-sol',
        effort: 'high',
        permissionPolicy: 'AUTO',
      },
    });
  });

  it('parses the provider-neutral v2 fixture', () => {
    const events = parseAgentEventSequence(fixture.events);

    expect(events.map(event => event.type)).toEqual([
      'user_message',
      'lifecycle',
      'assistant_message',
      'plan_updated',
      'command',
      'file_change',
      'tool',
      'approval_requested',
      'approval_resolved',
      'approval_requested',
      'error',
      'usage',
      'assistant_message',
      'lifecycle',
    ]);
    const messages = events.filter(event => event.type === 'assistant_message');
    expect(messages.map(event => event.payload.phase)).toEqual(['PARTIAL', 'FINAL']);
    expect(messages.map(event => event.source.providerItemId)).toEqual(['message-1', 'message-1']);
    const approval = events.find(event => event.type === 'approval_requested');
    expect(approval?.payload.actionable).toBe(true);
    expect(approval?.payload.availableDecisions).toEqual(['ALLOW_ONCE', 'DENY']);
  });

  it('accepts completed commentary as distinct from the terminal answer', () => {
    const commentary = structuredClone(fixture.events[2]);
    commentary.sequence = 1;
    commentary.payload.messageKind = 'COMMENTARY';

    const [parsed] = parseAgentEventSequence([commentary]);
    expect(parsed.type === 'assistant_message' && parsed.payload.messageKind).toBe('COMMENTARY');
  });

  it.each(['QUEUE_CHANGED', 'QUEUED_INPUT_STARTED', 'QUEUED_INPUT_PAUSED'])('accepts the queued-input lifecycle signal %s', (phase) => {
    const acknowledged = structuredClone(fixture.events[1]);
    acknowledged.sequence = 1;
    acknowledged.payload.phase = phase;

    const [event] = parseAgentEventSequence([acknowledged]);
    expect(event.type === 'lifecycle' && event.payload.phase).toBe(phase);
  });

  it('reads a task-wide allow, and defaults it off on resolutions stored before it existed', () => {
    const stored = parseAgentEventSequence(fixture.events).find(event => event.type === 'approval_resolved');
    expect(stored?.payload.forTask).toBe(false);
    const offered = structuredClone(fixture.events).map(event => {
      if (event.type === 'approval_requested') {
        return { ...event, payload: { ...event.payload, availableDecisions: ['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY'] } };
      }
      return event.type === 'approval_resolved' ? { ...event, payload: { ...event.payload, forTask: true } } : event;
    });
    const parsed = parseAgentEventSequence(offered);
    expect(parsed.find(event => event.type === 'approval_resolved')?.payload.forTask).toBe(true);
    expect(parsed.find(event => event.type === 'approval_requested')?.payload.availableDecisions)
      .toEqual(['ALLOW_ONCE', 'ALLOW_FOR_TASK', 'DENY']);
  });

  it('rejects a duplicate sequence', () => {
    const duplicate = structuredClone(fixture.events);
    duplicate[5].sequence = duplicate[4].sequence;

    expect(() => parseAgentEventSequence(duplicate)).toThrow('Duplicate or out-of-order event sequence 5');
  });

  it('rejects a sequence gap', () => {
    const gapped = structuredClone(fixture.events);
    gapped[5].sequence++;

    expect(() => parseAgentEventSequence(gapped)).toThrow('Event sequence gap: expected 6, received 7');
  });

  it('rejects a duplicate event id', () => {
    const duplicate = structuredClone(fixture.events);
    duplicate[5].eventId = duplicate[4].eventId;

    expect(() => parseAgentEventSequence(duplicate)).toThrow(`Duplicate eventId ${duplicate[4].eventId}`);
  });

  it('rejects events from another task', () => {
    const mixed = structuredClone(fixture.events);
    mixed[5].taskId = '33333333-3333-4333-8333-333333333333';

    expect(() => parseAgentEventSequence(mixed)).toThrow(
      'Mixed taskId: expected 11111111-1111-4111-8111-111111111111, received 33333333-3333-4333-8333-333333333333');
  });
});
