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
import { AgentTaskBody } from './AgentTaskScreen';
import fixture from './fixtures/agent-events-v2.json';
import { parseAgentEventSequence } from './events';
import { initialAgentTaskState, type AgentTaskState } from './model';
import { previewTask } from './preview';
import type { AgentQueuedInputDto, AgentTurnDto, ModelOptionDto } from '../types';

// The fixture's open approval would put the header on 'waiting on you'; the count needs a plain running turn.
const events = parseAgentEventSequence(fixture.events).filter((event) => event.type !== 'approval_requested');

const MODELS: ModelOptionDto[] = [
  { id: 'gpt-5.6-codex', name: '5.6 Terra', description: 'Balanced', efforts: ['medium', 'high'], defaultEffort: 'high', inputModalities: ['text', 'image'] },
];

function turn(state: string): AgentTurnDto {
  return {
    id: 'turn-1',
    taskId: 't-1',
    state,
    version: 1,
    createdAt: '2026-08-20T10:00:00Z',
    startedAt: '2026-08-20T10:00:01Z',
    endedAt: state === 'RUNNING' ? null : '2026-08-20T10:00:09Z',
    cancellationRequestedAt: null,
    delivered: true,
    knownUnsent: false,
    settings: null,
    terminalReason: null,
    metrics: { inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: null, costProvenance: 'UNAVAILABLE', elapsed: 'PT0S' },
  };
}

function queued(id: string, state: string, preview: string, extra: Partial<AgentQueuedInputDto> = {}): AgentQueuedInputDto {
  return {
    id,
    taskId: 't-1',
    position: Number(id.replace(/\D/g, '')),
    state,
    preview,
    settings: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO', sandboxPolicy: 'WORKSPACE_WRITE', maximumRuntimeMillis: 60000, attachments: [] },
    turnId: null,
    pauseReason: null,
    createdAt: '2026-08-20T10:00:02Z',
    updatedAt: '2026-08-20T10:00:02Z',
    ...extra,
  };
}

function body(state: Partial<AgentTaskState>): string {
  return renderToStaticMarkup(
    <AgentTaskBody
      state={{ ...initialAgentTaskState(), task: { ...previewTask('workspace-1', 't-1'), state: 'OPEN' }, connection: 'open', ...state }}
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
    />);
}

describe('the queue behind a running turn', () => {
  it('lists queued messages under the composer, with their settings, and counts them in the header', () => {
    const markup = body({
      turn: turn('RUNNING'),
      events,
      queue: [queued('q-1', 'QUEUED', 'Then update the design doc'), queued('q-2', 'QUEUED', 'Open a draft pull request', { settings: { model: 'gpt-5.6-codex', effort: 'medium', permissionPolicy: 'AUTO', sandboxPolicy: 'WORKSPACE_WRITE', maximumRuntimeMillis: 60000, attachments: [{ id: 'a-1', name: 'shot.png', mediaType: 'image/png' }] } })],
    });

    expect(markup).toContain('class="agt-queue is-attached"');
    expect(markup).toContain('Then update the design doc');
    expect(markup).toContain('Open a draft pull request');
    expect(markup).toContain('5.6 Terra medium');
    expect(markup).toContain('1 image');
    // A waiting row can be removed, and the header says how many wait.
    expect(markup.match(/aria-label="Remove from queue"/g)).toHaveLength(2);
    expect(markup).toContain('2 queued');
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
  });

  it('blocks Complete for live queue work but lets an uncertain row reach confirmation', () => {
    for (const state of ['QUEUED', 'PAUSED', 'STARTING']) {
      const waiting = body({ turn: turn('COMPLETED'), queue: [queued('q-1', state, 'Still waiting')] });
      expect(waiting).toMatch(/class="agt-headbtn is-labelled"[^>]*disabled/);
      expect(waiting).toContain('Run or remove every waiting queued message first');
    }

    const uncertain = body({ turn: turn('COMPLETED'), queue: [queued('q-1', 'UNCERTAIN', 'Maybe sent')] });
    expect(uncertain).not.toMatch(/class="agt-headbtn is-labelled"[^>]*disabled/);
  });

  it('shows a starting row with a spinner and nothing to press', () => {
    const markup = body({ turn: turn('RUNNING'), queue: [queued('q-1', 'STARTING', 'Starting now', { turnId: 'turn-2' })] });

    expect(markup).toContain('>Starting<');
    expect(markup).toMatch(/class="[^"]*agt-spin[^"]*"/);
    expect(markup).not.toContain('aria-label="Remove from queue"');
    expect(markup).not.toContain('>Discard<');
  });

  it('renders no strip for an empty queue or a finished task', () => {
    expect(body({ turn: turn('RUNNING'), queue: [] })).not.toContain('agt-queue');
    expect(body({ task: { ...previewTask('workspace-1', 't-1'), state: 'COMPLETED' }, queue: [queued('q-1', 'PAUSED', 'Left over', { pauseReason: 'x' })] }))
      .not.toContain('agt-queue');
  });

  it('gates Steer on the lane and Run next on a draft while a turn runs', () => {
    const running = body({ turn: turn('RUNNING'), composer: 'also check the tests', steering: true });
    expect(running).toMatch(/<button[^>]*class="agt-steer"(?![^>]*disabled)[^>]*title="Sends into the running turn now"/);
    expect(running).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*title="Runs after this turn finishes"/);

    const noSteering = body({ turn: turn('RUNNING'), composer: 'also check the tests', steering: false });
    expect(noSteering).toMatch(/<button[^>]*class="agt-steer"[^>]*disabled[^>]*title="This provider cannot take input into a running turn"/);
    expect(noSteering).toMatch(/<button[^>]*class="agt-compose__chip"(?![^>]*disabled)[^>]*>Run next</);

    // Neither control appears while idle, where Send is the only way to speak.
    const idle = body({ turn: turn('COMPLETED'), composer: 'next', steering: true });
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
