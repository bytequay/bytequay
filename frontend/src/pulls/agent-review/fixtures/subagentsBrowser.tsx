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
import { installDesktopFixture } from '../../../ide/fixtures/desktop';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { Bridge, PullRequestDetailDto } from '../../../types';
import type { AgentEventEnvelope } from '../../../agents/events';
import { agentTurn, createPreviewBridge } from '../../../agents/fixtures/previewBridge';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import PullsScreen from '../../PullsScreen';
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
  const detail: PullRequestDetailDto = {
    id: 9001, repo: review.repo, number: initial.number, title: 'Cancel uploads', htmlUrl: null, state: 'open', draft: false,
    mergeable: null, mergeableState: null, author: 'alice', authorAvatarUrl: null, createdAt: null, updatedAt: null,
    mergedAt: null, baseRef: 'master', headRef: 'feature', body: 'Implementation', labels: [], assignees: [], milestone: null,
    reviewers: [], additions: 1, deletions: 1, changedFiles: 1, approvalCount: 0, ciStatus: null,
    recentActivity: [], checkRuns: [], unavailableSections: [], viewerCanWrite: false,
  };
  let subscriptions = 0;
  let stops = 0;
  let notify: Parameters<Bridge['subscribeAgentReview']>[2] = () => {};
  const previous = window.bridge;
  const stubs = {
    getUserProfile: async () => ({ login: 'bob', name: null, avatarUrl: '', htmlUrl: '', publicRepos: 0, followers: 0,
      following: 0, bio: null, location: null, company: null, email: null }),
    getAgentReview: async () => review,
    getAgentTurn: async () => agentTurn('RUNNING', { taskId: review.task.id,
      createdAt: '2026-09-06T16:03:00Z', startedAt: '2026-09-06T16:03:01Z' }),
    readAgentReviewHistory: async () => ({ events: [...events], beforeCursor: null, afterCursor: null, latestSequence: events.length }),
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
      codeGraph: 'OFF' as const, sourceFingerprint: null, savedExperiment: false, files: [] }),
    listCodeRuns: async () => [],
    readWorktreeChanges: async (_host, request) => ({ scope: request.scope, selectedCommit: null, sourceFingerprint: 'worktree',
      branch: 'reviewer', baseSha: 'base', headSha: 'head', baseLabel: 'master', truncated: false,
      staged: [], unstaged: [], untracked: [], commits: [], files: [] }),
    fetchMergeMode: async () => ({ mergeQueue: false }),
    recordRecentTouch: async () => {},
  } satisfies Partial<Bridge>;
  Object.assign(window, { bridge: { ...createPreviewBridge(review.workspaceId), ...stubs } });
  const host = document.createElement('main');
  host.style.cssText = 'height:850px;width:1500px';
  document.body.append(host);
  const root = createRoot(host);
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
    flushSync(() => root.render(local
      ? <ReviewTaskScreen workspaceId={review.workspaceId} reviewId={review.id} browserOpen={false} onBack={() => {}} />
      : <PullsScreen initialPr={{ repo: review.repo, number: initial.number }} initialReviewId={review.id}
        repositoryScope={{ workspaceId: review.workspaceId, repository: review.repo }} reviewWorkspace={{ onBack: () => {} }} />));
    for (let attempt = 0; attempt < 60 && host.querySelector('.agt-agent-chip') === null; attempt++) await settle();
    const conversation = host.querySelector('.agr-workspace-conversation');
    assert(conversation !== null && tab() === null, 'Subagents must remain a conversation destination until clicked');
    assert(!conversation.textContent?.includes('Tracing the blocked writer.'), 'Child messages must stay out of the root transcript');
    const input = host.querySelector<HTMLTextAreaElement>('.agt-compose__input');
    assert(input !== null, 'The review composer must remain available');
    changeInput(input, 'Keep this unsent question'); await settle();
    const route = window.location.hash;
    const icon = chip('Abort contract', conversation).querySelector('svg');
    assert(icon !== null, 'The agent chip must contain its icon');
    icon.dispatchEvent(new MouseEvent('click', { bubbles: true })); await settle();
    assert(tab()?.textContent?.includes('Subagents 3') && tab()?.querySelector('[aria-pressed="true"]') !== null,
      'Clicking the icon must open and select the counted Subagents tab');
    assert(panel()?.hidden === false && panel()?.textContent?.includes('Tracing the blocked writer.')
      && panel()?.textContent?.includes('Check cancellation behavior.'), 'The selected agent must show its messages and prompt');
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
    click('Code & Tests'); await settle();
    const code = host.querySelector('.pull-detail__code');
    chip('Abort contract', conversation).click(); await settle();
    assert(code !== null && code === host.querySelector('.pull-detail__code') && code.hasAttribute('hidden'),
      'Opening an agent must keep the Code & Tests surface mounted');
    host.querySelector<HTMLButtonElement>('[aria-label="Close Subagents"]')?.click(); await settle();
    assert(tab() === null && !code.hasAttribute('hidden'), 'Closing Subagents must restore the covered WorkBench view');
    finished.click(); await settle();
    assert(tab() !== null && panel()?.textContent?.includes('The failure releases the writer.'), 'A finished chip must reopen a closed tab');
    assert(input.value === 'Keep this unsent question' && window.location.hash === route && subscriptions === 1 && stops === 0,
      'Inspecting agents must preserve the draft and route and reuse exactly one review subscription');
    return 'Review chips open live, completed and nested subagents in the existing inspector';
  }
  finally {
    flushSync(() => root.unmount()); host.remove(); desktop.dispose(); Object.assign(window, { bridge: previous });
  }
}
