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
import type { AgentTurnDto } from '../types';
import { AgentTaskBody } from './AgentTaskScreen';
import { parseAgentEventSequence, type AgentEventEnvelope } from './events';
import fixture from './fixtures/agent-events-v2.json';
import { previewTask } from './fixtures/previewBridge';
import { initialAgentTaskState } from './model';

const source = parseAgentEventSequence(fixture.events)[0].source;
const events: AgentEventEnvelope[] = [
  {
    schemaVersion: 2, eventId: 'progress-command', sequence: 101, taskId: 'task', turnId: 'turn', occurredAt: '2026-09-07T09:00:00Z',
    source: { ...source, providerItemId: 'command', providerEventId: null },
    type: 'command',
    payload: { state: 'PROGRESS', argv: ['mvn', 'verify'], output: 'Compiling source files', exitCode: null, truncated: false, cwd: '.', durationMs: null, actions: [] },
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
  return renderToStaticMarkup(<AgentTaskBody
    state={{
      ...initialAgentTaskState(),
      task: previewTask('workspace', 'task'),
      turn, turns: { turn }, events, connection: 'open', lastSequence: 102,
      historyPages: [{ events, beforeCursor: 'older', afterCursor, latestSequence: afterCursor === null ? 102 : 9000 }],
    }}
    models={[]}
    turnChoice={{ turn: true }}
    activityChoice={{ 'progress-command': true }}
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

describe('historical activity presentation', () => {
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
