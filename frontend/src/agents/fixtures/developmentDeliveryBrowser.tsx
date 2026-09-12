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
import type { AgentDevelopmentFactsDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto } from '../../types';
import AgentTaskScreen from '../AgentTaskScreen';
import { DEFAULT_DEVELOPMENT_AUTOMATION } from '../developmentAutomation';
import type { AgentEventEnvelope } from '../events';
import { CI_PROJECTION } from './ciAutofix';
import { createPreviewBridge } from './previewBridge';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
function changeInput(input: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Server projections exercise display/admission; they never launch providers or write to GitHub. */
async function deliveryResult(outcome: 'ready' | 'draft' | 'sync failure'): Promise<{ name: string; error?: string }> {
  const name = `Delivery ${outcome}: question, generated turns, CI repair, final observations and reconnect`;
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null && event.type === 'user_message', 'Fixture must identify a source user turn');
  const automation = { ...DEFAULT_DEVELOPMENT_AUTOMATION, ciLaneId: 'claude' };
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: false };
  task = { ...task, defaults: { ...task.defaults, developmentAutomation: automation } };
  const question = 'Should I preserve the public API?';
  const original = await bridge.getAgentTurn(event.turnId);
  assert(original.settings !== null, 'Fixture needs accepted settings');
  let turn: AgentTurnDto = { ...original, id: event.turnId, taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z',
    settings: { ...original.settings, developmentAutomation: automation },
    developmentResult: { readyForDelivery: false, message: question, stopAfter: outcome === 'draft' ? 'CI' : null } };
  const turns = new Map([[turn.id, turn]]);
  const history: AgentEventEnvelope[] = [{ ...event, taskId: task.id }];
  let sequence = event.sequence;
  let notify: (message: AgentStreamMessage) => void = () => {};
  let submitted = 0;
  let factsReads = 0;
  let prReads = 0;
  let facts: AgentDevelopmentFactsDto = {
    observedAt: '2026-01-01T12:00:00Z',
    local: { branch: 'codex/change', headSha: 'aaaaaaaa00', stagedFiles: 0, unstagedFiles: 1, untrackedFiles: 0, uncommittedFileCount: 1, commitCount: 0 },
    localError: null, remote: { headSha: null, pullRequest: null }, remoteError: null,
  };
  const emit = (phase: 'TURN_STARTED' | 'TURN_COMPLETED' | 'DEVELOPMENT_AUTOMATION_UPDATED', message = 'Development changed') => {
    const change: AgentEventEnvelope = { ...event, eventId: `delivery-${++sequence}`, sequence, taskId: task.id,
      turnId: phase === 'DEVELOPMENT_AUTOMATION_UPDATED' ? null : turn.id, type: 'lifecycle', payload: { phase, message } };
    history.push(change);
    notify({ subscriptionId: 'delivery', taskId: task.id, kind: 'events', events: [change] });
  };
  const recordTurn = () => { turns.set(turn.id, turn); task = { ...task, version: task.version + 1 }; };
  const generated = async (kind: 'AUTO_COMMIT' | 'CREATE_DRAFT_PR') => {
    turn = { ...turn, id: kind, state: 'RUNNING', endedAt: null, developmentResult: null,
      developmentOrigin: { kind, parentUserTurnId: 'answer-turn' } };
    recordTurn();
    task = { ...task, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } };
    history.push({ ...event, taskId: task.id, turnId: turn.id, eventId: `input-${kind}`, sequence: ++sequence,
      payload: { text: kind === 'AUTO_COMMIT' ? 'Commit the finished change' : 'Push the revision and create its draft', attachments: [], deliveryKind: 'TURN' } });
    emit('TURN_STARTED');
    await settle();
    assert(host.textContent?.includes(kind === 'AUTO_COMMIT' ? 'Committing' : 'Creating draft PR') === true, 'Generated activity must be visible');
  };
  const finishTurn = async () => {
    turn = { ...turn, state: 'COMPLETED', endedAt: `2026-01-01T12:0${sequence % 10}:00Z`,
      developmentResult: { readyForDelivery: true, message: null, stopAfter: outcome === 'draft' ? 'CI' : null } };
    recordTurn();
    task = { ...task, mutationClaim: null, developmentContinuationUnfinished: true };
    emit('TURN_COMPLETED');
    await settle();
  };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async id => { const result = turns.get(id); assert(result !== undefined, `Unknown fixture turn ${id}`); return result; };
  bridge.readAgentHistory = async () => ({ events: [...history], beforeCursor: null, afterCursor: null, latestSequence: sequence });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.subscribeAgentTask = async (id, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'delivery', taskId: id, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.getAgentDevelopmentFacts = async () => { factsReads++; return facts; };
  bridge.listAgentTaskPullRequests = async () => { prReads++; return []; };
  bridge.startAgentTurn = async request => {
    submitted++;
    assert(request.message === 'Preserve the public API', 'Only an explicit user answer may start development');
    turn = { ...turn, id: 'answer-turn', state: 'RUNNING', endedAt: null, developmentResult: null };
    recordTurn();
    task = { ...task, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '' } };
    history.push({ ...event, taskId: task.id, turnId: turn.id, eventId: 'answer', sequence: ++sequence,
      payload: { text: request.message, attachments: [], deliveryKind: 'TURN' } });
    return turn;
  };
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  const render = () => flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  render();
  try {
    await settle();
    assert(host.textContent?.includes(question) === true && host.textContent?.includes('1 uncommitted file') === true, 'Question must coexist with dirty work');
    assert(submitted === 0, 'Opening a questioning task must not launch delivery');
    let input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'Normal answer composer must exist');
    changeInput(input, 'Preserve the public API');
    await settle();
    host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.click();
    await settle();
    assert(Number(submitted) === 1 && !host.textContent?.includes(question), 'Accepted answer must clear the old question');
    changeInput(input, 'Keep my next development request');
    await settle();
    await finishTurn();
    await generated('AUTO_COMMIT');
    assert(facts.local !== null, 'Commit fixture requires local observations');
    facts = { ...facts, local: { ...facts.local, headSha: 'bbbbbbbb11', unstagedFiles: 0, uncommittedFileCount: 0, commitCount: 1 } };
    await finishTurn();
    await generated('CREATE_DRAFT_PR');
    const pr = { number: 17, url: CI_PROJECTION.pullRequestUrl, state: 'OPEN', draft: true, headSha: 'bbbbbbbb11' };
    facts = { ...facts, remote: { headSha: 'bbbbbbbb11', pullRequest: pr } };
    await finishTurn();
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', canMessage: false } };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(host.textContent?.includes('Fixing CI 1/3') === true, 'CI repair must be visible on the source');
    assert(host.textContent?.includes('Automation running; showing previous observations') === true, 'CI must not make old Git facts appear settled');
    let duringCiReads = factsReads;
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'GREEN', canMessage: false } };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(host.textContent?.includes('CI passed') === true && !host.textContent?.includes('Ready for review'), 'Green alone must not imply promotion');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled !== false
      && host.querySelector('button[aria-label="Stop automation"]') !== null, 'Green must retain ownership until source synchronization and final checks finish');
    assert(factsReads === duringCiReads, 'CI activity updates must not poll GitHub facts');
    const checkWait = 'Waiting for PR checks: required external build has not reported a result';
    emit('DEVELOPMENT_AUTOMATION_UPDATED', checkWait);
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Post-green missing checks must be visible without another GitHub fact read');
    assert(factsReads === duringCiReads, 'Check-wait messages must use existing events without polling GitHub');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Opening a running task must recover the coordinator reason from history');
    input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'Reloaded source must keep the composer available for drafting');
    changeInput(input, 'Keep my next development request');
    await settle();
    duringCiReads = factsReads;

    notify({ subscriptionId: 'delivery', taskId: task.id, kind: 'state', state: 'reconnecting' });
    await settle();
    assert(host.textContent?.includes('Reconnecting') === true, 'Connection loss must remain visible');
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Reconnect must retain the last coordinator check-wait reason');
    emit('DEVELOPMENT_AUTOMATION_UPDATED', 'Synchronizing source worktree');
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes('Synchronizing source worktree') === true, 'Fresh progress must replace the older check wait');
    const beforeReleasePrReads = prReads;
    assert(facts.local !== null, 'Synchronization fixture requires local observations');
    facts = { ...facts, observedAt: '2026-01-01T12:30:00Z', local: { ...facts.local, headSha: outcome === 'sync failure' ? 'bbbbbbbb11' : 'cccccccc22', commitCount: outcome === 'sync failure' ? 1 : 2 },
      remote: { headSha: 'cccccccc22', pullRequest: { ...pr, headSha: 'cccccccc22', draft: outcome !== 'ready' } } };
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false,
      state: outcome === 'sync failure' ? 'FAILED' : 'OPEN',
      terminalReason: outcome === 'sync failure' ? 'Automation failed · Delete task and start again. Source synchronization failed; published CI repairs remain on the draft.' : null,
      terminalAt: outcome === 'sync failure' ? '2026-01-01T12:30:00Z' : null };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(factsReads === duringCiReads, 'Disconnected settlement must wait for readable facts');
    notify({ subscriptionId: 'delivery', taskId: task.id, kind: 'state', state: 'open' });
    await settle();
    const result = host.querySelector('[aria-label="Observed development results"]');
    assert(result?.textContent?.includes('cccccccc') === true, 'Reconnect must read the repaired published head');
    assert(prReads > beforeReleasePrReads, 'Final release/reconnect must refresh the task PR list');
    assert(!result?.textContent?.includes('Automation running'), 'Settled final observations must no longer be labeled active');
    assert(!host.querySelector('.agt-barrier')?.textContent?.includes('Synchronizing source worktree'), 'Marker release must hide stale coordinator progress');
    assert(outcome === 'ready' ? result?.textContent?.includes('Ready for review · PR #17') === true : result?.textContent?.includes('Draft PR #17') === true,
      'Observed draft status must determine the final PR label');
    if (outcome === 'sync failure') {
      assert(result?.textContent?.includes('Local HEAD differs from remote') === true, 'Failed synchronization must expose the retained local and remote heads');
      assert(host.textContent?.includes('Automation failed · Delete task and start again. Source synchronization failed') === true, 'Failure must name the action and restart rule');
      assert(!host.textContent?.includes('Resume delivery'), 'Failed delivery must not offer a recovery continuation');
    } else {
      assert(!result?.textContent?.includes('Local HEAD differs from remote'), 'Successful source synchronization must show matching heads');
      assert(input.value === 'Keep my next development request', 'Completion must preserve the source draft');
      assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled === false, 'Successful delivery must keep the source task open');
      assert(host.querySelector<HTMLButtonElement>('button[aria-label="Mark it complete"]')?.disabled === false, 'Delivery must leave task closure explicit');
    }
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    await settle();
    assert(Number(submitted) === 1, 'Reopening final results must never send development or publication again');
    assert(host.textContent?.includes('ByteQuay · Auto commit') === true && host.textContent?.includes(`Open draft pull request ${task.generatedBranch}`) === true
      && !host.textContent?.includes('Push the revision and create its draft'), 'Replay must retain the commit label and show publication as its action');
    if (outcome === 'ready') {
      turn = { ...turn, id: 'later-question', developmentOrigin: null, developmentResult: { readyForDelivery: false, message: question, stopAfter: null } };
      recordTurn();
      emit('TURN_COMPLETED');
      await settle();
      assert(host.querySelector('.agt-status')?.textContent?.startsWith('Waiting for your answer') === true, 'A later question must take precedence over prior green/ready results');
      const html = host.innerHTML;
      assert(html.indexOf('agt-development-question') < html.indexOf('Observed development results'), 'The question must appear before the published result');
    }
    return { name };
  } catch (error) { return { name, error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function runDevelopmentDeliveryScenarios(): Promise<{ name: string; error?: string }[]> {
  return [await deliveryResult('ready'), await deliveryResult('draft'), await deliveryResult('sync failure')];
}
