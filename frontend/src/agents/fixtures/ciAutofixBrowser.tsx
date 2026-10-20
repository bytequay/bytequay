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

import { createFixtureRoot } from '../../fixtures/reactFixture';
import { flushSync } from 'react-dom';
import type { AgentPendingApprovalDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto, CiAutofixRunDto } from '../../types';
import { AgentTaskList } from '../AgentTasksView';
import AgentTaskScreen from '../AgentTaskScreen';
import { CI_PROJECTION, CI_RUN } from './ciAutofix';
import { createPreviewBridge } from './previewBridge';
import { assert, changeInput, settle, element } from '../../fixtures/harness';

function pressEnter(input: HTMLTextAreaElement) { input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })); }

/** Advance five-second polling; ordinary rendering and stream coalescing use real timers. */
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
    tick: () => { const due = [...timers.values()]; timers.clear(); due.forEach(run => run()); },
    restore: () => { window.setTimeout = originalSet; window.clearTimeout = originalClear; timers.clear(); },
  };
}

async function ciReplyAndOwnership(steering = false): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture needs a source turn');
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), id: 'source-task', interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: CI_PROJECTION };
  const sourceEvent = { ...event, taskId: task.id };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  let run: CiAutofixRunDto = { ...CI_RUN };
  if (steering) {
    run = { ...run, status: 'ACTIVE', activity: 'FIXING', currentTurnId: 'ci-turn' };
    task = { ...task, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', currentTurnId: 'ci-turn' } };
  }
  let sourceNotify: (message: AgentStreamMessage) => void = () => {};
  let failReply = true;
  const replies: { runId: string; message: string; key: string; expectedTurnId?: string }[] = [];
  let sourceReads = 0;
  bridge.getAgentTask = async id => { sourceReads++; assert(id === task.id, 'CI child must never use the generic interactive task endpoint'); return task; };
  bridge.getAgentTurn = async () => sourceTurn;
  bridge.readAgentHistory = async id => {
    assert(id === task.id, 'The source screen must not load a separate CI conversation');
    return { events: [sourceEvent], beforeCursor: null, afterCursor: null, latestSequence: 1 };
  };
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, notify) => {
    if (id === task.id) sourceNotify = notify;
    notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.getCiAutofixRun = async () => run;
  bridge.sendCiAutofixMessage = async (runId, message, key, expectedTurnId) => {
    replies.push({ runId, message, key, expectedTurnId });
    if (failReply) throw new Error('Message transport unavailable');
    run = { ...run, version: run.version + 1, status: 'ACTIVE', activity: 'FIXING', canMessage: false };
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', canMessage: false } };
    return run;
  };
  const timers = ciTimers();
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const sourceInput = element<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]', host, 'Source composer must remain visible during CI');
    changeInput(sourceInput, 'Continue development later');
    await settle();
    pressEnter(sourceInput);
    assert(!bridge.commands.some(command => command.name === 'startAgentTurn'), 'Source Enter must not compete with CI');
    changeInput(sourceInput, '@ciOther');
    sourceInput.setSelectionRange(3, 3);
    sourceInput.dispatchEvent(new Event('select', { bubbles: true }));
    await settle();
    assert(host.querySelector('[role="option"]') === null && sourceInput.value === '@ciOther', 'Editing an unrelated mention must not offer or overwrite its @ci prefix');
    changeInput(sourceInput, '@');
    await settle();
    const option = host.querySelector('[role="option"]');
    assert(option?.textContent?.includes('@ci') === true && option.getAttribute('aria-selected') === 'true', 'Typing @ must select CI Autofix by default');
    sourceInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true }));
    await settle();
    assert(String(sourceInput.value) === '@' && replies.length === 0, 'IME Enter must not select a recipient or submit');
    pressEnter(sourceInput);
    await settle();
    assert(String(sourceInput.value) === '@ci ' && replies.length === 0, 'Choosing the default recipient must insert @ci without submitting');
    changeInput(sourceInput, '@ci Use the compatibility fix');
    await settle();
    assert(!Array.from(host.querySelectorAll('button')).some(button => button.textContent === 'Steer' || button.textContent === 'Run next'), 'CI messages must not expose source steering or queueing');
    assert(host.querySelector('button[aria-label="Stop automation"]') !== null, 'The main composer must retain Stop while sending to CI');
    pressEnter(sourceInput);
    pressEnter(sourceInput);
    await settle();
    assert(Number(replies.length) === 1, 'Simultaneous sends must make one CI message request');
    assert(String(sourceInput.value) === '@ci Use the compatibility fix', 'Transport failure must preserve the complete routed draft');
    assert(host.textContent?.includes('Message transport unavailable') === true, 'Transport failure must remain visible for an explicit retry');
    if (steering) {
      task = { ...task, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', currentTurnId: 'later-ci-turn' } };
      timers.tick();
      await settle();
    }
    failReply = false;
    host.querySelector<HTMLButtonElement>('button[aria-label="Send to CI agent"]')?.click();
    await settle();
    assert(Number(replies.length) === 2 && replies[0].key === replies[1].key, 'Explicit retry must retain the same CI message idempotency key');
    assert(replies.every(reply => reply.expectedTurnId === (steering ? 'ci-turn' : undefined)), 'Retry must retain the original CI turn instead of steering a later repair');
    assert(replies[1].runId === CI_RUN.id && replies[1].message === 'Use the compatibility fix', 'Reply must target the linked run and strip the recipient token');
    assert(!bridge.commands.some(command => ['startAgentTurn', 'steerAgentTurn', 'queueAgentInput'].includes(command.name)), 'CI replies must never use generic task delivery');
    assert(String(sourceInput.value) === '', 'Accepted CI reply must clear the composer');
    changeInput(sourceInput, '@ci Keep another CI draft');
    await settle();
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, canMessage: false } };
    timers.tick();
    await settle();
    assert(String(sourceInput.value) === '@ci Keep another CI draft', 'Source polling must not discard a CI draft');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send to CI agent"]')?.disabled === true, 'Revoked CI message authority must disable Send');
    pressEnter(sourceInput);
    assert(Number(replies.length) === 2 && !bridge.commands.some(command => command.name === 'startAgentTurn'), 'Revoked CI authority must block Enter without falling back to the source');
    task = { ...task, version: task.version + 1, state: 'FAILED', developmentContinuationUnfinished: false };
    sourceNotify({ subscriptionId: task.id, taskId: task.id, kind: 'events', events: [{ ...sourceEvent, eventId: 'source-stopped', sequence: 2,
      turnId: null, type: 'lifecycle', payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: 'Stopped' } }] });
    await settle();
    const terminalReads = sourceReads;
    timers.tick(); await settle();
    assert(sourceReads === terminalReads, 'Terminal parent must stop CI polling');
    return { name: `@ci ${steering ? 'steers' : 'resumes'}, preserves retry identity, and honors revoked CI authority` };
  } catch (error) { return { name: `CI ${steering ? 'steering' : 'reply'} routing and ownership`, error: error instanceof Error ? error.message : String(error) }; }
  finally { dispose(); timers.restore(); }
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
  bridge.getCiAutofixRun = async () => ({ ...CI_RUN, status: 'ACTIVE', activity: 'WATCHING_CI', currentTurnId: null, parentTaskId: task.id });
  const timers = ciTimers();
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const before = reads;
    const lookups = bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length;
    pauseRead = true;
    timers.tick();
    await settle();
    timers.tick(); await settle();
    assert(reads === before + 1, 'A pending source read must not schedule an overlapping poll');
    assert(resolveRead !== null, 'Polling read must be pending');
    (resolveRead as (value: AgentTaskDto) => void)({ ...task, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', attemptsUsed: 2 } });
    await settle();
    assert(host.textContent?.includes('Fixing CI 2/3') === true, 'Cheap task projection must update CI activity');
    assert(bridge.commands.filter(command => command.name === 'listAgentTaskPullRequests').length === lookups, 'CI polling must not re-read GitHub pull requests');
    timers.tick(); await settle();
    assert(reads === before + 2, 'The next poll starts only after the previous read settles');
    return { name: 'Source CI polling is sequential and does not poll GitHub' };
  } catch (error) { return { name: 'Source CI polling', error: error instanceof Error ? error.message : String(error) }; }
  finally { dispose(); timers.restore(); }
}

export async function runCiAutofixScenarios(): Promise<{ name: string; error?: string }[]> {
  return [await ciReplyAndOwnership(), await ciReplyAndOwnership(true), await sourcePolling(), await ciApprovalCard(), await laterRunAndRecovery(), await deleteReferences(), await finishedCiRouting()];
}

async function ciApprovalCard(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture needs a source turn');
  const task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), id: 'source-task', interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: true,
    developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', canMessage: false } };
  const sourceEvent = { ...event, taskId: task.id };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  const approval: AgentPendingApprovalDto = {
    approvalId: 'ci-approval', taskId: 'ci-task', turnId: 'ci-turn', source: event.source,
    occurredAt: event.occurredAt, title: 'Allow Codex command', detail: 'mvn test', approvalKind: 'COMMAND',
    scope: ['run: mvn test'], sandboxed: false, availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
  };
  let parentTaskId = 'other-task';
  let ciApprovalReads = 0;
  bridge.getAgentTask = async id => { assert(id === task.id, 'The CI child is not an interactive task'); return task; };
  bridge.getAgentTurn = async () => sourceTurn;
  bridge.readAgentHistory = async () => ({ events: [sourceEvent], beforeCursor: null, afterCursor: null, latestSequence: 1 });
  bridge.subscribeAgentTask = async (id, _after, notify) => { notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' }); return () => {}; };
  bridge.getCiAutofixRun = async () => ({ ...CI_RUN, status: 'ACTIVE', activity: 'FIXING', currentTurnId: approval.turnId, parentTaskId });
  bridge.readAgentPendingApprovals = async (id, turnId) => {
    if (id === task.id) return [];
    ciApprovalReads++;
    assert(id === approval.taskId && turnId === approval.turnId, 'The source must read only its verified CI turn');
    return [approval];
  };
  const timers = ciTimers();
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    timers.tick();
    await settle();
    assert(host.querySelector('[aria-label="Approval: CI Autofix · Allow Codex command"]') === null, 'A CI run for another parent cannot show a card');
    assert(ciApprovalReads === 0, 'A mismatched CI run must not read approvals');
    parentTaskId = task.id;
    timers.tick();
    await settle();
    const allow = host.querySelector<HTMLButtonElement>('button[aria-label="Allow once: CI Autofix · Allow Codex command"]');
    assert(Number(ciApprovalReads) === 1 && allow !== null && host.querySelector('.agt-auto-pill__label')?.textContent?.includes('Needs approval') === true, 'The source Task screen must show the linked CI approval');
    allow.click();
    await settle();
    assert(bridge.commands.some(command => command.name === 'resolveAgentApproval' && command.args[0] === approval.approvalId && command.args[1] === 'ALLOW'), 'The card must answer the CI approval');
    assert(host.querySelector('[aria-label="Approval: CI Autofix · Allow Codex command"]') === null, 'The answered card must disappear before the next poll');
    return { name: 'Source Task screen shows and answers verified CI approvals' };
  } catch (error) { return { name: 'CI approval card', error: error instanceof Error ? error.message : String(error) }; }
  finally { dispose(); timers.restore(); }
}

async function finishedCiRouting(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null, 'Fixture needs a source turn');
  const task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null,
    developmentContinuationUnfinished: false, developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'CANCELLED', canMessage: false,
      summary: 'Development automation stopped; local and remote results were preserved' } };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => sourceTurn;
  bridge.readAgentHistory = async () => ({ events: [event], beforeCursor: null, afterCursor: null, latestSequence: event.sequence });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, notify) => { notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' }); return () => {}; };
  let runReads = 0;
  bridge.getCiAutofixRun = async () => { runReads++; throw new Error('Finished CI must not start recovery polling'); };
  const replies: string[] = [];
  bridge.sendCiAutofixMessage = async runId => { replies.push(runId); return CI_RUN; };
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    assert(host.querySelector('.agt-ci-panel, .agt-ci-history, .agt-ci-checks, .agt-ci-recovery') === null, 'The task must not render a CI log or recovery panel');
    assert(runReads === 0, 'Finished CI must not start another recovery poll');
    assert(!host.textContent?.includes('View CI Autofix history') && !host.textContent?.includes('Previous CI Autofix run'), 'Finished CI must not add history controls or summaries');
    assert(!host.textContent?.includes(task.developmentCi?.summary ?? 'missing summary'), 'The removed CI run summary must not remain above the composer');
    const input = element<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]', host, 'A completed CI run must leave the source composer available');
    changeInput(input, '@');
    await settle();
    const option = host.querySelector<HTMLButtonElement>('[role="option"]');
    assert(option !== null && option.textContent?.includes('@ci') === true, '@ must still offer CI as the default recipient after a run ends');
    option.click();
    await settle();
    assert(input.value === '@ci ' && replies.length === 0, 'Clicking a recipient must insert the mention without sending');
    changeInput(input, 'Please @CI retry the check');
    await settle();
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send to CI agent"]')?.disabled === true, 'A finished run must not accept a routed message');
    pressEnter(input);
    await settle();
    assert(replies.length === 0 && !bridge.commands.some(command => command.name === 'startAgentTurn'), 'A finished CI recipient must never fall back to source Send');
    assert(String(input.value) === 'Please @CI retry the check', 'Blocked CI input must retain its draft');
    changeInput(input, 'Continue development after CI');
    await settle();
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, 'Ordinary source input must be available after CI finishes');
    send.click();
    await settle();
    const command = bridge.commands.find(entry => entry.name === 'startAgentTurn');
    const request = command?.args[0] as { taskId?: string; message?: string } | undefined;
    assert(request?.taskId === task.id && request.message === 'Continue development after CI', 'Ordinary source input must use the normal turn endpoint');
    assert(replies.length === 0, 'Ordinary source input must not restart CI');
    return { name: 'Finished CI removes its log and panel, blocks @ci, and permits ordinary source messages' };
  } catch (error) { return { name: 'Finished CI routing', error: error instanceof Error ? error.message : String(error) }; }
  finally { dispose(); }
}

async function laterRunAndRecovery(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null && event.type === 'user_message', 'Fixture needs a source user turn');
  const oldCi = { ...CI_PROJECTION, status: 'FINISHED' as const, finishReason: 'GREEN', canMessage: false };
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), id: 'source-task', interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: true, developmentCi: oldCi };
  const sourceEvent = { ...event, taskId: task.id };
  const sourceTurn: AgentTurnDto = { ...await bridge.getAgentTurn(event.turnId), taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z' };
  bridge.getAgentTask = async id => { assert(id === task.id, 'CI child must not use generic task GET'); return task; };
  bridge.getAgentTurn = async () => sourceTurn;
  bridge.readAgentHistory = async () => ({ events: [sourceEvent], beforeCursor: null, afterCursor: null, latestSequence: 1 });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, notify) => { notify({ subscriptionId: id, taskId: id, kind: 'state', state: 'open' }); return () => {}; };
  const replies: { runId: string; message: string }[] = [];
  bridge.sendCiAutofixMessage = async (runId, message) => {
    replies.push({ runId, message });
    return { ...CI_RUN, id: 'new-ci-run', agentTaskId: 'new-ci-task' };
  };
  const timers = ciTimers();
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    await settle();
    const input = element<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]', host, 'Source composer must remain available for drafting');
    changeInput(input, 'Please @ci continue after this delivery');
    await settle();
    assert(host.querySelector('.agt-auto-pill__label')?.textContent === 'Automation active', 'Historical green must not label a new continuation CI passed');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send to CI agent"]')?.disabled === true, 'The previous finished run must not accept CI messages');

    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, runId: 'new-ci-run', agentTaskId: 'new-ci-task' } };
    timers.tick();
    await settle();
    assert(input.value === 'Please @ci continue after this delivery', 'Replacing the CI run must preserve the composer draft');
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send to CI agent"]');
    assert(send !== null && !send.disabled, 'The current paused run must become the message recipient');
    send.click();
    await settle();
    assert(replies.length === 1 && replies[0].runId === 'new-ci-run' && replies[0].message === 'Please continue after this delivery', 'A CI mention must route to the replacement run and strip its token');

    task = { ...task, version: task.version + 1,
      mutationClaim: { kind: 'RECOVERY', ownerId: 'lost-owner', acquiredAt: '2026-01-01T12:00:00Z' },
      developmentCi: { ...CI_PROJECTION, runId: 'new-ci-run', agentTaskId: 'new-ci-task', canMessage: false } };
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
    return { name: 'A later CI run receives @ci messages, while unresolved linked cleanup only offers Stop' };
  } catch (error) { return { name: 'Later CI run and recovery', error: error instanceof Error ? error.message : String(error) }; }
  finally { dispose(); timers.restore(); }
}

async function deleteReferences(): Promise<{ name: string; error?: string }> {
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  const task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), title: 'CI delivery task', mutationClaim: null,
    developmentContinuationUnfinished: false, developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'ERROR', canMessage: false } };
  const { host, root, dispose } = createFixtureRoot();
  let deleted = false;
  flushSync(() => root.render(<AgentTaskList tasks={[task]} loading={false} error={null} laneId="codex" providers={[]} models={[]}
    selection={null} draft="" blocker="No model" onDraftChange={() => {}} onSelectProvider={() => {}} onSelectModel={() => {}}
    onCreate={() => {}} onOpenTask={() => {}} onDelete={async () => { deleted = true; }} />));
  try {
    const remove = element<HTMLButtonElement>('button[aria-label="Delete task: CI delivery task"]', host, 'Settled source task must offer Delete');
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
  finally { dispose(); }
}
