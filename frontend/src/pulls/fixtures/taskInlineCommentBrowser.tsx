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
import AgentTaskScreen from '../../agents/AgentTaskScreen';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentEventEnvelope } from '../../agents/events';
import type { AgentQueuedInputDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto, Bridge, DiffCommitDto, DiffFileDto, PullRequestDetailDto } from '../../types';
import { remotePullRequest } from '../PullsScreen';
import type { CreateLocalReviewRequest } from '../local-review/contracts';
import type { LocalReviewMessageDto, LocalReviewThreadDto } from '../local-review/types';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
function changeInput(input: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function button(host: HTMLElement, text: string): HTMLButtonElement {
  const result = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === text);
  assert(result !== undefined, `Missing ${text} button`);
  return result;
}
const file: DiffFileDto = { filename: 'src/Example.java', status: 'modified', additions: 1, deletions: 1,
  patch: '@@ -1 +1 @@\n-old implementation\n+new implementation' };
const commits: DiffCommitDto[] = [
  { sha: '1111111', parentSha: '0000000', message: 'First', author: 'alice', authoredAt: null },
  { sha: '2222222', parentSha: '1111111', message: 'Second', author: 'alice', authoredAt: null },
];
const detail: PullRequestDetailDto = {
  id: 7, repo: 'owner/repo', number: 7, title: 'Implementation', htmlUrl: null, state: 'open', draft: true,
  mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
  mergedAt: null, baseRef: 'main', headRef: 'feature', body: null, labels: [], assignees: [], milestone: null,
  reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null, recentActivity: [],
  checkRuns: [], unavailableSections: [], viewerCanWrite: true,
};

const INTERNAL_PROMPT = 'INTERNAL_LOCAL_REVIEW_CONTEXT_NOT_A_USER_MESSAGE';

async function scenario(mode: 'idle' | 'queued' | 'unsent'): Promise<void> {
  const bridge = createPreviewBridge('workspace-1');
  const original = bridge.events.find(event => event.type === 'user_message');
  assert(original !== undefined && original.turnId !== null, 'Fixture must retain one completed turn');
  const event: AgentEventEnvelope = { ...original, payload: { ...original.payload, attachments: [] } };
  const history: AgentEventEnvelope[] = [event];
  const savedAutomation = { autoCommit: false, autoDraftPr: false, autoCiFix: false, ciLaneId: 'codex' };
  let task: AgentTaskDto = { ...await bridge.getAgentTask(event.taskId, 'workspace-1'), interactive: true, mutationClaim: null,
    defaults: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO', developmentAutomation: savedAutomation } };
  const fixtureTurn = await bridge.getAgentTurn(original.turnId);
  let turn: AgentTurnDto = { ...fixtureTurn, id: original.turnId, state: 'COMPLETED', developmentResult: null,
    createdAt: '2026-08-20T10:00:00Z', startedAt: '2026-08-20T10:00:00Z', endedAt: '2026-08-20T10:01:00Z',
    metrics: { ...fixtureTurn.metrics, elapsed: 'PT1M' } };
  let notify: (message: AgentStreamMessage) => void = () => {};
  const queue: AgentQueuedInputDto[] = [];
  const requests: CreateLocalReviewRequest[] = [];
  const saved = new Map<string, LocalReviewThreadDto>();
  let retries = 0;
  let finishCreate: () => void = () => {};
  const getCatalog = bridge.getModelSettings;
  bridge.getModelSettings = async () => {
    const catalog = await getCatalog();
    return { ...catalog, providers: catalog.providers.map(provider => ({ ...provider,
      models: provider.models.map(model => ({ ...model, inputModalities: ['text', 'image'] })) })) };
  };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async () => turn;
  bridge.readAgentHistory = async () => ({ events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 });
  bridge.readAgentEvents = async () => [...history];
  bridge.readAgentPendingApprovals = async () => [];
  bridge.listAgentQueue = async () => [...queue];
  bridge.listAgentTaskPullRequests = async () => [remotePullRequest(detail, 'alice')];
  bridge.subscribeAgentTask = async (taskId, _after, listener) => {
    notify = listener;
    listener({ subscriptionId: 'inline', taskId, kind: 'state', state: 'open' });
    return () => {};
  };
  bridge.pickAgentAttachments = async () => [{ id: 'draft-image', name: 'draft.png', mediaType: 'image/png', byteSize: 1, width: 1, height: 1 }];
  bridge.readAgentAttachment = async () => { throw new Error('Image preview is unavailable in this fixture'); };
  bridge.listLocalReviewComments = async query => [...saved.values()].filter(thread => 'taskId' in query
    ? thread.taskId === query.taskId : thread.repo === query.repo && thread.number === query.number);
  const publishHistory = (message: LocalReviewMessageDto, body: string): void => {
    if (message.turnId === null) return;
    turn = { ...turn, id: message.turnId, state: 'COMPLETED', endedAt: '2026-08-20T10:02:00Z' };
    task = { ...task, version: task.version + 1, mutationClaim: null };
    const user: AgentEventEnvelope = { ...event, eventId: message.messageEventId ?? `input-${message.id}`, turnId: message.turnId,
      sequence: (history.at(-1)?.sequence ?? 0) + 1, payload: { text: body, attachments: [], deliveryKind: 'TURN' } };
    const complete: AgentEventEnvelope = { ...user, eventId: `complete-${message.id}`, sequence: user.sequence + 1,
      type: 'lifecycle', payload: { phase: 'TURN_COMPLETED', message: 'Completed' } };
    history.push(user, complete);
    notify({ subscriptionId: 'inline', taskId: task.id, kind: 'events', events: [user, complete] });
  };
  bridge.createLocalReviewComment = async request => {
    requests.push(request);
    await new Promise<void>(resolve => { finishCreate = resolve; });
    const createdAt = '2026-08-20T10:01:00Z';
    const message: LocalReviewMessageDto = { id: request.messageId, author: 'USER', body: request.body, createdAt,
      turnId: mode === 'idle' ? 'inline-turn' : null, queuedInputId: mode === 'queued' ? 'inline-queued' : null,
      messageEventId: mode === 'idle' ? 'inline-event' : null,
      deliveryState: mode === 'unsent' ? 'UNSENT' : mode === 'queued' ? 'QUEUED' : 'COMPLETED',
      deliveryError: mode === 'unsent' ? 'Agent unavailable; retry this saved comment' : null };
    const thread: LocalReviewThreadDto = { id: request.id, taskId: request.taskId, taskVersion: task.version, workspaceId: request.workspaceId,
      repo: request.repo, number: request.number, path: request.path, side: request.side, startLine: request.startLine,
      line: request.line, baseSha: request.baseSha, headSha: request.headSha, code: request.code,
      resolved: false, version: 1, createdAt, updatedAt: createdAt, messages: [message] };
    saved.set(thread.id, thread);
    if (mode === 'queued') {
      assert(turn.settings !== null, 'Fixture turn requires settings');
      queue.push({ id: 'inline-queued', taskId: task.id, position: 1, state: 'QUEUED', preview: INTERNAL_PROMPT,
        settings: turn.settings, turnId: null, pauseReason: null, createdAt, updatedAt: createdAt });
    }
    publishHistory(message, INTERNAL_PROMPT);
    return thread;
  };
  bridge.replyLocalReviewComment = async request => {
    const existing = saved.get(request.threadId);
    assert(existing !== undefined, 'Reply must target a saved local review');
    const user: LocalReviewMessageDto = { ...existing.messages[0], id: request.messageId, body: request.body,
      turnId: 'reply-turn', messageEventId: 'reply-event', queuedInputId: null, deliveryState: 'COMPLETED', deliveryError: null };
    const answer: LocalReviewMessageDto = { ...user, id: 'agent-answer', author: 'AGENT', body: 'The implementation is simpler now.',
      messageEventId: null, deliveryState: null };
    const updated = { ...existing, version: existing.version + 1, messages: [...existing.messages, user, answer] };
    saved.set(updated.id, updated);
    publishHistory(user, INTERNAL_PROMPT);
    return updated;
  };
  bridge.resolveLocalReviewComment = async request => {
    const existing = saved.get(request.threadId);
    assert(existing !== undefined && request.expectedVersion === existing.version, 'Resolution must use the saved version');
    const updated = { ...existing, version: existing.version + 1, resolved: request.resolved };
    saved.set(updated.id, updated);
    return updated;
  };
  bridge.retryLocalReviewComment = async request => {
    retries++;
    const existing = saved.get(request.threadId);
    assert(existing !== undefined && existing.messages[0].id === request.messageId, 'Retry must retain the original saved user identity');
    const user: LocalReviewMessageDto = { ...existing.messages[0], turnId: 'retry-turn', messageEventId: 'retry-event', deliveryState: 'COMPLETED', deliveryError: null };
    const updated = { ...existing, version: existing.version + 1, messages: [user] };
    saved.set(updated.id, updated);
    publishHistory(user, INTERNAL_PROMPT);
    return updated;
  };
  let githubWrites = 0;
  const prBridge = {
    findAgentReview: async () => null,
    getUserProfile: async () => ({ login: 'alice', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    fetchPrDiffFiles: async () => [file], fetchPrDiffCommits: async () => commits,
    refreshPrDiffFiles: async (_repo, _number, base, head) => {
      assert(base === '1111111' && head === '2222222', 'Task selection must compare the requested commit'); return [file];
    },
    postPrReviewComment: async () => { githubWrites++; throw new Error('Private feedback must not post to GitHub'); },
  } satisfies Pick<Bridge, 'findAgentReview' | 'getUserProfile' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'refreshPrDiffFiles' | 'postPrReviewComment'>;
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...bridge, ...prBridge } });
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  const render = () => flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    render(); await settle();
    const composer = host.querySelector<HTMLTextAreaElement>('[aria-label="Message the agent"]');
    assert(composer !== null, 'Live task must load its normal composer');
    changeInput(composer, 'Keep my unrelated draft'); await settle();
    const attach = host.querySelector<HTMLButtonElement>('[aria-label="Attach an image"]');
    assert(attach !== null && !attach.disabled, 'Fixture must exercise a retained image attachment');
    attach.click(); await settle();
    const changes = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.includes('Changes'));
    assert(changes !== undefined, 'The associated draft PR must open the task panel');
    changes.click(); await settle();
    const choice = host.querySelector<HTMLInputElement>('[aria-label="Select 2222222: Second"]');
    assert(choice !== null, 'Task must show its real commit picker');
    choice.click(); await settle();
    if (mode === 'queued') {
      turn = { ...turn, state: 'RUNNING', endedAt: null };
      task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '2026-01-01T00:00:00Z' } };
      const started: AgentEventEnvelope = { ...event, eventId: 'started', sequence: event.sequence + 1,
        type: 'lifecycle', payload: { phase: 'TURN_STARTED', message: 'Started elsewhere' } };
      history.push(started); notify({ subscriptionId: 'inline', taskId: task.id, kind: 'events', events: [started] }); await settle();
    }
    host.querySelector<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]')?.click(); await settle();
    const inline = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
    assert(inline !== null, 'Own draft PR must offer the shared private inline composer');
    changeInput(inline, 'Please simplify this implementation'); await settle();
    const send = button(host, 'Send to agent'); send.click(); send.click(); await settle();
    assert(requests.length === 1 && button(host, 'Sending…').disabled, 'Double click must save and submit only one local discussion');
    finishCreate(); await settle();
    const request = requests[0];
    assert(request.path === file.filename && request.side === 'RIGHT' && request.line === 1 && request.startLine === 1
      && request.headSha === '2222222' && request.baseSha === '1111111' && request.code === 'new implementation'
      && request.body === 'Please simplify this implementation' && request.repo === detail.repo && request.number === detail.number,
    'Durable create must carry the exact selected anchor and human comment');
    assert(!('message' in request) && !('attachments' in request), 'The renderer must send structured local review data without a synthetic user prompt');
    assert(composer.value === 'Keep my unrelated draft' && host.querySelector('[aria-label="Remove image draft.png"]') !== null
      && task.defaults.developmentAutomation === savedAutomation,
    'Saving a local comment must preserve the main draft, attachments, and automation settings');
    assert(host.querySelector('.pull-diff-review') === null, 'Saved comments must leave the transient composer');
    const localCard = host.querySelector<HTMLElement>('.pull-diff-code [data-local-review-thread]');
    assert(localCard !== null && localCard.textContent?.includes(request.body) === true, 'The saved comment must stay at its diff line');
    for (let attempt = 0; attempt < 20 && host.querySelector('.agt-local-review [data-local-review-thread]') === null; attempt++) await settle();
    const transcriptCard = host.querySelector<HTMLElement>('.agt-local-review [data-local-review-thread]');
    assert(transcriptCard !== null, 'Task transcript must load the saved private card after the bounded history refresh');
    assert(transcriptCard.textContent?.includes(request.body) === true
      && transcriptCard.querySelector('.pr-local-review__snapshot')?.textContent?.includes(request.code) === true,
    'Task transcript card must show the human comment and its reference code');
    assert(!host.textContent?.includes(INTERNAL_PROMPT), 'Internal instructions must not appear in the task transcript or queue preview');
    if (mode === 'unsent') {
      assert(localCard.textContent?.includes('Not sent') === true && localCard.textContent?.includes('Agent unavailable; retry this saved comment') === true,
        'A saved but undispatched comment must visibly offer retry');
      button(localCard, 'Retry').click(); await settle();
      assert(retries === 1 && saved.get(request.id)?.messages.length === 1 && !host.textContent?.includes('Agent unavailable; retry this saved comment'),
        'Retry must update the same persisted comment without duplicating its text');
    }
    if (mode === 'queued') assert(host.textContent?.includes('queued') === true && queue.length === 1,
      'Queued delivery must remain associated with its private comment');
    if (mode === 'idle') {
      button(localCard, 'Reply').click(); await settle();
      const input = localCard.querySelector<HTMLTextAreaElement>('[aria-label="Reply to local review"]');
      assert(input !== null, 'Local card must allow a follow-up');
      changeInput(input, 'Please explain the simpler version'); await settle();
      button(localCard, 'Reply to agent').click(); await settle();
      assert(saved.get(request.id)?.messages.length === 3 && localCard.textContent?.includes('The implementation is simpler now.') === true,
        'A private reply and agent answer must remain in the same persistent card');
      button(localCard, 'Resolve').click(); await settle();
      assert(saved.get(request.id)?.resolved === true, 'User resolution must be persisted');
      const prTab = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === 'PR #7');
      assert(prTab !== undefined, 'The same task must expose its PR timeline');
      prTab.click(); await settle();
      const timelineCard = host.querySelector<HTMLElement>('.pr-timeline [data-local-review-thread]');
      assert(timelineCard?.textContent?.includes('Resolved') === true && timelineCard.textContent?.includes('The implementation is simpler now.') === true,
        'The PR timeline must show the same resolved user/agent discussion');
      button(host, 'Review & Learning').click(); await settle();
      const learningCard = host.querySelector<HTMLElement>('.agr-local-comments [data-local-review-thread]');
      assert(learningCard?.textContent?.includes('The implementation is simpler now.') === true && learningCard.textContent?.includes('Resolved') === true,
        'Review & Learning must show the same private discussion before the first agent review');
      assert(![...host.querySelectorAll('button')].some(element => element.textContent === 'Start review')
        && host.textContent?.includes('After a turn changes code or commits') === true && !host.querySelector('.agr-strip__tabs'),
        'The development PR must use automatic learning summaries without manual startup or Activity subtabs');
    }
    assert(githubWrites === 0 && !bridge.commands.some(command => ['startAgentTurn', 'queueAgentInput', 'steerAgentTurn'].includes(command.name)),
      'Private UI must dispatch through the durable local-review endpoint without direct task or GitHub writes');
    flushSync(() => root.unmount()); root = createRoot(host); render(); await settle();
    assert(host.querySelector('[data-local-review-thread]')?.textContent?.includes(request.body) === true
      && !host.textContent?.includes(INTERNAL_PROMPT), 'Reopening a task must recover local discussion data without exposing internal prompts');
  }
  finally { flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
}

export async function runTaskInlineCommentScenarios(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  for (const mode of ['idle', 'queued', 'unsent'] as const) {
    const name = `Live persistent local review: ${mode}`;
    try { await scenario(mode); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
