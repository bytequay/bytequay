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
import { useCallback } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { CreateAgentTaskRequest, StartAgentTurnRequest } from '../../types';
import { TaskComposer, useAgentTaskCreation } from '../AgentTasksView';
import { createPreviewBridge } from './previewBridge';
import AgentTaskScreen from '../AgentTaskScreen';
import { automationForSubmission, DEFAULT_DEVELOPMENT_AUTOMATION } from '../developmentAutomation';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }

async function createLocalTask(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const live = useCallback(() => bridge, []);
    const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', initialLaneId: 'codex', bridge: live, onOpenTask: () => {} });
    return <TaskComposer draft={creation.draft} blocker={creation.blocker} laneId={creation.laneId}
      providers={creation.catalog?.providers ?? []} models={creation.models} selection={creation.selection}
      automation={creation.automationControl} busy={creation.busy} onDraftChange={creation.setDraft}
      onSelectProvider={creation.selectProvider} onSelectModel={(model, effort, serviceTier) => creation.setSelection({ model, effort, serviceTier })}
      onCreate={() => { void creation.create(); }} />;
  }
  flushSync(() => root.render(<Harness />));
  try {
    await settle();
    const menu = host.querySelector('summary');
    assert(menu !== null, 'Automation must be visible before Send is enabled');
    menu.click();
    await settle();
    const localOnly = host.querySelector<HTMLInputElement>('.is-local input');
    assert(localOnly !== null, 'Local only must be a separate submission control');
    localOnly.click();
    await settle();
    const input = host.querySelector<HTMLInputElement>('input[aria-label="Describe a task"]');
    assert(input !== null, 'Task composer must exist');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, 'Inspect the change locally');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    const send = host.querySelector<HTMLButtonElement>('.agt-send');
    assert(send !== null && !send.disabled, 'Ready task with visible Local only must be sendable');
    send.click();
    await settle();
    const command = bridge.commands.find(command => command.name === 'createAgentTask');
    assert(command !== undefined, 'One composite task request must be submitted');
    const request = command.args[0] as CreateAgentTaskRequest;
    assert(request.defaults.developmentAutomation?.autoCommit === true, 'Local only must preserve saved task defaults');
    assert(request.developmentAutomation?.autoCommit === false && request.developmentAutomation.autoDraftPr === false
      && request.developmentAutomation.autoCiFix === false, 'Only the first turn must disable all automatic steps');
    assert(bridge.commands.filter(command => command.name === 'createAgentTask').length === 1, 'A successful submit must not duplicate creation');
    assert(host.querySelector<HTMLInputElement>('.is-local input')?.checked === false, 'Local only must clear after acceptance');
    assert(host.querySelector<HTMLInputElement>('.agt-automation-option input')?.checked === true, 'Default-on selection must remain for the next task');
    return [{ name: 'Local only preserves task defaults and submits one explicit first-turn override' }];
  }
  catch (error) { return [{ name: 'Local only submission', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function retryLocalTurn(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const userEvent = bridge.events.find(event => event.type === 'user_message');
  assert(userEvent?.turnId !== null && userEvent !== undefined, 'Fixture must identify a durable input');
  const localOnly = automationForSubmission(DEFAULT_DEVELOPMENT_AUTOMATION, true);
  const task = { ...await bridge.getAgentTask(userEvent.taskId, 'workspace-1'), interactive: true };
  task.defaults = { ...task.defaults, developmentAutomation: DEFAULT_DEVELOPMENT_AUTOMATION };
  const turn = { ...await bridge.getAgentTurn(userEvent.turnId), id: userEvent.turnId, state: 'FAILED', failureKind: 'AUTHENTICATION_REQUIRED' as const };
  assert(turn.settings !== null, 'Fixture must have accepted turn settings');
  turn.settings = { ...turn.settings, developmentAutomation: localOnly };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [userEvent], beforeCursor: null, afterCursor: null, latestSequence: userEvent.sequence });
  bridge.readAgentEvents = async () => [userEvent];
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    listener({ subscriptionId: 'retry', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const confirm = window.confirm;
  window.confirm = () => true;
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const refresh = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Refresh login status');
    assert(refresh !== undefined, 'Failed authentication must offer a fresh readiness check');
    refresh.click();
    await settle();
    const retry = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Retry last message…');
    assert(retry !== undefined && !retry.disabled, 'Successful readiness check must enable explicit retry');
    retry.click();
    await settle();
    const command = bridge.commands.find(command => command.name === 'startAgentTurn');
    assert(command !== undefined, 'Explicit retry must submit the durable input');
    const request = command.args[0] as StartAgentTurnRequest;
    assert(request.developmentAutomation?.autoCommit === false && request.developmentAutomation.autoDraftPr === false
      && request.developmentAutomation.autoCiFix === false, 'Retry must retain the accepted Local only policy despite current default-on settings');
    return [{ name: 'Authentication retry retains accepted automation instead of current task defaults' }];
  }
  catch (error) { return [{ name: 'Authentication retry policy', error: error instanceof Error ? error.message : String(error) }]; }
  finally { window.confirm = confirm; flushSync(() => root.unmount()); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  return [...await createLocalTask(), ...await retryLocalTurn()];
}
