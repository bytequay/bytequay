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
import type { AgentStreamMessage, AgentTaskDto, AgentTurnDto, PullRequestDetailDto, PullRequestDetailRefreshDto, PullRequestDto, RepoLabelDto, RepoUserDto } from '../../types';
import AgentTaskScreen from '../AgentTaskScreen';
import { DEFAULT_DEVELOPMENT_AUTOMATION } from '../developmentAutomation';
import type { AgentEventEnvelope } from '../events';
import { CI_PROJECTION } from './ciAutofix';
import { createPreviewBridge } from './previewBridge';
import { assert, changeInput, settle } from '../../fixtures/harness';


/** The published pull request's detail, which the opened panel reads through the window bridge. */
function publishedDetail(draft: boolean): PullRequestDetailDto {
  return {
    id: CI_PROJECTION.pullRequestNumber, repo: 'acme/demo', number: CI_PROJECTION.pullRequestNumber, title: 'Preserve the public API',
    htmlUrl: CI_PROJECTION.pullRequestUrl, state: 'open', draft, mergeable: null, mergeableState: null, author: 'alice',
    authorAvatarUrl: null, createdAt: '2026-01-01T12:10:00Z', updatedAt: '2026-01-01T12:10:00Z', mergedAt: null, baseRef: 'master',
    headRef: CI_PROJECTION.headRef, body: 'Keeps the public API.', labels: [], assignees: [], milestone: null, reviewers: [],
    additions: 3, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: 'NONE', recentActivity: [], checkRuns: [],
    unavailableSections: [], viewerCanWrite: false,
  };
}

/** The published pull request as the task's own list reports it, which is all the chip reads. */
function publishedPullRequest(draft: boolean): PullRequestDto {
  return {
    id: CI_PROJECTION.pullRequestNumber, repo: 'acme/demo', number: CI_PROJECTION.pullRequestNumber, title: 'Preserve the public API',
    author: 'alice', htmlUrl: CI_PROJECTION.pullRequestUrl, createdAt: '2026-01-01T12:10:00Z', updatedAt: '2026-01-01T12:10:00Z',
    origin: 'AUTHORED', labels: [], labelColors: null, draft, viewedAt: null, reviewedAt: null, handledAction: null,
    requestedReviewers: [], ciStatus: null, additions: 3, deletions: 1, commentCount: 0, state: 'open',
    mergeable: null, mergeableState: null, headPushedAt: '2026-01-01T12:10:00Z', headRef: CI_PROJECTION.headRef,
  };
}

/** Server projections exercise display/admission; they never launch providers or write to GitHub. */
async function deliveryResult(outcome: 'ready' | 'draft' | 'sync failure', publish: (detail: PullRequestDetailDto) => void): Promise<{ name: string; error?: string }> {
  const name = `Delivery ${outcome}: accepted automation, question, generated turns and final observations`;
  const bridge = createPreviewBridge('workspace-1');
  const event = bridge.events[0];
  assert(event.turnId !== null && event.type === 'user_message', 'Fixture must identify a source user turn');
  const automation = { ...DEFAULT_DEVELOPMENT_AUTOMATION, autoCiFix: outcome !== 'draft', ciLaneId: 'claude' };
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true,
    mutationClaim: null, developmentContinuationUnfinished: false };
  task = { ...task, defaults: { ...task.defaults, developmentAutomation: automation } };
  const question = 'Should I preserve the public API?';
  const original = await bridge.getAgentTurn(event.turnId);
  assert(original.settings !== null, 'Fixture needs accepted settings');
  let turn: AgentTurnDto = { ...original, id: event.turnId, taskId: task.id, state: 'COMPLETED', endedAt: '2026-01-01T12:00:00Z',
    settings: { ...original.settings, developmentAutomation: automation },
    developmentResult: { readyForDelivery: false, message: question } };
  const turns = new Map([[turn.id, turn]]);
  const history: AgentEventEnvelope[] = [{ ...event, taskId: task.id }];
  let sequence = event.sequence;
  let notify: (message: AgentStreamMessage) => void = () => {};
  let submitted = 0;
  let prReads = 0;
  let pullRequests: PullRequestDto[] = [];
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
      developmentResult: { readyForDelivery: true, message: null } };
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
  bridge.listAgentTaskPullRequests = async () => { prReads++; return pullRequests; };
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
    let input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'Normal answer composer must exist');
    assert(input.placeholder === 'Waiting for your answer…', 'A durable question must ask for its answer before any delivery');
    assert(host.querySelector('.agt-pr-chip') === null, 'A branch without a pull request must offer no chip');
    assert(submitted === 0, 'Opening a questioning task must not launch delivery');
    changeInput(input, 'Preserve the public API');
    await settle();
    host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.click();
    await settle();
    assert(Number(submitted) === 1 && host.querySelector<HTMLTextAreaElement>('.agt-compose__input')?.placeholder === 'Message the agent',
      'Accepted answer must clear the old question');
    changeInput(input, 'Keep my next development request');
    await settle();
    await finishTurn();
    await generated('AUTO_COMMIT');
    await finishTurn();
    await generated('CREATE_DRAFT_PR');
    pullRequests = [publishedPullRequest(true)];
    publish(publishedDetail(true));
    await finishTurn();
    if (!automation.autoCiFix) {
      task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false };
      emit('DEVELOPMENT_AUTOMATION_UPDATED', 'Development automation settled');
      await settle();
      assert(host.querySelector('.agt-pr-chip')?.textContent?.includes('#17') === true, 'The published pull request must reach the composer chip');
      assert(host.querySelector('.agt-pr-chip .agt-pr-icon.is-draft') !== null, 'Disabling Auto CI fix must leave the published PR draft');
      assert(!host.textContent?.includes('Fixing CI') && !host.textContent?.includes('CI passed'), 'Draft-only delivery must not claim CI work');
      assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled === false, 'Publication must release the source when CI is disabled');
      flushSync(() => root.unmount());
      root = createRoot(host);
      render();
      await settle();
      assert(host.querySelector('.agt-pr-chip .agt-pr-icon.is-draft') !== null && Number(submitted) === 1, 'Reopening draft-only delivery must retain the result without starting work');
      return { name };
    }
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, status: 'ACTIVE', activity: 'FIXING', canMessage: false } };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(host.textContent?.includes('Fixing CI 1/3') === true, 'CI repair must be visible on the source');
    task = { ...task, version: task.version + 1, developmentCi: { ...CI_PROJECTION, status: 'FINISHED', finishReason: 'GREEN', canMessage: false } };
    emit('DEVELOPMENT_AUTOMATION_UPDATED');
    await settle();
    assert(host.querySelector('.agt-status')?.textContent?.startsWith('Automation running') === true
      && !host.textContent?.includes('Ready for review'), 'Green alone must not imply promotion');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled !== false
      && host.querySelector('button[aria-label="Stop automation"]') !== null, 'Green must retain ownership until source synchronization and final checks finish');
    const checkWait = 'Waiting for PR checks: required external build has not reported a result';
    emit('DEVELOPMENT_AUTOMATION_UPDATED', checkWait);
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Post-green missing checks must stay visible');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Opening a running task must recover the coordinator reason from history');
    input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Message the agent"]');
    assert(input !== null, 'Reloaded source must keep the composer available for drafting');
    changeInput(input, 'Keep my next development request');
    await settle();

    notify({ subscriptionId: 'delivery', taskId: task.id, kind: 'state', state: 'reconnecting' });
    await settle();
    assert(host.textContent?.includes('Reconnecting') === true, 'Connection loss must remain visible');
    assert(host.querySelector('.agt-barrier')?.textContent?.includes(checkWait) === true, 'Reconnect must retain the last coordinator check-wait reason');
    emit('DEVELOPMENT_AUTOMATION_UPDATED', 'Synchronizing source worktree');
    await settle();
    assert(host.querySelector('.agt-barrier')?.textContent?.includes('Synchronizing source worktree') === true, 'Fresh progress must replace the older check wait');
    const beforeReleasePrReads = prReads;
    pullRequests = [publishedPullRequest(outcome !== 'ready')];
    publish(publishedDetail(outcome !== 'ready'));
    task = { ...task, version: task.version + 1, developmentContinuationUnfinished: false, state: 'OPEN', terminalReason: null, terminalAt: null };
    emit('DEVELOPMENT_AUTOMATION_UPDATED', outcome === 'sync failure'
      ? 'Automation stopped. Source synchronization failed; published CI repairs remain on the draft. Your task and files are retained; send a message to continue.'
      : 'Development automation settled');
    await settle();
    notify({ subscriptionId: 'delivery', taskId: task.id, kind: 'state', state: 'open' });
    await settle();
    const chip = host.querySelector('.agt-pr-chip');
    assert(chip?.textContent?.includes('#17') === true, 'Settled delivery must name its pull request on the composer');
    assert(prReads > beforeReleasePrReads, 'Final release/reconnect must refresh the task PR list');
    assert(!host.querySelector('.agt-barrier')?.textContent?.includes('Synchronizing source worktree'), 'Marker release must hide stale coordinator progress');
    assert(chip?.querySelector(outcome === 'ready' ? '.agt-pr-icon.is-open' : '.agt-pr-icon.is-draft') !== null,
      'The observed draft state must determine the chip mark');
    assert(host.querySelector('.agt-panel') !== null, 'A task that published a pull request must open on it');
    if (outcome === 'sync failure') {
      assert(!host.textContent?.includes('Resume delivery'), 'Failed delivery must not offer a recovery continuation');
      assert(!host.textContent?.includes('Delete task'), 'A stopped continuation must not require deleting the task');
    }
    assert(input.value === 'Keep my next development request', 'Settling the continuation must preserve the source draft');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Send message"]')?.disabled === false, 'Settled delivery must keep the source task open');
    assert(host.querySelector<HTMLButtonElement>('button[aria-label="Mark it complete"]')?.disabled === false, 'Delivery must leave task closure explicit');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    await settle();
    assert(Number(submitted) === 1, 'Reopening final results must never send development or publication again');
    assert(host.textContent?.includes('ByteQuay · Auto commit') === true && host.textContent?.includes(`Open draft pull request ${task.generatedBranch}`) === true
      && !host.textContent?.includes('Push the revision and create its draft'), 'Replay must retain the commit label and show publication as its action');
    if (outcome === 'ready') {
      turn = { ...turn, id: 'later-question', developmentOrigin: null, developmentResult: { readyForDelivery: false, message: question } };
      recordTurn();
      emit('TURN_COMPLETED');
      await settle();
      assert(host.querySelector('.agt-status')?.textContent?.startsWith('Waiting for your answer') === true, 'A later question must take precedence over prior green/ready results');
      assert(host.querySelector<HTMLTextAreaElement>('.agt-compose__input')?.placeholder === 'Waiting for your answer…',
        'A later question must ask for the answer in the composer');
    }
    return { name };
  } catch (error) { return { name, error: error instanceof Error ? error.message : String(error) }; }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

/**
 * A published pull request now opens the task's panel, and that panel reads GitHub through the
 * window bridge rather than the task bridge, so these scenarios supply the reads it makes on open.
 */
export async function runDevelopmentDeliveryScenarios(): Promise<{ name: string; error?: string }[]> {
  const original = Object.getOwnPropertyDescriptor(window, 'bridge');
  let detail = publishedDetail(true);
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    fetchPullRequestDetailByRef: async (): Promise<PullRequestDetailDto> => detail,
    refreshPullRequestDetailByRef: async (): Promise<PullRequestDetailRefreshDto> => ({ full: true, detail, status: null }),
    fetchPrDiffFiles: async (): Promise<[]> => [],
    fetchPrDiffCommits: async (): Promise<[]> => [],
    getUserProfile: async (): Promise<null> => null,
    fetchRepoUsers: async (): Promise<RepoUserDto[]> => [],
    fetchRepoLabels: async (): Promise<RepoLabelDto[]> => [],
  } });
  try {
    return [await deliveryResult('ready', value => { detail = value; }),
      await deliveryResult('draft', value => { detail = value; }),
      await deliveryResult('sync failure', value => { detail = value; })];
  }
  finally {
    if (original === undefined) delete (window as { bridge?: unknown }).bridge;
    else Object.defineProperty(window, 'bridge', original);
  }
}
