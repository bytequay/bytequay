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
import { beforeEach, describe, expect, it } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import {
  activeAgents,
  activityExpanded,
  activityPhrase,
  activityRunning,
  activitySummary,
  currentPlan,
  formatDuration,
  planChip,
  planSummary,
  projectAgents,
  projectTranscript,
  turnElapsedMs,
  turnLabel,
  type ActivityBlock,
} from './transcript';

const fixtureEvents = parseAgentEventSequence(fixture.events);

/** Narrows a value the test has already asserted is present, without a non-null assertion. */
function must<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) throw new Error('expected a value');
  return value;
}

const TURN = 'turn-1';
let sequence = 0;

function event(
  type: AgentEventEnvelope['type'],
  payload: Record<string, unknown>,
  options: { itemId?: string; complete?: boolean; at?: string; turnId?: string | null } = {},
): AgentEventEnvelope {
  sequence++;
  return {
    schemaVersion: 2,
    eventId: `event-${sequence}`,
    sequence,
    taskId: 'task-1',
    turnId: options.turnId === undefined ? TURN : options.turnId,
    source: {
      providerThreadId: 'thread-1',
      parentProviderThreadId: null,
      logicalAgentPath: null,
      providerTurnId: 'codex-turn-1',
      providerItemId: options.itemId ?? null,
      providerEventId: options.complete === false ? null : `provider-${sequence}`,
      providerOccurredAt: null,
    },
    occurredAt: options.at ?? `2026-08-20T10:00:${String(sequence).padStart(2, '0')}Z`,
    type,
    payload,
  } as AgentEventEnvelope;
}

/** An event from a subagent's thread: it names the child and the parent that spawned it. */
function childEvent(
  type: AgentEventEnvelope['type'],
  payload: Record<string, unknown>,
  child: string,
  label: string | null = 'History tests',
  parent = 'thread-root',
): AgentEventEnvelope {
  const base = event(type, payload);
  return {
    ...base,
    source: { ...base.source, providerThreadId: child, parentProviderThreadId: parent, logicalAgentPath: label },
  } as AgentEventEnvelope;
}

function command(state: string, extra: Record<string, unknown> = {}, options: Parameters<typeof event>[2] = {}): AgentEventEnvelope {
  return event('command', {
    state,
    argv: [],
    output: null,
    exitCode: null,
    truncated: false,
    cwd: null,
    durationMs: null,
    actions: [],
    ...extra,
  }, options);
}

describe('projecting the transcript', () => {
  beforeEach(() => {
    sequence = 0;
  });

  it('accumulates streamed deltas and lets the completed item replace them', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'I am ' }, { itemId: 'm-1', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'tracing' }, { itemId: 'm-1', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'I am tracing the call.' }, { itemId: 'm-1' }),
    ]);
    expect(turns[0].blocks).toEqual([
      { kind: 'text', id: 'event-1', role: 'commentary', text: 'I am tracing the call.', complete: true },
    ]);
  });

  it('replaces a streamed answer with the authoritative final one and keeps it out of the blocks', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Root ' }, { itemId: 'm-2', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'cause' }, { itemId: 'm-2', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'Root cause found.' }, { itemId: 'm-2' }),
    ]);
    expect(turns[0].blocks).toEqual([]);
    expect(turns[0].answer).toEqual({ id: 'event-1', text: 'Root cause found.', complete: true });
  });

  it('keeps reasoning summaries and plan text as their own readable blocks', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'REASONING_SUMMARY', phase: 'FINAL', text: 'Checked the failing test first.' }, { itemId: 'r-1' }),
      event('assistant_message', { messageKind: 'PLAN_TEXT', phase: 'PARTIAL', text: '1. Reproduce' }, { itemId: 'p-1', complete: false }),
      event('assistant_message', { messageKind: 'PLAN_TEXT', phase: 'PARTIAL', text: '\n2. Fix' }, { itemId: 'p-1', complete: false }),
    ]);
    expect(turns[0].blocks.map((block) => block.kind === 'text' ? [block.role, block.text, block.complete] : block.kind)).toEqual([
      ['reasoning', 'Checked the failing test first.', true],
      ['planText', '1. Reproduce\n2. Fix', false],
    ]);
  });

  it('merges a command by item identity: output deltas accumulate, completion is authoritative', () => {
    const turns = projectTranscript([
      command('STARTED', { argv: ['mvn test'], cwd: 'backend' }, { itemId: 'exec-1' }),
      command('PROGRESS', { output: '[INFO] Running' }, { itemId: 'exec-1', complete: false }),
      command('PROGRESS', { output: ' tests\n' }, { itemId: 'exec-1', complete: false }),
      command('COMPLETED', { argv: ['mvn test'], output: '[INFO] Running tests\n[INFO] BUILD SUCCESS', exitCode: 0, durationMs: 41000, cwd: 'backend' }, { itemId: 'exec-1' }),
    ]);
    const block = turns[0].blocks[0] as ActivityBlock;
    expect(block.members).toHaveLength(1);
    expect(block.members[0]).toMatchObject({
      kind: 'command',
      command: 'mvn test',
      state: 'COMPLETED',
      output: '[INFO] Running tests\n[INFO] BUILD SUCCESS',
      exitCode: 0,
      durationMs: 41000,
      cwd: 'backend',
    });
    expect(block.endedAt).toBe('2026-08-20T10:00:04Z');
  });

  it('groups contiguous activity into one block and starts another after anything else', () => {
    const turns = projectTranscript([
      command('COMPLETED', { argv: ['sed -n 1,40p A.java'], exitCode: 0, actions: [{ kind: 'READ', path: 'A.java', query: null }] }, { itemId: 'exec-1' }),
      event('file_change', { state: 'COMPLETED', relativePath: 'A.java', change: 'MODIFIED', diff: '@@\n-old\n+new\n+more' }, { itemId: 'patch-1' }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Now the tests.' }, { itemId: 'm-3' }),
      command('STARTED', { argv: ['mvn test'] }, { itemId: 'exec-2' }),
    ]);
    const kinds = turns[0].blocks.map((block) => block.kind);
    expect(kinds).toEqual(['activity', 'text', 'activity']);
    const first = turns[0].blocks[0] as ActivityBlock;
    expect(first.members.map((member) => member.kind)).toEqual(['command', 'file']);
    expect(first.members[1]).toMatchObject({ kind: 'file', added: 2, removed: 1 });
    expect(activityRunning(first)).toBe(false);
    expect(activityRunning(turns[0].blocks[2] as ActivityBlock)).toBe(true);
  });

  it('derives the folded sentence from typed facts, so it reads the same from the fixture every time', () => {
    const turns = projectTranscript(fixtureEvents);
    const activity = turns[0].blocks.find((block): block is ActivityBlock => block.kind === 'activity');
    expect(activity).toBeDefined();
    expect(activitySummary(must(activity).members)).toBe('Ran 1 command, edited 1 file, and used 1 tool');
    const again = must(projectTranscript(fixtureEvents)[0].blocks.find((block): block is ActivityBlock => block.kind === 'activity'));
    expect(activitySummary(again.members)).toBe(activitySummary(must(activity).members));
    expect(activitySummary([
      { kind: 'command', id: 'a', command: 'cat x', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [{ kind: 'READ', path: 'x', query: null }, { kind: 'READ', path: 'y', query: null }] },
      { kind: 'command', id: 'b', command: 'cat x', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [{ kind: 'READ', path: 'x', query: null }] },
      { kind: 'command', id: 'c', command: 'mvn test', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [] },
      { kind: 'command', id: 'd', command: 'mvn verify', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [] },
      { kind: 'file', id: 'e', path: 'A.java', change: 'MODIFIED', diff: null, added: 0, removed: 0 },
      { kind: 'file', id: 'f', path: 'B.java', change: 'ADDED', diff: null, added: 0, removed: 0 },
    ])).toBe('Read 2 files, ran 2 commands, and edited 2 files');
    expect(activityPhrase([
      { kind: 'command', id: 'a', command: 'cat x', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [{ kind: 'READ', path: 'x', query: null }] },
      { kind: 'command', id: 'c', command: 'mvn test', state: 'STARTED', output: null, exitCode: null, truncated: false, cwd: null, durationMs: null, actions: [] },
    ])).toBe('reading files and running commands');
  });

  it('replaces the plan in place when a newer plan arrives', () => {
    const turns = projectTranscript([
      event('plan_updated', { items: [{ text: 'Reproduce', state: 'IN_PROGRESS' }, { text: 'Fix', state: 'PENDING' }] }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Working.' }, { itemId: 'm-4' }),
      event('plan_updated', { items: [{ text: 'Reproduce', state: 'COMPLETED' }, { text: 'Fix', state: 'IN_PROGRESS' }] }),
    ]);
    expect(turns[0].blocks.map((block) => block.kind)).toEqual(['plan', 'text']);
    const plan = currentPlan(turns);
    expect(plan?.id).toBe('event-1');
    expect(plan?.steps).toEqual([{ text: 'Reproduce', state: 'COMPLETED' }, { text: 'Fix', state: 'IN_PROGRESS' }]);
    expect(planChip(must(plan).steps)).toBe('Step 2 of 2');
    expect(planSummary(must(plan).steps)).toBe('1 completed · 1 in progress');
    expect(planChip([{ text: 'a', state: 'COMPLETED' }])).toBe('Completed');
  });

  it('runs the live clock only until the turn ends', () => {
    const running = projectTranscript([
      event('lifecycle', { phase: 'TURN_STARTED', message: 'started' }, { at: '2026-08-20T10:00:00Z' }),
    ])[0];
    expect(turnElapsedMs(running, Date.parse('2026-08-20T10:00:38Z'))).toBe(38_000);
    expect(turnLabel(running, Date.parse('2026-08-20T10:00:38Z'), false)).toBe('Turn 1 · Working for 38s');
    expect(turnLabel(running, Date.parse('2026-08-20T10:00:38Z'), true)).toBe('Turn 1 · Waiting on you');

    const ended = projectTranscript([
      event('lifecycle', { phase: 'TURN_STARTED', message: 'started' }, { at: '2026-08-20T10:00:00Z' }),
      event('lifecycle', { phase: 'TURN_COMPLETED', message: 'done' }, { at: '2026-08-20T10:04:51Z' }),
    ])[0];
    // A later clock reading changes nothing once the turn has ended.
    expect(turnElapsedMs(ended, Date.parse('2026-08-20T11:00:00Z'))).toBe(291_000);
    expect(turnLabel(ended, Date.parse('2026-08-20T11:00:00Z'), false)).toBe('Turn 1 · Worked for 4m 51s');

    const interrupted = projectTranscript([
      event('lifecycle', { phase: 'TURN_STARTED', message: 'started' }, { at: '2026-08-20T10:00:00Z' }),
      event('lifecycle', { phase: 'INTERRUPTED', message: 'Backend restarted while the turn was active' }, { at: '2026-08-20T10:00:12Z' }),
    ])[0];
    expect(turnLabel(interrupted, Date.parse('2026-08-20T11:00:00Z'), false)).toBe('Turn 1 · Interrupted after 12s');
    expect(interrupted.blocks).toHaveLength(1);
    expect(interrupted.blocks[0]).toMatchObject({
      kind: 'error', code: 'INTERRUPTED', message: 'Backend restarted while the turn was active', retryable: false,
    });
    expect(formatDuration(3_720_000)).toBe('1h 02m');
  });

  it('keeps a running group open and folds a finished one unless the user chose otherwise', () => {
    const running: ActivityBlock = { kind: 'activity', id: 'g', startedAt: '2026-08-20T10:00:00Z', endedAt: null, members: [
      { kind: 'command', id: 'a', command: 'sleep 5', state: 'STARTED', output: null, exitCode: null, truncated: false, cwd: null, durationMs: null, actions: [] },
    ] };
    const finished: ActivityBlock = { ...running, endedAt: '2026-08-20T10:00:05Z', members: [{ ...running.members[0], state: 'COMPLETED' } as ActivityBlock['members'][number]] };
    expect(activityExpanded(running, undefined)).toBe(true);
    expect(activityExpanded(running, false)).toBe(true);
    expect(activityExpanded(finished, undefined)).toBe(false);
    expect(activityExpanded(finished, true)).toBe(true);
  });

  it('keeps a child out of its parent transcript and shows it as a chip instead', () => {
    const events = [
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Delegating.' }, { itemId: 'm-1' }),
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'History tests' }, 'child-1'),
      childEvent('assistant_message', { messageKind: 'PROMPT', phase: 'FINAL', text: 'run the persistence tests' }, 'child-1'),
      childEvent('command', {
        state: 'COMPLETED', argv: ['mvn test'], output: 'ok', exitCode: 0, truncated: false,
        cwd: null, durationMs: 1000, actions: [],
      }, 'child-1'),
      childEvent('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: '3 tests failed' }, 'child-1'),
      childEvent('lifecycle', { phase: 'AGENT_COMPLETED', message: 'Agent completed' }, 'child-1'),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'The suite is green now.' }, { itemId: 'm-2' }),
    ];

    const root = projectTranscript(events);
    // The child's command and its answer stay in the child's transcript.
    expect(root[0].blocks.map((block) => block.kind)).toEqual(['text', 'agents', 'agents']);
    expect(root[0].answer?.text).toBe('The suite is green now.');
    const chips = root[0].blocks.filter((block) => block.kind === 'agents');
    expect(chips.map((block) => block.kind === 'agents' ? [block.phase, block.threadIds] : null)).toEqual([
      ['started', ['child-1']],
      ['finished', ['child-1']],
    ]);

    // Opening the child reads the same events, filtered; it is not a second transcript.
    const child = projectTranscript(events, 'child-1');
    expect(child[0].blocks.map((block) => block.kind)).toEqual(['text', 'activity']);
    expect(child[0].answer?.text).toBe('3 tests failed');
  });

  it('builds the agent tree with lineage, purpose, outcome and result', () => {
    const events = [
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'History tests' }, 'child-1'),
      childEvent('assistant_message', { messageKind: 'PROMPT', phase: 'FINAL', text: 'run the persistence tests' }, 'child-1'),
      childEvent('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: '3 tests failed' }, 'child-1'),
      childEvent('lifecycle', { phase: 'AGENT_COMPLETED', message: 'done' }, 'child-1'),
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'Renderer range' }, 'child-2', 'Renderer range'),
      // A grandchild names its own parent, not the root.
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'Range probe' }, 'child-3', 'Range probe', 'child-2'),
    ];

    const agents = projectAgents(events);

    expect(agents.map((agent) => [agent.threadId, agent.parentThreadId, agent.label, agent.status])).toEqual([
      ['child-1', 'thread-root', 'History tests', 'completed'],
      ['child-2', 'thread-root', 'Renderer range', 'running'],
      ['child-3', 'child-2', 'Range probe', 'running'],
    ]);
    expect(agents[0].purpose).toBe('run the persistence tests');
    expect(agents[0].result).toBe('3 tests failed');
    // What is still working is what the completion barrier waits for.
    expect(activeAgents(agents).map((agent) => agent.threadId)).toEqual(['child-2', 'child-3']);
  });

  it('marks a child whose history is being recovered, then clears it', () => {
    const recovering = projectAgents([
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'Range probe' }, 'child-9'),
      childEvent('lifecycle', { phase: 'BACKFILL_STARTED', message: 'Recovering' }, 'child-9'),
    ]);
    expect(recovering[0].backfilling).toBe(true);

    const recovered = projectAgents([
      childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'Range probe' }, 'child-9'),
      childEvent('lifecycle', { phase: 'BACKFILL_STARTED', message: 'Recovering' }, 'child-9'),
      childEvent('lifecycle', { phase: 'BACKFILL_COMPLETED', message: 'Recovered' }, 'child-9'),
    ]);
    expect(recovered[0].backfilling).toBe(false);
  });

  it('orders turns by first appearance and folds approvals into their requests', () => {
    const turns = projectTranscript(fixtureEvents);
    expect(turns).toHaveLength(1);
    expect(turns[0].user?.text).toBe('Review the authentication change');
    expect(turns[0].user?.attachments).toEqual([{ id: 'attachment-1', name: 'flow.png', mediaType: 'image/png' }]);
    const approvals = turns[0].blocks.filter((block) => block.kind === 'approval');
    expect(approvals.map((block) => block.kind === 'approval' ? [block.approvalId, block.outcome, block.actionable] : null)).toEqual([
      ['approval-1', 'ALLOWED', false],
      ['approval-2', null, true],
    ]);
    expect(turns[0].answer?.text).toBe('I found one authentication regression.');
    expect(turns[0].outcome).toBe('completed');
    const second = projectTranscript([
      ...fixtureEvents,
      event('user_message', { text: 'Again', attachments: [], deliveryKind: 'TURN' }, { turnId: 'turn-2', itemId: 'user-2' }),
    ]);
    expect(second.map((turn) => turn.ordinal)).toEqual([1, 2]);
  });
});
