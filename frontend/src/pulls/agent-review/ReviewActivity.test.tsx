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
import { NO_AGENT_CAPABILITIES } from '../../agents/capabilities';
import { parseAgentReview, type ReviewMessageDto } from './contracts';
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
  it('enables the shared provider and model controls while preserving the running turn', () => {
    const provider = { id: 'codex', label: 'Codex', readiness: 'READY' as const, diagnostic: '',
      selectedModel: 'gpt-review', selectedEffort: 'high', capabilities: NO_AGENT_CAPABILITIES,
      models: [{ id: 'gpt-review', name: 'GPT-Review', description: '', efforts: ['high'], defaultEffort: 'high' }] };
    const review = { ...props.review, task: { ...props.review.task, selectionLaneId: 'codex', model: 'gpt-review', effort: 'high' } };
    const html = render({ review, providers: [provider], onSelectModel: () => {} });
    expect(html).toMatch(/aria-label="Model and effort for the next message"(?![^>]*disabled)[^>]*>/);
    expect(html).toContain('aria-label="Provider: Codex"');
    expect(html).toContain('aria-label="Stop this turn"');
    expect(render({ review, providers: [provider], onSelectModel: () => {}, busy: true }))
      .toMatch(/aria-label="Model and effort for the next message"[^>]*disabled/);
  });

  it('enables steering for a Claude review whose provider supports it', () => {
    const review = { ...props.review, task: { ...props.review.task, laneId: 'claude-cli', laneLabel: 'Claude', steering: true } };
    const html = render({ review, composer: 'Docker started now' });
    expect(html).toMatch(/class="agt-steer"(?![^>]*disabled)[^>]*>Steer<\/button>/);
    expect(html).toContain('>Run next</button>');
  });

  it('offers queued steering without requiring a composer draft', () => {
    const review = { ...props.review, queuedInputIds: ['queued-1'] };
    const html = render({ review, onSteerQueued: () => {} });
    expect(html).toMatch(/class="agt-queue__link"(?![^>]*disabled)[^>]*>Steer<\/button>/);
    expect(render({ review, onSteerQueued: () => {}, busy: true })).toMatch(/class="agt-queue__link"[^>]*disabled[^>]*>Steer<\/button>/);
  });
  it('lets a queue paused by an unsuccessful turn be resumed', () => {
    const review = { ...reviewFixture(), queuedInputIds: ['queued-1'], queuePause: 'The previous turn failed' };
    const html = render({ review, onResumeQueue: () => {} });
    expect(html).toContain('>Resume queue</button>');
    expect(html).toContain('title="The previous turn failed"');
    expect(render({ review: { ...review, queuePause: null }, onResumeQueue: () => {} })).not.toContain('Resume queue');
  });
  it('folds saved local discussions and retains the exact generated prompt for inspection', () => {
    if (props.review.number === null) throw new Error('Fixture requires a published review');
    const first = events[0];
    const html = render({ localReview: {
      threads: [{ id: 'local-review', taskId: props.review.task.id, taskVersion: 1, laneId: 'codex', workspaceId: props.review.workspaceId,
        repo: props.review.repo, number: props.review.number, path: 'src/Example.java', side: 'RIGHT', startLine: null,
        line: 2, baseSha: null, headSha: null, code: 'return value;', resolved: false, version: 1,
        createdAt: first.occurredAt, updatedAt: first.occurredAt,
        messages: [{ id: 'question', author: 'USER', body: 'Explain this return value', createdAt: first.occurredAt,
          turnId: first.turnId, queuedInputId: null, messageEventId: first.eventId, deliveryState: 'COMPLETED', deliveryError: null }] }],
      onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {},
    } });
    expect(html).toContain('data-local-review-thread="local-review"');
    expect(html).toContain('Explain this return value');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('Review the authentication change');
    expect(html).not.toContain('class="agt-user__bubble"');
  });

  it('shares the walking turtle and preserves reconnect, approval and completion states', () => {
    expect(liveLine()).toContain('agt-critter is-moving');
    expect(liveLine()).toContain('aria-label="Agent working"');
    const command = events.find(event => event.type === 'command');
    if (command?.type !== 'command') throw new Error('Missing command fixture');
    const running: AgentEventEnvelope = { ...command, payload: { ...command.payload, state: 'STARTED', exitCode: null, output: null } };
    expect(render({ events: [...props.events, running] }).match(/>Running npm test<\/span>/g)).toHaveLength(1);
    expect(liveLine({ events: [...props.events, running] })).not.toContain('Running npm test');
    expect(liveLine({ events: [...props.events, command] })).toContain('agt-critter is-moving');
    expect(liveLine({ connection: 'reconnecting' })).toContain('>Reconnecting…</span>');
    expect(liveLine({ connection: 'reconnecting' })).not.toContain('is-walking');
    expect(liveLine({ review: { ...props.review, pendingApprovals: 1 } })).toContain('>Waiting for approval</span>');
    expect(liveLine({ review: { ...props.review, pendingApprovals: 1 } })).not.toContain('is-walking');
    expect(liveLine({ review: reviewFixture() })).toBeUndefined();
    expect(liveLine({ history: { before: null, after: 'newer', loading: false, onLoad: () => {}, onFollow: () => {} } })).toBeUndefined();
  });

  it('uses the task multiline composer and supported delivery controls', () => {
    const claude = { ...props.review, task: { ...props.review.task, steering: false, nestedAgents: false } };
    const html = render({ review: claude, composer: 'Check the edge case' });
    expect(html).toContain('<textarea');
    expect(html).toContain('class="agt-compose__input"');
    expect(html).toContain('placeholder="Let&#x27;s ship something"');
    expect(html).toMatch(/aria-label="Attach an image"[^>]*disabled=""/);
    expect(html).toMatch(/aria-label="Model and effort for the next message"[^>]*disabled=""/);
    expect(html).toContain('class="agt-steer" disabled=""');
    expect(html).toContain('>Run next</button>');
    expect(html).toContain('aria-label="Stop this turn"');
    expect(html).not.toContain('agr-compose');
    expect(html).not.toContain('no subagents');
    expect(html).not.toContain('Private — never published');
    expect(render()).not.toContain('>Run next</button>');
  });

  it('keeps history but removes execution input during recovery or after closure', () => {
    const html = render({ review: { ...props.review, activeTurn: null }, composer: 'Continue' });
    expect(html).toContain('Recovery required');
    expect(html).toContain('Review pull request');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('Review the authentication change');
    expect(html).not.toContain('<textarea');
    expect(html).not.toContain('class="agt-live"');
    expect(render({ review: { ...reviewFixture(), state: 'CLOSED' } })).not.toContain('<textarea');
  });

  it('shows actionable approvals whose event is outside the history window', () => {
    const html = render({ events: [], pendingApprovals: [{
      approvalId: 'approval-outside-page', taskId: 'task-1', turnId: 'turn-1',
      source: events[0].source, occurredAt: '2026-09-06T16:03:10Z', title: 'Run verification',
      detail: 'npm test', approvalKind: 'COMMAND', scope: [], sandboxed: true, availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
    }] });
    expect(html).toContain('aria-label="Allow once: Run verification"');
    expect(html).toContain('>Waiting for approval</span>');
  });

  it('marks a pending command that runs outside the sandbox', () => {
    const pending = (sandboxed: boolean) => render({ events: [], pendingApprovals: [{
      approvalId: 'approval-outside-sandbox', taskId: 'task-1', turnId: 'turn-1',
      source: events[0].source, occurredAt: '2026-09-06T16:03:10Z', title: 'Allow Codex command',
      detail: 'mvn test', approvalKind: 'COMMAND', scope: [], sandboxed, availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
    }] });
    expect(pending(false)).toContain('aria-label="Runs outside the sandbox"');
    expect(pending(true)).not.toContain('Runs outside the sandbox');
  });

  it('attributes the generated request without attributing the user’s steer to ByteQuay', () => {
    const first = events[0];
    if (first.type !== 'user_message') throw new Error('Missing user fixture');
    const steered: AgentEventEnvelope = { ...first, eventId: 'steered-input', sequence: 3,
      payload: { ...first.payload, text: 'Also check cancellation', deliveryKind: 'STEER' } };
    const html = render({ events: [...props.events, steered] });
    expect(html.match(/agt-user__request-action">Review pull request/g)).toHaveLength(1);
    expect(html).not.toContain('ByteQuay · Review request');
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain('Review the authentication change');
    expect(html).toContain('Also check cancellation');
    expect(html).toContain('Steered into the running turn');
  });
});


describe('compact review requests', () => {
  const first = events[0];
  if (first.type !== 'user_message') throw new Error('Missing user fixture');
  const prompt = 'Question about internal-card-id: USER TEXT. Reply privately with review_reply. Selected card: duplicate context';
  const questionEvent: AgentEventEnvelope = { ...first, payload: { ...first.payload, text: prompt } };
  const base = reviewFixture();
  const question = { ...base.messages[0], runId: base.runs[0].id, cardId: `${base.runs[0].id}:limitations`,
    body: 'Which Maven commands did you run?\nPlease include the flags.', turnId: first.turnId };

  it('waits for review metadata before rendering an unfamiliar input and shows a known card immediately', () => {
    const event = { ...questionEvent, turnId: 'new-question-turn' };
    const pending = render({ review: base, events: [event], reviewSequence: 0 });
    expect(pending).not.toContain(prompt);
    expect(pending).not.toContain('agt-user__bubble');
    const html = render({ review: { ...base, messages: [{ ...question, turnId: event.turnId }] }, events: [event], reviewSequence: 0 });
    expect(html).toContain('agt-review-request');
    expect(html).toContain(question.body);
    expect(html).not.toContain(prompt);
    // Only a completed review read can establish that an unmatched input is ordinary user text.
    expect(render({ review: base, events: [event], reviewSequence: event.sequence })).toContain(prompt);
  });

  it('opens a question about a finding on the finding card itself, as private threads do', () => {
    const finding = base.findings[0];
    const onFinding: ReviewMessageDto = { ...question, cardId: null, findingId: finding.id, targetRevision: finding.revision };
    const review = { ...base, messages: [onFinding] };
    const html = render({ review, events: [questionEvent], findingCard: value => <article data-finding-card={value.id} /> });
    expect(html).toContain(`Ask about review</span><span class="agt-review-request__topic" title="${finding.label} · ${finding.title}">`);
    expect(html).toContain(`<article data-finding-card="${finding.id}"></article>`);
    expect(html).not.toContain('<div class="agt-review-request__body">Which Maven');
    expect(render({ review, events: [questionEvent] })).toContain('<div class="agt-review-request__body">Which Maven');
  });

  it('renders the saved question and topic after reload without exposing the generated prompt', () => {
    const review = parseAgentReview(JSON.parse(JSON.stringify({ ...base, messages: [question] })));
    const html = render({ review, events: [questionEvent] });
    expect(html).toContain('<article class="agt-user__request agt-review-request">');
    expect(html).toContain('Ask about review');
    expect(html).toContain('Limitations');
    expect(html).toContain(question.body);
    expect(html).toContain('Private · Revision 1 · Reviewed head d9a0c41');
    expect(html).not.toContain('review_reply');
    expect(html).not.toContain('Selected card:');
    expect(html).not.toContain('internal-card-id');
  });

  it('keeps subsequent replies distinct and does not replace unrelated steering or agent replies', () => {
    const reply = { ...question, id: 'follow-up', turnId: 'turn-follow-up', body: 'Why did you choose those flags?' };
    const replyEvent: AgentEventEnvelope = { ...questionEvent, eventId: 'reply-event', turnId: reply.turnId, sequence: 2 };
    const steer: AgentEventEnvelope = { ...questionEvent, eventId: 'steer', turnId: reply.turnId, sequence: 3,
      payload: { ...first.payload, text: 'Also check cancellation', deliveryKind: 'STEER' } };
    const html = render({ review: { ...base, messages: [question, reply] }, events: [questionEvent, replyEvent, steer] });
    expect(html.match(/agt-user__request agt-review-request/g)).toHaveLength(2);
    expect(html).toContain('Reply to review');
    expect(html).toContain(reply.body);
    expect(html).toContain('Also check cancellation');
    expect(html).toContain('Steered into the running turn');
    const ordinary = render({ review: { ...base, messages: [{ ...question, author: 'AGENT' }] }, events: [{ ...questionEvent, turnId: 'ordinary-turn' }] });
    expect(ordinary).toContain(prompt);
    expect(ordinary).not.toContain('agt-review-request');
    const steerOnly = render({ review: { ...base, messages: [reply] }, events: [steer] });
    expect(steerOnly).not.toContain('agt-review-request');
  });

  it('matches a claimed queue message by its durable event even after a retry changes its turn', () => {
    const queued = { ...question, queuedInputId: 'queued-question', messageEventId: first.eventId, turnId: 'retry-turn' };
    const event: AgentEventEnvelope = { ...questionEvent, payload: { ...first.payload, text: prompt, deliveryKind: 'QUEUED' } };
    const html = render({ review: parseAgentReview({ ...base, messages: [queued] }), events: [event], reviewSequence: 0 });
    expect(html).toContain('Ask about review');
    expect(html).toContain(question.body);
    expect(html).not.toContain(prompt);
    const waiting = render({ review: { ...base, messages: [{ ...queued, turnId: null, messageEventId: null }], queuedInputIds: ['queued-question'] }, events: [] });
    expect(waiting).toContain(question.body);
    expect(waiting).not.toContain('agt-review-request');
  });
});
