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
import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { assert, changeInput, settle, until } from '../../fixtures/harness';
import ReviewActivity from '../../pulls/agent-review/ReviewActivity';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import type { AgentPendingApprovalDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto, Bridge } from '../../types';
import { AgentHomeView } from '../AgentHome';
import AgentTaskScreen, { AgentTaskBody } from '../AgentTaskScreen';
import { AgentTaskList, useAgentTaskCreation } from '../AgentTasksView';
import { NO_AGENT_CAPABILITIES } from '../capabilities';
import { CHECKING_ACCEPTANCE, UNCONFIRMED_REQUEST } from '../contracts';
import type { AgentEventEnvelope } from '../events';
import { agentTaskReducer, initialAgentTaskState, type AgentTaskState } from '../model';
import { agentTurn, createPreviewBridge, previewTask, TASK_BODY_NOOPS } from './previewBridge';
export { run as sendHistoryPerformance } from './sendHistoryPerformanceBrowser';

const TASK_ID = 'composer-performance';
const AT = '2026-09-06T16:03:00Z';
const SOURCE: AgentEventEnvelope['source'] = { providerThreadId: 'root', providerTurnId: null, providerItemId: null,
  providerEventId: null, parentProviderThreadId: null, logicalAgentPath: '/' };
const workspaceBridge: Pick<Bridge, 'getLinkedWorkspaceWork' | 'getRelatedWorkspaceRequest' | 'getBackportChains'> = {
  getLinkedWorkspaceWork: async () => [], getRelatedWorkspaceRequest: async () => null, getBackportChains: async () => [],
};
const base = (sequence: number, turnId: string) => ({ schemaVersion: 2 as const, eventId: `event-${sequence}`,
  sequence, taskId: TASK_ID, turnId, occurredAt: AT, source: SOURCE });
const request = (sequence: number, turnId: string, text: string): AgentEventEnvelope => ({ ...base(sequence, turnId),
  type: 'user_message', payload: { text, attachments: [], deliveryKind: 'TURN' } });
const answer = (sequence: number, turnId: string, text: string, final = true): AgentEventEnvelope => ({ ...base(sequence, turnId),
  type: 'assistant_message', payload: { text, messageKind: final ? 'ANSWER' : 'COMMENTARY', phase: 'FINAL' } });

async function exercise(surface: 'task' | 'review', verifyCaching: boolean) {
  let payloadReads = 0;
  const events = Array.from({ length: 1000 }, (_, index): AgentEventEnvelope => {
    const turnId = `history-${Math.floor(index / 10)}`;
    const event = index % 10 === 0 ? request(index + 1, turnId, `Explain change ${turnId}`)
      : answer(index + 1, turnId, index % 10 === 9
        ? `## Result ${turnId}\n\n${'The implementation keeps **request ordering** and validates the `turnId` before sending.\n\n'.repeat(8)}`
        : `Inspecting change ${index}. The request and response remain associated.`, index % 10 === 9);
    const payload = event.payload;
    Object.defineProperty(event, 'payload', { get: () => { payloadReads++; return payload; } });
    return event;
  });
  const turns = Object.fromEntries(Array.from({ length: 100 }, (_, index) => {
    const id = `history-${index}`;
    return [id, agentTurn('COMPLETED', { id, taskId: TASK_ID, startedAt: AT, endedAt: '2026-09-06T16:04:00Z' })];
  }));
  const initial: AgentTaskState = { ...initialAgentTaskState(), task: { ...previewTask('workspace-1', TASK_ID), mutationClaim: null },
    turn: turns['history-99'], turns, events, lastSequence: 1000, latestTurnId: 'history-99', connection: 'open',
    capabilities: { ...NO_AGENT_CAPABILITIES, nestedAgentActivity: true },
    historyPages: [{ events, beforeCursor: null, afterCursor: null, latestSequence: 1000 }] };
  const reviewBase = reviewFixture({ runs: [], messages: [], byteQuayTurnIds: [] });
  const review = { ...reviewBase, task: { ...reviewBase.task, id: TASK_ID, nestedAgents: true } };
  const host = document.createElement('div');
  host.style.cssText = 'height: 640px; width: 840px; display: flex; flex-direction: column';
  document.body.append(host);
  const root = createRoot(host);
  let update = (_patch: Partial<AgentTaskState>): void => { throw new Error('Not mounted'); };
  let approve = (_approvals: AgentPendingApprovalDto[]): void => { throw new Error('Not mounted'); };
  function Conversation() {
    const [state, setState] = useState(initial);
    const [approvals, setApprovals] = useState<AgentPendingApprovalDto[]>([]);
    update = patch => setState(current => ({ ...current, ...patch }));
    approve = setApprovals;
    const onCompose = (text: string) => setState(current => agentTaskReducer(current, { type: 'composer/changed', text }));
    const onSend = () => setState(current => ({
      ...agentTaskReducer(current, { type: 'message/sent', text: current.composer }), composer: '', sending: true,
    }));
    return surface === 'task'
      ? <AgentTaskBody {...TASK_BODY_NOOPS} state={state} models={[]} pendingApprovals={approvals}
        onCompose={onCompose} onSend={onSend} now={Date.parse('2026-09-06T16:05:00Z')} />
      : <ReviewActivity review={review} events={state.events} turns={state.turns} models={[]} pendingApprovals={approvals}
        now={Date.parse('2026-09-06T16:05:00Z')} composer={state.composer} busy={state.sending} pendingMessage={state.pendingMessage}
        onCompose={onCompose} onSend={onSend} onSteer={() => {}} onQueue={() => {}} onStop={() => {}} onDecide={() => {}} onRemoveQueued={() => {}} />;
  }
  try {
    flushSync(() => root.render(<Conversation />));
    await settle();
    assert(host.querySelectorAll('.agt-turn').length === 100, `${surface}: all 100 turns must render`);
    assert(payloadReads > 1000, `${surface}: historical payload instrumentation must observe initial projection`);
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, `${surface}: composer is missing`);
    payloadReads = 0;
    const inputMilliseconds: number[] = [];
    const draft = 'Check ordering';
    for (let index = 1; index <= draft.length; index++) {
      const started = performance.now();
      flushSync(() => changeInput(input, draft.slice(0, index)));
      inputMilliseconds.push(performance.now() - started);
      await settle();
    }
    assert(input.value === draft, `${surface}: typing must update the controlled draft`);
    const draftPayloadReads = payloadReads;
    if (verifyCaching) assert(draftPayloadReads === 0, `${surface}: typing reread ${draftPayloadReads} historical payloads`);
    const send = host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]');
    assert(send !== null && !send.disabled, `${surface}: a draft should enable Send`);
    payloadReads = 0;
    const started = performance.now();
    flushSync(() => send.click());
    const sendMilliseconds = performance.now() - started;
    await settle();
    assert(input.value.length === 0, `${surface}: sending must clear the draft`);
    assert(host.querySelector('.agt-turn.is-latest .agt-user__bubble')?.textContent === draft,
      `${surface}: sending must display the local pending message`);
    const sendPayloadReads = payloadReads;
    if (verifyCaching) assert(sendPayloadReads === 0, `${surface}: sending reread ${sendPayloadReads} historical payloads`);

    const approvalEvent: Extract<AgentEventEnvelope, { type: 'approval_requested' }> = {
      ...base(1002, 'latest'), type: 'approval_requested', payload: { approvalId: 'approval', approvalKind: 'COMMAND',
        title: 'Verify ordering', detail: 'npm test', scope: [], sandboxed: true, actionable: true, availableDecisions: ['ALLOW_ONCE', 'DENY'] },
    };
    const approval: AgentPendingApprovalDto = { ...approvalEvent.payload, taskId: TASK_ID, turnId: 'latest', source: SOURCE, occurredAt: AT };
    const latest = agentTurn('RUNNING', { id: 'latest', taskId: TASK_ID, startedAt: AT });
    const updatedEvents = [...events, request(1001, 'latest', 'New durable request'), approvalEvent];
    payloadReads = 0;
    flushSync(() => {
      update({ events: updatedEvents, turns: { ...turns, latest }, turn: latest, pendingMessage: null, sending: false,
        latestTurnId: 'latest', lastSequence: 1002,
        historyPages: [{ events: updatedEvents, beforeCursor: null, afterCursor: null, latestSequence: 1002 }] });
      approve([approval]);
    });
    await settle();
    assert(payloadReads > 0 && host.textContent?.includes('New durable request') === true,
      `${surface}: new events must update the transcript`);
    assert(host.querySelector('[aria-label="Allow once: Verify ordering"]') !== null, `${surface}: the new approval must be actionable`);
    flushSync(() => approve([]));
    await settle();
    assert(host.querySelector('[aria-label="Allow once: Verify ordering"]') === null,
      `${surface}: an approval removed from durable authority must stop being actionable`);
    payloadReads = 0;
    const ended = { ...latest, state: 'FAILED', endedAt: '2026-09-06T16:04:00Z' };
    flushSync(() => update({ turn: ended, turns: { ...turns, latest: ended } }));
    await settle();
    assert(payloadReads > 0 && host.querySelector('[data-history-anchor="latest"] .agt-turn__label')?.textContent?.includes('Interrupted') === true,
      `${surface}: durable turn changes must update the projected outcome`);
    return { name: surface, draftPayloadReads, sendPayloadReads, inputMilliseconds, sendMilliseconds };
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function run(verifyCaching = true) {
  window.bridge = workspaceBridge as Bridge;
  const results = [];
  for (const surface of ['task', 'review'] as const) {
    try { results.push(await exercise(surface, verifyCaching)); }
    catch (error) { results.push({ name: surface, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

export async function sendProgress(): Promise<string> {
  const bridge = createPreviewBridge('workspace-1');
  const previous = agentTurn('COMPLETED', { id: 'previous', taskId: TASK_ID, startedAt: AT, endedAt: AT });
  const next = agentTurn('RUNNING', { id: 'next', taskId: TASK_ID, startedAt: AT });
  let task = { ...previewTask('workspace-1', TASK_ID), interactive: true };
  let events = [request(1, previous.id, 'Earlier request'), answer(2, previous.id, 'Earlier answer')];
  let historyReads = 0;
  let starts = 0;
  let answered = false;
  let notify = (_message: AgentStreamMessage): void => { throw new Error('Not subscribed'); };
  let release = (): void => { throw new Error('No pending submission'); };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async id => id === previous.id ? previous : next;
  bridge.readAgentPendingApprovals = async () => [];
  bridge.readAgentHistory = async () => {
    historyReads++;
    return { events, beforeCursor: null, afterCursor: null, latestSequence: events.at(-1)?.sequence ?? 0 };
  };
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'send-progress', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.startAgentTurn = async input => {
    assert(input.message === 'Check send progress', 'The wrapper must submit the displayed draft');
    starts++;
    return new Promise<AgentTurnDto>(resolve => { release = () => { answered = true; resolve(next); }; });
  };
  window.bridge = { ...bridge, ...workspaceBridge } as unknown as Bridge;
  const host = document.createElement('div');
  host.style.cssText = 'height: 640px; width: 840px';
  document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={TASK_ID} bridge={bridge} onBack={() => {}} />));
    await until(() => host.textContent?.includes('Earlier answer') === true, 'The real task wrapper must load its history');
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'The real task wrapper must render its composer');
    changeInput(input, 'Check send progress');
    await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.disabled === false, 'The loaded task must be sendable');
    host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.click();
    await settle();
    assert(starts === 1 && !answered, 'Send must start exactly one pending bridge request');
    assert(input.value === '' && host.querySelector('.agt-turn.is-latest .agt-user__bubble')?.textContent === 'Check send progress',
      'The pending message must appear and the draft clear before the bridge responds');

    task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: next.id, acquiredAt: AT } };
    events = [...events, request(3, next.id, 'Check send progress'), answer(4, next.id, 'Inspecting the first command output.', false)];
    const before = historyReads;
    notify({ subscriptionId: 'send-progress', taskId: TASK_ID, kind: 'changed', sequence: 4, turnId: next.id });
    assert(historyReads === before + 1, 'The first output notification must request history immediately without waiting for a batching timer');
    await until(() => host.textContent?.includes('Inspecting the first command output.') === true,
      'The first log must display while startAgentTurn is still pending');
    assert(!answered, 'Displaying streamed logs must not depend on the start response');
    assert([...host.querySelectorAll('.agt-user__bubble')].filter(bubble => bubble.textContent === 'Check send progress').length === 1,
      'The durable input must replace its local placeholder without duplicating the message');
    release();
    await until(() => !input.disabled, 'The composer must recover after the start response is accepted');
    assert(starts === 1, 'Painting initial progress must not resubmit the request');
    return 'The message and first logs appear before the start response, with immediate history loading and no duplicate message';
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function sendFailures(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const kind of ['rejected', 'unconfirmed', 'streamed'] as const) {
    const bridge = createPreviewBridge('workspace-1');
    const previous = agentTurn('COMPLETED', { id: 'previous', taskId: TASK_ID, startedAt: AT, endedAt: AT });
    const next = agentTurn('RUNNING', { id: 'next', taskId: TASK_ID, startedAt: AT });
    const task: AgentTaskDto = { ...previewTask('workspace-1', TASK_ID), interactive: true, mutationClaim: null };
    let events = [request(1, previous.id, 'Earlier request'), answer(2, previous.id, 'Earlier answer')];
    let current = previous;
    let hold = false;
    let reconcile = false;
    let release = (): void => {};
    let notify = (_message: AgentStreamMessage): void => {};
    const keys: string[] = [];
    const accepted = new Set<string>();
    bridge.getAgentTask = async () => {
      if (!hold) return task;
      reconcile = true;
      return new Promise<AgentTaskDto>(resolve => { release = () => { hold = false; resolve(task); }; });
    };
    bridge.getAgentTurn = async () => current;
    bridge.readAgentPendingApprovals = async () => [];
    bridge.readAgentHistory = async () => ({ events, beforeCursor: null, afterCursor: null, latestSequence: events.at(-1)?.sequence ?? 0 });
    bridge.subscribeAgentTask = async (taskId, _after, listener) => {
      notify = listener;
      listener({ subscriptionId: 'send-failure', taskId, kind: 'state', state: 'open' });
      return () => {};
    };
    bridge.startAgentTurn = async input => {
      keys.push(input.idempotencyKey);
      if (kind === 'rejected') { hold = true; throw new Error('Request rejected before acceptance'); }
      accepted.add(input.idempotencyKey);
      if (kind === 'unconfirmed' && keys.length === 1) { hold = true; throw new Error(UNCONFIRMED_REQUEST); }
      current = next;
      events = [...events, request(3, next.id, 'Check send failure')];
      return next;
    };
    window.bridge = { ...bridge, ...workspaceBridge } as unknown as Bridge;
    const host = document.createElement('div');
    host.style.cssText = 'height: 640px; width: 840px';
    document.body.append(host);
    const root = createRoot(host);
    try {
      flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={TASK_ID} bridge={bridge} onBack={() => {}} />));
      await until(() => host.textContent?.includes('Earlier answer') === true, 'Load the task before testing submission failures');
      const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
      assert(input !== null, 'The task composer must be mounted');
      changeInput(input, 'Check send failure');
      const send = () => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]');
      await until(() => send()?.disabled === false, 'The task must accept a message');
      send()?.click();
      if (kind !== 'streamed') {
        const notice = kind === 'rejected' ? 'Request rejected before acceptance' : CHECKING_ACCEPTANCE;
        await until(() => host.querySelector('.agt-notice')?.textContent === notice, 'A known submission error must display before reconciliation returns');
        assert(reconcile && hold && input.value === 'Check send failure', 'Reconciliation must remain pending while the draft is restored');
        if (kind === 'rejected') {
          assert(host.querySelector('[data-history-anchor="pending-message"]') === null && accepted.size === 0,
            'A rejected request must not remain shown as accepted');
        }
        else {
          assert(host.querySelector('.agt-live')?.textContent === 'Checking acceptance…', 'An ambiguous send must keep its pending delivery visible');
          release();
          await settle();
          await until(() => send()?.disabled === false, 'The uncertain request must be safely retryable');
          send()?.click();
          await until(() => keys.length === 2 && !input.disabled, 'The retry must settle');
          assert(keys[0] === keys[1] && accepted.size === 1, 'A lost response must not create another accepted turn on retry');
          notify({ subscriptionId: 'send-failure', taskId: TASK_ID, kind: 'changed', sequence: 3, turnId: next.id });
          await until(() => host.querySelector('[data-history-anchor="pending-message"]') === null, 'The durable message must replace uncertain local progress');
          assert([...host.querySelectorAll('.agt-user__bubble')].filter(bubble => bubble.textContent === 'Check send failure').length === 1,
            'Retry reconciliation must show one sent message');
        }
      }
      else {
        await until(() => !input.disabled, 'Successful startup must finish before the provider error is streamed');
        events = [...events, { ...base(4, next.id), type: 'error', payload: { code: 'RATE_LIMIT', message: 'Provider rate limit 429', retryable: true } }];
        notify({ subscriptionId: 'send-failure', taskId: TASK_ID, kind: 'changed', sequence: 4, turnId: next.id });
        await until(() => host.querySelector('.agt-error')?.textContent?.includes('Provider rate limit 429') === true,
          'A provider error after successful submission must display through the stream');
        assert(keys.length === 1 && host.textContent?.includes(CHECKING_ACCEPTANCE) !== true,
          'A streamed provider retry must not become a submission failure or resend');
      }
      results.push({ name: kind });
    }
    catch (error) { results.push({ name: kind, error: error instanceof Error ? error.message : String(error) }); }
    finally { release(); flushSync(() => root.unmount()); host.remove(); }
  }
  return results;
}

export async function firstMessageProgress(): Promise<string> {
  for (const surface of ['home', 'tasks']) {
    const bridge = createPreviewBridge('workspace-1');
    const live = () => bridge;
    let creates = 0;
    let opened: string | null = null;
    let release = (): void => { throw new Error('No pending creation'); };
    bridge.listAgentTasks = async () => [];
    bridge.getAgentTaskStartup = async () => ({ phase: 'WORKTREE' });
    bridge.createAgentTask = async input => {
      assert(input.message === 'Start the first task', `${surface}: creation must submit the draft`);
      creates++;
      return new Promise<AgentTaskDto>(resolve => { release = () => resolve(previewTask('workspace-1', input.taskId)); });
    };
    window.bridge = { ...bridge, ...workspaceBridge } as unknown as Bridge;
    const host = document.createElement('div');
    host.style.cssText = 'height: 640px; width: 840px';
    document.body.append(host);
    const root = createRoot(host);
    const open = (id: string) => { opened = id; };
    const Creation = () => {
      const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', bridge: live, onOpenTask: open });
      const props = { ...creation.composer, tasks: creation.tasks, error: creation.error,
        startup: creation.startup, repository: 'bytequay/bytequay', onOpenTask: open };
      return surface === 'home' ? <AgentHomeView {...props} onOpenReview={() => {}} onShowAll={() => {}} />
        : <AgentTaskList {...props} loading={creation.loading} />;
    };
    try {
      flushSync(() => root.render(<Creation />));
      await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, `${surface}: creation composer must load`);
      const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
      assert(input !== null, `${surface}: creation composer is missing`);
      changeInput(input, 'Start the first task');
      await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send — creates the task"]')?.disabled === false,
        `${surface}: provider and automation settings must enable submission`);
      host.querySelector<HTMLButtonElement>('[aria-label="Send — creates the task"]')?.click();
      await settle();
      assert(creates === 1 && opened === null, `${surface}: exactly one creation must wait for acceptance before navigation`);
      assert(host.querySelector('.agt-user__bubble')?.textContent === 'Start the first task' && host.textContent?.includes('Starting task…') === true,
        `${surface}: the first message and starting state must appear before creation responds`);
      assert(host.querySelector('[aria-label="Send — creates the task"]') === null, `${surface}: starting must prevent duplicate creation`);
      await until(() => host.querySelector('[role="status"]')?.textContent?.includes('Creating the task branch and worktree') === true,
        `${surface}: startup progress must update before creation responds`);
      assert(opened === null, `${surface}: startup progress must not bypass first-turn acceptance`);
      release();
      await until(() => opened !== null, `${surface}: accepted creation must open its task`);
      assert(creates === 1, `${surface}: opening the task must not send again`);
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
  return 'Home and Tasks show the first message and startup progress before creation responds, then open the accepted task once';
}

export async function firstMessageFailures(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const surface of ['home', 'tasks']) for (const ambiguous of [false, true]) {
    const bridge = createPreviewBridge('workspace-1');
    const live = () => bridge;
    const requests: Parameters<Bridge['createAgentTask']>[0][] = [];
    let opened: string | null = null;
    let release = (): void => {};
    bridge.listAgentTasks = async () => [];
    bridge.getAgentTaskStartup = async () => ({ phase: 'WORKTREE' });
    bridge.createAgentTask = async input => {
      requests.push(input);
      if (requests.length === 1) throw new Error(ambiguous ? UNCONFIRMED_REQUEST : 'Creation rejected before acceptance');
      await new Promise<void>(resolve => { release = resolve; });
      if (!ambiguous) throw new Error('Creation rejected before acceptance');
      return previewTask('workspace-1', input.taskId);
    };
    window.bridge = { ...bridge, ...workspaceBridge } as unknown as Bridge;
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const open = (id: string) => { opened = id; };
    const Creation = () => {
      const creation = useAgentTaskCreation({ workspaceId: 'workspace-1', bridge: live, onOpenTask: open });
      const props = { ...creation.composer, tasks: creation.tasks, error: creation.error,
        startup: creation.startup, repository: 'bytequay/bytequay', onOpenTask: open };
      return surface === 'home' ? <AgentHomeView {...props} onOpenReview={() => {}} onShowAll={() => {}} />
        : <AgentTaskList {...props} loading={creation.loading} />;
    };
    const name = `${surface}-${ambiguous ? 'unconfirmed' : 'rejected'}`;
    try {
      flushSync(() => root.render(<Creation />));
      await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'Load the new-task composer');
      const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]');
      assert(input !== null, 'The composer must exist');
      changeInput(input, 'Check first-message recovery');
      await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send — creates the task"]')?.disabled === false, 'Enable the first send');
      host.querySelector<HTMLButtonElement>('[aria-label="Send — creates the task"]')?.click();
      const notice = ambiguous ? CHECKING_ACCEPTANCE : 'Creation rejected before acceptance';
      await until(() => host.querySelector('[role="alert"]')?.textContent === notice, 'Show the first error while replay is held');
      assert(requests.length === 2 && opened === null, 'Reconciliation must remain pending');
      assert(JSON.stringify(requests[0]) === JSON.stringify(requests[1]), 'Reconciliation must replay the exact composite creation request');
      assert(host.querySelector('[aria-label="Send — creates the task"]') === null, 'Reconciliation must prevent another submission');
      if (ambiguous) assert(host.querySelector('[role="status"]')?.textContent === 'Checking acceptance…', 'An uncertain first send must not claim it failed');
      release();
      if (ambiguous) await until(() => opened === requests[0].taskId, 'A replay must open the already accepted task once');
      else {
        await until(() => host.querySelector('textarea[aria-label="Describe a task"]') !== null, 'Restore the rejected creation draft');
        assert(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Describe a task"]')?.value === 'Check first-message recovery'
          && opened === null, 'Keep the rejected message editable without opening a task');
      }
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { release(); flushSync(() => root.unmount()); host.remove(); }
  }
  return results;
}

export async function sendAfterNavigation(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const failure of ['Request rejected', UNCONFIRMED_REQUEST, 'This message was already sent']) {
    const bridge = createPreviewBridge('workspace-1');
    const task: AgentTaskDto = { ...previewTask('workspace-1', TASK_ID), interactive: true, mutationClaim: null };
    const old = agentTurn('COMPLETED', { id: 'previous', taskId: TASK_ID, endedAt: AT });
    let release = (): void => {};
    bridge.getAgentTask = async () => task;
    bridge.getAgentTurn = async () => old;
    bridge.readAgentPendingApprovals = async () => [];
    bridge.readAgentHistory = async () => ({ events: [request(1, old.id, 'Earlier request'), answer(2, old.id, 'Earlier answer')],
      beforeCursor: null, afterCursor: null, latestSequence: 2 });
    bridge.subscribeAgentTask = async (taskId, _after, listener) => {
      listener({ subscriptionId: 'navigation', taskId, kind: 'state', state: 'open' }); return () => {};
    };
    bridge.startAgentTurn = async () => new Promise<AgentTurnDto>((_resolve, reject) => { release = () => reject(new Error(failure)); });
    window.bridge = { ...bridge, ...workspaceBridge } as unknown as Bridge;
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const screen = () => <AgentTaskScreen workspaceId="workspace-1" taskId={TASK_ID} bridge={bridge} onBack={() => {}} />;
    try {
      flushSync(() => root.render(screen()));
      await until(() => host.textContent?.includes('Earlier answer') === true, 'Load the first task mount');
      const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
      assert(input !== null, 'The first composer must exist');
      changeInput(input, 'Message before navigation');
      await until(() => host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.disabled === false, 'The task must accept a draft');
      host.querySelector<HTMLButtonElement>('[aria-label="Send message"]')?.click();
      await settle();
      flushSync(() => root.render(null));
      release();
      await settle();
      flushSync(() => root.render(screen()));
      await until(() => host.textContent?.includes('Earlier answer') === true, 'The task must load when reopened');
      const reopened = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
      assert(reopened?.value === (failure === 'Request rejected' ? 'Message before navigation' : ''),
        'Only a known unsent request may be handed to a new composer with a new request key');
      assert(host.querySelector('.agt-notice') === null, 'The previous mount must not apply late error feedback');
      results.push({ name: failure });
    }
    catch (error) { results.push({ name: failure, error: error instanceof Error ? error.message : String(error) }); }
    finally { release(); flushSync(() => root.unmount()); host.remove(); }
  }
  return results;
}
