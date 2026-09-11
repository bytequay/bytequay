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
import type { AgentDevelopmentFactsDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto, CreateAgentTaskRequest, StartAgentTurnRequest } from '../../types';
import { TaskComposer, useAgentTaskCreation } from '../AgentTasksView';
import { createPreviewBridge } from './previewBridge';
import AgentTaskScreen from '../AgentTaskScreen';
import { runCiAutofixScenarios } from './ciAutofixBrowser';
import { runDevelopmentDeliveryScenarios } from './developmentDeliveryBrowser';
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
  return [...await createLocalTask(), ...await retryLocalTurn(), ...await continuationAdmission(), ...await observedResultsRefresh(), ...await runCiAutofixScenarios(), ...await runDevelopmentDeliveryScenarios()];
}

async function continuationAdmission(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, developmentContinuationUnfinished: true };
  let turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'COMPLETED' };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.readAgentEvents = async () => [event];
  let notify: (message: AgentStreamMessage) => void = () => {};
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'continuation', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null && !input.disabled, 'A reloaded owned task must keep its composer editable');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Use the smaller change');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle();
    assert(!bridge.commands.some(command => command.name === 'startAgentTurn'), 'Reload and Enter must not start competing work');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Mark it complete"]')?.disabled === true, 'Complete must wait for automation');
    const stop = host.querySelector<HTMLButtonElement>('button[aria-label="Stop automation"]');
    assert(stop !== null, 'Owned task must offer Stop automation through the existing stop flow');
    stop.click();
    await settle();
    assert(document.body.textContent?.includes('marks the task failed once its agents have stopped') === true, 'Stop must explain the failure boundary');
    const cancel = Array.from(document.querySelectorAll('button')).find(button => button.textContent === 'Cancel');
    assert(cancel !== undefined, 'Existing stop confirmation must remain cancellable');
    cancel.click();
    await settle();
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false };
    turn = { ...turn, version: turn.version + 1, developmentResult: { readyForDelivery: false, message: 'Which change should I use?', stopAfter: null } };
    notify({ subscriptionId: 'continuation', taskId: task.id, kind: 'events', events: [{ ...event,
      eventId: 'automation-settled', sequence: event.sequence + 1, turnId: null, type: 'lifecycle',
      payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Waiting for your answer' } }] });
    await settle();
    assert(host.textContent?.includes('Waiting for your answer') === true, 'Released ownership must show the durable question');
    assert(input.value === 'Use the smaller change', 'Ownership changes must preserve the composer draft');
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'The user must be able to answer normally after ownership release');
    send.click();
    await settle();
    assert(bridge.commands.filter(command => command.name === 'startAgentTurn').length === 1, 'An answer must start exactly one ordinary turn');
    return [{ name: 'Continuation ownership blocks competing work and releases the normal answer composer' }];
  }
  catch (error) { return [{ name: 'Continuation admission', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function observedResultsRefresh(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null };
  let turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.readAgentEvents = async () => [event];
  let notify: (message: AgentStreamMessage) => void = () => {};
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'observations', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  let reads = 0;
  let rejectRead = false;
  const facts: AgentDevelopmentFactsDto = {
    observedAt: '2026-01-01T12:00:00Z',
    local: { branch: 'codex/change', headSha: '1234567890', stagedFiles: 0, unstagedFiles: 0, untrackedFiles: 0, uncommittedFileCount: 0, commitCount: 1 },
    localError: null, remote: { headSha: null, pullRequest: null }, remoteError: null,
  };
  bridge.getAgentDevelopmentFacts = async () => {
    reads++;
    if (rejectRead) throw new Error('GitHub temporarily unavailable');
    return facts;
  };
  let sequence = event.sequence;
  const emit = (phase: 'TURN_STARTED' | 'TURN_COMPLETED' | 'DEVELOPMENT_AUTOMATION_UPDATED') => {
    const current = ++sequence;
    notify({ subscriptionId: 'observations', taskId: task.id, kind: 'events', events: [{ ...event,
      eventId: `observation-${current}`, sequence: current, turnId: phase === 'DEVELOPMENT_AUTOMATION_UPDATED' ? null : turn.id,
      type: 'lifecycle', payload: { phase, message: 'Task changed' } }] });
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    assert(reads === 1, `Initial settled task must read results once, saw ${reads}`);
    assert(host.textContent?.includes('1 local commit') === true, 'Observed commits must appear beside the conversation');
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Composer must exist');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Keep this draft');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    task = { ...task, version: task.version + 1 };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(reads === 1, 'Typing and unrelated task version changes must not fetch GitHub');

    turn = { ...turn, id: 'next-development-turn', version: turn.version + 1, state: 'RUNNING', endedAt: null };
    task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } };
    emit('TURN_STARTED');
    await settle();
    assert(reads === 1, 'An active provider must not trigger a fresh fact read');
    assert(host.textContent?.includes('Agent running; showing previous observations') === true, 'Running work must identify retained observations');
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: true };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(reads === 1, 'Marker changes while a provider runs must not fetch facts');

    turn = { ...turn, version: turn.version + 1, state: 'COMPLETED', endedAt: '2026-01-01T12:01:00Z' };
    task = { ...task, version: task.version + 1, mutationClaim: null };
    emit('TURN_COMPLETED');
    await settle();
    assert(Number(reads) === 2, 'Settled latest turn must refresh exactly once');
    rejectRead = true;
    const beforePrReads = bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length;
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(Number(reads) === 3, 'Settled automation marker must trigger one final observation');
    assert(bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length > beforePrReads,
      'Final automation settlement without another turn must refresh the task PR list');
    assert(host.textContent?.includes('Could not refresh results. GitHub temporarily unavailable') === true, 'Failed refresh must be visible');
    assert(host.textContent?.includes('Last checked') === true && host.textContent?.includes('1 local commit') === true, 'Failed refresh must retain its dated local observation');
    assert(input.value === 'Keep this draft', 'Fact updates must preserve the composer draft');
    return [{ name: 'Development facts refresh only on settled changes and retain dated observations during execution or failure' }];
  }
  catch (error) { return [{ name: 'Development fact refresh', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
