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
import { prDetail } from './pullRequest';
import AgentTaskScreen from '../../agents/AgentTaskScreen';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentEventEnvelope } from '../../agents/events';
import type { AgentQueuedInputDto, AgentStreamMessage, AgentTaskDto, AgentTurnDto, Bridge, DiffCommitDto, DiffFileDto } from '../../types';
import { remotePullRequest } from '../PullsScreen';
import type { CreateLocalReviewRequest } from '../local-review/contracts';
import type { LocalReviewMessageDto, LocalReviewThreadDto } from '../local-review/types';
import { runScenarios, element as fixtureElement, assert, changeInput, settle } from '../../fixtures/harness';

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
const detail = prDetail({
  id: 7, repo: 'owner/repo', number: 7, title: 'Implementation', htmlUrl: null, draft: true,
  mergeable: null, mergeableState: null, author: 'alice', updatedAt: null,
  deletions: 1,
});

const INTERNAL_PROMPT = 'INTERNAL_LOCAL_REVIEW_CONTEXT_NOT_A_USER_MESSAGE';
const viewerAvatar = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22"><circle cx="11" cy="11" r="11" fill="blue"/></svg>')}`;

async function scenario(mode: 'idle' | 'queued' | 'unsent' | 'published'): Promise<void> {
  const bridge = createPreviewBridge('workspace-1');
  const original = bridge.events.find(event => event.type === 'user_message');
  assert(original !== undefined && original.turnId !== null, 'Fixture must retain one completed turn');
  const event: AgentEventEnvelope = { ...original, payload: { ...original.payload, attachments: [] } };
  const history: AgentEventEnvelope[] = [event];
  const savedAutomation = { level: 'COMMIT' as const, ciLaneId: 'codex' };
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
  if (mode === 'idle') {
    for (const source of [{ id: 'earlier', summary: 'earlier-summary', card: 'limitations', title: 'Earlier limitations' },
      { id: 'removed', summary: 'current-summary', card: 'evidence', title: 'Supporting evidence & tests' }]) {
      const message: LocalReviewMessageDto = { id: `${source.id}-learning-message`, author: 'USER', body: 'Why was the offline build unavailable?',
        createdAt: turn.createdAt, turnId: `${source.id}-learning-turn`, queuedInputId: null, messageEventId: `${source.id}-learning-event`,
        deliveryState: 'COMPLETED', deliveryError: null };
      saved.set(`${source.id}-learning-thread`, { id: `${source.id}-learning-thread`, taskId: task.id, taskVersion: task.version, laneId: 'codex',
        workspaceId: task.workspaceId, repo: '', number: 0, path: '', side: 'RIGHT', startLine: null, line: 0,
        baseSha: null, headSha: null, code: '', resolved: true, version: 1, createdAt: turn.createdAt, updatedAt: turn.createdAt,
        learningAnchor: { turnId: source.summary, cardId: source.card, title: source.title, body: 'The offline build was unavailable.' },
        messages: [message] });
      history.push({ ...event, eventId: message.messageEventId, turnId: message.turnId, sequence: (history.at(-1)?.sequence ?? 0) + 1,
        payload: { text: INTERNAL_PROMPT, attachments: [], deliveryKind: 'TURN' } });
    }
  }
  let retries = 0;
  let finishCreate: () => void = () => {};
  let finishRefresh: () => void = () => {};
  const getCatalog = bridge.getModelSettings;
  bridge.getModelSettings = async () => {
    const catalog = await getCatalog();
    return { ...catalog, providers: catalog.providers.map(provider => ({ ...provider,
      models: provider.models.map(model => ({ ...model, inputModalities: ['text', 'image'] })) })) };
  };
  bridge.getAgentTask = async () => task;
  bridge.getAgentTurn = async id => ({ ...turn, id });
  bridge.getDevelopmentLearning = async () => mode === 'idle' ? { ...turn, id: 'current-summary', developmentResult: {
    readyForDelivery: true, message: null, learningSummary: { purpose: 'Simplify optional value handling.',
      commits: [{ sha: '2222222', title: 'Simplify the implementation', summary: 'Validate the optional before returning its value.' }],
      combinedResult: 'Simpler validation.', evidence: 'The offline build was unavailable.', limitations: [] },
  } } : null;
  bridge.readAgentHistory = async () => ({ events: [...history], beforeCursor: null, afterCursor: null, latestSequence: history.at(-1)?.sequence ?? 0 });
  bridge.readAgentPendingApprovals = async () => [];
  bridge.listAgentQueue = async () => [...queue];
  const viewer = mode === 'published' ? 'bob' : 'alice';
  bridge.listAgentTaskPullRequests = async () => [remotePullRequest(detail, viewer)];
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
    const thread: LocalReviewThreadDto = { id: request.id, taskId: request.taskId, taskVersion: task.version, laneId: 'codex', workspaceId: request.workspaceId,
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
    if (mode === 'idle') {
      const refresh = new Promise<void>(resolve => { finishRefresh = resolve; });
      bridge.getAgentTask = async () => { await refresh; return task; };
    }
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
  let localReads = 0;
  const posted: Parameters<Bridge['postPrReviewComment']>[] = [];
  const prBridge = {
    findAgentReview: async () => null,
    findTaskAgentReview: async () => null,
    listAgentReviews: async () => [],
    getUserProfile: async () => ({ login: viewer, name: null, avatarUrl: viewerAvatar, htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    fetchPrDiffFiles: async () => [file], fetchPrDiffCommits: async () => commits,
    readWorktreeChanges: async (host, request) => {
      assert(host.kind === 'task' && host.taskId === task.id && host.workspaceId === task.workspaceId,
        'Working changes must read the current task worktree');
      localReads++;
      return { branch: 'feature', baseSha: '0000000', headSha: 'local-only', baseLabel: 'main', scope: request.scope,
        selectedCommit: null, sourceFingerprint: 'working-copy', truncated: false, staged: [], unstaged: ['local.txt'], untracked: [],
        files: [{ filename: 'local.txt', status: 'modified', additions: 1, deletions: 0, patch: '@@ -0,0 +1 @@\n+Unpublished development edit' }],
        commits: [{ sha: 'local-only', parentSha: '2222222', message: 'Unpushed work', author: 'alice', authoredAt: null }] };
    },
    refreshPrDiffFiles: async (_repo, _number, base, head) => {
      assert(base === '1111111' && head === '2222222', 'Task selection must compare the requested commit'); return [file];
    },
    postPrReviewComment: async (...request) => {
      githubWrites++;
      assert(mode === 'published', 'Private feedback must not post to GitHub');
      posted.push(request);
      if (posted.length === 1) throw new Error('GitHub did not confirm this comment, so it may have been posted.');
      return detail;
    },
  } satisfies Pick<Bridge, 'findAgentReview' | 'findTaskAgentReview' | 'listAgentReviews' | 'getUserProfile' | 'fetchPullRequestDetailByRef' | 'refreshPullRequestDetailByRef'
    | 'fetchPrDiffFiles' | 'fetchPrDiffCommits' | 'readWorktreeChanges' | 'refreshPrDiffFiles' | 'postPrReviewComment'>;
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...bridge, ...prBridge } });
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  const render = () => flushSync(() => root.render(<AgentTaskScreen workspaceId="workspace-1" taskId={task.id} bridge={bridge} onBack={() => {}} />));
  try {
    render(); await settle();
    const composer = fixtureElement<HTMLTextAreaElement>('[aria-label="Message the agent"]', host, 'Live task must load its normal composer');
    changeInput(composer, 'Keep my unrelated draft'); await settle();
    const attach = host.querySelector<HTMLButtonElement>('[aria-label="Attach an image"]');
    assert(attach !== null && !attach.disabled, 'Fixture must exercise a retained image attachment');
    attach.click(); await settle();
    if (mode === 'idle') {
      button(host, 'Summary').click(); await settle();
    }
    const readsBeforeChanges = localReads;
    if (mode === 'idle') button(host, 'View changes').click();
    else button(host, 'Changes').click();
    await settle();
    assert(host.querySelector('[aria-label="Changes source"]') === null && localReads === readsBeforeChanges
      && !host.querySelector('.agt-panel .pull-diff-file')?.textContent?.includes('Unpublished development edit'),
    'Linked Changes must read the published PR without a source selector or another worktree read');
    if (mode === 'published') {
      const add = fixtureElement<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]', host, 'The default Changes view must offer the inline comment gutter');
      add.click(); await settle();
      const inline = fixtureElement<HTMLTextAreaElement>('.pull-diff-review textarea', host, 'Clicking a published line must open its review composer');
      changeInput(inline, 'Please cover this implementation with a test'); await settle();
      const post = button(host, 'Post now');
      assert(!post.disabled && githubWrites === 0, 'A published line must allow posting without writing before the explicit action');
      post.click(); await settle();
      assert(host.querySelector('.pull-diff-review [role="alert"]')?.textContent?.includes('may have been posted') === true,
        'A send with an unknown outcome must keep its composer and say so');
      button(host, 'Post now').click(); await settle();
      const [request, retry] = posted;
      assert(posted.length === 2 && request[0] === detail.repo && request[1] === detail.number
        && request[2].path === file.filename && request[2].side === 'RIGHT' && request[2].line === 1 && request[2].startLine === null
        && request[2].commitId === '2222222' && request[2].body === 'Please cover this implementation with a test',
      'Post now must publish to the displayed PR revision and selected line');
      assert(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(request[3]) && retry[3] === request[3],
        'A retry from the same comment box must send its key again, so the backend cannot post it twice');
      assert(host.querySelector('.pull-diff-review') === null && composer.value === 'Keep my unrelated draft'
        && host.querySelector('[aria-label="Remove image draft.png"]') !== null && requests.length === 0
        && !bridge.commands.some(command => ['startAgentTurn', 'queueAgentInput', 'steerAgentTurn'].includes(command.name)),
      'Posting a review comment must close its composer while preserving the task draft and avoiding agent dispatch');
      return;
    }
    if (mode === 'idle') {
      assert(host.querySelector('[aria-label="Deselect 2222222: Second"]') !== null,
        'A development summary commit must navigate directly to that published commit');
    }
    else {
      const choice = fixtureElement<HTMLInputElement>('[aria-label="Select 2222222: Second"]', host, 'Task must show its real commit picker');
      choice.click(); await settle();
    }
    if (mode === 'queued') {
      turn = { ...turn, state: 'RUNNING', endedAt: null };
      task = { ...task, version: task.version + 1, mutationClaim: { kind: 'TURN', ownerId: turn.id, acquiredAt: '2026-01-01T00:00:00Z' } };
      const started: AgentEventEnvelope = { ...event, eventId: 'started', sequence: event.sequence + 1,
        type: 'lifecycle', payload: { phase: 'TURN_STARTED', message: 'Started elsewhere' } };
      history.push(started); notify({ subscriptionId: 'inline', taskId: task.id, kind: 'events', events: [started] }); await settle();
    }
    host.querySelector<HTMLButtonElement>('[aria-label="Add a comment on new line 1"]')?.click(); await settle();
    const inline = fixtureElement<HTMLTextAreaElement>('.pull-diff-review textarea', host, 'Own draft PR must offer the shared private inline composer');
    changeInput(inline, 'Please simplify this implementation'); await settle();
    const send = button(host, 'Send to agent'); send.click(); send.click(); await settle();
    assert(requests.length === 1 && host.querySelector('.is-review-composer [role="status"]')?.textContent === 'Sending…'
      && host.querySelector('.pull-diff-review') === null, 'Double click must submit once and replace the inline editor with a compact status');
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
    assert(host.querySelector('.is-review-composer') === null, 'Saved comments must close the composer without waiting for the task refresh');
    finishRefresh(); await settle();
    const localCard = host.querySelector<HTMLElement>('.pull-diff-code [data-local-review-thread]');
    assert(localCard !== null && localCard.textContent?.includes(request.body) === true, 'The saved comment must stay at its diff line');
    for (let attempt = 0; attempt < 20 && host.querySelector(`.agt-local-review [data-local-review-thread="${request.id}"]`) === null; attempt++) await settle();
    const transcriptCard = fixtureElement<HTMLElement>(`.agt-local-review [data-local-review-thread="${request.id}"]`, host, 'Task transcript must load the saved private card after the bounded history refresh');
    assert(transcriptCard.textContent?.includes(request.body) === true
      && transcriptCard.querySelector('.is-excerpt')?.textContent?.includes(request.code) === true,
    'Task transcript card must show the human comment and its reference code');
    const assertViewerAvatar = (card: Element) => {
      const avatar = card.querySelector<HTMLImageElement>('.user-avatar-trigger img.avatar');
      assert(avatar?.getAttribute('src') === viewerAvatar && avatar.alt === viewer && avatar.complete && avatar.naturalWidth > 0,
        'Saved local comments must use the signed-in profile and shared user avatar in every surface');
    };
    assertViewerAvatar(localCard);
    assertViewerAvatar(transcriptCard);
    assert(!host.innerText.includes(INTERNAL_PROMPT), 'Internal instructions must stay hidden until their disclosure is opened, including in the queue preview');
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
      const input = fixtureElement<HTMLTextAreaElement>('[aria-label="Reply to local review"]', localCard, 'Local card must allow a follow-up');
      assert(localCard.querySelector<HTMLButtonElement>('.pull-diff-review__bar .pull-diff-review__private')?.disabled === true
        && localCard.textContent?.includes('Private — only you and the agent') === false,
      'A task reply must show the Private label instead of a help text');
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
      assertViewerAvatar(timelineCard);
      for (let attempt = 0; attempt < 20 && ![...host.querySelectorAll('.agt-review-request__open')].some(candidate => candidate.textContent?.includes('Reply to review')); attempt++) await settle();
      const replyRequest = [...host.querySelectorAll<HTMLButtonElement>('.agt-review-request__open')]
        .find(candidate => candidate.textContent?.includes('Reply to review'));
      assert(replyRequest !== undefined, 'A transcript reply must keep its private discussion card');
      const replyCard = replyRequest.closest<HTMLElement>('.agt-review-request');
      replyRequest.click(); await settle();
      const inlineThread = replyCard?.querySelector<HTMLElement>(`[data-local-review-thread="${request.id}"]`);
      assert(host.querySelector('.agt-panel[aria-label="Summary"]') === null && host.querySelector('.pr-timeline [data-local-review-thread]') !== null
        && replyCard?.classList.contains('is-open') === true && inlineThread?.textContent?.includes('The implementation is simpler now.') === true,
      'Clicking a reply on code must open its discussion in place and leave the panel on the PR');
      assertViewerAvatar(inlineThread);
      [...host.querySelectorAll<HTMLButtonElement>('.agt-panel button')].find(candidate => candidate.textContent?.trim().startsWith('WorkBench'))?.click(); await settle();
      button(host, 'Summary').click(); await settle();
      const learningCard = host.querySelector<HTMLDetailsElement>(`.agt-panel [data-local-review-thread="${request.id}"]`);
      assert(learningCard?.textContent?.includes('The implementation is simpler now.') === true && learningCard.textContent?.includes('Resolved') === true,
        'Summary must show the same private discussion before the first agent review');
      assert(![...host.querySelectorAll('button')].some(element => element.textContent === 'Start review')
        && host.querySelector('[data-learning-card="purpose"]')?.textContent?.includes('Simplify optional value handling.') === true && !host.querySelector('.agr-strip__tabs'),
        'The development PR must show the current intention without manual startup or Activity subtabs');
      const earlierRequest = [...host.querySelectorAll<HTMLButtonElement>('.agt-review-request__open')]
        .find(candidate => candidate.textContent?.includes('Earlier limitations'));
      assert(earlierRequest !== undefined, 'Earlier learning discussions must retain their transcript navigation');
      host.querySelector<HTMLButtonElement>('[aria-label="Show or hide the side panel"]')?.click(); await settle();
      assert(host.querySelector('.agt-panel') === null, 'Fixture must exercise navigation from a hidden panel');
      earlierRequest.click(); await settle();
      const earlierCard = host.querySelector<HTMLDetailsElement>('.agt-panel .agr-learning-history [data-local-review-thread="earlier-learning-thread"]');
      assert(earlierCard?.open === true && earlierCard.closest<HTMLDetailsElement>('.agr-learning-history')?.open === true,
        'A question about an earlier summary must open its old report section and resolved discussion');
      assert(earlierCard.contains(document.activeElement), 'Earlier-summary navigation must focus the exact discussion inside the side panel');
      const removedRequest = [...host.querySelectorAll<HTMLButtonElement>('.agt-review-request__open')]
        .find(candidate => candidate.textContent?.includes('Supporting evidence & tests'));
      const removedCard = host.querySelector<HTMLDetailsElement>('.agt-panel .agr-learning-history [data-local-review-thread="removed-learning-thread"]');
      assert(removedRequest !== undefined && removedCard !== null && removedCard.textContent?.includes('Why was the offline build unavailable?') === true,
        'A saved discussion on a removed card from the current summary must remain under Earlier summary discussions');
      assert(host.querySelector('.agt-panel [data-learning-card="evidence"]') === null,
        'Preserving its discussion must not restore the removed evidence report card');
      removedCard.closest<HTMLDetailsElement>('.agr-learning-history').open = false;
      removedRequest.click(); await settle();
      assert(removedCard.open && removedCard.closest<HTMLDetailsElement>('.agr-learning-history').open && removedCard.contains(document.activeElement),
        'The green request header must open and focus the exact saved discussion on the removed current-summary card');
      assert(composer.value === 'Keep my unrelated draft' && host.querySelector('[aria-label="Remove image draft.png"]') !== null,
        'Review navigation must preserve the user’s draft and image attachment');
    }
    assert(githubWrites === 0 && !bridge.commands.some(command => ['startAgentTurn', 'queueAgentInput', 'steerAgentTurn'].includes(command.name)),
      'Private UI must dispatch through the durable local-review endpoint without direct task or GitHub writes');
    flushSync(() => root.unmount()); root = createRoot(host); render(); await settle();
    assert(host.querySelector(`[data-local-review-thread="${request.id}"]`)?.textContent?.includes(request.body) === true
      && !host.innerText.includes(INTERNAL_PROMPT), 'Reopening a task must recover local discussion data with internal prompts folded');
  }
  finally { finishRefresh(); flushSync(() => root.unmount()); host.remove(); Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
}

export async function runTaskInlineCommentScenarios(): Promise<{ name: string; error?: string }[]> {
  return runScenarios((['idle', 'queued', 'unsent', 'published'] as const).map(mode => [
    mode === 'published' ? 'Task Changes opens the published PR for explicit line comments' : `Live persistent local review: ${mode}`,
    () => scenario(mode),
  ]));
}
