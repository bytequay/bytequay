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

import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AgentProviderStatus, AgentQueuedInputDto, AgentTaskDto, AgentTurnDto, ModelOptionDto } from '../types';
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';
import { latestStep, TurnView } from './AgentConversation';
import { draftPullRequestRequest } from './DevelopmentFacts';
import { ciActivity, ciAutofixRequest, ciMessage, ciMessageUnavailable } from './ciAutofix';
import { parseAgentDevelopmentCi, parseAgentTask, parseAgentTurn, parseCiAutofixRun } from './contracts';
import { taskStatePill } from './taskStatus';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import { agentTurn, previewTask } from './fixtures/previewBridge';
import { renderTaskBody } from './fixtures/taskBody';
import { NO_AGENT_CAPABILITIES } from './capabilities';
import { CI_PROJECTION, CI_RUN } from './fixtures/ciAutofix';
import contracts from './fixtures/agent-contracts-v1.json';
import fixture from './fixtures/agent-events-v2.json';
import { projectTranscript, type CommandAction, type TranscriptTurn } from './transcript';
import type { LocalReviewMessageDto, LocalReviewThreadDto } from '../pulls/local-review/types';

describe('the queue behind a running turn', () => {
  // The fixture's open approval would put the header on 'waiting on you'; the count needs a plain running turn.
  const events = parseAgentEventSequence(fixture.events).filter((event) => event.type !== 'approval_requested');

  const MODELS: ModelOptionDto[] = [
    { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high', inputModalities: ['text', 'image'] },
  ];

  const turn = (state: string): AgentTurnDto =>
    agentTurn(state, { endedAt: state === 'RUNNING' ? null : '2026-08-20T10:00:09Z' });

  function queued(id: string, state: string, preview: string, extra: Partial<AgentQueuedInputDto> = {}): AgentQueuedInputDto {
    return {
      id,
      taskId: 't-1',
      position: Number(id.replace(/\D/g, '')),
      state,
      preview,
      settings: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO', maximumRuntimeMillis: 60000, attachments: [] },
      turnId: null,
      pauseReason: null,
      createdAt: '2026-08-20T10:00:02Z',
      updatedAt: '2026-08-20T10:00:02Z',
      ...extra,
    };
  }

  function body(state: Partial<AgentTaskState>, providerStatus: AgentProviderStatus | null = null): string {
    return renderTaskBody({ task: { ...previewTask('workspace-1', 't-1'), state: 'OPEN' }, connection: 'open', ...state }, {
      models: MODELS, providerStatus, onEditQueued: () => {},
    });
  }

  it('steers and edits queued messages while preserving drafts in Chromium', async () => {
    const result = await runBrowserFixture<string>({ prefix: 'bytequay-queue-', entry: 'src/agents/fixtures/queueBrowser.tsx',
      global: 'QueueTests', styles: true, charset: true, width: 1280, height: 800, timeoutMs: 30_000 });
    expect(result).toContain('queued steering preserves drafts');
    expect(result).toContain('queued editing restores full drafts');
  }, deadline(45_000));
  it('lists queued messages under the composer with their settings', () => {
    const markup = body({
      turn: turn('RUNNING'),
      events,
      queue: [queued('q-1', 'QUEUED', 'Then update the design doc'), queued('q-2', 'QUEUED', 'Open a draft pull request', { settings: { model: 'gpt-5.6-codex', effort: 'medium', permissionPolicy: 'AUTO', maximumRuntimeMillis: 60000, attachments: [{ id: 'a-1', name: 'shot.png', mediaType: 'image/png' }] } })],
    });

    expect(markup).toContain('class="agt-queue is-attached"');
    expect(markup).toContain('Then update the design doc');
    expect(markup).toContain('Open a draft pull request');
    expect(markup).toContain('5.6 Terra medium');
    expect(markup).toContain('1 image');
    // Each waiting row can be removed.
    expect(markup.match(/aria-label="Remove from queue"/g)).toHaveLength(2);
    expect(markup.match(/aria-label="Edit queued message"/g)).toHaveLength(2);
    // Queued text is not in the transcript: only the queue shows it until it runs.
    expect(markup).not.toContain('agt-user__bubble">Then update');
  });

  it('shows a paused queue as bordered rows with a resume link only on the head, once the task is idle', () => {
    const paused = body({
      turn: turn('FAILED'),
      queue: [
        queued('q-1', 'PAUSED', 'First paused', { pauseReason: 'Turn failed: provider crashed' }),
        queued('q-2', 'PAUSED', 'Second paused', { pauseReason: 'Turn failed: provider crashed' }),
      ],
    });

    expect(paused).toContain('class="agt-queue"');
    expect(paused.match(/>Paused</g)).toHaveLength(2);
    expect(paused).toContain('title="Turn failed: provider crashed"');
    expect(paused.match(/>Resume queue</g)).toHaveLength(1);
    expect(paused.indexOf('First paused')).toBeLessThan(paused.indexOf('Resume queue'));
    expect(paused.indexOf('Resume queue')).toBeLessThan(paused.indexOf('Second paused'));

    // While a turn runs, a paused row waits without offering to resume.
    const running = body({ turn: turn('RUNNING'), queue: [queued('q-1', 'PAUSED', 'Later', { pauseReason: 'Backend restarted' })] });
    expect(running).not.toContain('Resume queue');

    const unavailable = body({
      turn: turn('FAILED'),
      queue: [queued('q-1', 'PAUSED', 'Wait for the provider', { pauseReason: 'Provider unavailable' })],
    }, {
      laneId: 'claude',
      readiness: 'TEMPORARILY_UNAVAILABLE',
      diagnostic: 'readiness probe timed out',
      capabilities: NO_AGENT_CAPABILITIES,
      signInCommand: null,
    });
    expect(unavailable).toMatch(/class="agt-queue__link"[^>]*disabled[^>]*title="Check provider readiness before resuming the queue"/);
    expect(unavailable).toContain('Claude is unavailable');
  });

  it('marks an uncertain delivery, offers only Discard, and never a resend', () => {
    const markup = body({
      turn: turn('INTERRUPTED'),
      queue: [queued('q-1', 'UNCERTAIN', 'Re-run the failing suite once more', { pauseReason: 'Backend restarted while the input was being sent', turnId: 'turn-9' })],
    });

    expect(markup).toContain('class="agt-queue__row is-uncertain"');
    expect(markup).toContain('>Delivery uncertain<');
    expect(markup).toContain('never re-sends by itself');
    expect(markup).toContain('>Discard<');
    expect(markup).not.toContain('Resume queue');
    expect(markup).not.toContain('aria-label="Remove from queue"');
    expect(markup).not.toContain('aria-label="Edit queued message"');
  });

  it('blocks Complete for live queue work but lets an uncertain row reach confirmation', () => {
    for (const state of ['QUEUED', 'PAUSED', 'STARTING']) {
      const waiting = body({ turn: turn('COMPLETED'), queue: [queued('q-1', state, 'Still waiting')] });
      expect(waiting).toMatch(/class="agt-headbtn"[^>]*aria-label="Mark it complete"[^>]*disabled/);
      expect(waiting).toContain('Run or remove every waiting queued message first');
    }

    const uncertain = body({ turn: turn('COMPLETED'), queue: [queued('q-1', 'UNCERTAIN', 'Maybe sent')] });
    expect(uncertain).not.toMatch(/class="agt-headbtn"[^>]*aria-label="Mark it complete"[^>]*disabled/);
  });

  it('shows a starting row with a spinner and nothing to press', () => {
    const markup = body({ turn: turn('RUNNING'), queue: [queued('q-1', 'STARTING', 'Starting now', { turnId: 'turn-2' })] });

    expect(markup).toContain('>Starting<');
    expect(markup).toMatch(/class="[^"]*agt-spin[^"]*"/);
    expect(markup).not.toContain('aria-label="Remove from queue"');
    expect(markup).not.toContain('aria-label="Edit queued message"');
    expect(markup).not.toContain('>Discard<');
  });

  it('offers queued steering with an empty composer only for unclaimed inputs on a steering lane', () => {
    const state = { turn: turn('RUNNING'), composer: '', capabilities: { ...NO_AGENT_CAPABILITIES, steering: true },
      queue: [queued('q-1', 'QUEUED', 'Send this now')] };
    expect(body(state)).toMatch(/class="agt-queue__link"(?![^>]*disabled)[^>]*>Steer<\/button>/);
    expect(body({ ...state, sending: true })).toMatch(/class="agt-queue__link"[^>]*disabled[^>]*>Steer<\/button>/);
    expect(body({ ...state, capabilities: NO_AGENT_CAPABILITIES })).toMatch(/class="agt-queue__link"[^>]*disabled[^>]*>Steer<\/button>/);
    for (const inputState of ['PAUSED', 'UNCERTAIN', 'STARTING']) {
      expect(body({ ...state, queue: [queued('q-1', inputState, 'Previously claimed', { turnId: 'turn-old' })] })).not.toContain('>Steer</button>');
    }
  });

  it('renders no strip for an empty queue or a finished task', () => {
    expect(body({ turn: turn('RUNNING'), queue: [] })).not.toContain('agt-queue');
    expect(body({ task: { ...previewTask('workspace-1', 't-1'), state: 'COMPLETED' }, queue: [queued('q-1', 'PAUSED', 'Left over', { pauseReason: 'x' })] }))
      .not.toContain('agt-queue');
  });

  it('gates Steer on the lane and Run next on a draft while a turn runs', () => {
    const running = body({ turn: turn('RUNNING'), composer: 'also check the tests', capabilities: { ...NO_AGENT_CAPABILITIES, steering: true } });
    expect(running).toMatch(/<button[^>]*class="agt-steer"(?![^>]*disabled)[^>]*title="Sends into the running turn now"/);
    expect(running).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*title="Runs after this turn finishes"/);

    const noSteering = body({ turn: turn('RUNNING'), composer: 'also check the tests', capabilities: { ...NO_AGENT_CAPABILITIES, steering: false } });
    expect(noSteering).toMatch(/<button[^>]*class="agt-steer"[^>]*disabled[^>]*title="This provider cannot take input into a running turn"/);
    expect(noSteering).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*>Run next</);

    // Neither control appears while idle, where Send is the only way to speak.
    const idle = body({ turn: turn('COMPLETED'), composer: 'next', capabilities: { ...NO_AGENT_CAPABILITIES, steering: true } });
    expect(idle).not.toContain('>Steer<');
    expect(idle).not.toContain('>Run next<');
  });

  it('marks the composer busy and freezes its draft while a submission is pending', () => {
    const markup = body({ turn: turn('RUNNING'), composer: 'also check the tests', sending: true });
    expect(markup).toContain('class="agt-compose" aria-busy="true"');
    expect(markup).toMatch(/<textarea[^>]*disabled/);
    expect(markup).toMatch(/aria-label="Attach an image"[^>]*disabled/);
  });
});

describe('the step a running task card shows', () => {
  const source = parseAgentEventSequence(fixture.events)[0].source;
  const command = (item: string, sequence: number, state: string, argv: string[], actions: unknown[], description: string | null = null,
    occurredAt = '2026-10-22T09:00:00Z'): AgentEventEnvelope => ({
    schemaVersion: 2, eventId: `${item}-${sequence}`, sequence, taskId: 'task', turnId: 'turn', occurredAt,
    source: { ...source, providerItemId: item, providerEventId: null }, type: 'command',
    payload: { state, argv, output: null, exitCode: null, truncated: false, cwd: '.', durationMs: null, actions, description },
  } as AgentEventEnvelope);
  const step = latestStep;
  const read: CommandAction = { kind: 'READ', name: 'DomainTranslator.java', path: 'core/main/DomainTranslator.java', query: null };

  it('names the newest command by what it parsed, the command inside its login shell, or the provider\'s label', () => {
    expect(step([])).toBeNull();
    expect(step([command('read', 1, 'COMPLETED', ['/bin/zsh -lc "sed -n 1,100p DomainTranslator.java"'], [read])]))
      .toMatchObject({ kind: 'READ', text: 'DomainTranslator.java' });
    // A finished read gives way to the command still running after it, unwrapped from its shell.
    expect(step([command('read', 1, 'COMPLETED', ['sed'], [read]),
      command('build', 2, 'STARTED', ['/bin/zsh -lc \'./mvnw clean install -DskipTests\''], [{ kind: 'OTHER', name: null, path: null, query: null }])]))
      .toMatchObject({ kind: 'RUN', text: './mvnw clean install -DskipTests' });
    expect(step([command('test', 1, 'STARTED', ['./mvnw test'], [], 'Run the domain translator tests')]))
      .toMatchObject({ kind: 'RUN', text: 'Run the domain translator tests' });
  });

  it('times a step from its first event, and to its last once it has finished', () => {
    const started = command('test', 1, 'STARTED', ['./mvnw test'], [], null, '2026-10-22T09:00:00Z');
    const at = Date.parse('2026-10-22T09:00:00Z');
    expect(step([started])).toMatchObject({ startedAt: at, endedAt: null });
    expect(step([started, command('test', 2, 'PROGRESS', [], [], null, '2026-10-22T09:00:05Z')])).toMatchObject({ startedAt: at, endedAt: null });
    expect(step([started, command('test', 3, 'COMPLETED', ['./mvnw test'], [], null, '2026-10-22T09:00:12Z')])).toMatchObject({ startedAt: at, endedAt: at + 12_000 });
  });

  it('takes a described tool step\'s kind from its verb, and calls any other tool a tool', () => {
    const tool = (name: string, description: string | null): AgentEventEnvelope => ({
      schemaVersion: 2, eventId: name, sequence: 1, taskId: 'task', turnId: 'turn', occurredAt: '2026-10-22T09:00:00Z',
      source: { ...source, providerItemId: name, providerEventId: null }, type: 'tool',
      payload: { name, state: 'STARTED', summary: name, durationMs: null, description },
    } as AgentEventEnvelope);
    expect(step([tool('Read', 'Read core/main/DomainTranslator.java · lines 240–380')]))
      .toMatchObject({ kind: 'READ', text: 'core/main/DomainTranslator.java · lines 240–380' });
    expect(step([tool('Grep', 'Searched extractDecimal in plugin/files')])).toMatchObject({ kind: 'SEARCH', text: 'extractDecimal in plugin/files' });
    expect(step([tool('Glob', 'Listed **/*.java')])).toMatchObject({ kind: 'LIST', text: '**/*.java' });
    expect(step([tool('mcp__bytequay__work_items', null)])).toMatchObject({ kind: 'TOOL', text: 'work_items' });
  });
});

describe('historical activity presentation', () => {
  const source = parseAgentEventSequence(fixture.events)[0].source;
  const events: AgentEventEnvelope[] = [
    {
      schemaVersion: 2, eventId: 'progress-command', sequence: 101, taskId: 'task', turnId: 'turn', occurredAt: '2026-09-07T09:00:00Z',
      source: { ...source, providerItemId: 'command', providerEventId: null },
      type: 'command',
      payload: { state: 'PROGRESS', argv: ['mvn', 'verify'], output: 'Compiling source files', exitCode: null, truncated: false, cwd: '.', durationMs: null, actions: [], description: null },
    },
    {
      schemaVersion: 2, eventId: 'partial-text', sequence: 102, taskId: 'task', turnId: 'turn', occurredAt: '2026-09-07T09:00:01Z',
      source: { ...source, providerItemId: 'text', providerEventId: null },
      type: 'assistant_message', payload: { messageKind: 'COMMENTARY', phase: 'PARTIAL', text: 'Checking the build' },
    },
  ];

  function markup(completed: boolean, afterCursor: string | null): string {
    const turn: AgentTurnDto = {
      id: 'turn', taskId: 'task', state: completed ? 'COMPLETED' : 'RUNNING', version: 1,
      createdAt: '2026-09-07T08:57:00Z', startedAt: '2026-09-07T08:57:00Z', endedAt: completed ? '2026-09-07T09:05:00Z' : null,
      cancellationRequestedAt: null, delivered: true, knownUnsent: false, settings: null, terminalReason: null,
      metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, elapsed: 'PT8M' },
    };
    return renderTaskBody({
      task: previewTask('workspace', 'task'),
      turn, turns: { turn }, events, connection: 'open', lastSequence: 102,
      historyPages: [{ events, beforeCursor: 'older', afterCursor, latestSequence: afterCursor === null ? 102 : 9000 }],
    }, { turnChoice: { turn: true }, activityChoice: { 'progress-command': true } });
  }

  it('keeps earlier command output readable without a live status after the durable turn completed', () => {
    const html = markup(true, 'newer');
    expect(html).toContain('Compiling source files');
    expect(html).toContain('Checking the build');
    expect(html).toContain('Worked for 8m 00s');
    expect(html).not.toContain('>Working<');
    expect(html).not.toContain('agt-spin');
    expect(html).not.toContain('agt-cursor');
    expect(html).not.toContain('exit 0');
  });

  it('does not animate an older page while the task continues running elsewhere', () => {
    const html = markup(false, 'newer');
    expect(html).toContain('Compiling source files');
    expect(html).not.toContain('agt-act is-running');
    expect(html).not.toContain('agt-icon is-running');
    expect(html).not.toContain('agt-cursor');
    expect(html).not.toContain('>Running mvn verify<');
  });

  it('still shows current command progress and streamed text at the latest running turn', () => {
    const html = markup(false, null);
    expect(html).toContain('agt-act is-running');
    expect(html).toContain('agt-icon is-running');
    expect(html).toContain('agt-cursor');
    expect(html).toContain('>Running mvn verify<');
  });
});

describe('generated development request presentation', () => {
  const baseTurn = parseAgentTurn(contracts.turn);
  function taskHtml(turn: AgentTurnDto, approvals = false): string {
    const task: AgentTaskDto = { ...previewTask('workspace-1', turn.taskId), interactive: true, mutationClaim: null };
    const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, turns: { [turn.id]: turn },
      events: parseAgentEventSequence(fixture.events).filter(event => approvals || !event.type.startsWith('approval')).map(event => ({ ...event, taskId: task.id, turnId: turn.id })), connection: 'open' as const };
    return renderTaskBody(state);
  }

  it('asks for the durable answer in the composer, not a pinned card', () => {
    const question = 'Which implementation should I use?';
    const html = taskHtml({ ...baseTurn, state: 'COMPLETED', developmentResult: { readyForDelivery: false, message: question } });
    expect(html).not.toContain(question);
    expect(html).toContain('placeholder="Waiting for your response…"');
  });

  it('attributes generated requests using trusted durable origins and names live activity', () => {
    for (const [kind, shown, activity] of [
      ['AUTO_COMMIT', 'ByteQuay · Auto commit', 'Committing'],
      ['CREATE_DRAFT_PR', 'Open draft pull request', 'Creating draft PR'],
      ['REFINE_SUMMARY', 'Refresh the change intention', 'Refining summary'],
    ] as const) {
      const origin = { kind, parentUserTurnId: 'source-turn' };
      const turn = parseAgentTurn({ ...contracts.turn, state: 'RUNNING', developmentOrigin: { ...origin, hiddenPath: '/secret' } });
      expect(turn.developmentOrigin).toEqual(origin);
      const html = taskHtml(turn);
      expect(html).toContain(shown);
      expect(html).toContain(activity);
      expect(taskHtml(turn, true)).toContain('>Needs approval<');
    }
    expect(() => parseAgentTurn({ ...contracts.turn, developmentOrigin: { kind: 'USER', parentUserTurnId: 'source' } })).toThrow();
    expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).not.toContain('ByteQuay · Auto commit');
  });

  it('shows the create-PR action and branches without the sent prompt', () => {
    const ordinary: AgentTurnDto = { ...baseTurn, taskId: 'c587a121' };
    const branch = 'contributor/bytequay/find-and-refactor-minor-issue-c587a121';
    const route = { action: 'Open draft pull request', head: branch, base: 'master' };
    const generated: AgentTurnDto = { ...ordinary, developmentOrigin: { kind: 'CREATE_DRAFT_PR', parentUserTurnId: 'source-turn' } };
    const said = (text: string, attachments: unknown[] = []) => ({ text, attachments });
    // The lines DevelopmentFollowupCoordinator.publicationPrompt writes for a fork route, the last one naming any PR to reuse.
    const publication = (base = "'master'", pullRequest = 'No pull request tracked this branch at verification.') => [
      'ByteQuay · Create draft PR',
      `Worktree: '/w/worktrees/t'. Branch: '${branch}'. Exact clean HEAD to publish: e5083dc3.`,
      `Base repository/branch: owner/repository / ${base}. Push repository: alice/repository.`,
      `and never read or ask for credentials. ${pullRequest}`,
    ].join('\n');
    expect(draftPullRequestRequest(generated, said(publication()), branch)).toEqual(route);
    expect(draftPullRequestRequest(generated, said(publication()), null)).toEqual({ ...route, head: null });
    // Publishing a later revision into a pull request that is already open opens nothing, draft or not.
    const reuse = 'Pull request https://github.com/owner/repository/pull/12 already tracks this branch; publishing updates it and creates no second one.';
    expect(draftPullRequestRequest(generated, said(publication(undefined, reuse)), branch)).toEqual({ ...route, action: 'Update pull request #12' });
    // The base arrives shell-quoted, so a quote inside the branch name must survive.
    expect(draftPullRequestRequest(generated, said(publication(`'o'"'"'brien/fix'`)), branch)?.base).toBe("o'brien/fix");
    // A reworded template still reads as the action; only its base goes unnamed.
    expect(draftPullRequestRequest(generated, said('Push the revision and create its draft'), branch)).toEqual({ ...route, base: null });
    // Only the trusted origin qualifies, and only with its durable turn: a person who types the same
    // words gets an ordinary bubble, because they authorize no publication.
    expect(draftPullRequestRequest(ordinary, said(publication()), branch)).toBeNull();
    expect(draftPullRequestRequest(ordinary, said('Open a draft pull request for this task.'), branch)).toBeNull();
    expect(draftPullRequestRequest(undefined, said(publication()), branch)).toBeNull();

    const html = renderToStaticMarkup(<TurnView
      turn={{ turnId: generated.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [{ id: 'request', text: publication(), attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={{ [generated.id]: generated }} activityChoice={{}} previews={{}} agentLabels={{}} generatedBranch={branch}
      onToggle={() => {}} onToggleActivity={() => {}} />);
    expect(html).toContain('Open draft pull request');
    expect(html).toContain(`title="${route.head} → master">${route.head} → master</span>`);
    expect(html).not.toContain('agt-review-request__prompt');
    expect(html).not.toContain("Exact clean HEAD to publish: e5083dc3.");
    expect(html).toContain('→ master');
    expect(html).not.toContain('agt-user__bubble');
    expect(html).not.toContain('gh pr create');
  });

  it('folds only trusted generated development requests while preserving user messages and steering', () => {
    const prompt = 'ByteQuay · Refining summary\nRefresh this development task’s private Review & Learning cards from the final code and actual commits.';
    const promptHtml = prompt.replace('&', '&amp;');
    const user: TranscriptTurn['users'][number] = { id: 'request', text: prompt, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' };
    const html = (durable: AgentTurnDto | undefined, users = [user]) => renderToStaticMarkup(<TurnView
      turn={{ turnId: baseTurn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null, users }}
      first={false} open={false} now={0} models={[]} durableTurns={durable === undefined ? {} : { [durable.id]: durable }}
      activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />);
    for (const kind of ['REFINE_SUMMARY', 'AUTO_COMMIT', 'CREATE_DRAFT_PR'] as const) {
      const generated = { ...baseTurn, developmentOrigin: { kind, parentUserTurnId: 'source-turn' } };
      const markup = html(generated);
      if (kind === 'REFINE_SUMMARY') {
        // One line says what it does, so there is no head to expand.
        expect(markup).toContain('agt-review-request__summary');
        expect(markup).not.toContain('agt-review-request__bar');
      }
      else {
        expect(markup).toContain('agt-review-request__preview');
        expect(markup).toContain('aria-expanded="false"');
        expect(markup).toContain('class="agt-review-request__content" hidden=""');
      }
      expect(markup).not.toContain('agt-review-request__prompt');
      expect(markup).not.toContain(`<div class="agt-review-request__body">${promptHtml}</div>`);
      expect(markup).not.toContain('agt-user__bubble');
      for (const delivery of ['STEER', 'QUEUED'] as const) {
        const ordinary = html(generated, [{ ...user, delivery }]);
        expect(ordinary).toContain('agt-user__bubble');
        expect(ordinary).toContain(promptHtml);
        expect(ordinary).not.toContain('agt-review-request');
      }
      const steered = html(generated, [user, { ...user, id: 'steered', delivery: 'STEER' }]);
      expect(steered).toContain(`<div class="agt-user__bubble"><div>${promptHtml}</div>`);
    }
    for (const durable of [baseTurn, undefined]) {
      const ordinary = html(durable);
      expect(ordinary).toContain(`<div class="agt-user__bubble"><div>${promptHtml}</div>`);
      expect(ordinary).not.toContain('agt-review-request');
    }
  });

  it('holds an opening request until its durable turn says whether ByteQuay generated it', () => {
    const prompt = 'ByteQuay · Refining summary\nRefresh this task’s private Review & Learning cards.';
    const steer = 'Also check the docs.';
    const html = (durable: AgentTurnDto | undefined, loading: boolean) => renderToStaticMarkup(<TurnView
      turn={{ turnId: baseTurn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [{ id: 'request', text: prompt, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' },
          { id: 'steered', text: steer, attachments: [], delivery: 'STEER', at: '2026-01-01T12:00:01Z' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={durable === undefined ? {} : { [durable.id]: durable }} durableTurnsLoading={loading}
      activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />);
    const pending = html(undefined, true);
    expect(pending).not.toContain('Refining summary');
    expect(pending).toContain(steer);
    // A person's own message is never held, even while records load.
    expect(renderToStaticMarkup(<TurnView
      turn={{ turnId: baseTurn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [{ id: 'request', text: steer, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={{}} durableTurnsLoading
      activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />)).toContain(steer);
    const generated = { ...baseTurn, developmentOrigin: { kind: 'REFINE_SUMMARY' as const, parentUserTurnId: 'source-turn' } };
    expect(html(generated, true)).toContain('Refresh the change intention');
    expect(html(generated, true)).not.toContain('Refining summary');
    // Without a load in flight, a missing record leaves the message as sent.
    expect(html(undefined, false)).toContain('Refining summary');
    // A CI repair is recognized only by its durable settings, so its prompt waits the same way.
    const ci = 'Verified current CI context:\n- Pull request: acme/demo#17\n- Remote head: abc1234 (fix)';
    const ciHtml = (loading: boolean) => renderToStaticMarkup(<TurnView
      turn={{ turnId: baseTurn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [{ id: 'request', text: ci, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={{}} durableTurnsLoading={loading}
      activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />);
    expect(ciHtml(true)).not.toContain('Verified current CI context');
    expect(ciHtml(false)).toContain('Verified current CI context');
  });

  it('keeps the open approval of a folded turn and hides the rest of its log', () => {
    const [turn] = projectTranscript(parseAgentEventSequence(fixture.events));
    const html = renderToStaticMarkup(<TurnView turn={turn} first={false} open={false} now={0} models={[]} durableTurns={{}}
      activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />);
    expect(html).toContain('aria-label="Approval: Run a command in this workspace"');
    expect(html).not.toContain('Approval: Run the test suite');
    expect(html).not.toContain('agt-plan');
  });

  it('shows a review-thread reply as its card before a thread read links it to the turn', () => {
    const threadId = '7c0d6a8e-5d1b-4c2b-9f3e-0a1b2c3d4e5f';
    const reply = 'What does this pull request resolve?';
    // The lines LocalReviewService.prompt writes, the instructions shortened.
    const prompt = (body: string) => `Private local review comment ${threadId}.\nRead list_local_review_comments with commentId="${threadId}" for the saved context. `
      + 'Answer within this private review.\nUse reply_local_review_comment to answer this thread privately.\n\nUser comment:\n' + body;
    const message = (id: string, body: string): LocalReviewMessageDto => ({ id, author: 'USER', body, createdAt: '2026-01-01T12:00:00Z',
      turnId: null, queuedInputId: null, messageEventId: null, deliveryState: 'PENDING', deliveryError: null });
    // Cast: the thread DTO gains fields over time that this rendering does not read.
    const thread = (...messages: LocalReviewMessageDto[]) => ({ id: threadId, taskId: baseTurn.taskId, taskVersion: 1, workspaceId: 'workspace',
      repo: 'acme/demo', number: 17, path: 'src/Example.java', side: 'RIGHT', startLine: 12, line: 13, baseSha: null, headSha: null, code: 'int value;',
      resolved: false, version: 1, createdAt: '2026-01-01T12:00:00Z', updatedAt: '2026-01-01T12:00:00Z', messages }) as LocalReviewThreadDto;
    const html = (threads: LocalReviewThreadDto[], text = prompt(reply)) => renderToStaticMarkup(<TurnView
      turn={{ turnId: baseTurn.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
        users: [{ id: 'reply-event', text, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
      first={false} open={false} now={0} models={[]} durableTurns={{}} activityChoice={{}} previews={{}} agentLabels={{}}
      localReview={{ threads, onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} }}
      onToggle={() => {}} onToggleActivity={() => {}} />);
    const card = (markup: string) => {
      expect(markup).not.toContain('agt-user__bubble');
      expect(markup).not.toContain('list_local_review_comments');
      expect(markup).toContain(reply);
    };
    // The read already has the reply, not yet its turn.
    const unlinked = html([thread(message('ask', 'Why is this needed?'), message('reply', reply))]);
    card(unlinked);
    expect(unlinked).toContain('Reply to review');
    // The reply went through another read of the same thread, so this one has only the thread.
    const missing = html([thread(message('ask', 'Why is this needed?'))]);
    card(missing);
    expect(missing).toContain('Reply to review');
    expect(html([thread(message('ask', reply))])).toContain('Ask about review');
    // Without its thread the message stays as sent.
    expect(html([])).toContain('agt-user__bubble');
    expect(html([thread(message('ask', reply))], reply)).toContain('agt-user__bubble');
  });

  it('uses Idle for settled runtime and preserves live automation and recovery precedence in task cards', () => {
    expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).toContain('>Idle<');
    const task = previewTask('workspace-1', baseTurn.taskId);
    expect(taskStatePill({ ...task, mutationClaim: null }).label).toBe('Idle');
    expect(taskStatePill({ ...task, mutationClaim: null, developmentContinuationUnfinished: true }).label).toBe('Automation running');
    expect(taskStatePill({ ...task, mutationClaim: { kind: 'RECOVERY', ownerId: 'owner', acquiredAt: '' }, developmentContinuationUnfinished: true }).label).toBe('Recovery required');
  });
});

describe('CI automation presentation', () => {
  it('narrows CI projections and requires explicit current permission for child messages', () => {
    expect(parseAgentDevelopmentCi({ ...CI_PROJECTION, privatePath: '/secret' })).toEqual(CI_PROJECTION);
    expect(parseAgentTask({ ...contracts.task, developmentCi: CI_PROJECTION }).developmentCi).toEqual(CI_PROJECTION);
    expect(parseCiAutofixRun({ ...CI_RUN, runtimePath: '/secret' })).toEqual(CI_RUN);
    expect(() => parseAgentDevelopmentCi({ ...CI_PROJECTION, canMessage: 'true' })).toThrow();
    expect(() => parseCiAutofixRun({ ...CI_RUN, canMessage: undefined })).toThrow();
    expect(() => parseAgentDevelopmentCi({ ...CI_PROJECTION, status: 'READY' })).toThrow();
  });

  it('recognizes a standalone CI recipient without treating emails or longer handles as routing', () => {
    expect(ciMessage('@ci Use the compatibility fix')).toBe('Use the compatibility fix');
    expect(ciMessage('  @CI\nTry again  ')).toBe('Try again');
    expect(ciMessage('Please fix this @ci')).toBe('Please fix this');
    expect(ciMessage('@ci')).toBe('');
    for (const text of ['Continue development', 'build@ci', '@circle fix this', '@ci-bot help']) {
      expect(ciMessage(text)).toBeNull();
    }
  });

  const paused: AgentTaskState & { task: AgentTaskDto } = {
    ...initialAgentTaskState(), connection: 'open' as const, composer: '@ci Use the compatibility fix',
    task: { ...previewTask('workspace-1', 'source-task'), interactive: true, mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: CI_PROJECTION },
  };

  it('requires current paused CI authority and a bounded text message', () => {
    expect(ciMessageUnavailable(paused)).toBeNull();
    expect(ciMessageUnavailable({ ...paused, composer: '@ci' })).not.toBeNull();
    expect(ciMessageUnavailable({ ...paused, composer: '@ci ' + 'a'.repeat(16_384) })).toBeNull();
    expect(ciMessageUnavailable({ ...paused, composer: '@ci ' + 'a'.repeat(16_385) })).not.toBeNull();
    expect(ciMessageUnavailable({ ...paused, connection: 'reconnecting' })).not.toBeNull();
    expect(ciMessageUnavailable({ ...paused, sending: true })).not.toBeNull();
    for (const ci of [undefined, { ...CI_PROJECTION, canMessage: false }, { ...CI_PROJECTION, status: 'ACTIVE' as const }, { ...CI_PROJECTION, status: 'FINISHED' as const }]) {
      expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, developmentCi: ci } })).not.toBeNull();
    }
    expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, developmentContinuationUnfinished: false } })).toBeNull();
    expect(ciMessageUnavailable({ ...paused, task: { ...paused.task, state: 'COMPLETED' } })).not.toBeNull();
  });

  it('shows CI activity separately and routes the composer without a history panel', () => {
    expect(ciActivity(CI_PROJECTION)).toBe('CI needs your input');
    const render = (state: AgentTaskState = paused) => renderTaskBody(state);
    const html = render();
    expect(html).toContain('CI needs your input');
    expect(html).toContain('aria-label="Send to CI agent"');
    expect(html).toContain('aria-label="Stop automation"');
    expect(html).not.toContain('>Steer<');
    expect(html).not.toContain('>Run next<');
    const deepSeek = render({ ...paused, task: { ...paused.task, laneId: 'deepseek-key-123' } });
    expect(deepSeek).toContain('aria-label="Send to CI agent"');
    expect(deepSeek).toContain('CI needs your input');
    for (const label of ['CI Autofix status', 'CI Autofix run', 'View CI Autofix history', 'Open CI Autofix', 'repair attempts']) {
      expect(html).not.toContain(label);
    }
    const finished = render({ ...paused, task: { ...paused.task, developmentContinuationUnfinished: false,
      developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED', canMessage: false } } });
    expect(finished).toContain('This CI Autofix run has ended.');
    expect(finished).not.toContain('Previous CI Autofix run');
    expect(finished).toMatch(/<button[^>]*aria-label="Send to CI agent"[^>]*disabled/);
  });

  it('shows waiting CI as automation while the agent is idle and keeps the next draft visible', () => {
    const state: AgentTaskState = { ...paused, composer: 'Make another change', task: { ...paused.task,
      developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'WATCHING_CI', attemptsUsed: 0, canMessage: false } } };
    const html = renderTaskBody(state);
    expect(html).toContain('Waiting for CI</span>');
    expect(html).toContain('class="agt-state is-idle">Idle</span>');
    expect(html).not.toContain('agt-spin');
    expect(html).not.toContain('agt-stop-square');
    expect(html).not.toContain('>Steer<');
    expect(html).not.toContain('>Run next<');
    expect(html).toContain('aria-label="Stop automation"');
    expect(html).toContain('placeholder="Draft your next message…"');
    expect(html).toContain('Make another change</textarea>');
    expect(html).toMatch(/<button[^>]*aria-label="Send message"[^>]*disabled/);
    expect(ciMessageUnavailable({ ...state, composer: '@ci Continue' })).not.toBeNull();
  });

  it('shows CI Autofix beside an idle task without blocking its next message', () => {
    const state: AgentTaskState = { ...paused, composer: 'Make another change', task: { ...paused.task, developmentContinuationUnfinished: false,
      developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'WATCHING_CI', attemptsUsed: 0, canMessage: false } } };
    const html = renderTaskBody(state);
    expect(html).toMatch(/<span class="agt-auto-pill__label" role="status">.*Waiting for CI<\/span>/);
    expect(html).toContain('aria-label="Stop CI Autofix"');
    expect(html).not.toContain('Stop automation');
    expect(html).not.toContain('placeholder="Draft your next message…"');
    expect(html).toMatch(/<button[^>]*aria-label="Send message"(?![^>]*disabled)[^>]*>/);
    expect(ciActivity({ ...CI_PROJECTION, status: 'ACTIVE', activity: 'BASE_FAILURE', canMessage: false })).toBe('Already failing on the base branch');
    expect(ciActivity({ ...CI_PROJECTION, status: 'ACTIVE', activity: 'WAITING_TO_RERUN', canMessage: false })).toBe('Waiting for all CI to finish');
  });

  it('allows steering the linked CI turn using its own provider capability', () => {
    const active: AgentTaskState = { ...paused, task: { ...paused.task,
      developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', currentTurnId: 'ci-turn' } } };
    expect(ciMessageUnavailable(active)).toBeNull();
    const html = renderTaskBody(active);
    expect(html).toContain('Steer the running CI agent');
    expect(html).toMatch(/<button[^>]*aria-label="Send to CI agent"(?![^>]*disabled)[^>]*>/);
    for (const ci of [
      { ...active.task?.developmentCi, currentTurnId: null },
      { ...active.task?.developmentCi, canMessage: false },
      { ...active.task?.developmentCi, activity: 'PUSHING' },
    ]) {
      expect(ciMessageUnavailable({ ...active, task: { ...paused.task, developmentCi: { ...CI_PROJECTION, ...ci } } })).not.toBeNull();
    }
  });

  it('folds only a saved CI turn’s generated opening request', () => {
    const turn = parseAgentTurn(contracts.turn);
    const ciTurn = { ...turn, settings: { ...contracts.turn.settings, permissionPolicy: 'CI_AUTOFIX' } };
    const prompt = 'Verified current CI context:\n- Pull request: acme/demo#17\n- Remote head: ' + 'a'.repeat(40);
    expect(ciAutofixRequest(ciTurn, prompt)).toMatchObject({ action: 'ByteQuay · Fix CI', topic: 'acme/demo#17' });
    expect(ciAutofixRequest(turn, prompt)).toBeNull();
    expect(ciAutofixRequest(undefined, prompt)).toBeNull();
    expect(ciAutofixRequest(ciTurn, 'Please retry the failing check')).toBeNull();
    expect(ciAutofixRequest(ciTurn, 'Explain this request:\n' + prompt)).toBeNull();
    expect(ciAutofixRequest(ciTurn, prompt.replace('acme/demo#17', 'arbitrary text'))).toBeNull();
  });
});
