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
import type { AgentTurnDto } from '../types';
import {
  activeAgents,
  activityRunning,
  activitySummary,
  currentPlan,
  formatDuration,
  planChip,
  planSummary,
  projectAgents,
  projectTranscript,
  settleFinalTurn,
  turnElapsedMs,
  turnLabel,
  withApprovalAuthority,
  type ActivityBlock,
  type CommandAction,
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

  it('does not render the task-level queue refresh signal as transcript content', () => {
    expect(projectTranscript([
      event('lifecycle', { phase: 'QUEUE_CHANGED', message: 'Queued input added' }, { turnId: null }),
    ])).toEqual([]);
  });

  it('accumulates streamed deltas and lets the completed item replace them', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'I am ' }, { itemId: 'm-1', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'tracing' }, { itemId: 'm-1', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'I am tracing the call.' }, { itemId: 'm-1' }),
    ]);
    expect(turns[0].blocks).toEqual([
      { kind: 'text', anchorId: '["thread-1","turn-1","codex-turn-1","m-1"]', id: 'event-1', role: 'commentary', text: 'I am tracing the call.', complete: true },
    ]);
  });

  it('replaces a streamed answer with the authoritative final one and keeps it out of the blocks', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Root ' }, { itemId: 'm-2', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'cause' }, { itemId: 'm-2', complete: false }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'Root cause found.' }, { itemId: 'm-2' }),
    ]);
    expect(turns[0].blocks).toEqual([]);
    expect(turns[0].answer).toEqual({ id: 'event-1', anchorId: '["thread-1","turn-1","codex-turn-1","m-2"]', text: 'Root cause found.', complete: true });
  });

  it('preserves earlier completed commentary when the latest item becomes the terminal answer', () => {
    const turns = projectTranscript([
      event('assistant_message', { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'I will inspect the tests.' }, { itemId: 'm-1' }),
      event('assistant_message', { messageKind: 'COMMENTARY', phase: 'PARTIAL', text: 'The fix is ' }, { itemId: 'm-2', complete: false }),
      event('assistant_message', { messageKind: 'COMMENTARY', phase: 'FINAL', text: 'The fix is ready.' }, { itemId: 'm-2' }),
      event('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'The fix is ready and verified.' }, { itemId: 'm-2' }),
    ]);

    expect(turns[0].blocks).toEqual([
      { kind: 'text', anchorId: '["thread-1","turn-1","codex-turn-1","m-1"]', id: 'event-1', role: 'commentary', text: 'I will inspect the tests.', complete: true },
    ]);
    expect(turns[0].answer).toEqual({ id: 'event-2', anchorId: '["thread-1","turn-1","codex-turn-1","m-2"]', text: 'The fix is ready and verified.', complete: true });
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

  it('shows repeated action metadata once per command throughout streaming and replay', () => {
    const read: CommandAction = { kind: 'READ', name: 'DEVELOPMENT.md', path: 'DEVELOPMENT.md', query: null };
    const search: CommandAction = { kind: 'SEARCH', name: null, path: 'src', query: 'TODO' };
    const argv = ['sed -n 1,100p DEVELOPMENT.md; sed -n 101,200p DEVELOPMENT.md; sed -n 201,300p DEVELOPMENT.md'];
    const events = [
      command('STARTED', { argv, actions: [read, read, read] }, { itemId: 'exec-1' }),
      command('PROGRESS', { output: 'first lines' }, { itemId: 'exec-1', complete: false }),
      command('COMPLETED', { argv, actions: [read, read, read], output: 'all three ranges', exitCode: 0 }, { itemId: 'exec-1' }),
      command('COMPLETED', { argv: ['rg TODO src; rg TODO src'], actions: [search, search], exitCode: 0 }, { itemId: 'exec-2' }),
    ];

    for (const length of [1, 2, 3, 4]) {
      const block = projectTranscript(events.slice(0, length))[0].blocks[0] as ActivityBlock;
      expect(block.members[0]).toMatchObject({ command: argv[0], actions: [read] });
    }
    const block = projectTranscript(events)[0].blocks[0] as ActivityBlock;
    expect(block.members[0]).toMatchObject({ output: 'all three ranges', exitCode: 0 });
    expect(block.members[1]).toMatchObject({ actions: [search] });
    expect(activitySummary(block.members)).toBe('Read 1 file and ran 1 search');
    expect(events[0].payload).toMatchObject({ actions: [read, read, read] });
  });

  it('preserves different action paths and queries and separate calls with the same actions', () => {
    const actions: CommandAction[] = [
      { kind: 'READ', name: 'README.md', path: 'first/README.md', query: null },
      { kind: 'READ', name: 'README.md', path: 'second/README.md', query: null },
      { kind: 'SEARCH', name: null, path: 'src', query: 'TODO' },
      { kind: 'SEARCH', name: null, path: 'test', query: 'TODO' },
      { kind: 'SEARCH', name: null, path: 'src', query: 'FIXME' },
    ];
    const block = projectTranscript([
      command('COMPLETED', { actions }, { itemId: 'exec-1' }),
      command('COMPLETED', { actions }, { itemId: 'exec-2' }),
    ])[0].blocks[0] as ActivityBlock;

    expect(block.members).toHaveLength(2);
    for (const member of block.members) expect(member).toMatchObject({ actions });
    expect(activitySummary(block.members)).toBe('Read 2 files and ran 6 searches');
  });

  it('derives the folded sentence from typed facts, so it reads the same from the fixture every time', () => {
    const turns = projectTranscript(fixtureEvents);
    const activity = turns[0].blocks.find((block): block is ActivityBlock => block.kind === 'activity');
    expect(activity).toBeDefined();
    expect(activitySummary(must(activity).members)).toBe('Ran 1 command, edited 1 file, and used 1 tool');
    const again = must(projectTranscript(fixtureEvents)[0].blocks.find((block): block is ActivityBlock => block.kind === 'activity'));
    expect(activitySummary(again.members)).toBe(activitySummary(must(activity).members));
    expect(activitySummary([
      { kind: 'command', id: 'a', command: 'cat x', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [{ kind: 'READ', path: 'x', query: null }, { kind: 'READ', path: 'y', query: null }], description: null },
      { kind: 'command', id: 'b', command: 'cat x', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [{ kind: 'READ', path: 'x', query: null }], description: null },
      { kind: 'command', id: 'c', command: 'mvn test', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [], description: null },
      { kind: 'command', id: 'd', command: 'mvn verify', state: 'COMPLETED', output: null, exitCode: 0, truncated: false, cwd: null, durationMs: null, actions: [], description: null },
      { kind: 'file', id: 'e', path: 'A.java', change: 'MODIFIED', diff: null, added: 0, removed: 0 },
      { kind: 'file', id: 'f', path: 'B.java', change: 'ADDED', diff: null, added: 0, removed: 0 },
    ])).toBe('Read 2 files, ran 2 commands, and edited 2 files');
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

  it('clears a plan and never presents a previous turn as the current plan', () => {
    const planned = event('plan_updated', { items: [{ text: 'Reproduce', state: 'IN_PROGRESS' }] });
    expect(currentPlan(projectTranscript([planned, event('plan_updated', { items: [] })]))?.steps).toEqual([]);
    const turns = projectTranscript([planned, event('user_message', { text: 'Explain the fix', attachments: [], deliveryKind: 'TURN' }, { turnId: 'turn-2' })]);
    expect(currentPlan(turns)).toBeNull();
    expect(turns[0].blocks.some(block => block.kind === 'plan')).toBe(true);
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
    expect(turns[0].users[0].text).toBe('Review the authentication change');
    expect(turns[0].users[0].attachments).toEqual([{ id: 'attachment-1', name: 'flow.png', mediaType: 'image/png' }]);
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

  it('keeps every message sent into one running turn in sequence order', () => {
    const turns = projectTranscript([
      event('user_message', { text: 'Start here', attachments: [], deliveryKind: 'TURN' }),
      event('user_message', { text: 'Also check the tests', attachments: [], deliveryKind: 'STEER' }),
      event('user_message', { text: 'And the docs', attachments: [], deliveryKind: 'STEER' }),
    ]);

    expect(turns[0].users.map((user) => [user.text, user.delivery])).toEqual([
      ['Start here', 'TURN'],
      ['Also check the tests', 'STEER'],
      ['And the docs', 'STEER'],
    ]);
  });
});


describe('bounded transcript windows', () => {
  const completedTurn: AgentTurnDto = {
    id: TURN,
    taskId: 'task-1',
    state: 'COMPLETED',
    version: 1,
    createdAt: '2026-08-20T10:00:00Z',
    startedAt: '2026-08-20T10:00:00Z',
    endedAt: '2026-08-20T10:05:00Z',
    cancellationRequestedAt: null,
    delivered: true,
    knownUnsent: false,
    settings: null,
    terminalReason: null,
    metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT5M' },
  };

  it.each(['COMPLETED', 'FAILED', 'CANCELLED'])('does not keep page-local activity live after the durable turn is %s', (state) => {
    const events = [
      command('PROGRESS', { output: 'Compiling files\n' }, { itemId: 'long-build' }),
      event('tool', { name: 'Tool', state: 'STARTED', summary: 'Inspecting', durationMs: null }, { itemId: 'tool' }),
      command('FAILED', { exitCode: 1 }, { itemId: 'failed' }),
      command('COMPLETED', { exitCode: 0 }, { itemId: 'done' }),
      event('assistant_message', { messageKind: 'COMMENTARY', phase: 'PARTIAL', text: 'Earlier text' }, { itemId: 'text', complete: false }),
    ];
    const turn = projectTranscript(events, null, { [TURN]: { ...completedTurn, state } }, false)[0];
    const activity = turn.blocks[0] as ActivityBlock;

    expect(turn.endedAt).toBe(completedTurn.endedAt);
    expect(activityRunning(activity)).toBe(false);
    expect(activity.members).toMatchObject([
      { kind: 'command', state: 'UNKNOWN', output: 'Compiling files\n', exitCode: null },
      { kind: 'tool', state: 'UNKNOWN' },
      { kind: 'command', state: 'FAILED', exitCode: 1 },
      { kind: 'command', state: 'COMPLETED', exitCode: 0 },
    ]);
    expect(activity.endedAt).toBeNull();
    expect(turn.blocks[1]).toMatchObject({ kind: 'text', complete: false, historical: true, text: 'Earlier text' });
    expect(events[0].payload).toMatchObject({ state: 'PROGRESS' });
  });

  it('keeps historical progress neutral while another page holds the actual command outcome', () => {
    const progress = command('PROGRESS', { output: 'Compiling\n' }, { itemId: 'build' });
    const completion = command('FAILED', { output: 'Compile failed\n', exitCode: 1 }, { itemId: 'build' });
    const runningTurn: AgentTurnDto = { ...completedTurn, state: 'RUNNING', endedAt: null };
    const project = (events: AgentEventEnvelope[], atLatest: boolean): ActivityBlock =>
      projectTranscript(events, null, { [TURN]: runningTurn }, false, atLatest)[0].blocks[0] as ActivityBlock;

    expect(activityRunning(project([progress], true))).toBe(true);
    expect(activityRunning(project([progress], false))).toBe(false);
    expect(project([progress], false).members[0]).toMatchObject({ state: 'UNKNOWN', exitCode: null });
    expect(project([progress, completion], true).members[0]).toMatchObject({ state: 'FAILED', output: 'Compile failed\n', exitCode: 1 });
  });

  it('uses retained turn completion and terminal task facts without inventing an item outcome', () => {
    const progress = command('STARTED', { argv: ['build'] }, { itemId: 'build' });
    const completion = event('lifecycle', { phase: 'TURN_COMPLETED', message: 'done' });
    const lifecycleTurn = projectTranscript([progress, completion])[0];
    const terminalTaskTurn = settleFinalTurn(projectTranscript([progress])[0], completedTurn.endedAt, true);
    for (const turn of [lifecycleTurn, terminalTaskTurn]) {
      const activity = turn.blocks[0] as ActivityBlock;
      expect(activityRunning(activity)).toBe(false);
      expect(activity.members[0]).toMatchObject({ state: 'UNKNOWN', exitCode: null });
    }
    const partialAnswer = { ...terminalTaskTurn, answer: { id: 'answer', text: 'Partial answer', complete: false } };
    expect(settleFinalTurn(partialAnswer, completedTurn.endedAt, true).answer).toMatchObject({ complete: false, historical: true });
  });

  it('does not keep child items live after child or durable root completion', () => {
    const progress = childEvent('tool', { name: 'Inspect', state: 'STARTED', summary: '', durationMs: null }, 'child');
    const completion = childEvent('lifecycle', { phase: 'AGENT_COMPLETED', message: 'done' }, 'child');
    const childEnded = projectTranscript([progress, completion], 'child')[0];
    const rootEnded = projectTranscript([progress], 'child', { [TURN]: completedTurn })[0];
    for (const turn of [childEnded, rootEnded]) {
      const activity = turn.blocks[0] as ActivityBlock;
      expect(activityRunning(activity)).toBe(false);
      expect(activity.members[0]).toMatchObject({ state: 'UNKNOWN' });
    }
    const restarted = childEvent('lifecycle', { phase: 'AGENT_STARTED', message: 'follow-up' }, 'child');
    const resumed = projectTranscript([completion, restarted, progress], 'child')[0];
    expect(resumed.endedAt).toBeNull();
    expect(activityRunning(resumed.blocks[0] as ActivityBlock)).toBe(true);
  });

  it('omits invented turn numbers and keeps item anchors stable when older deltas arrive', () => {
    const first = event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Earlier ' }, { itemId: 'streamed', complete: false });
    const later = event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'later' }, { itemId: 'streamed', complete: false });
    const tail = projectTranscript([later], null, {}, false)[0];
    const expanded = projectTranscript([first, later], null, {}, false)[0];
    expect(tail.ordinal).toBe(0);
    expect(turnLabel(tail, 0, false)).toBe('Turn');
    expect(tail.blocks[0]).toMatchObject({ anchorId: '["thread-1","turn-1","codex-turn-1","streamed"]', text: 'later' });
    expect(expanded.blocks[0]).toMatchObject({ anchorId: '["thread-1","turn-1","codex-turn-1","streamed"]', text: 'Earlier later' });
  });

  it('keeps the final answer anchor when an older partial for the same item loads', () => {
    const partial = event('assistant_message', { messageKind: 'ANSWER', phase: 'PARTIAL', text: 'Partial' }, { itemId: 'answer', complete: false });
    const final = event('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: 'Final answer' }, { itemId: 'answer' });
    const tail = projectTranscript([final])[0].answer;
    const expanded = projectTranscript([partial, final])[0].answer;
    expect(tail?.id).not.toBe(expanded?.id);
    expect(tail?.anchorId).toBe('["thread-1","turn-1","codex-turn-1","answer"]');
    expect(tail?.anchorId).toBe(expanded?.anchorId);
  });

  it('never enables a historical root or child approval whose resolution is outside the page', () => {
    const request = fixtureEvents.find((entry) => entry.type === 'approval_requested');
    if (request === undefined) throw new Error('missing approval fixture');
    const child = { ...request, source: { ...request.source, providerThreadId: 'child', parentProviderThreadId: 'root' } };
    const root = withApprovalAuthority(projectTranscript([request]), new Set());
    const nested = withApprovalAuthority(projectTranscript([child], 'child'), new Set());
    for (const turns of [root, nested]) expect(turns[0].blocks[0]).toMatchObject({ kind: 'approval', actionable: false, outcome: 'Resolution outside this history window' });
    expect(withApprovalAuthority(projectTranscript([request]), new Set([request.payload.approvalId]))[0].blocks[0])
      .toMatchObject({ actionable: true, outcome: null });
  });

  it('does not infer a running child from an incomplete historical window', () => {
    const sample = fixtureEvents[0];
    const child = { ...sample, turnId: 'old-turn', source: { ...sample.source, providerThreadId: 'child', parentProviderThreadId: 'root' } };
    expect(projectAgents([child], 'current-turn', false)[0].status).toBe('unknown');
    expect(activeAgents(projectAgents([child], 'current-turn', false))).toEqual([]);
  });
});
