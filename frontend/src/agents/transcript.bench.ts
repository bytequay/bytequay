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
import { bench, describe, expect } from 'vitest';
import type { AgentEventEnvelope } from './events';
import { projectTranscript } from './transcript';

describe('project a tool-heavy retained history window', () => {
  for (const count of [100, 500, 1000]) {
    const events: AgentEventEnvelope[] = Array.from({ length: count }, (_, item) =>
      (['STARTED', 'PROGRESS', 'COMPLETED'] as const).map((state, index): AgentEventEnvelope => ({
        schemaVersion: 2,
        eventId: `event-${item * 3 + index}`,
        sequence: item * 3 + index + 1,
        taskId: 'task',
        turnId: 'turn',
        occurredAt: '2026-08-20T10:00:00Z',
        source: { providerThreadId: 'root', providerTurnId: 'turn', providerItemId: `command-${item}`,
          providerEventId: null, parentProviderThreadId: null, logicalAgentPath: '/' },
        type: 'command',
        payload: { state, argv: ['rg', 'TODO', `src/file-${item}.ts`], output: state === 'PROGRESS' ? 'matched line\n' : null,
          exitCode: state === 'COMPLETED' ? 0 : null, truncated: false, cwd: null, durationMs: null, actions: [], description: null },
      })),
    ).flat();
    const block = projectTranscript(events)[0].blocks[0];
    expect(block.kind).toBe('activity');
    if (block.kind !== 'activity') throw new Error('Expected an activity group');
    expect(block.members).toHaveLength(count);
    expect(block.endedAt).toBe(events.at(-1)?.occurredAt);
    bench(`${events.length} events / ${count} commands`, () => { projectTranscript(events); });
  }
});
