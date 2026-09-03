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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentTaskList, creationBlocker } from './AgentTasksView';
import { AgentTaskBody } from './AgentTaskScreen';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { previewTask } from './preview';
import type { AgentTaskDto, AgentTurnDto, ModelOptionDto, ModelSettingsDto } from '../types';

const events = parseAgentEventSequence(fixture.events);

/** A catalog shaped like a probed, ready Codex lane. */
function readyCatalog(models: ModelOptionDto[]): ModelSettingsDto {
  return {
    mainId: 'codex',
    providers: [{
      id: 'codex',
      label: 'Codex',
      readiness: 'READY',
      version: '0.152.1',
      diagnostic: '',
      models,
      selectedModel: models[0]?.id ?? '',
      selectedEffort: 'high',
      main: true,
    }],
    apiKeys: [],
    keyProviders: [],
  };
}

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high', inputModalities: ['text', 'image'] },
  { id: 'text-only', name: 'Text only', description: '', efforts: ['high'], defaultEffort: 'high', inputModalities: ['text'] },
];

/** Everything the list needs beyond its rows, so each test states only what it varies. */
const listProps = {
  laneId: 'codex',
  models: MODELS,
  selection: { model: 'gpt-5.6-codex', effort: 'high' as string | null },
  title: 'New agent task',
  blocker: null as string | null,
  onTitleChange: () => {},
  onSelectModel: () => {},
  onCreate: () => {},
  onOpenTask: () => {},
  onOpenSettings: () => {},
};

function task(id: string, state: string, title: string): AgentTaskDto {
  return { ...previewTask('workspace-1', id), state, title };
}

function turn(state: string, extra: Partial<AgentTurnDto> = {}): AgentTurnDto {
  return {
    id: 'turn-1',
    taskId: 't-1',
    state,
    version: 1,
    createdAt: '2026-08-20T10:00:00Z',
    startedAt: '2026-08-20T10:00:01Z',
    endedAt: null,
    cancellationRequestedAt: null,
    delivered: true,
    settings: null,
    terminalReason: null,
    metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, costProvenance: 'UNAVAILABLE', elapsed: 'PT0S' },
    ...extra,
  };
}

function body(state: Partial<AgentTaskState>, activityChoice: Record<string, boolean> = {}): string {
  return renderToStaticMarkup(
    <AgentTaskBody
      state={{ ...initialAgentTaskState(), ...state }}
      activityChoice={activityChoice}
      models={MODELS}
      onSelectModel={() => {}}
      onBack={() => {}}
      onSend={() => {}}
      onCancel={() => {}}
      onComplete={() => {}}
      onDecide={() => {}}
      onCompose={() => {}}
      onStopServer={() => {}}
      onMarkFailed={() => {}}
      onRetry={() => {}}
    />);
}

const css = readFileSync(resolve(process.cwd(), 'src/css/agents.css'), 'utf8');

/** The declarations of one exact selector, as `property: value` strings. */
function declarations(selector: string): string[] {
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} is missing from agents.css`).toBeGreaterThan(-1);
  return css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
    .split(';')
    .map(declaration => declaration.replace(/\/\*[^]*?\*\//g, '').trim())
    .filter(declaration => declaration !== '');
}

describe('agent task layout', () => {
  it('scrolls the transcript rather than the screen, so the composer stays reachable', () => {
    // A long transcript must move under a fixed header and composer, not push them off-screen.
    expect(declarations('.agt-screen')).toContain('height: 100%');
    expect(declarations('.agt-screen')).toContain('overflow: hidden');
    expect(declarations('.agt-transcript')).toContain('overflow-y: auto');
    expect(declarations('.agt-transcript')).toContain('flex: 1');
    // A flex child will not shrink below its content without this, which re-breaks the scroll.
    expect(declarations('.agt-transcript')).toContain('min-height: 0');
  });
});

describe('agent task list', () => {
  it('separates open from finished and names a reserved checkout', () => {
    const reserved = {
      ...task('t-3', 'OPEN', 'Stuck work'),
      mutationClaim: { kind: 'RECOVERY', ownerId: 'launch-1', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        tasks={[task('t-1', 'OPEN', 'Rename the widget'), task('t-2', 'COMPLETED', 'Old work'), reserved]}
        loading={false}
        error={null}
        />);

    // Asserting the separator too: a JSX text node does not interpret \\u escapes, so a
    // hand-written one renders literally and only an exact match catches it.
    expect(markup).toContain('2 open \u00b7 1 finished');
    expect(markup).not.toContain('u00b7');
    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('New agent task');
    // Wire enums never reach the user.
    expect(markup).toContain('>Open<');
    expect(markup).toContain('>Completed<');
    expect(markup).toContain('>Recovery required<');
    expect(markup).not.toContain('OPEN');
    // The list reads live data now; nothing may call it fixture data.
    expect(markup).not.toContain('Fixture data');
  });

  it('pins the lane and offers the catalog model, not an invented one', () => {
    const markup = renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />);

    // The lane is fixed at creation, so it must not render as a picker.
    expect(markup).toContain('Codex');
    expect(markup).toContain('agt-lane');
    expect(markup).toContain('5.6 Terra');
    expect(markup).toContain('high');
    expect(markup).not.toContain('disabled=""');
  });

  it('refuses to offer creation when the provider has no model to run', () => {
    // The backend rejects a blank model, so an empty catalog must block the button, not the POST.
    const markup = renderToStaticMarkup(
      <AgentTaskList
        {...listProps}
        models={[]}
        selection={null}
        blocker={creationBlocker(readyCatalog([]), 'codex', null)}
        tasks={[]}
        loading={false}
        error={null}
      />);

    expect(markup).toMatch(/<button[^>]*disabled[^>]*>\s*New agent task/);
    expect(markup).toContain('No Codex model is available yet');
    // The catalog is only fetched by a readiness check, so the copy must send them there.
    expect(markup).toContain('Workspace settings');
    expect(markup).toContain('also fetches its models');
    expect(markup).toContain('Open workspace settings');
  });

  it('links a provider that is not ready to the settings that fix it', () => {
    const catalog: ModelSettingsDto = {
      ...readyCatalog(MODELS),
      providers: [{ ...readyCatalog(MODELS).providers[0], readiness: 'AUTHENTICATION_REQUIRED' }],
    };

    const blocker = creationBlocker(catalog, 'codex', { model: 'gpt-5.6-codex', effort: 'high' });

    // The same typed readiness Workspace settings shows, with its remedy, not a generic failure.
    expect(blocker).toContain('Codex is not ready');
    expect(blocker).toContain('Sign in');
    expect(blocker).toContain('codex login');
    // A lane with its own home names the exact command, path included.
    const ownHome: ModelSettingsDto = {
      ...catalog,
      providers: [{ ...catalog.providers[0], signInCommand: 'CODEX_HOME="/x/codex-home" codex login' }],
    };
    expect(creationBlocker(ownHome, 'codex', { model: 'gpt-5.6-codex', effort: 'high' }))
      .toContain('CODEX_HOME="/x/codex-home" codex login');

    // A probe that names its obstacle is repeated verbatim, because the remedy alone is generic.
    const unavailable: ModelSettingsDto = {
      ...catalog,
      providers: [{
        ...catalog.providers[0],
        readiness: 'TEMPORARILY_UNAVAILABLE',
        diagnostic: 'exec-policy rules at ~/.codex/rules would be inherited',
      }],
    };
    expect(creationBlocker(unavailable, 'codex', { model: 'gpt-5.6-codex', effort: 'high' }))
      .toContain('exec-policy rules at ~/.codex/rules');
    expect(creationBlocker(null, 'codex', null)).toContain('Agent providers');
    expect(creationBlocker(readyCatalog(MODELS), 'codex', { model: 'gpt-5.6-codex', effort: 'high' })).toBeNull();
  });

  it('shows loading, empty and error states rather than an unexplained blank list', () => {
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading error={null} />))
      .toContain('Loading agent tasks\u2026');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />))
      .toContain('No agent tasks yet');
    // A live list must not promise fixture behaviour.
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error={null} />))
      .not.toContain('never runs an agent');
    expect(renderToStaticMarkup(
      <AgentTaskList {...listProps} tasks={[]} loading={false} error="backend refused" />))
      .toContain('backend refused');
  });
});

describe('agent task detail', () => {
  it('never leaks a raw escape sequence into rendered text', () => {
    // A JSX text node does not interpret \\u escapes; one written there renders literally.
    // This has slipped through twice, so it is asserted over every state the body can be in.
    const states: Array<Partial<AgentTaskState>> = [
      { events, connection: 'open', task: task('t-1', 'OPEN', 'x') },
      { connection: 'connecting' },
      { connection: 'reconnecting', lastSequence: 7 },
      { connection: 'failed', connectionDetail: 'gone' },
      { connection: 'open', gap: true },
    ];
    for (const state of states) {
      expect(body(state)).not.toMatch(/\\u[0-9a-f]{4}|u00b7|u2014|u2026/);
    }
  });


  it('prints the whole fixture transcript', () => {
    const folded = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' });
    // The fixture's activity group is finished, so it folds to its sentence until it is opened.
    expect(folded).not.toContain('$ npm test');
    const markup = body({ task: task('t-1', 'OPEN', 'Rename the widget'), events, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).toContain('Rename the widget');
    expect(markup).toContain('Review the authentication change');
    expect(markup).toContain('Plan');
    expect(markup).toContain('$ npm test');
    expect(markup).toContain('Approval');
    expect(markup).toContain('in 1200');
    expect(markup).toContain('cost unavailable');
    expect(markup).toContain('Live');
    // A finished group folds to a sentence derived from its typed members.
    expect(markup).toContain('Ran 1 command, edited 1 file, and used 1 tool');
  });

  it('anchors a composer with Send, Stop this turn and idle-only Complete', () => {
    const vision = { ...task('t-1', 'OPEN', 'x'), defaults: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO' } };
    const idle = body({ task: vision, connection: 'open', composer: 'go' });

    expect(idle).toContain('Message the agent');
    expect(idle).toContain('>Send<');
    expect(idle).toContain('Complete');
    // Idle with no turn: Send is live, and only Stop is disabled because there is nothing to stop.
    expect(idle.match(/disabled/g)).toHaveLength(1);
    expect(idle).toMatch(/<button[^>]*disabled[^>]*>Stop this turn</);
    // A disabled Complete has to say why it is disabled.
    expect(body({ connection: 'open' })).toContain('Available when the task is idle');
    // The bounded status line is what assistive technology hears; it names the state, not tokens.
    expect(idle).toContain('aria-live="polite"');
    expect(idle).toContain('>Ready<');
  });

  it('offers the last-resort server stop only while a turn runs', () => {
    const running = body({ task: task('t-1', 'OPEN', 'x'), turn: turn('RUNNING'), connection: 'open' });

    expect(running).toContain('Stop server');
    expect(running).toMatch(/<button[^>]*>Stop this turn</);
    expect(running).not.toMatch(/<button[^>]*disabled[^>]*>Stop this turn</);
    expect(running).toContain('>Running<');
    expect(body({ task: task('t-1', 'OPEN', 'x'), connection: 'open' })).not.toContain('Stop server');
  });

  it('replaces the mutation controls with the recovery card while the checkout is reserved', () => {
    const held = {
      ...task('t-1', 'OPEN', 'x'),
      mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = body({
      task: held,
      turn: turn('FAILED', { terminalReason: 'Codex CLI 0.152.1 finished the turn but proc-1 could not be shown stopped' }),
      connection: 'open',
      composer: 'go',
    });

    expect(markup).toContain('Recovering agent execution');
    expect(markup).toContain('could not be shown to have stopped writing');
    // The diagnostic is bounded to what the backend recorded, and the way out is named.
    expect(markup).toContain('proc-1 could not be shown stopped');
    expect(markup).toContain('Mark task failed');
    expect(markup).toContain('Only this task');
    expect(markup).not.toMatch(/<button[^>]*disabled[^>]*>Stop this task/);
    // Nothing may send, retry or complete until the process is verified gone.
    expect(markup).toMatch(/<button[^>]*disabled[^>]*>Send</);
    expect(markup).toMatch(/<button[^>]*disabled[^>]*>Retry last message</);
    expect(markup).toMatch(/<button[^>]*disabled[^>]*>Complete</);
    expect(markup).toContain('Unlocks after recovery');
    expect(markup).toContain('>Recovering<');
  });

  it('cannot stop a process that predates this run, and says so', () => {
    const reserved = {
      ...task('t-1', 'OPEN', 'x'),
      mutationClaim: { kind: 'RECOVERY', ownerId: 'launch-9', acquiredAt: '2026-08-20T10:00:00Z' },
    };
    const markup = body({ task: reserved, connection: 'open' });

    expect(markup).toContain('from before the restart');
    expect(markup).toMatch(/<button[^>]*disabled[^>]*>Stop this task/);
    expect(markup).toContain('restart ByteQuay');
  });

  it('offers Retry only for an idle task whose last message was delivered and did not complete', () => {
    const failedTurn: Partial<AgentTaskState> = {
      task: task('t-1', 'OPEN', 'x'),
      events,
      connection: 'open',
      turn: turn('FAILED', { terminalReason: 'boom' }),
    };

    expect(body(failedTurn)).toMatch(/<button[^>]*>Retry last message</);
    // Delivery started but was never acknowledged: resending could run the message twice.
    expect(body({ ...failedTurn, turn: turn('INTERRUPTED', { delivered: false, terminalReason: 'lost' }) }))
      .not.toContain('Retry last message');
    expect(body({ ...failedTurn, turn: turn('COMPLETED') })).not.toContain('Retry last message');
  });

  it('shows the kept result instead of a composer once the task is closed', () => {
    const completed: AgentTaskDto = {
      ...task('t-1', 'COMPLETED', 'Shipped'),
      terminalAt: '2026-08-20T10:05:00Z',
      checkoutRemovedAt: '2026-08-20T10:05:01Z',
      summary: {
        finalResponse: 'All done.',
        resultBranch: 'bytequay/task/t-1',
        resultHead: 'abc123',
        tests: [],
        metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, costProvenance: 'UNAVAILABLE', elapsed: 'PT1S' },
        learnings: [],
        notes: null,
        children: [],
      },
    };
    const markup = body({ task: completed, connection: 'open' });

    expect(markup).toContain('bytequay/task/t-1');
    expect(markup).toContain('abc123');
    expect(markup).toContain('the branch remains');
    expect(markup).not.toContain('Message the agent');
    expect(markup).not.toContain('>Complete<');

    const failed = body({
      task: { ...task('t-1', 'FAILED', 'Gave up'), terminalReason: 'Marked failed by the user during recovery' },
      connection: 'open',
    });
    expect(failed).toContain('Marked failed by the user');
    expect(failed).toContain('kept for inspection');
  });

  it('shows the settings each message actually ran with, from its durable turn', () => {
    const firstTurn = events[0].turnId ?? '';
    const ran = turn('COMPLETED', {
      id: firstTurn,
      settings: {
        model: 'gpt-5.6-codex',
        effort: 'medium',
        permissionPolicy: 'AUTO',
        sandboxPolicy: 'WORKSPACE_WRITE',
        maximumRuntimeMillis: 3600000,
        attachments: [],
      },
    });
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open', turns: { [firstTurn]: ran } });

    // The snapshot the turn recorded, not the defaults: a later change must not rewrite history.
    expect(markup).toContain('5.6 Terra medium \u00b7 Auto');
    expect(body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' })).not.toContain('5.6 Terra medium');
  });

  it('folds command output behind a keyboard-operable disclosure', () => {
    const markup = body({ task: task('t-1', 'OPEN', 'x'), events, connection: 'open' }, { '00000000-0000-4000-8000-000000000005': true });

    expect(markup).toContain('<details class="agt-out">');
    expect(markup).toContain('<summary>Output</summary>');
    // Approval buttons carry the request they answer, not just "Allow once".
    expect(markup).toContain('aria-label="Allow once: ');
  });

  it('names the model and policy the next turn will use, not the raw slug', () => {
    const markup = body({
      task: task('t-1', 'OPEN', 'x'),
      connection: 'open',
      effective: {
        model: 'gpt-5.6-codex',
        effort: 'high',
        permissionPolicy: 'AUTO',
        sandboxPolicy: 'WORKSPACE_WRITE',
        maximumRuntimeMillis: 3600000,
        attachments: [],
      },
    });

    expect(markup).toContain('Next turn: 5.6 Terra');
    expect(markup).toContain('high');
    // The backend policy enum is shown as prose; the enum name is not user-facing copy.
    expect(markup).toContain('Auto');
    expect(markup).not.toContain('AUTO');
    // A change lands on the next turn, so the wording must not promise it affects this one.
    const nextTurnLine = markup.slice(markup.indexOf('agt-next'), markup.indexOf('agt-composer'));
    expect(nextTurnLine).toContain('Next turn');
    expect(nextTurnLine).not.toContain('this turn');
  });

  it('offers the image control only for a model that accepts images, and shows drafts and previews', () => {
    const vision = { ...task('t-1', 'OPEN', 'x'), defaults: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO' } };
    const withImages = body({ task: vision, connection: 'open', attachments: [
      { id: 'att-1', name: 'stack-trace.png', mediaType: 'image/png', byteSize: 212 * 1024, width: 800, height: 600 },
    ] });
    expect(withImages).toMatch(/<button[^>]*class="agt-attach"[^>]*title="5.6 Terra accepts images"[^>]*>/);
    expect(withImages).not.toMatch(/<button[^>]*class="agt-attach"[^>]*disabled/);
    expect(withImages).toContain('stack-trace.png · 212 KB');
    expect(withImages).toContain('aria-label="Remove image stack-trace.png"');

    const textOnly = { ...vision, defaults: { ...vision.defaults, model: 'text-only' } };
    const noImages = body({ task: textOnly, connection: 'open' });
    expect(noImages).toMatch(/<button[^>]*class="agt-attach"[^>]*title="Text only does not accept images"[^>]*disabled/);

    // A sent message shows its image once the bytes arrived, and says so when they never will.
    const ready = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: vision, events, connection: 'open' }}
        previews={{ 'attachment-1': { status: 'ready', url: 'blob:preview' } }}
        models={MODELS}
        onSelectModel={() => {}}
        onBack={() => {}}
        onSend={() => {}}
        onCancel={() => {}}
        onComplete={() => {}}
        onDecide={() => {}}
        onCompose={() => {}}
        onStopServer={() => {}}
        onMarkFailed={() => {}}
        onRetry={() => {}}
      />);
    expect(ready).toContain('<img class="agt-image" src="blob:preview" alt="flow.png"');
    const gone = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: vision, events, connection: 'open' }}
        previews={{ 'attachment-1': { status: 'unavailable' } }}
        models={MODELS}
        onSelectModel={() => {}}
        onBack={() => {}}
        onSend={() => {}}
        onCancel={() => {}}
        onComplete={() => {}}
        onDecide={() => {}}
        onCompose={() => {}}
        onStopServer={() => {}}
        onMarkFailed={() => {}}
        onRetry={() => {}}
      />);
    expect(gone).toContain('flow.png · unavailable');
  });

  it('shows subagents as chips, lists them in the panel, and inspects one without a second route', () => {
    const child = (
      type: string,
      payload: Record<string, unknown>,
      threadId = 'child-1',
      label: string | null = 'History tests',
    ) => ({
      ...events[1],
      eventId: `child-${type}-${threadId}-${JSON.stringify(payload).length}`,
      type,
      payload,
      source: {
        ...events[1].source,
        providerThreadId: threadId,
        parentProviderThreadId: 'thread-root',
        logicalAgentPath: label,
      },
    });
    const withAgents = [
      ...events,
      child('lifecycle', { phase: 'AGENT_STARTED', message: 'History tests' }),
      child('assistant_message', { messageKind: 'PROMPT', phase: 'FINAL', text: 'run the persistence tests' }),
      child('assistant_message', { messageKind: 'ANSWER', phase: 'FINAL', text: '3 tests failed' }),
      child('lifecycle', { phase: 'AGENT_STARTED', message: 'Renderer range' }, 'child-2', 'Renderer range'),
    ] as typeof events;
    const state = { task: task('t-1', 'OPEN', 'x'), events: withAgents, connection: 'open' as const };

    const closed = body(state);
    // The parent transcript names the agents; their work is not inlined into it.
    expect(closed).toContain('History tests');
    expect(closed).toContain('started working');
    expect(closed).not.toContain('3 tests failed');
    expect(closed).toContain('Subagents · 2');
    // Two agents are still working, so the barrier says what Send and Complete are waiting for.
    expect(closed).toContain('2 subagents are still working');

    const panel = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), ...state }}
        panelOpen
        models={MODELS}
        onSelectModel={() => {}}
        onBack={() => {}}
        onSend={() => {}}
        onCancel={() => {}}
        onComplete={() => {}}
        onDecide={() => {}}
        onCompose={() => {}}
        onStopServer={() => {}}
        onMarkFailed={() => {}}
        onRetry={() => {}}
      />);
    expect(panel).toContain('aria-label="Subagents"');
    expect(panel).toContain('Active · 2');
    expect(panel).toContain('run the persistence tests');

    const opened = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), ...state }}
        panelOpen
        openAgent="child-1"
        models={MODELS}
        onSelectModel={() => {}}
        onBack={() => {}}
        onSend={() => {}}
        onCancel={() => {}}
        onComplete={() => {}}
        onDecide={() => {}}
        onCompose={() => {}}
        onStopServer={() => {}}
        onMarkFailed={() => {}}
        onRetry={() => {}}
      />);
    // The child's own answer is inspectable, and getting there is a filter, not a route.
    expect(opened).toContain('aria-label="Agent: History tests"');
    expect(opened).toContain('3 tests failed');
    expect(opened).toContain('Back to all subagents');
  });

  it('keeps a cleaned-up agent listed from the summary and says its activity expired', () => {
    const completed: AgentTaskDto = {
      ...task('t-1', 'COMPLETED', 'Shipped'),
      deleteAfter: '2026-08-27T10:05:00Z',
      summary: {
        finalResponse: 'All done.',
        resultBranch: 'bytequay/task/t-1',
        resultHead: 'abc123',
        tests: [],
        metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, costProvenance: 'UNAVAILABLE', elapsed: 'PT1S' },
        learnings: [],
        notes: null,
        children: [{
          threadId: 'child-gone',
          parentThreadId: 'thread-root',
          label: 'Range probe',
          purpose: 'check the selected range',
          outcome: 'COMPLETED',
          result: 'the selection is correct',
          startedAt: '2026-08-20T10:00:00Z',
          endedAt: '2026-08-20T10:02:08Z',
        }],
      },
    };
    const props = {
      models: MODELS,
      onSelectModel: () => {},
      onBack: () => {},
      onSend: () => {},
      onCancel: () => {},
      onComplete: () => {},
      onDecide: () => {},
      onCompose: () => {},
      onStopServer: () => {},
      onMarkFailed: () => {},
      onRetry: () => {},
    };
    const listed = renderToStaticMarkup(
      <AgentTaskBody state={{ ...initialAgentTaskState(), task: completed, connection: 'open' }} panelOpen {...props} />);
    expect(listed).toContain('Range probe');
    expect(listed).toContain('expired');

    const opened = renderToStaticMarkup(
      <AgentTaskBody
        state={{ ...initialAgentTaskState(), task: completed, connection: 'open' }}
        panelOpen
        openAgent="child-gone"
        {...props}
      />);
    // The steps are gone; what was kept is still shown, and nothing is reconstructed.
    expect(opened).toContain('Activity expired');
    expect(opened).toContain('Result — kept');
    expect(opened).toContain('the selection is correct');
  });

  it('says Connecting in the composer until the stream is open', () => {
    expect(body({ connection: 'connecting' })).toContain('placeholder="Connecting\u2026"');
    expect(body({ connection: 'open' })).toContain('placeholder="Message the agent"');
  });

  it('keeps a healthy connection in the header instead of spending a row on it', () => {
    const open = body({ connection: 'open' });
    // Live state is the header dot only; no full-width strip.
    expect(open).toContain('agt-conn-dot is-open');
    expect(open).not.toContain('class="agt-conn ');
    expect(open).toContain('aria-label="Live"');
  });

  it('says it is reconnecting, from where, without claiming anything was lost', () => {
    const markup = body({ connection: 'reconnecting', lastSequence: 214 });

    expect(markup).toContain('agt-conn is-reconnecting');
    // The resume point is the load-bearing part: "catching up" alone asks for faith.
    expect(markup).toContain('catching up from event 214');
    expect(markup).toContain('Nothing is lost');
  });

  it('offers a decision on a pending approval and none on a settled one', () => {
    const markup = body({ events, connection: 'open' });

    // The fixture carries both, so the reviewable state is not only the settled one.
    expect(markup).toContain('Allow once');
    expect(markup).toContain('>Deny<');
    // The asking agent is part of the identity of the request, not decoration.
    expect(markup).toContain('root agent');
    expect(markup).toContain('Approved once');
    expect(markup).toContain('git push origin fix/preview-limit');
    // The scope is what the decision is actually about.
    expect(markup).toContain('cwd: workspaces/jackchen-trino');
  });

  it('surfaces a held gap and a terminal error instead of a silent hole', () => {
    expect(body({ connection: 'open', gap: true })).toContain('waiting for a missing event');

    const failed = body({ connection: 'failed', connectionDetail: 'stream refused with status 404' });
    expect(failed).toContain('agt-conn is-failed');
    expect(failed).toContain('status 404');

    const errored = body({ events, connection: 'open' });
    expect(errored).toContain('agt-error');
    expect(errored).toContain('role="alert"');
  });
});
