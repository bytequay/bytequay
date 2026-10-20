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
import { expect, it } from 'vitest';
import { TurnView } from './AgentConversation';
import { parseAgentTurn } from './contracts';
import contracts from './fixtures/agent-contracts-v1.json';
import { workflowRequest } from './WorkflowRequest';
import type { AgentTurnDto } from '../types';
import type { TranscriptTurn } from './transcript';

const base = parseAgentTurn(contracts.turn);
const cases = [
  ['CHERRY_PICK', 'Resolve only this cherry-pick.\nSubject: Fix scan', 'Resolve cherry-pick', 'single fixup commit'],
  ['REBASE', 'Resolve only this rebase conflict.\nWork branch: topic', 'Resolve rebase', 'No commits or publication'],
  ['CONTEXT_BUILD', 'Check the code in directory x', 'Check specifications', 'No builds, file edits or publication'],
  ['CONTEXT_BUILD', 'Reproduce one spec check candidate', 'Verify specification gap', 'Temporary test only'],
  ['CONTEXT_BUILD', 'Learn repository memory for a/b', 'Learn repository memory', 'User-owned memories stay unchanged'],
] as const;

function render(durable: AgentTurnDto | undefined, users: TranscriptTurn['users']) {
  return renderToStaticMarkup(<TurnView
    turn={{ turnId: base.id, ordinal: 2, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null, users }}
    first={false} open={false} now={0} models={[]} durableTurns={durable === undefined ? {} : { [base.id]: durable }}
    activityChoice={{}} previews={{}} agentLabels={{}} onToggle={() => {}} onToggleActivity={() => {}} />);
}

it.each(cases)('folds the %s workflow request with its restrictions and exact prompt', (kind, prompt, action, restriction) => {
  const durable = parseAgentTurn({ ...contracts.turn, workflowRequest: kind });
  const user: TranscriptTurn['users'][number] = { id: 'request', text: prompt, attachments: [], delivery: 'TURN', at: '' };
  const html = render(durable, [user]);
  expect(html).toContain(`ByteQuay · ${action}`);
  expect(html).toContain(restriction);
  expect(html).toContain('aria-expanded="false"');
  expect(html).toContain('class="agt-review-request__content" hidden=""');
  expect(html).toContain('<details class="agt-review-request__prompt"><summary>Prompt sent to agent</summary>');
  expect(html).toContain(prompt);
  expect(html).not.toContain('agt-user__bubble');
  for (const delivery of ['STEER', 'QUEUED'] as const) {
    expect(render(durable, [{ ...user, delivery }])).not.toContain('agt-review-request');
    expect(render(durable, [user, { ...user, id: 'followup', delivery }])).toContain('agt-user__bubble');
  }
  for (const ordinary of [base, undefined]) {
    expect(render(ordinary, [user])).toContain('agt-user__bubble');
    expect(workflowRequest(ordinary, prompt)).toBeNull();
  }
});

it('keeps unknown helper prompts readable and rejects unknown workflow kinds', () => {
  expect(workflowRequest({ ...base, workflowRequest: 'CONTEXT_BUILD' }, 'A new helper request')).toBeNull();
  expect(parseAgentTurn(contracts.turn).workflowRequest).toBeNull();
  expect(() => parseAgentTurn({ ...contracts.turn, workflowRequest: 'USER' })).toThrow();
});
