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
import type { AgentStreamMessage, AgentTaskDto, AgentTurnDto, CiAutofixRunDto } from '../../types';
import { AgentTaskList } from '../AgentTasksView';
import AgentTaskScreen from '../AgentTaskScreen';
import { CI_PROJECTION, CI_RUN } from './ciAutofix';
import { createPreviewBridge } from './previewBridge';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
function changeInput(input: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Only advance CI's five-second reads; ordinary rendering and stream coalescing use real timers. */
function ciTimers() {
  const originalSet = window.setTimeout;
  const originalClear = window.clearTimeout;
  let id = 1_000_000;
  const timers = new Map<number, () => void>();
  Object.defineProperty(window, 'setTimeout', { configurable: true, writable: true, value: (handler: TimerHandler, delay?: number, ...args: unknown[]): number => {
    if (delay !== 5000 || typeof handler !== 'function') return originalSet(handler, delay, ...args);
    const key = ++id;
    timers.set(key, () => handler(...args));
    return key;
  } });
  Object.defineProperty(window, 'clearTimeout', { configurable: true, writable: true, value: (key?: number) => { if (key !== undefined && timers.delete(key)) return; originalClear(key); } });
  return {
    size: () => timers.size,
    tick: () => { const due = [...timers.values()]; timers.clear(); due.forEach(run => run()); },
    restore: () => { window.setTimeout = originalSet; window.clearTimeout = originalClear; timers.clear(); },
  };
}

async function ciReplyAndOwnership(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture needs a source turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), id: 'source-task', interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: CI_PROJECTION };
  const sourceEvent = { ...event, taskId: task.id };
  const childEvent = { ...sourceEvent, taskId: 'ci-task', turnId: 'ci-turn', eventId: 'ci-user', sequence: 1 };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  const childTurn: AgentTurnDto = { ...sourceTurn, id: 'ci-turn', taskId: 'ci-task' };
  let run: CiAutofixRunDto = { ...CI_RUN };
  let sourceNotify: (message: AgentStreamMessage) => void = () => {};
  let failReply = true;
  const replies: { runId: string; message: string; key: string }[] = [];
  bridge.getAgentTask = async id => { assert(id === task.id, 'CI child must never use the generic interactive task endpoint'); return task; };
  bridge.getAgentTurn = async id => id === childTurn.id ? childTurn : sourceTurn;
  bridge.readAgentHistory = async id => ({ events: [id === task.id ? sourceEvent : childEvent], beforeCursor: null, afterCursor: null, latestSequence: 1 });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, notify) => {
    if (id === task.id) sourceNotify = notify;
    notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.getCiAutofixRun = async () => run;
  bridge.sendCiAutofixMessage = async (runId, message, key) => {
    replies.push({ runId, message, key });
    if (failReply) throw new Error('Message transport unavailable');
    run = { ...run, version: run.version + 1, status: 'ACTIVE', activity: 'FIXING', canMessage: false };
    return run;
  };
  const timers = ciTimers();
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const sourceInput = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(sourceInput !== null, 'Source composer must remain visible during CI');
    changeInput(sourceInput, 'Continue development later');
    await settle();
    sourceInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    assert(!bridge.commands.some(command => command.name === 'startAgentTurn'), 'Source Enter must not compete with CI');
    const open = Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Open CI Autofix');
    assert(open !== undefined, 'Source must navigate to its CI run');
    open.click();
    await settle();
    const reply = host.querySelector<HTMLTextAreaElement>('.agt-ci-reply textarea');
    assert(reply !== null, 'The linked CI task must have a scoped reply form');
    changeInput(reply, 'Use the compatibility fix');
    await settle();
    const form = host.querySelector<HTMLFormElement>('.agt-ci-reply');
    assert(form !== null, 'CI reply form must exist');
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    assert(replies.length === 1, 'Simultaneous child sends must make one CI message request');
    assert(reply.value === 'Use the compatibility fix', 'Transport failure must preserve the CI draft');
    assert(sourceInput.value === 'Continue development later', 'CI interaction must preserve the source draft');
    failReply = false;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    assert(Number(replies.length) === 2 && replies[0].key === replies[1].key, 'Explicit retry must retain the same CI message idempotency key');
    assert(replies[1].runId === CI_RUN.id, 'Reply must target the CI run');
    assert(!bridge.commands.some(command => command.name === 'startAgentTurn'), 'No child reply may go through generic task Send');
    assert(String(reply.value) === '', 'Accepted child reply should clear only its own draft');
    changeInput(reply, 'Keep another CI draft');
    await settle();
    run = { ...run, version: run.version + 1, status: 'PAUSED', activity: 'PAUSED', canMessage: false };
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, canMessage: false } };
    timers.tick();
    await settle();
    assert(String(reply.value) === 'Keep another CI draft', 'Run polling must not discard a CI draft');
    assert(host.querySelector<HTMLButtonElement>('.agt-ci-reply button')?.disabled === true, 'Revoked child message authority must disable Send');
    task = { ...task, version: task.version + 1, state: 'FAILED', developmentContinuationUnfinished: false };
    sourceNotify({ subscriptionId: task.id, taskId: task.id, kind: 'events', events: [{ ...sourceEvent, eventId: 'source-stopped', sequence: 2,
      turnId: null, type: 'lifecycle', payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Stopped' } }] });
    await settle();
    assert(timers.size() === 0, 'Terminal parent must stop both CI polling loops');
    return { name: 'CI replies preserve source drafts, use child scope, retain retry identity and stop on revoked authority' };
  } catch (error) { return { name: 'CI child reply and ownership', error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); timers.restore(); }
}

async function sourcePolling(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture needs a source turn');
  const task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null,
    developmentContinuationUnfinished: true, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE' as const, activity: 'WATCHING_CI' } };
  const turn = { ...await bridge.getAgentTurn(event.turnId), state: 'COMPLETED' };
  let resolveRead: ((value: AgentTaskDto) => void) | null = null;
  let pauseRead = false;
  let reads = 0;
  bridge.getAgentTask = async () => { reads++; return pauseRead ? await new Promise<AgentTaskDto>(resolve => { resolveRead = resolve; }) : task; };
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.subscribeAgentTask = async (id, _after, notify) => { notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' }); return () => {}; };
  const timers = ciTimers();
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const before = reads;
    const lookups = bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length;
    pauseRead = true;
    timers.tick();
    await settle();
    assert(reads === before + 1 && timers.size() === 0, 'A pending source read must not schedule an overlapping poll');
    assert(resolveRead !== null, 'Polling read must be pending');
    (resolveRead as (value: AgentTaskDto) => void)({ ...task, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', attemptsUsed: 2 } });
    await settle();
    assert(host.textContent?.includes('Fixing CI 2/3') === true, 'Cheap task projection must update CI activity');
    assert(bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length === lookups, 'CI polling must not re-read GitHub pull requests');
    assert(timers.size() === 1, 'The next poll starts only after the previous read settles');
    return { name: 'Source CI polling is sequential and does not poll GitHub' };
  } catch (error) { return { name: 'Source CI polling', error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); timers.restore(); }
}

export async function runCiAutofixScenarios(): Promise<{ name: string; error?: string }[]> {
  return [await ciReplyAndOwnership(), await sourcePolling(), await laterRunAndRecovery(), await deleteReferences()];
}

async function laterRunAndRecovery(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null && event.type === 'user_message', 'Fixture needs a source user turn');
  const oldCi = { ...CI_PROJECTION, status: 'FINISHED' as const, finishReason: 'GREEN', canMessage: false };
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), id: 'source-task', interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: oldCi };
  let run: CiAutofixRunDto = { ...CI_RUN, version: 100, status: 'FINISHED', finishReason: 'GREEN', canMessage: false };
  const sourceEvent = { ...event, taskId: task.id };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  const childTurn = { ...sourceTurn, id: 'old-ci-turn', taskId: 'ci-task' };
  const newTurn = { ...childTurn, id: 'new-ci-turn', taskId: 'new-ci-task' };
  bridge.getAgentTask = async id => { assert(id === task.id, 'CI child must not use generic task GET'); return task; };
  bridge.getAgentTurn = async id => id === newTurn.id ? newTurn : id === childTurn.id ? childTurn : sourceTurn;
  bridge.readAgentHistory = async id => ({ events: [id === task.id ? sourceEvent : { ...sourceEvent, taskId: id,
    turnId: id === newTurn.taskId ? newTurn.id : childTurn.id, eventId: `user-${id}`, payload: {
      text: id === newTurn.taskId ? 'Repair the new CI run' : 'Repair the earlier CI run', attachments: [], deliveryKind: 'TURN',
    } }], beforeCursor: null, afterCursor: null, latestSequence: 1 });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, notify) => { notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' }); return () => {}; };
  bridge.getCiAutofixRun = async id => { assert(id === run.id, 'Panel must load the current run'); return run; };
  const timers = ciTimers();
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'Source composer must remain available for drafting');
    changeInput(input, 'Continue after this delivery');
    await settle();
    assert(host.querySelector('.agt-status')?.textContent?.startsWith('Automation running') === true, 'Historical green must not label a new continuation CI passed');
    assert(!host.textContent?.includes('CI Autofix is running'), 'A finished historical CI run must not display the active CI barrier');
    Array.from(host.querySelectorAll('button')).find(button => button.textContent === 'Open CI Autofix')?.click();
    await settle();
    assert(host.querySelector('.agt-ci-panel')?.textContent?.includes('Repair the earlier CI run') === true, 'The earlier run must be inspectable');
    assert(!host.querySelector('.agt-ci-panel')?.textContent?.includes('Stop automation'), 'The historical CI panel must not offer active controls');

    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, runId: 'new-ci-run', agentTaskId: newTurn.taskId } };
    run = { ...CI_RUN, id: 'new-ci-run', agentTaskId: newTurn.taskId, version: 1 };
    timers.tick();
    await settle();
    const panel = host.querySelector('.agt-ci-panel');
    assert(panel?.textContent?.includes('Repair the new CI run') === true, 'A new run version1 must replace the earlier version100 conversation');
    assert(!panel?.textContent?.includes('Repair the earlier CI run'), 'The old transcript must not remain under the new run');
    assert(panel?.querySelector('textarea[id="ci-reply-new-ci-run"]') !== null, 'Replies must now target the new run');
    assert(input.value === 'Continue after this delivery', 'Replacing the CI run must preserve the source draft');

    task = { ...task, version: task.version + 1,
      mutationClaim: { kind: 'RECOVERY', ownerId: 'lost-owner', acquiredAt: '2026-01-01T12:00:00Z' },
      developmentCi: { ...CI_PROJECTION, runId: run.id, agentTaskId: newTurn.taskId, canMessage: false } };
    run = { ...run, version: 2, canMessage: false };
    timers.tick();
    await settle();
    const recovery = host.querySelector('.agt-recovery');
    assert(recovery !== null && !recovery.textContent?.includes('Mark task failed'), 'Linked CI recovery must not bypass Stop through Mark failed');
    assert(!recovery.textContent?.includes('Retry last message'), 'Failed continuation must not offer delivery retry');
    const stop = Array.from(recovery.querySelectorAll('button')).find(button => button.textContent === 'Stop automation…');
    assert(stop !== undefined && !stop.disabled, 'Stop must remain available while linked cleanup is unresolved');
    stop.click();
    await settle();
    assert(bridge.commands.some(command => command.name === 'getAgentRuntimeStopImpact'), 'Recovery Stop must use the existing stop confirmation path');
    assert(!bridge.commands.some(command => command.name === 'markAgentTaskFailed'), 'Recovery controls must not call Mark failed');
    return { name: 'A later CI run replaces old history, while unresolved linked cleanup only offers Stop' };
  } catch (error) { return { name: 'Later CI run and recovery', error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); timers.restore(); }
}

async function deleteReferences(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  const task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), title: 'CI delivery task', mutationClaim: null,
    developmentContinuationUnfinished: false, developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'ERROR', canMessage: false } };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let deleted = false;
  flushSync(() => root.render(<AgentTaskList tasks={[task]} loading={false} error={null} laneId="codex" providers={[]} models={[]}
    selection={null} draft="" blocker="No model" onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}}
    onCreate={() => {}} onOpenTask={() => {}} onDelete={async () => { deleted = true; }} />));
  try {
    const remove = host.querySelector<HTMLButtonElement>('button[aria-label="Delete task: CI delivery task"]');
    assert(remove !== null, 'Settled source task must offer Delete');
    remove.click();
    await settle();
    const dialog = document.querySelector('dialog');
    assert(task.generatedBranch !== null && dialog?.textContent?.includes(task.generatedBranch) === true, 'Delete must name the persisted generated branch');
    assert(dialog?.textContent?.includes(`bytequay/task/${task.id}`) === false, 'Delete must not reconstruct the generated branch from the task ID');
    assert(dialog?.textContent?.includes('Linked CI tasks and their local worktrees are also removed after their agents stop.') === true, 'Delete must disclose linked local cleanup');
    assert(dialog?.textContent?.includes('These remote results remain on GitHub:') === true, 'Delete must preserve remote results explicitly');
    assert(dialog?.querySelector('a[href="https://github.com/acme/demo/pull/17"]') !== null, 'Delete must show the retained PR reference');
    assert(dialog?.textContent?.includes('codex/change') === true, 'Delete must show the retained remote branch');
    assert(!deleted, 'Showing references must not delete before confirmation');
    return { name: 'Delete confirmation names linked local cleanup and preserved remote references' };
  } catch (error) { return { name: 'CI delete references', error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
