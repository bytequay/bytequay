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
import { prDetail } from '../../fixtures/pullRequest';
import { installDesktopFixture } from '../../../ide/fixtures/desktop';
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import type { Bridge, PullRequestDetailDto } from '../../../types';
import type { TaskDiscussionDto } from '../../../agents/discussions/contracts';
import type { AgentEventEnvelope } from '../../../agents/events';
import { agentTurn, createPreviewBridge } from '../../../agents/fixtures/previewBridge';
import { element as fixtureElement, assert, changeInput, settle } from '../../../fixtures/harness';
import ReviewTaskScreen from '../../../agents/ReviewTaskScreen';
import { runningReviewFixture } from './review';
import '../../../css/base.css';

export async function run(local = false): Promise<string> {
  const desktop = installDesktopFixture();
  const initial = runningReviewFixture();
  const review = { ...initial, number: local ? null : initial.number, task: { ...initial.task, nestedAgents: true } };
  const events: AgentEventEnvelope[] = [];
  const append = (thread: string, parent: string | null, label: string | null, item: Pick<AgentEventEnvelope, 'type' | 'payload'>) => {
    const sequence = events.length + 1;
    events.push({ schemaVersion: 2, eventId: `event-${sequence}`, sequence, taskId: review.task.id, turnId: 'turn-1',
      occurredAt: `2026-09-06T16:03:${String(sequence).padStart(2, '0')}Z`,
      source: { providerThreadId: thread, parentProviderThreadId: parent, logicalAgentPath: label,
        providerTurnId: 'provider-turn', providerItemId: `item-${sequence}`, providerEventId: `done-${sequence}` },
      ...item } as AgentEventEnvelope);
  };
  const life = (thread: string, parent: string | null, label: string | null, phase: 'TURN_STARTED' | 'AGENT_STARTED' | 'AGENT_COMPLETED') =>
    append(thread, parent, label, { type: 'lifecycle', payload: { phase, message: '' } });
  const text = (thread: string, parent: string | null, label: string | null, messageKind: 'COMMENTARY' | 'ANSWER' | 'PROMPT', body: string) =>
    append(thread, parent, label, { type: 'assistant_message', payload: { messageKind, phase: 'FINAL', text: body } });
  life('root', null, null, 'TURN_STARTED');
  text('root', null, null, 'COMMENTARY', 'Checking upload cancellation.');
  life('live', 'root', '/root/abort_contract', 'AGENT_STARTED');
  text('live', 'root', '/root/abort_contract', 'PROMPT', 'Check cancellation behavior.');
  text('live', 'root', '/root/abort_contract', 'COMMENTARY', 'Tracing the blocked writer.');
  life('nested', 'live', '/root/abort_contract/queue_cleanup', 'AGENT_STARTED');
  text('nested', 'live', '/root/abort_contract/queue_cleanup', 'COMMENTARY', 'Inspecting queue cleanup.');
  life('done', 'root', '/root/sdk_semantics', 'AGENT_STARTED');
  text('done', 'root', '/root/sdk_semantics', 'COMMENTARY', 'Checking the failure path.');
  text('done', 'root', '/root/sdk_semantics', 'ANSWER', 'The failure releases the writer.');
  life('done', 'root', '/root/sdk_semantics', 'AGENT_COMPLETED');
  text('root', null, null, 'ANSWER', 'Cancellation releases the writer.');
  const detail: PullRequestDetailDto = prDetail({
    id: 9001, repo: review.repo, number: initial.number, title: 'Cancel uploads', htmlUrl: null,
    mergeable: null, mergeableState: null, author: 'alice', updatedAt: null,
    baseRef: 'master', body: 'Implementation',
    deletions: 1,
    viewerCanWrite: false,
  });
  let subscriptions = 0;
  let stops = 0;
  let notify: Parameters<Bridge['subscribeAgentReview']>[2] = () => {};
  const previous = window.bridge;
  let discussion: TaskDiscussionDto | null = null;
  let opens = 0;
  let replies = 0;
  let reviewReads = 0;
  let readsBeforeHistory: number | null = null;
  const stubs = {
    listReviewDiscussions: async () => discussion === null ? [] : [discussion],
    openReviewDiscussion: async (reviewId, request) => {
      assert(reviewId === review.id && request.turnId === 'turn-1' && request.quote === 'releases the writer' && request.occurrence === 1,
        'The selected passage must go through the owning review');
      assert(request.question === 'How does cancellation do that?', 'The inline question changed');
      opens++;
      discussion = { id: 'passage-1', taskId: review.task.id, ...request, pending: false, resolved: false,
        providerLane: review.task.laneId, comment: null, replyDraft: null, replyDraftError: null, version: 1, createdAt: review.createdAt, updatedAt: review.createdAt,
        messages: [
          { id: 'question-1', author: 'USER', body: request.question, createdAt: review.createdAt, error: null },
          { id: 'answer-1', author: 'AGENT', body: 'Cancellation closes the writer.', createdAt: review.createdAt, error: null },
        ] };
      return discussion;
    },
    askReviewDiscussion: async (reviewId, id, question) => {
      assert(reviewId === review.id && id === discussion?.id, 'Follow-ups must stay on the review discussion');
      replies++;
      discussion = { ...discussion, version: discussion.version + 1, messages: [...discussion.messages,
        { id: 'question-2', author: 'USER', body: question, createdAt: review.createdAt, error: null },
        { id: 'answer-2', author: 'AGENT', body: 'Repeated cancellation is safe.', createdAt: review.createdAt, error: null },
      ] };
      return discussion;
    },
    resolveReviewDiscussion: async (reviewId, id, resolved, version) => {
      assert(reviewId === review.id && id === discussion?.id && version === discussion.version, 'Resolve must use the current review discussion version');
      discussion = { ...discussion, resolved, version: version + 1 };
      return discussion;
    },
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    getAgentReview: async () => { reviewReads++; return review; },
    getAgentTurn: async () => agentTurn('RUNNING', { taskId: review.task.id,
      createdAt: '2026-09-06T16:03:00Z', startedAt: '2026-09-06T16:03:01Z' }),
    readAgentReviewHistory: async () => {
      readsBeforeHistory ??= reviewReads;
      return { events: [...events], beforeCursor: null, afterCursor: null, latestSequence: events.length };
    },
    readAgentReviewApprovals: async () => [],
    subscribeAgentReview: async (_id, _after, listener) => {
      subscriptions++;
      notify = listener;
      listener({ kind: 'state', state: 'open', subscriptionId: 'review-stream', taskId: review.task.id });
      return () => { stops++; };
    },
    fetchAttentionPrs: async () => [],
    fetchPullRequestDetailByRef: async () => detail,
    refreshPullRequestDetailByRef: async () => ({ full: true as const, detail, status: null }),
    refreshAllPullRequestDetailByRef: async () => detail,
    fetchPrDiffFiles: async () => [],
    fetchPrDiffCommits: async () => [],
    refreshPrDiffFiles: async () => [],
    findPullRequestTask: async () => null,
    listLocalReviewComments: async () => [],
    listAgentReviews: async () => [review],
    listCodeFiles: async () => ({ source: 'WORKING_COPY' as const, revision: 'head', truncated: false,
      sourceFingerprint: null, savedExperiment: false, files: [] }),
    listCodeRuns: async () => [],
    readWorktreeChanges: async (_host, request) => ({ scope: request.scope, selectedCommit: null, sourceFingerprint: 'worktree',
      branch: 'reviewer', baseSha: 'base', headSha: 'head', baseLabel: 'master', truncated: false,
      staged: [], unstaged: [], untracked: [], commits: [], files: [] }),
    fetchMergeMode: async () => ({ mergeQueue: false }),
    recordRecentTouch: async () => {},
  } satisfies Partial<Bridge>;
  Object.assign(window, { bridge: { ...createPreviewBridge(review.workspaceId), ...stubs } });
  const { host, root, dispose } = createFixtureRoot('main');
  host.style.cssText = 'height:850px;width:1500px';
  const chip = (label: string, scope: Element = host) => {
    const button = [...scope.querySelectorAll<HTMLButtonElement>('.agt-agent-chip')].find(node => node.textContent === label);
    assert(button !== undefined, `Missing agent chip: ${label}`);
    return button;
  };
  const click = (label: string) => {
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(node => node.textContent?.trim().startsWith(label));
    assert(button !== undefined, `Missing button: ${label}`);
    button.click();
  };
  const panel = () => host.querySelector<HTMLElement>('.pull-detail > .agt-panel__scroll');
  const tab = () => host.querySelector('[aria-label="Task destinations"] .pull-detail__utility');
  try {
    flushSync(() => root.render(<ReviewTaskScreen workspaceId={review.workspaceId} reviewId={review.id} browserOpen={false} onBack={() => {}} />));
    for (let attempt = 0; attempt < 60 && host.querySelector('.agt-agent-chip') === null; attempt++) await settle();
    assert(readsBeforeHistory === 1, `The task screen's review read must open the conversation; it read ${readsBeforeHistory} times`);
    const conversation = host.querySelector('.agr-workspace-conversation');
    assert(conversation !== null && tab() === null, 'Subagents must remain a conversation destination until clicked');
    assert(!conversation.textContent?.includes('Tracing the blocked writer.'), 'Child messages must stay out of the root transcript');
    const input = fixtureElement<HTMLTextAreaElement>('.agt-compose__input', host, 'The review composer must remain available');
    changeInput(input, 'Keep this unsent question'); await settle();
    const route = window.location.hash;
    const icon = fixtureElement<Element>('svg', chip('Abort contract', conversation), 'The agent chip must contain its icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true })); await settle();
    assert(tab()?.textContent?.includes('Subagents 3') && tab()?.querySelector('[aria-pressed="true"]') !== null,
      'Clicking the icon must open and select the counted Subagents tab');
    assert(panel()?.hidden === false && panel()?.textContent?.includes('Tracing the blocked writer.')
      && !panel()?.textContent?.includes('Check cancellation behavior.'), 'The selected agent must show its messages without its prompt');
    assert(host.querySelector('[aria-label="WorkBench views"]') === null, 'Subagents must be a top-level destination');
    chip('Queue cleanup', panel() ?? host).click(); await settle();
    assert(panel()?.textContent?.includes('Inspecting queue cleanup.') && !panel()?.textContent?.includes('Tracing the blocked writer.'),
      'Nested chips must open that exact child in the same inspector');
    const finished = [...conversation.querySelectorAll<HTMLButtonElement>('.agt-agent-chip')].filter(node => node.textContent === 'SDK semantics').at(-1);
    assert(finished !== undefined, 'The finished row must contain the completed agent');
    finished.click(); await settle();
    assert(panel()?.textContent?.includes('The failure releases the writer.'), 'Finished chips must open the retained result');
    panel()?.querySelector<HTMLButtonElement>('.agt-panel__worked')?.click(); await settle();
    assert(panel()?.textContent?.includes('Checking the failure path.'), 'A completed agent must retain its expandable messages');
    chip('Abort contract', conversation).click(); await settle();
    text('live', 'root', '/root/abort_contract', 'COMMENTARY', 'Cancellation confirmed by the test.');
    notify({ kind: 'changed', taskId: review.task.id, subscriptionId: 'review-stream', sequence: events.length, turnId: 'turn-1' });
    for (let attempt = 0; attempt < 60 && !panel()?.textContent?.includes('Cancellation confirmed by the test.'); attempt++) await settle();
    assert(panel()?.textContent?.includes('Cancellation confirmed by the test.'), 'The open inspector must follow the existing review stream');
    panel()?.querySelector<HTMLButtonElement>('[aria-label="Back to all subagents"]')?.click(); await settle();
    assert(panel()?.querySelectorAll('.agt-panel__row').length === 3 && panel()?.textContent?.includes('Done · 1'),
      'Back must show the existing active and completed agent lists');
    click('WorkBench'); await settle();
    assert(panel()?.hidden === true && tab() !== null, 'Switching back to WorkBench must retain the Subagents tab');
    click('Tears of time'); await settle();
    const code = host.querySelector('.pull-detail__code');
    chip('Abort contract', conversation).click(); await settle();
    assert(code !== null && code === host.querySelector('.pull-detail__code') && code.hasAttribute('hidden'),
      'Opening an agent must keep the Tears of time surface mounted');
    host.querySelector<HTMLButtonElement>('[aria-label="Close Subagents"]')?.click(); await settle();
    assert(tab() === null && !code.hasAttribute('hidden'), 'Closing Subagents must restore the covered WorkBench view');
    finished.click(); await settle();
    assert(tab() !== null && panel()?.textContent?.includes('The failure releases the writer.'), 'A finished chip must reopen a closed tab');
    assert(input.value === 'Keep this unsent question' && window.location.hash === route && subscriptions === 1 && stops === 0,
      'Inspecting agents must preserve the draft and route and reuse exactly one review subscription');
    click('WorkBench'); await settle();
    click('Tears of time'); await settle();
    const answer = conversation.querySelector('.agt-answer .agt-md p');
    assert(answer?.firstChild !== null && answer?.firstChild !== undefined, 'Missing the root agent answer');
    const quote = 'releases the writer';
    const node = answer.firstChild;
    const offset = (node.textContent ?? '').indexOf(quote);
    window.getSelection()?.setBaseAndExtent(node, offset, node, offset + quote.length);
    document.body.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); await settle();
    const ask = fixtureElement<HTMLButtonElement>('.agt-disc-pill', conversation, 'Review text selection must offer Ask about this');
    ask.click(); await settle();
    const question = fixtureElement<HTMLTextAreaElement>('[aria-label="Ask about this passage"]', conversation, 'The shared passage composer must open');
    changeInput(question, 'How does cancellation do that?'); await settle();
    conversation.querySelector<HTMLButtonElement>('.agt-disc-pop [aria-label="Ask"]')?.click(); await settle();
    assert(opens === 1 && panel()?.textContent?.includes('Cancellation closes the writer.'), 'The saved discussion must open in the side panel');
    assert(code.hasAttribute('hidden') && input.value === 'Keep this unsent question', 'Discussing text must preserve the code surface and main draft');
    panel()?.querySelector<HTMLButtonElement>('.agt-disc-card__reply')?.click(); await settle();
    const reply = panel()?.querySelector<HTMLTextAreaElement>('[aria-label="Reply"]');
    assert(reply !== null && reply !== undefined, 'A discussion must accept follow-up questions');
    changeInput(reply, 'What if it repeats?'); await settle();
    panel()?.querySelector<HTMLButtonElement>('.agt-disc-reply [aria-label="Ask"]')?.click(); await settle();
    assert(replies === 1 && panel()?.textContent?.includes('Repeated cancellation is safe.'), 'The follow-up must use the review bridge');
    panel()?.querySelector<HTMLButtonElement>('.agt-disc-card__done')?.click(); await settle();
    assert(panel()?.textContent?.includes('Reopen'), 'The discussion must resolve locally');
    host.querySelector<HTMLButtonElement>('[aria-label="Close Discussions"]')?.click(); await settle();
    assert(!code.hasAttribute('hidden'), 'Closing Discussions must restore the covered WorkBench view');
    conversation.querySelector<HTMLButtonElement>('.agt-disc-mark')?.click(); await settle();
    assert(panel()?.textContent?.includes('Repeated cancellation is safe.'), 'The highlighted passage must reopen its saved discussion');
    assert(subscriptions === 1 && stops === 0 && window.location.hash === route, 'Discussion must reuse the review workspace and subscription');
    review.state = 'CLOSED';
    notify({ kind: 'changed', taskId: review.task.id, subscriptionId: 'review-stream', sequence: events.length, turnId: 'turn-1' });
    for (let attempt = 0; attempt < 60 && !panel()?.textContent?.includes('discussions are read-only'); attempt++) await settle();
    assert(panel()?.textContent?.includes('discussions are read-only') && panel()?.querySelector('.agt-disc-card__reply') === null,
      'Ended reviews must retain discussions without accepting questions');
    return 'Review chips open live, completed and nested subagents; selected answers open reusable discussions';
  }
  finally {
    dispose(); desktop.dispose(); Object.assign(window, { bridge: previous });
  }
}
