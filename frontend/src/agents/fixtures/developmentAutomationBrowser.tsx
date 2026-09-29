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
import type { AgentStreamMessage, AgentTaskDto, AgentTurnDto, CreateAgentTaskRequest, PullRequestDto, StartAgentTurnRequest } from '../../types';
import { parseAgentEventSequence, type AgentEventEnvelope } from '../events';
import { TaskComposer, useAgentTaskCreation } from '../AgentTasksView';
import { createPreviewBridge } from './previewBridge';
import AgentTaskScreen from '../AgentTaskScreen';
import { runCiAutofixScenarios } from './ciAutofixBrowser';
import { runDevelopmentDeliveryScenarios } from './developmentDeliveryBrowser';
import { automationForSubmission, DEFAULT_DEVELOPMENT_AUTOMATION } from '../developmentAutomation';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/agents.css';


async function createLocalTask(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const live = useCallback(() => bridge, []);
    const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', bridge: live, onOpenTask: () => {} });
    return <TaskComposer {...creation.composer} />;
  }
  flushSync(() => root.render(<Harness />));
  try {
    await settle();
    const menu = host.querySelector('summary');
    assert(menu !== null, 'Automation must be visible before Send is enabled');
    menu.click();
    await settle();
    const localOnly = host.querySelector<HTMLInputElement>('.is-local input');
    assert(localOnly !== null, 'Keeping commits local must be a separate submission control');
    assert(host.textContent?.includes('Keep commits local for this turn') === true, 'Local publication must explain that commits are still created');
    localOnly.click();
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null, 'Task composer must exist');
    changeInput(input, 'Inspect the change locally');
    await settle();
    const send = host.querySelector<HTMLButtonElement>('.agt-send');
    assert(send !== null && !send.disabled, 'Ready task with local publication selected must be sendable');
    send.click();
    await settle();
    const command = bridge.commands.find(command => command.name === 'createAgentTask');
    assert(command !== undefined, 'One composite task request must be submitted');
    const request = command.args[0] as CreateAgentTaskRequest;
    assert(request.defaults.developmentAutomation?.autoDraftPr === true, 'Keeping commits local must preserve saved task defaults');
    assert(request.developmentAutomation?.autoDraftPr === false && request.developmentAutomation.autoCiFix === false,
      'Only the first turn must disable automatic publication and CI');
    assert(bridge.commands.filter(command => command.name === 'createAgentTask').length === 1, 'A successful submit must not duplicate creation');
    assert(host.querySelector<HTMLInputElement>('.is-local input')?.checked === false, 'Local publication override must clear after acceptance');
    assert(host.querySelector<HTMLInputElement>('.agt-automation-option input')?.checked === true, 'Default-on selection must remain for the next task');
    return [{ name: 'Keeping commits local preserves task defaults and submits one explicit first-turn override' }];
  }
  catch (error) { return [{ name: 'Local publication submission', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function createDeepSeekTask(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const settings = await bridge.getModelSettings();
  const laneId = 'deepseek-key-123';
  bridge.getModelSettings = async () => ({
    ...settings,
    providers: [...settings.providers, {
      ...settings.providers[0], id: laneId, label: 'DeepSeek · personal',
      models: [{ id: 'deepseek-flash', name: 'deepseek-flash', description: '', efforts: [], defaultEffort: null }],
      selectedModel: 'deepseek-flash', selectedEffort: '',
    }],
  });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const live = useCallback(() => bridge, []);
    const creation = useAgentTaskCreation({ workspaceId: 'workspace-1',
      initialLaunch: { laneId, model: 'deepseek-flash', effort: null }, bridge: live, onOpenTask: () => {} });
    return <TaskComposer {...creation.composer} />;
  }
  flushSync(() => root.render(<Harness />));
  try {
    await settle();
    assert(host.textContent?.includes('draft PR · CI fix') === true, 'DeepSeek must inherit the saved automation defaults');
    const ciOption = [...host.querySelectorAll<HTMLLabelElement>('.agt-automation-option')]
      .find(label => label.textContent?.includes('Auto CI fix'))?.querySelector<HTMLInputElement>('input');
    assert(ciOption !== undefined && ciOption !== null && ciOption.checked, 'DeepSeek must start with saved Auto CI fix enabled');
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null, 'DeepSeek task composer must exist');
    changeInput(input, 'Fix failing checks');
    await settle();
    const send = host.querySelector<HTMLButtonElement>('.agt-send');
    assert(send !== null && !send.disabled, 'Ready DeepSeek task must be sendable with saved automation');
    send.click();
    await settle();
    const command = bridge.commands.find(item => item.name === 'createAgentTask');
    assert(command !== undefined, 'DeepSeek creation must submit one task');
    const request = command.args[0] as CreateAgentTaskRequest;
    assert(request.laneId === laneId && request.defaults.developmentAutomation?.autoCiFix === true
      && request.developmentAutomation?.autoCiFix === true, 'DeepSeek must inherit saved Auto CI fix for task and first turn');
    assert(request.defaults.developmentAutomation?.autoDraftPr === true && request.developmentAutomation?.autoDraftPr === true,
      'DeepSeek must inherit saved draft publication for task and first turn');
    return [{ name: 'DeepSeek creation inherits saved automation for task and first turn' }];
  }
  catch (error) { return [{ name: 'DeepSeek automation submission', error: error instanceof Error ? error.message : String(error) }]; }
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
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    listener({ subscriptionId: 'retry', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const confirm = window.confirm;
  window.confirm = () => { throw new Error('The task screen must ask through its own dialog'); };
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
    const confirmRetry = document.querySelector<HTMLButtonElement>('dialog.bq-confirm-dialog .bq-button--primary');
    assert(confirmRetry?.textContent === 'Retry', 'Retry must be confirmed in the app dialog before it starts a turn');
    assert(bridge.commands.every(command => command.name !== 'startAgentTurn'), 'An unanswered question must start no turn');
    confirmRetry.click();
    await settle();
    const command = bridge.commands.find(command => command.name === 'startAgentTurn');
    assert(command !== undefined, 'Explicit retry must submit the durable input');
    const request = command.args[0] as StartAgentTurnRequest;
    assert(request.developmentAutomation?.autoDraftPr === false && request.developmentAutomation.autoCiFix === false,
      'Retry must retain the accepted local publication policy despite current default-on settings');
    return [{ name: 'Authentication retry retains accepted automation instead of current task defaults' }];
  }
  catch (error) { return [{ name: 'Authentication retry policy', error: error instanceof Error ? error.message : String(error) }]; }
  finally { window.confirm = confirm; flushSync(() => root.unmount()); host.remove(); }
}

async function multilineDraft(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  function Harness() {
    const live = useCallback(() => bridge, []);
    const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', bridge: live, onOpenTask: () => {} });
    return <TaskComposer {...creation.composer} />;
  }
  flushSync(() => root.render(<Harness />));
  try {
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
    assert(input !== null, 'Task composer must exist');
    const press = (shiftKey: boolean): void => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey, bubbles: true, cancelable: true }));
    };
    changeInput(input, 'First line');
    await settle();
    const oneLine = input.clientHeight;
    assert(input.scrollWidth <= input.clientWidth, 'A draft must wrap instead of scrolling sideways');
    changeInput(input, 'First line\nSecond line\nThird line');
    await settle();
    assert(input.clientHeight > oneLine, `A multi-line draft must grow the composer, stayed at ${oneLine}px`);
    assert(input.scrollHeight <= input.clientHeight + 1, 'A grown composer must show the whole draft');
    press(true);
    await settle();
    assert(bridge.commands.filter(command => command.name === 'createAgentTask').length === 0, 'Shift+Enter must add a line rather than create the task');
    press(false);
    await settle();
    assert(bridge.commands.filter(command => command.name === 'createAgentTask').length === 1, 'Enter must still create the task');
    return [{ name: 'The task draft wraps onto new lines and grows the composer' }];
  }
  catch (error) { return [{ name: 'Multi-line task draft', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  return [...await createLocalTask(), ...await createDeepSeekTask(), ...await multilineDraft(), ...await retryLocalTurn(), ...await localReviewExperiments(), ...await localReviewExperiments(true), ...await continuationAdmission(), ...await stopPreservesTask(), ...await pullRequestLookupPolicy(), ...await worktreeVersionRefresh(), ...await worktreeVersionRefresh(true), ...await runCiAutofixScenarios(), ...await runDevelopmentDeliveryScenarios()];
}

async function localReviewExperiments(reloadPrivate = false): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  let automationReads = 0;
  if (reloadPrivate) bridge.getDevelopmentAutomation = async () => { automationReads++; throw new Error('Automation settings unavailable'); };
  const originalBridge = Object.getOwnPropertyDescriptor(window, 'bridge');
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    ...bridge,
    fetchPullRequestDetailByRef: async () => { throw new Error('PR detail unavailable in this fixture'); },
    refreshPullRequestDetailByRef: async () => { throw new Error('PR detail unavailable in this fixture'); },
    refreshAllPullRequestDetailByRef: async () => { throw new Error('PR detail unavailable in this fixture'); },
    fetchPrDiffFiles: async () => [],
    fetchPrDiffCommits: async () => [],
    getUserProfile: async (): Promise<null> => null,
    findAgentReview: async (): Promise<null> => null,
  } });
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null,
    developmentContinuationUnfinished: false, localReviewHead: 'a'.repeat(40) };
  const turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  const pr: PullRequestDto = {
    id: 1, repo: 'acme/demo', number: 1, title: 'Development result', author: 'alice', htmlUrl: 'https://github.com/acme/demo/pull/1',
    createdAt: '', updatedAt: '', origin: 'AUTHORED', labels: [], labelColors: null, draft: true, viewedAt: null, reviewedAt: null,
    handledAction: null, requestedReviewers: [], ciStatus: null, additions: 1, deletions: 0, commentCount: 0,
    state: 'open', mergeable: null, mergeableState: null, headPushedAt: null, headRef: task.generatedBranch,
  };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.listAgentTaskPullRequests = async () => [pr];
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    listener({ subscriptionId: 'local-review', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const complete = bridge.completeAgentTask;
  bridge.completeAgentTask = async (...args) => {
    const result = await complete(...args);
    task = { ...task, state: 'COMPLETED', localReviewHead: null, version: task.version + 1 };
    return result;
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    assert(!host.textContent?.includes('Local review · changes stay uncommitted')
      && !host.textContent?.includes('Sync PR head') && !host.textContent?.includes('Finish local review')
      && !host.textContent?.includes('Review locally'), 'Private review must not add a manual housekeeping panel');
    assert(host.querySelector('.agt-automation') === null, 'Private review must hide development publication controls');
    const completeButton = host.querySelector<HTMLButtonElement>('button[aria-label="Mark it complete"]');
    assert(completeButton !== null && !completeButton.disabled, 'Private review uses the normal completion action');
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null && !input.disabled, 'Review mode keeps the normal composer available for experiments');
    const experimentRequest = reloadPrivate ? '@ci Add another private test' : 'Add another private test';
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, experimentRequest);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    assert(!bridge.commands.some(command => command.name === 'syncAgentLocalReview' || command.name === 'finishAgentLocalReview'),
      'Reading private review must not move the checkout or discard experiments');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled === false, 'An experiment request can use the normal composer');
    if (reloadPrivate) {
      assert(automationReads === 0, 'Private review must not depend on unavailable development automation settings');
      host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.click();
      await settle();
      const sent = bridge.commands.find(command => command.name === 'startAgentTurn');
      assert(sent !== undefined && (sent.args[0] as StartAgentTurnRequest).developmentAutomation === undefined,
        'Private experiment submissions must not carry development publication selections');
      assert((sent.args[0] as StartAgentTurnRequest).message === experimentRequest
        && !bridge.commands.some(command => command.name === 'sendCiAutofixMessage'), 'Private review must send @ci text to its reviewer without routing a remote-writing CI task');
      return [{ name: 'Reloaded private review submits experiments without reading development automation settings' }];
    }
    completeButton.click();
    await settle();
    assert(bridge.commands.filter(command => command.name === 'completeAgentTask').length === 1,
      'Clean private review must complete through the ordinary action');
    assert(!bridge.commands.some(command => command.name === 'finishAgentLocalReview'), 'Completion must not discard experiments');
    return [{ name: 'Private review completes normally without a housekeeping panel or discard action' }];
  }
  catch (error) { return [{ name: 'Local review experiments', error: error instanceof Error ? error.message : String(error) }]; }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    if (originalBridge === undefined) delete (window as { bridge?: unknown }).bridge;
    else Object.defineProperty(window, 'bridge', originalBridge);
  }
}

async function stopPreservesTask(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, developmentContinuationUnfinished: true,
    mutationClaim: { kind: 'TURN', ownerId: event.turnId, acquiredAt: '2026-01-01T12:00:00Z' } };
  let turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'RUNNING' };
  const history = [event];
  let notify: (message: AgentStreamMessage) => void = () => {};
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'stop-preserved', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.getAgentRuntimeStopImpact = async () => ({ taskId: task.id, generation: `task:${task.version}`,
    activeTurnId: turn.state === 'RUNNING' ? turn.id : null, liveProcess: true });
  let stops = 0;
  bridge.stopAgentRuntime = async (taskId, workspaceId, generation) => {
    assert(taskId === task.id && workspaceId === task.workspaceId && generation === `task:${task.version}`, 'Stop must target the freshly confirmed task generation');
    stops++;
    turn = { ...turn, version: turn.version + 1, state: 'INTERRUPTED', terminalReason: 'The request was interrupted' };
    task = { ...task, version: task.version + 1 };
    if (stops === 1) throw new Error('Shutdown remains unverified. Follow the process diagnostic before checking again.');
    task = { ...task, mutationClaim: null, developmentContinuationUnfinished: false };
    const stopped: AgentEventEnvelope = { ...event, eventId: 'stopped-preserved', sequence: event.sequence + 1, turnId: null, type: 'lifecycle',
      payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Automation stopped. Your task and files are retained; send a message to continue.' } };
    history.push(stopped);
    notify({ subscriptionId: 'stop-preserved', taskId, kind: 'events', events: [stopped] });
    return task;
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'An active task must allow drafting the next message');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Continue with the smaller change');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    for (let attempt = 0; attempt < 2; attempt++) {
      const stop = attempt === 0 ? host.querySelector<HTMLButtonElement>('button[aria-label="Stop automation"]')
        : Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Stop automation…');
      assert(stop != null, 'The task must offer an explicit shutdown check');
      stop.click();
      await settle();
      const dialog = document.querySelector('dialog');
      assert(dialog?.textContent?.includes('keeps the task open') === true, 'Stop must explain task preservation');
      const confirm = Array.from(dialog.querySelectorAll('button')).find(button => button.textContent === 'Stop automation');
      assert(confirm !== undefined, 'The existing Stop confirmation must be required');
      confirm.click();
      await settle();
      if (attempt === 0) {
        assert(host.textContent?.includes('Could not verify shutdown') === true, 'An unresolved shutdown must remain blocked');
        assert(host.textContent?.includes('Follow the process diagnostic') === true, 'The backend diagnostic must remain visible');
        assert(host.querySelector('.agt-recovery .agt-spin') === null && host.querySelector('.agt-state__dot') === null, 'Blocked shutdown must not claim background progress');
        assert(host.querySelector('textarea') === null, 'Unverified writers must block new work');
      }
    }
    assert(task.state === 'OPEN' && task.terminalAt === null, 'Verified Stop must preserve this open task');
    assert(host.querySelector<HTMLTextAreaElement>('textarea')?.value === 'Continue with the smaller change', 'Verified Stop must restore the retained draft');
    assert(!bridge.commands.some(command => command.name === 'startAgentTurn' || command.name === 'markAgentTaskFailed' || command.name === 'createAgentTask'), 'Stop must not replay input, fail or replace the task');
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'The user must be able to continue in the same task');
    const automation = Array.from(host.querySelectorAll<HTMLInputElement | HTMLSelectElement>('.agt-automation input, .agt-automation select'));
    assert(automation.length === 5 && automation.every(control => control.disabled), 'A started task must show its publication automation read-only');
    send.click();
    await settle();
    const requests = bridge.commands.filter(command => command.name === 'startAgentTurn');
    assert(requests.length === 1 && (requests[0].args[0] as StartAgentTurnRequest).message === 'Continue with the smaller change', 'Only the explicit new message may start a turn');
    return [{ name: 'Verified Stop preserves the task and draft while unresolved shutdown remains blocked' }];
  } catch (error) { return [{ name: 'Stop preserves the open task', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
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
    assert(document.body.textContent?.includes('keeps the task open') === true, 'Stop must explain that the task is preserved');
    const cancel = Array.from(document.querySelectorAll('button')).find(button => button.textContent === 'Cancel');
    assert(cancel !== undefined, 'Existing stop confirmation must remain cancellable');
    cancel.click();
    await settle();
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false };
    turn = { ...turn, version: turn.version + 1, developmentResult: { readyForDelivery: false, message: 'Which change should I use?' } };
    notify({ subscriptionId: 'continuation', taskId: task.id, kind: 'events', events: [{ ...event,
      eventId: 'automation-settled', sequence: event.sequence + 1, turnId: null, type: 'lifecycle',
      payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Waiting for your answer' } }] });
    await settle();
    assert(input.placeholder === 'Waiting for your answer…', 'Released ownership must ask for the durable answer in the composer');
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

async function pullRequestLookupPolicy(): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null };
  let turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  let notify: (message: AgentStreamMessage) => void = () => {};
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'observations', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  const lookups = (): number => bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length;
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
    const settled = lookups();
    assert(settled > 0, 'A settled task must look up its branch pull requests once');
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Composer must exist');
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, 'Keep this draft');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    task = { ...task, version: task.version + 1 };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(lookups() === settled, 'Typing and unrelated task version changes must not reach GitHub');

    turn = { ...turn, id: 'next-development-turn', version: turn.version + 1, state: 'RUNNING', endedAt: null };
    task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } };
    emit('TURN_STARTED');
    await settle();
    assert(lookups() === settled, 'An active provider must not trigger a fresh lookup');
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: true };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(lookups() === settled, 'Marker changes while a provider runs must not reach GitHub');

    turn = { ...turn, version: turn.version + 1, state: 'COMPLETED', endedAt: '2026-01-01T12:01:00Z' };
    task = { ...task, version: task.version + 1, mutationClaim: null };
    emit('TURN_COMPLETED');
    await settle();
    const afterTurn = lookups();
    assert(afterTurn > settled, 'A settled latest turn must refresh the task pull requests');
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(lookups() > afterTurn, 'Final automation settlement without another turn must refresh the task PR list');
    assert(input.value === 'Keep this draft', 'Lookups must preserve the composer draft');

    const panel = host.querySelector<HTMLButtonElement>('[aria-label="Show or hide the side panel"]');
    assert(panel !== null, 'The side panel toggle must exist');
    const afterAutomation = lookups();
    for (let click = 0; click < 3; click++) {
      panel.click();
      await settle();
    }
    assert(lookups() === afterAutomation, 'Showing and hiding the side panel must not re-read GitHub');

    // The panel is open on its last click; a re-check must refresh under it, not blank it.
    bridge.listAgentTaskPullRequests = () => new Promise<PullRequestDto[]>(() => {});
    turn = { ...turn, id: 'later-development-turn', version: turn.version + 1, endedAt: '2026-01-01T12:02:00Z' };
    emit('TURN_COMPLETED');
    await settle();
    assert(host.textContent?.includes('Checking for a pull request') !== true,
      'A later lookup must leave the answered panel alone');
    return [{ name: 'Task pull requests are looked up only on settled turns and settled automation markers' }];
  }
  catch (error) { return [{ name: 'Task pull request lookup', error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}


async function worktreeVersionRefresh(conflictAfterRefresh = false): Promise<{ name: string; error?: string }[]> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture must identify a turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null,
    developmentContinuationUnfinished: false };
  let turn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), id: event.turnId, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  let taskReads = 0;
  let accepted = 0;
  const requests: StartAgentTurnRequest[] = [];
  bridge.getAgentTask = async () => { taskReads++; return task; };
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  let notify: (message: AgentStreamMessage) => void = () => {};
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'worktree-update', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.startAgentTurn = async request => {
    requests.push(request);
    if (conflictAfterRefresh && requests.length === 1) task = { ...task, version: task.version + 1 };
    if (request.expectedTaskVersion !== task.version) throw new Error(`Task version changed: ${task.id}`);
    accepted++;
    turn = { ...turn, id: 'after-worktree-update', state: 'RUNNING', endedAt: null };
    task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } };
    return turn;
  };
  let sequence = event.sequence;
  const emit = () => {
    const events = parseAgentEventSequence([{ ...event, eventId: `worktree-${++sequence}`, sequence, turnId: null,
      type: 'lifecycle', payload: { phase: 'WORKTREE_UPDATED', message: 'WORKTREE_INTERNAL_REFRESH_SIGNAL' } }], sequence - 1);
    const message: AgentStreamMessage = { subscriptionId: 'worktree-update', taskId: task.id, kind: 'events', events };
    notify(message);
    return message;
  };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const name = conflictAfterRefresh ? 'A racing worktree update preserves the input and requires an explicit retry'
    : 'Worktree save and run lifecycle updates refresh the version before Send without changing the draft';
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'The idle task must expose its composer');
    const draft = 'Keep this draft after saving\nthen explain the test result.';
    changeInput(input, draft); await settle();
    const initialReads = taskReads;
    task = { ...task, version: task.version + 2 };
    emit(); await settle();
    assert(taskReads > initialReads && input.value === draft, 'A background save must refresh the authoritative task and preserve the draft');
    const afterSaveReads = taskReads;
    task = { ...task, version: task.version + 1, mutationClaim: { kind: 'LOCAL_RUN', ownerId: 'run-1', acquiredAt: '' } };
    emit(); await settle();
    assert(taskReads > afterSaveReads && input.value === draft, 'A local run claim must reach the existing projection refresh');
    const afterClaimReads = taskReads;
    task = { ...task, version: task.version + 1, mutationClaim: null };
    const released = emit(); await settle();
    assert(taskReads > afterClaimReads && input.value === draft, 'Releasing a failed or completed run must preserve the draft and refresh its version');
    const afterReleaseReads = taskReads;
    notify(released); await settle();
    assert(taskReads === afterReleaseReads && requests.length === 0, 'Replayed worktree signals must neither reread nor submit the draft');
    assert(!host.textContent?.includes('WORKTREE_INTERNAL_REFRESH_SIGNAL'), 'Task-level worktree signals must stay out of the conversation');
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'The released task must allow the retained message to be sent');
    const expectedVersion = task.version;
    send.click(); await settle();
    assert(Number(requests.length) === 1 && requests[0].expectedTaskVersion === expectedVersion && requests[0].message === draft,
      'Send must use the newly observed version and unchanged message exactly once');
    if (conflictAfterRefresh) {
      assert(Number(accepted) === 0 && input.value === draft && host.textContent?.includes('Your input is preserved; try again.') === true,
        'A newer racing mutation must preserve the draft and explain the explicit retry');
      assert(!host.textContent?.includes(`Task version changed: ${task.id}`), 'A task UUID must not be presented as a meaningful version');
      const retryVersion = task.version;
      host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.click(); await settle();
      assert(Number(requests.length) === 2 && requests[1].expectedTaskVersion === retryVersion && requests[1].idempotencyKey === requests[0].idempotencyKey,
        'Only another explicit Send may retry with the refreshed version and retained request identity');
    }
    assert(Number(accepted) === 1 && String(input.value) === '', 'The accepted message must clear once without duplicate submission');
    return [{ name }];
  }
  catch (error) { return [{ name, error: error instanceof Error ? error.message : String(error) }]; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
