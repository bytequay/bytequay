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
import type { ComponentProps } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { parseAgentEventSequence, type AgentEventEnvelope } from '../../agents/events';
import fixture from '../../agents/fixtures/agent-events-v2.json';
import ReviewActivity from './ReviewActivity';
import { reviewFixture, runningReviewFixture } from './fixtures/review';

const events = parseAgentEventSequence(fixture.events).map(event => ({ ...event, turnId: 'turn-1' }));
const props: ComponentProps<typeof ReviewActivity> = {
  review: runningReviewFixture(), events: events.slice(0, 2), turns: {}, models: [],
  pendingApprovals: [], now: Date.parse('2026-09-06T16:04:00Z'), composer: '', busy: false,
  onCompose: () => {}, onSend: () => {}, onSteer: () => {}, onQueue: () => {}, onStop: () => {},
  onDecide: () => {}, onRemoveQueued: () => {},
};
const render = (overrides: Partial<typeof props> = {}) => renderToStaticMarkup(<ReviewActivity {...props} {...overrides} />);
const liveLine = (overrides: Partial<typeof props> = {}) => render(overrides).match(/<div class="agt-live"[^]*?<\/div>/)?.[0];

describe('the unified review conversation', () => {
  it('uses the task live status for thinking, commands, reconnect, approval and completion', () => {
    expect(liveLine()).toContain('>Thinking</span>');
    const command = events.find(event => event.type === 'command');
    if (command?.type !== 'command') throw new Error('Missing command fixture');
    const running: AgentEventEnvelope = { ...command, payload: { ...command.payload, state: 'STARTED', exitCode: null, output: null } };
    expect(liveLine({ events: [...props.events, running] })).toContain('>Running npm test</span>');
    expect(liveLine({ events: [...props.events, command] })).toContain('>Thinking</span>');
    expect(liveLine({ connection: 'reconnecting' })).toContain('>Reconnecting…</span>');
    expect(liveLine({ connection: 'reconnecting' })).not.toContain('is-active');
    expect(liveLine({ review: { ...props.review, pendingApprovals: 1 } })).toContain('>Waiting for approval</span>');
    expect(liveLine({ review: reviewFixture() })).toBeUndefined();
    expect(liveLine({ history: { before: null, after: 'newer', loading: false, onLoad: () => {}, onFollow: () => {} } })).toBeUndefined();
  });

  it('uses the task multiline composer and supported delivery controls', () => {
    const claude = { ...props.review, task: { ...props.review.task, steering: false, nestedAgents: false } };
    const html = render({ review: claude, composer: 'Check the edge case' });
    expect(html).toContain('<textarea');
    expect(html).toContain('class="agt-compose__input"');
    expect(html).toContain('placeholder="Message the agent"');
    expect(html).toMatch(/aria-label="Attach an image"[^>]*disabled=""/);
    expect(html).toMatch(/aria-label="Model and effort for the next message"[^>]*disabled=""/);
    expect(html).toContain('class="agt-steer" disabled=""');
    expect(html).toContain('>Run next</button>');
    expect(html).toContain('aria-label="Stop this turn"');
    expect(html).not.toContain('agr-compose');
    expect(html).not.toContain('no subagents');
    expect(html).not.toContain('Private');
    expect(render()).not.toContain('>Run next</button>');
  });

  it('keeps history but removes execution input during recovery or after closure', () => {
    const html = render({ review: { ...props.review, activeTurn: null }, composer: 'Continue' });
    expect(html).toContain('Recovery required');
    expect(html).toContain('Review the authentication change');
    expect(html).not.toContain('<textarea');
    expect(html).not.toContain('class="agt-live"');
    expect(render({ review: { ...reviewFixture(), state: 'CLOSED' } })).not.toContain('<textarea');
  });

  it('shows actionable approvals whose event is outside the history window', () => {
    const html = render({ events: [], pendingApprovals: [{
      approvalId: 'approval-outside-page', taskId: 'task-1', turnId: 'turn-1',
      source: events[0].source, occurredAt: '2026-09-06T16:03:10Z', title: 'Run verification',
      detail: 'npm test', approvalKind: 'COMMAND', scope: [], availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
    }] });
    expect(html).toContain('aria-label="Allow once: Run verification"');
    expect(html).toContain('>Waiting for approval</span>');
  });

  it('attributes the generated request without attributing the user’s steer to ByteQuay', () => {
    const first = events[0];
    if (first.type !== 'user_message') throw new Error('Missing user fixture');
    const steered: AgentEventEnvelope = { ...first, eventId: 'steered-input', sequence: 3,
      payload: { ...first.payload, text: 'Also check cancellation', deliveryKind: 'STEER' } };
    const html = render({ events: [...props.events, steered] });
    expect(html.match(/ByteQuay · Review request/g)).toHaveLength(1);
    expect(html).toContain('Also check cancellation');
    expect(html).toContain('Steered into the running turn');
  });
});
