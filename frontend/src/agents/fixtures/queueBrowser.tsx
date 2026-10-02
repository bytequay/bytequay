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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import AgentTaskScreen from '../AgentTaskScreen';
import { createPreviewBridge } from './previewBridge';
import type { AgentTurnDto } from '../../types';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';

export async function run(): Promise<string> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'The fixture needs an active turn');
  const turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'RUNNING', endedAt: null };
  const readTask = bridge.getAgentTask;
  bridge.getAgentTask = async (...args) => ({ ...await readTask(...args), interactive: true, developmentContinuationUnfinished: false,
    mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } });
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    listener({ subscriptionId: 'queue', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  for (let index = 0; index < 2; index++) await bridge.queueAgentInput({ taskId: event.taskId, workspaceId: 'workspace-1',
    expectedVersion: index, message: 'Long queued message '.repeat(40), idempotencyKey: `queue-${index}` });
  const steer = bridge.steerAgentQueuedInput;
  let attempt = 0;
  bridge.steerAgentQueuedInput = async (...args) => {
    if (++attempt === 1) throw new Error('The turn changed before acceptance');
    const result = await steer(...args);
    return attempt === 3 ? { ...result, state: 'UNKNOWN', failure: 'Provider response lost' } : result;
  };
  const host = document.createElement('div');
  host.style.cssText = 'height:600px;width:600px';
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={event.taskId} bridge={bridge} onBack={() => {}} />));
    for (let frame = 0; frame < 30 && !host.querySelector('.agt-queue__link:not(:disabled)'); frame++) await settle();
    const input = host.querySelector<HTMLTextAreaElement>('.agt-compose__input');
    const firstSteer = (): HTMLButtonElement => {
      const button = [...host.querySelectorAll<HTMLButtonElement>('.agt-queue__link')].find(button => button.textContent === 'Steer');
      assert(button !== undefined && !button.disabled, `A queued message needs an enabled Steer button (attempt ${attempt})`);
      return button;
    };
    assert(input !== null && String(input.value) === '', 'The composer starts empty');
    firstSteer();
    for (const width of [480, 600, 1200]) {
      host.style.width = `${width}px`;
      const queue = host.querySelector<HTMLElement>('.agt-queue');
      const composer = host.querySelector<HTMLElement>('.agt-compose');
      const text = host.querySelector<HTMLElement>('.agt-queue__text');
      assert(queue !== null && composer !== null && text !== null, 'The queue and composer must render');
      const bounds = queue.getBoundingClientRect();
      const inputBounds = composer.getBoundingClientRect();
      assert(bounds.left > inputBounds.left && bounds.right < inputBounds.right, 'Queue must stay inside the composer edges');
      assert(text.scrollWidth > text.clientWidth && getComputedStyle(text).textOverflow === 'ellipsis', 'Long queued text must use ellipsis');
      assert(firstSteer().getBoundingClientRect().right < bounds.right, 'Steer must stay inside the queue');
    }
    changeInput(input, 'Keep this separate draft');
    await settle();
    firstSteer().click();
    await settle();
    assert(host.querySelectorAll('.agt-queue__row').length === 2, 'A rejected steer must retain the queued message');
    assert(host.textContent?.includes('The turn changed before acceptance') === true, 'The rejection must be visible');
    firstSteer().click();
    await settle();
    assert(host.querySelectorAll('.agt-queue__row').length === 1, 'Successful steering must remove only the selected row');
    assert(input.value === 'Keep this separate draft', 'Queued steering must not overwrite the composer');
    const command = bridge.commands.find(command => command.name === 'steerAgentQueuedInput');
    assert(JSON.stringify(command?.args) === JSON.stringify([event.taskId, 'workspace-1', 'queued-1', turn.id]), 'Steer must target the queued ID and displayed turn');
    firstSteer().click();
    await settle();
    assert(host.querySelector('.agt-queue') === null, 'Uncertain delivery must not remain eligible to run from the queue');
    assert(host.textContent?.includes('The steer may not have reached the agent') === true, 'Uncertain delivery must be explained');
    assert(input.value === 'Keep this separate draft', 'An uncertain steer must also preserve the composer');
    return 'queued steering preserves drafts, rejects safely, and stays inside the composer';
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
