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
import { useThreadSnapshot } from '../../../useThreadSnapshot';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import DevelopmentLearningPanel from '../../../agents/DevelopmentLearningPanel';
import { parseAgentTurn } from '../../../agents/contracts';
import agentContracts from '../../../agents/fixtures/agent-contracts-v1.json';
import { previewTask } from '../../../agents/fixtures/previewBridge';
import type { AgentTurnDto, Bridge, DevelopmentLearningSummaryDto } from '../../../types';
import { DiffViewer, PendingReviewCard } from '../../PullChanges';
import { ReviewThreadCard, ReviewThreadMessage } from '../../ReviewThreadCard';
import MarkdownBody from '../../MarkdownBody';
import ReviewSummaryCard from '../../agent-review/ReviewSummaryCard';
import ReviewFindingCard from '../../agent-review/ReviewFindingCard';
import ReviewComposer from '../../agent-review/ReviewComposer';
import '../../../css/base.css';
import '../../../css/markdown-editor.css';
import '../../../css/pulls.css';
import '../../../css/agent-review.css';
import { reviewFixture } from '../../agent-review/fixtures/review';
import type { ReviewMessageDto } from '../../agent-review/contracts';
import LocalReviewThreadCard from '../LocalReviewThreadCard';
import { findingConversation, localReviewConversation } from '../conversation';
import type { LocalReviewThreadDto, LocalReviewThreads } from '../types';
import { useLocalReviewThreads, type LocalReviewBridgePort } from '../useLocalReviewThreads';
import { assert, changeInput, settle } from '../../../fixtures/harness';

function button(host: HTMLElement, text: string) {
  const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent === text);
  assert(value !== undefined, `Missing ${text} button`);
  return value;
}
function initialThread(): LocalReviewThreadDto {
  return { id: 'local-1', taskId: 'task-1', taskVersion: 1, workspaceId: 'workspace-1', repo: 'owner/repo', number: 17,
    path: 'src/Example.java', side: 'RIGHT', startLine: null, line: 2, baseSha: '1111111', headSha: '2222222',
    code: 'new value', resolved: false, version: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    messages: [{ id: 'user-1', author: 'USER', body: 'Why is this needed?', createdAt: '2026-01-01T00:00:00Z',
      turnId: 'turn-1', queuedInputId: null, messageEventId: 'event-1', deliveryState: 'COMPLETED', deliveryError: null },
    { id: 'agent-1', author: 'AGENT', body: 'It validates the requested value.', createdAt: '2026-01-01T00:01:00Z',
      turnId: 'turn-1', queuedInputId: null, messageEventId: 'event-2', deliveryState: null, deliveryError: null }] };
}

async function replyAndResolve(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  let thread = initialThread();
  let replies = 0;
  let resolutions = 0;
  let disabledReason: string | null = null;
  const render = () => flushSync(() => root.render(<LocalReviewThreadCard thread={thread} disabledReason={disabledReason}
    onRetry={async () => { throw new Error('No failed message to retry'); }}
    onReply={async (id, body) => {
      assert(id === thread.id && body === 'Please keep the validation', 'Reply must target the saved private thread');
      if (++replies === 1) throw new Error('Could not save reply');
      thread = { ...thread, version: thread.version + 1, messages: [...thread.messages,
        { ...thread.messages[0], id: 'user-2', body }, { ...thread.messages[1], id: 'agent-2', body: 'The validation is retained.' }] };
      render();
    }}
    onResolve={async (id, resolved) => {
      assert(id === thread.id, 'Resolution must target the saved private thread');
      resolutions++;
      thread = { ...thread, version: thread.version + 1, resolved };
      render();
    }} />));
  render();
  try {
    assert(host.querySelector('.lrc.is-local') !== null && host.querySelector('.lrc__chip')?.textContent === 'Local'
      && host.querySelector('.pr-inline-thread, .pr-thread-row') === null,
    'Private threads must retain their local tone and destination on the shared frame');
    assert(host.querySelectorAll('.lrc-message').length === 2 && host.textContent?.includes('You') === true && host.textContent?.includes('Agent') === true,
      'User and agent messages must share the local card\'s message rows');
    button(host, 'Reply').click();
    await settle();
    let input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Reply to local review"]');
    assert(input !== null, 'Reply must open the normal inline editor');
    input.focus();
    assert(getComputedStyle(input).borderTopColor === 'rgb(9, 105, 218)', 'A focused reply must draw the editor’s blue border');
    const cancel = button(host, 'Cancel');
    cancel.focus();
    assert(cancel.matches(':focus-visible') && getComputedStyle(cancel).outlineStyle === 'solid' && getComputedStyle(cancel).outlineWidth === '2px',
      'A keyboard-focused footer button must show the editor’s focus ring');
    host.className = 'agr-pane';
    input.focus();
    assert(getComputedStyle(input).borderTopColor === 'rgb(9, 105, 218)' && getComputedStyle(input).outlineStyle === 'none',
      'The review pane’s focus ring must not draw over the reply editor');
    assert(host.querySelector<HTMLButtonElement>('.pull-diff-review__bar .pull-diff-review__private')?.disabled === true
      && host.textContent?.includes('Private — only you and the agent') === false, 'A private reply must show the Private label instead of a help text');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle();
    assert(host.querySelector('textarea') === null, 'Escape must close an empty reply');
    button(host, 'Reply').click();
    await settle();
    input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Reply to local review"]');
    assert(input !== null, 'Reply must reopen the inline editor');
    changeInput(input, 'Please keep the validation');
    await settle();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle();
    assert(input.isConnected && input.value === 'Please keep the validation', 'Escape must not discard a written reply');
    disabledReason = 'A run is using this working copy.';
    render();
    await settle();
    const blocked = button(host, 'Reply to agent');
    assert(blocked.disabled && blocked.title === disabledReason && !input.disabled && !button(host, 'Cancel').disabled
      && host.querySelector('.pull-diff-review [role="status"]')?.textContent === disabledReason
      && host.querySelector('.pr-local-review__note') === null,
    'A blocked reply must keep editing available and carry its reason as a title and a visible status');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true, cancelable: true }));
    await settle();
    assert(replies === 0, 'Cmd+Enter must not reply while blocked');
    disabledReason = null;
    render();
    await settle();
    button(host, 'Preview').click();
    await settle();
    button(host, 'Reply to agent').click();
    await settle();
    assert(host.querySelector('[role="alert"]')?.textContent === 'Could not save reply' && input.value === 'Please keep the validation',
      'Failed reply must retain the draft and show its error');
    button(host, 'Reply to agent').click();
    await settle();
    assert(host.querySelector('textarea') === null && host.querySelectorAll('.lrc-message').length === 4,
      'Accepted reply and agent answer must appear in the same saved thread');
    button(host, 'Resolve').click();
    await settle();
    const resolved = host.querySelector<HTMLDetailsElement>('.lrc');
    assert(resolved !== null && !resolved.open && resolved.querySelector('.lrc__resolved')?.textContent === 'Resolved', 'Resolution must fold the local card');
    resolved.querySelector('summary')?.click();
    button(host, 'Reopen').click();
    await settle();
    assert(resolutions === 2 && host.querySelector<HTMLDetailsElement>('.lrc')?.open === true && host.querySelector('.lrc__resolved') === null,
      'The user must be able to reopen a local thread');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    assert(host.querySelectorAll('.lrc-message').length === 4, 'Remounting from saved data must retain both sides of the discussion');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function savedRetry(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let thread = initialThread();
  thread = { ...thread, messages: [{ ...thread.messages[0], deliveryState: 'UNSENT', deliveryError: 'The agent is unavailable', turnId: null }] };
  let retries = 0;
  let finish: () => void = () => {};
  const render = () => flushSync(() => root.render(<LocalReviewThreadCard thread={thread}
    onReply={async () => { throw new Error('Retry must not create a new comment'); }} onResolve={async () => {}}
    onRetry={async (threadId, messageId) => {
      assert(threadId === thread.id && messageId === 'user-1', 'Retry must retain the original saved message identity');
      retries++;
      await new Promise<void>(resolve => { finish = resolve; });
      thread = { ...thread, messages: [{ ...thread.messages[0], deliveryState: 'QUEUED', deliveryError: null, queuedInputId: 'queue-1' }] };
      render();
    }} />));
  render();
  try {
    assert(host.textContent?.includes('Not sent') === true && host.querySelector('[role="alert"]')?.textContent === 'The agent is unavailable',
      'A saved unsent message must expose its delivery failure');
    const retry = button(host, 'Retry');
    retry.click(); retry.click();
    await settle();
    assert(retries === 1 && button(host, 'Retrying…').disabled, 'A retry in flight must not dispatch twice');
    finish();
    await settle();
    assert(host.querySelectorAll('.lrc-message').length === 1 && host.textContent?.includes('queued') === true
      && host.querySelector('[role="alert"]') === null, 'Accepted retry must update delivery state without duplicating user text');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function pendingThreadResponse(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let thread = initialThread();
  thread = { ...thread, messages: [...thread.messages, { ...thread.messages[0], id: 'follow-up', deliveryState: 'ACCEPTED' }] };
  const unrelated = { ...initialThread(), id: 'other-thread' };
  let replies = 0;
  const actions = { onReply: async () => { replies++; }, onResolve: async () => {}, onRetry: async () => {} };
  const render = () => flushSync(() => root.render(<>
    <LocalReviewThreadCard thread={thread} {...actions} />
    <LocalReviewThreadCard thread={unrelated} {...actions} />
  </>));
  const setDelivery = (deliveryState: string) => {
    thread = { ...thread, messages: thread.messages.map(message => message.id === 'follow-up' ? { ...message, deliveryState } : message) };
    render();
  };
  render();
  try {
    const card = host.querySelector<HTMLElement>('[data-local-review-thread="local-1"]');
    const other = host.querySelector<HTMLElement>('[data-local-review-thread="other-thread"]');
    assert(card !== null && other !== null, 'Both discussions must render');
    for (const state of ['ACCEPTED', 'RUNNING', 'QUEUED', 'STARTING']) {
      setDelivery(state);
      const reason = state === 'QUEUED' ? 'Your comment is queued. You can reply when the agent finishes.'
        : 'The agent is working on a response. You can reply when it finishes.';
      const reply = button(card, 'Reply');
      assert(reply.disabled && reply.title === reason && card.querySelector('[role="status"]')?.textContent === reason,
        `${state} feedback must explain why another reply is unavailable`);
      reply.click(); await settle();
      assert(card.querySelector('textarea') === null && !button(other, 'Reply').disabled && !button(card, 'Resolve').disabled,
        'Only the waiting discussion must block new replies; resolution remains available');
    }
    setDelivery('COMPLETED');
    button(card, 'Reply').click(); await settle();
    const input = card.querySelector('textarea');
    assert(input !== null, 'Completion must make replies available again');
    changeInput(input, 'Keep my follow-up draft'); await settle();
    setDelivery('RUNNING');
    assert(input.disabled && input.value === 'Keep my follow-up draft' && button(card, 'Reply to agent').disabled
      && button(card, 'Better words').disabled && !button(card, 'Cancel').disabled
      && card.querySelector('[role="status"]')?.textContent?.includes('working on a response') === true,
    'A discussion becoming active must disable an already-open editor without discarding its draft');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true, cancelable: true }));
    button(card, 'Reply to agent').click(); await settle();
    assert(replies === 0, 'Neither keyboard nor button submission may send while the agent is answering');
    for (const state of ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'UNSENT', 'PAUSED', 'REMOVED', 'ABANDONED']) {
      setDelivery(state);
      assert(!input.disabled && input.value === 'Keep my follow-up draft' && !button(card, 'Reply to agent').disabled
        && !card.querySelector('[role="status"]')?.textContent,
      `${state} feedback must release the editor and retain its draft`);
    }
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function anchoredDiff(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const thread = initialThread();
  const localReview: LocalReviewThreads = { threads: [thread], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} };
  const render = (head = '2222222', replacement = 'new value', path = thread.path) => flushSync(() => root.render(<DiffViewer
    files={[{ filename: path, status: 'modified', additions: 1, deletions: 1, patch: `@@ -1,2 +1,2 @@\n context\n-old value\n+${replacement}` }]}
    commits={[{ sha: head, parentSha: '1111111', message: 'Change', author: 'alice', authoredAt: '2026-01-01T00:00:00Z' }]}
    commitRange={{ start: 0, end: 0 }} localReview={localReview} />));
  render();
  try {
    const inline = host.querySelector('[data-local-review-thread="local-1"]');
    assert(inline?.closest('tr')?.classList.contains('is-local-review-thread') === true
      && inline.querySelector('.pr-local-review__snapshot') === null, 'Exact selected revision and code must place one normal local card under the diff line');
    render('3333333');
    await settle();
    let saved = host.querySelector('[data-local-review-thread="local-1"]');
    assert(saved !== null && saved.closest('tr') === null && saved.textContent?.includes('Outdated') === true
      && saved.querySelector('.pr-local-review__snapshot')?.textContent?.includes('new value') === true,
    'A new revision must retain the recorded snapshot outside current code lines');
    render('2222222', 'unrelated value');
    await settle();
    saved = host.querySelector('[data-local-review-thread="local-1"]');
    assert(saved !== null && saved.closest('tr') === null, 'Even matching refs must not attach saved feedback to different source text');
    render('2222222', 'new value', 'src/Other.java');
    await settle();
    assert(host.querySelectorAll('[data-local-review-thread="local-1"]').length === 1
      && host.querySelector('[data-local-review-thread="local-1"]')?.textContent?.includes(thread.path) === true,
    'A file absent from this diff must still retain its local review card');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function independentLearningCards(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  let review = reviewFixture({ messages: [] });
  const run = review.runs[0];
  const purposeId = `${run.id}:purpose`;
  const commitId = `${run.id}:commit:${run.commits[0].sha}`;
  const requests: string[] = [];
  const render = () => flushSync(() => root.render(<ReviewSummaryCard review={review} run={run} findings={review.findings}
    viewerLogin="reader" viewerAvatarUrl={null} busy={false} onSelectFinding={() => {}}
    onAsk={async (body, revision, cardId) => {
      assert(revision === run.summary?.revision, 'The question must retain the summary revision');
      requests.push(cardId);
      const message: ReviewMessageDto = { id: `question-${requests.length}`, findingId: null, runId: run.id, cardId,
        author: 'USER' as const, body, targetRevision: revision, turnId: 'turn-question', queuedInputId: null, createdAt: run.createdAt };
      review = { ...review, messages: [...review.messages, message,
        { ...message, id: `answer-${requests.length}`, author: 'AGENT', body: `Answer to ${body}` }] };
      render();
    }} />));
  const section = (id: string) => {
    const card = host.querySelector<HTMLElement>(`[data-review-card="${id}"]`);
    assert(card !== null, `Missing independent learning card ${id}`);
    return card;
  };
  const ask = async (id: string, question: string) => {
    button(section(id), 'Ask reviewer').click();
    await settle();
    const input = section(id).querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Each card must open its own question composer');
    changeInput(input, question);
    await settle();
    button(section(id), 'Ask reviewer').click();
    await settle();
  };
  render();
  try {
    assert(section(purposeId).querySelector('.lrc.is-purpose') !== null,
      'Learning cards must reuse the development private review card');
    await ask(purposeId, 'Explain the PR goal');
    await ask(commitId, 'Explain this implementation');
    assert(requests.length === 2 && requests[0] === purposeId && requests[1] === commitId,
      'Questions on independent cards must send different saved targets');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    const purpose = section(purposeId);
    const commit = section(commitId);
    assert(purpose.textContent?.includes('Answer to Explain the PR goal') === true
      && purpose.textContent?.includes('Explain this implementation') === false,
    'The overview must retain only its own user question and agent answer after reload');
    assert(commit.textContent?.includes('Answer to Explain this implementation') === true
      && commit.textContent?.includes('Explain the PR goal') === false,
    'The commit must retain only its own user question and agent answer after reload');
    assert(purpose.querySelectorAll('.lrc-message').length === 3 && commit.querySelectorAll('.lrc-message').length === 3,
      'Each card must display its report, question and answer using shared comment rows');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function reviewOwnedLocalDiscussion(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  let saved = { ...initialThread(), taskId: 'review-owned-task' };
  let replies = 0;
  const bridge: LocalReviewBridgePort = {
    listLocalReviewComments: async () => [saved],
    createLocalReviewComment: async () => { throw new Error('This discussion already exists'); },
    resolveLocalReviewComment: async () => { throw new Error('No resolution requested'); },
    retryLocalReviewComment: async () => { throw new Error('No retry requested'); },
    replyLocalReviewComment: async request => {
      assert(request.taskId === 'review-owned-task' && request.expectedTaskVersion === 9,
        'The reply must use the review-owned task and its latest version from the discussion endpoint');
      replies++;
      saved = { ...saved, version: saved.version + 1, taskVersion: 10,
        messages: [...saved.messages, { ...saved.messages[0], id: request.messageId, body: request.body }] };
      return saved;
    },
  };
  function ReviewHookHarness() {
    const localReview = useLocalReviewThreads({ repo: saved.repo, number: saved.number, workspaceId: saved.workspaceId }, { bridge });
    return <>{localReview.threads.map(thread => <LocalReviewThreadCard key={thread.id} thread={thread} {...localReview} />)}</>;
  }
  flushSync(() => root.render(<ReviewHookHarness />));
  try {
    await settle();
    saved = { ...saved, taskVersion: 9 };
    button(host, 'Reply').click();
    await settle();
    const input = host.querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Review-owned discussions must allow private replies');
    changeInput(input, 'Explain this code without changing it');
    await settle();
    button(host, 'Reply to agent').click();
    await settle();
    assert(replies === 1 && host.querySelector('[role="alert"]') === null && host.textContent?.includes('Explain this code without changing it') === true,
      'Review-owned tasks must accept local replies without the interactive task endpoint');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function developmentLearningRefresh(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  let root = createRoot(host);
  const task = { ...previewTask('workspace-learning', 'task-learning'), version: 3 };
  const summary: DevelopmentLearningSummaryDto = {
    purpose: 'Preserve statistics when a transaction rolls back.',
    commits: [
      { sha: '1'.repeat(40), title: 'Keep the previous statistics', summary: 'Capture a snapshot before the first write.' },
      { sha: '2'.repeat(40), title: 'Restore the saved snapshot', summary: 'Restore the snapshot using one overwrite.' },
    ],
    combinedResult: 'A partial write can be undone without losing prior statistics.',
    evidence: 'Ran the rollback unit tests. Both snapshot and partial-write cases passed.',
    limitations: ['A live metastore was unavailable.'],
  };
  const firstTurn: AgentTurnDto = { ...parseAgentTurn(agentContracts.turn), id: 'refinement-1', taskId: task.id, state: 'COMPLETED',
    developmentOrigin: { kind: 'REFINE_SUMMARY', parentUserTurnId: 'implementation-1' },
    developmentResult: { readyForDelivery: true, message: null, learningSummary: summary } };
  let savedTurn: AgentTurnDto | null = null;
  let savedThreads: LocalReviewThreadDto[] = [];
  let refreshKey = 0;
  let learningDisabled: string | null = null;
  let reads = 0;
  let questions = 0;
  const commitId = `commit:${summary.commits[0].sha}`;
  const bridge: LocalReviewBridgePort & Pick<Bridge, 'getAgentTask' | 'getDevelopmentLearning'> = {
    getAgentTask: async (taskId, workspaceId) => {
      assert(taskId === task.id && workspaceId === task.workspaceId, 'A question must refresh its owning development task');
      return { ...task, version: 12 };
    },
    getDevelopmentLearning: async (taskId, workspaceId) => {
      assert(taskId === task.id && workspaceId === task.workspaceId, 'Learning must load the report for this development task');
      reads++;
      return savedTurn;
    },
    listLocalReviewComments: async query => {
      assert('taskId' in query && query.taskId === task.id && query.workspaceId === task.workspaceId, 'Learning discussions must remain task-scoped');
      return savedThreads;
    },
    createDevelopmentLearningComment: async request => {
      assert(request.taskId === task.id && request.workspaceId === task.workspaceId && request.summaryTurnId === firstTurn.id
        && request.cardId === commitId && request.expectedTaskVersion === 12,
      'A learning question must retain its exact source turn and commit while using the latest task version');
      questions++;
      const old = initialThread();
      const thread: LocalReviewThreadDto = { ...old, id: request.id, taskId: task.id, workspaceId: task.workspaceId,
        repo: 'owner/repo', number: 0, path: '', line: 0, startLine: 0, code: '', headSha: null, baseSha: null,
        learningAnchor: { turnId: firstTurn.id, cardId: commitId, title: `${summary.commits[0].sha.slice(0, 7)} · ${summary.commits[0].title}`,
          body: summary.commits[0].summary },
        messages: [{ ...old.messages[0], id: request.messageId, body: request.body },
          { ...old.messages[1], body: 'The snapshot is captured before mutation so rollback restores the original values.' }] };
      savedThreads = [thread];
      return thread;
    },
    createLocalReviewComment: async () => { throw new Error('Learning questions must not create diff comments'); },
    replyLocalReviewComment: async () => { throw new Error('No follow-up reply requested'); },
    resolveLocalReviewComment: async () => { throw new Error('No resolution requested'); },
    retryLocalReviewComment: async () => { throw new Error('No retry requested'); },
  };
  function LearningHarness() {
    const localReview = useLocalReviewThreads({ taskId: task.id, workspaceId: task.workspaceId }, { bridge });
    return <DevelopmentLearningPanel task={task} repository="owner/repo" refreshKey={refreshKey} refining={savedTurn === null}
      disabledReason={learningDisabled} localReview={localReview} bridge={bridge} />;
  }
  const render = () => flushSync(() => root.render(<LearningHarness />));
  const card = (id: string) => {
    const found = host.querySelector<HTMLElement>(`[data-learning-card="${id}"]`);
    assert(found !== null, `Missing development learning card ${id}`);
    return found;
  };
  render();
  try {
    await settle();
    assert(host.textContent?.includes('Refining summary') === true && !host.textContent?.includes('Start review'),
      'Development learning must refine automatically without starting a separate review');
    savedTurn = firstTurn;
    refreshKey++;
    render();
    await settle();
    assert(card('purpose').textContent?.includes(summary.purpose) === true
      && card(commitId).textContent?.includes(summary.commits[0].summary) === true
      && card(`commit:${summary.commits[1].sha}`).textContent?.includes(summary.commits[1].summary) === true,
    'The saved report must refresh into an intention card and separate commit implementation cards');
    assert(host.querySelectorAll('[data-learning-card]').length === 3 && !host.textContent?.includes('Start review')
      && host.querySelector('[data-learning-card="evidence"], [data-learning-card="combined-result"], [data-learning-card="limitations"]') === null,
      'The report must contain only its intention and two implementation cards, without a separate review launcher');
    const visible = () => [...host.querySelectorAll<HTMLElement>('[data-learning-card]')].filter(item => item.closest('[hidden]') === null)
      .map(item => item.dataset.learningCard).join();
    const outline = [...host.querySelectorAll<HTMLButtonElement>('.agr-summary-nav__item')];
    assert(outline.map(item => item.title).join() === ['Intention', ...summary.commits.map(commit => commit.title)].join()
      && visible() === 'purpose' && host.querySelector('.agr-event') === null,
    'The outline must list the intention and each commit, open on the intention, and draw no timeline rows above the cards');
    outline[1].click();
    await settle();
    assert(visible() === commitId && outline[1].getAttribute('aria-current') === 'true',
      'Picking a commit in the outline must show only that commit and its card');
    button(card(commitId), 'Ask agent').click();
    await settle();
    const blockedInput = card(commitId).querySelector<HTMLTextAreaElement>('textarea');
    assert(blockedInput !== null, 'Each implementation card must open a question editor');
    learningDisabled = 'The agent is reconnecting.';
    render();
    await settle();
    const blockedAsk = button(card(commitId), 'Ask agent');
    assert(!blockedInput.disabled && !button(card(commitId), 'Cancel').disabled && blockedAsk.disabled && blockedAsk.title === learningDisabled
      && card(commitId).querySelector('[role="status"]')?.textContent === learningDisabled,
    'A blocked question must keep editing and Cancel available and carry its reason as a title and a visible status');
    blockedInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await settle();
    assert(card(commitId).querySelector('textarea') === null, 'Escape must still close a blocked question');
    learningDisabled = null;
    render();
    await settle();
    button(card(commitId), 'Ask agent').click();
    await settle();
    const input = card(commitId).querySelector<HTMLTextAreaElement>('textarea');
    assert(input !== null, 'Each implementation card must allow a scoped question');
    changeInput(input, 'Why capture the snapshot before writing?');
    await settle();
    button(card(commitId), 'Ask agent').click();
    await settle();
    const discussion = host.querySelector<HTMLElement>('[data-local-review-thread]');
    assert(questions === 1 && discussion?.querySelectorAll('.lrc-message').length === 3
      && discussion.textContent?.includes(summary.commits[0].summary) === true
      && discussion.textContent?.includes('rollback restores the original values') === true,
    'The accepted question must display its saved summary, user message and agent reply in the shared private card');
    assert(card('purpose').textContent?.includes('Why capture the snapshot') === false
      && card(`commit:${summary.commits[1].sha}`).textContent?.includes('rollback restores the original values') === false,
    'Commit questions and answers must remain separate from the intention and other commit cards');
    savedTurn = { ...firstTurn, id: 'refinement-2', developmentResult: { readyForDelivery: true, message: null,
      learningSummary: { ...summary, purpose: 'Preserve statistics and reject conflicting updates.',
        commits: [{ ...summary.commits[0], summary: 'Capture the snapshot and check the update version.' }, summary.commits[1]] } } };
    refreshKey++;
    render();
    await settle();
    assert(card('purpose').textContent?.includes('reject conflicting updates') === true
      && card(commitId).textContent?.includes('check the update version') === true
      && card(commitId).textContent?.includes('Why capture the snapshot') === false,
    'A later refinement must show its new report without inheriting an older card discussion');
    const earlier = host.querySelector<HTMLDetailsElement>('.agr-learning-history');
    assert(earlier !== null && earlier.querySelectorAll('[data-local-review-thread]').length === 1
      && earlier.textContent?.includes(summary.commits[0].summary) === true,
    'Questions on an older report must remain available with the original summary under earlier discussions');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    await settle();
    assert(reads >= 4 && card('purpose').textContent?.includes('reject conflicting updates') === true
      && host.querySelector('.agr-learning-history')?.textContent?.includes('rollback restores the original values') === true,
    'Reload must restore the kept report and earlier discussions without loading conversation history');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function sharedReviewEditor(): Promise<void> {
  for (const width of [760, 320, 240]) {
    const host = document.createElement('div');
    host.style.width = `${width}px`;
    document.body.append(host);
    const root = createRoot(host);
    const submissions: { outgoing: boolean; body: string }[] = [];
    let cancellations = 0;
    const Harness = ({ busy = false, outgoing = false }: { busy?: boolean; outgoing?: boolean }) => {
      const [value, setValue] = useState('');
      return <>
        <div className="reference-editor"><DiffViewer
          files={[{ filename: 'Example.java', status: 'modified', additions: 1, deletions: 1, patch: '@@ -1 +1 @@\n-old value\n+new value' }]}
          onAskAgent={async () => { throw new Error('Reference editor must not submit'); }} /></div>
        <div className="agr-finding"><ReviewComposer repository="owner/repo" value={value} onChange={setValue} busy={busy}
          placeholder="Ask about this implementation…" ariaLabel="Review question" primaryLabel={outgoing ? 'Post now' : 'Ask agent'}
          outgoing={outgoing ? 'Posts to GitHub on Example.java R12–R13' : null}
          suggestion={{ startLine: 12, lines: ['old first', 'old second'] }}
          onSubmit={() => { submissions.push({ outgoing, body: value }); }} onCancel={() => { cancellations++; }} /></div>
      </>;
    };
    const render = (busy = false, outgoing = false) => flushSync(() => root.render(<Harness busy={busy} outgoing={outgoing} />));
    render();
    try {
      host.querySelector<HTMLButtonElement>('.reference-editor [aria-label="Add a comment on new line 1"]')?.click();
      await settle();
      const editor = host.querySelector<HTMLElement>('.agr-composer');
      const input = editor?.querySelector('textarea');
      const referenceInput = host.querySelector<HTMLTextAreaElement>('.reference-editor .pull-diff-review textarea');
      assert(editor !== null && input !== null && input !== undefined && referenceInput !== null, 'Both review surfaces must mount their editor');
      const properties = ['borderTopColor', 'borderTopWidth', 'borderRadius', 'padding', 'fontSize', 'backgroundColor', 'boxShadow', 'outlineStyle'] as const;
      referenceInput.focus();
      const referenceStyle = getComputedStyle(referenceInput);
      const expectedStyle = properties.map(property => referenceStyle[property]);
      input.focus();
      const reviewStyle = getComputedStyle(input);
      assert(properties.every((property, index) => reviewStyle[property] === expectedStyle[index]),
        'The review textarea must use the code-diff editor’s actual focused appearance');
      assert(editor.scrollWidth <= editor.clientWidth + 1, 'The editor and wrapped toolbar must fit a narrow card');
      assert(editor.querySelector<HTMLButtonElement>('.pull-diff-review__bar .pull-diff-review__private')?.disabled === true
        && editor.textContent?.includes('Private — only you and the agent') === false, 'Private questions must show a disabled Private label in the editor bar');
      const previewTab = button(editor, 'Preview');
      previewTab.scrollIntoView({ block: 'center' });
      const previewBounds = previewTab.getBoundingClientRect();
      assert([previewBounds.left + 2, previewBounds.left + previewBounds.width / 2, previewBounds.right - 2]
        .every(x => document.elementFromPoint(x, previewBounds.top + previewBounds.height / 2) === previewTab),
      `The private label must not cover the Preview tab at ${width}px`);
      const privateBounds = button(editor, 'Private').getBoundingClientRect();
      const editorBounds = editor.getBoundingClientRect();
      assert(width === 240 || Math.abs(privateBounds.left + privateBounds.width / 2 - editorBounds.left - editorBounds.width / 2) < 1
        && previewBounds.right <= privateBounds.left,
      'The private label must stay centered without overlapping the tabs');
      changeInput(input, 'Review this snapshot');
      input.setSelectionRange(12, 20);
      await settle();
      editor.querySelector<HTMLButtonElement>('[aria-label="Bold"]')?.click();
      await settle();
      assert(input.value === 'Review this **snapshot**', 'The shared toolbar must format the selected text');
      button(editor, 'Preview').click(); await settle();
      assert(input.hidden && editor.querySelector('.markdown-body strong')?.textContent === 'snapshot', 'Preview must render Markdown while retaining the editor');
      button(editor, 'Write').click(); await settle();
      assert(!input.hidden && document.activeElement === input && input.value === 'Review this **snapshot**', 'Write must restore the same focused text input');
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true }));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      assert(submissions.length === 1 && !submissions[0].outgoing && cancellations === 1, 'Keyboard submit and cancel must retain their private callbacks');
      render(true); await settle();
      assert(input.disabled && button(editor, 'Cancel').disabled && button(editor, 'Ask agent').disabled
        && editor.querySelector<HTMLButtonElement>('[aria-label="Bold"]')?.disabled === true,
      'A busy request must disable editing and duplicate actions like the diff editor');
      render(false, true); await settle();
      const suggestion = '```suggestion\nnew value\n```';
      changeInput(input, suggestion); await settle();
      button(editor, 'Preview').click(); await settle();
      const preview = editor.querySelector('.pull-diff-review__preview');
      assert(preview?.querySelectorAll('.md-suggestion__row.is-del').length === 2
        && preview.querySelector('.md-suggestion__number')?.textContent === '12', 'Outgoing preview must preserve the exact two-line suggestion base');
      assert(editor.textContent?.includes('Posts to GitHub on Example.java R12–R13') === true && submissions.length === 1,
        'Opening an outgoing preview must retain its target without publishing');
      const notice = editor.querySelector<HTMLElement>('.agr-composer__outgoing');
      const actions = editor.querySelector('footer');
      assert(notice !== null && actions !== null && editor.lastElementChild === notice
        && notice.getBoundingClientRect().top >= actions.getBoundingClientRect().bottom,
      'The outgoing notice must be at the bottom below the buttons at every card width');
      button(editor, 'Post now').click();
      assert(Number(submissions.length) === 2 && submissions[1].outgoing && submissions[1].body === suggestion,
        'Only an explicit outgoing action may pass the original suggestion fence to its callback');
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
}

async function sharedCardLayout(): Promise<void> {
  const host = document.createElement('div');
  host.className = 'pull-diff-threads';
  document.body.append(host);
  const root = createRoot(host);
  const thread = initialThread();
  const noAction = async () => {};
  try {
    flushSync(() => root.render(<>
      <ReviewThreadCard title={<span className="lrc__title">{thread.path}:R2</span>} resolved={false} controls={null}>
        <ReviewThreadMessage timestamp={thread.createdAt} author={<strong>You</strong>}>
          <MarkdownBody body={thread.messages[0].body} />
        </ReviewThreadMessage>
      </ReviewThreadCard>
      <LocalReviewThreadCard thread={thread} showCode={false} onReply={noAction} onResolve={noAction} onRetry={noAction} />
      <PendingReviewCard comment={{ path: thread.path, side: 'RIGHT', line: 2, startLine: null, startSide: null, commitId: null, body: thread.messages[0].body }}
        repository={thread.repo} editBody={null} onEdit={() => {}} onSave={() => {}} onDiscard={() => {}} />
    </>));
    for (const width of [900, 360]) {
      host.style.width = `${width}px`;
      await settle();
      const cards = [...host.querySelectorAll<HTMLDetailsElement>(':scope > details')];
      assert(cards.length === 3, 'GitHub, local and pending comments must all render the shared disclosure');
      const styles = cards.map(card => [card, card.querySelector('.lrc__head'), card.querySelector('.lrc-message'), card.querySelector('.lrc-message__head')]
        .map(element => {
          assert(element !== null, 'Every review comment must use the shared header and message layout');
          const style = getComputedStyle(element);
          return [style.borderRadius, style.padding, style.fontFamily, style.fontSize, style.lineHeight].join('|');
        }).join(';'));
      assert(new Set(styles).size === 1, 'All review comment variants must have the same base spacing and typography');
      for (const card of cards) {
        assert(card.scrollWidth <= card.clientWidth + 1, 'Review cards must fit narrow panes');
        card.querySelector('summary')?.click(); await settle();
        assert(!card.open, 'Every review card must fold with its shared header');
        card.querySelector('summary')?.click(); await settle();
        assert(card.open, 'Every review card must reopen without losing its content');
      }
      assert(cards[0].querySelector('.lrc__chip') === null && cards[1].querySelector('.lrc__chip')?.textContent === 'Local'
        && cards[2].querySelector('.pull-diff-pending__badge')?.textContent === 'Pending', 'Destination add-ons must stay distinct');
    }
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function pendingReviewerQuestion(): Promise<void> {
  const draft = Array.from({ length: 100 }, (_, index) => `Question ${index + 1}: please explain the previous and current partition statistics.`).join('\n');
  for (const kind of ['summary', 'finding']) {
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const requests: Array<{ resolve: () => void; reject: (error: Error) => void; publish: () => void }> = [];
    const Surface = () => {
      const [review, setReview] = useState(() => reviewFixture({ messages: [] }));
      const ask = (body: string) => new Promise<void>((resolve, reject) => {
        assert(body === draft, 'Sending must retain the entire large question');
        requests.push({ resolve, reject, publish: () => setReview(current => ({ ...current, messages: [{
          ...reviewFixture().messages[0], id: 'new-question', author: 'USER', body,
          findingId: kind === 'finding' ? review.findings[0].id : null,
          cardId: kind === 'summary' ? `${review.runs[0].id}:purpose` : null,
          runId: review.runs[0].id,
        }] })) });
      });
      return kind === 'summary'
        ? <ReviewSummaryCard review={review} run={review.runs[0]} findings={review.findings} viewerLogin="me" viewerAvatarUrl={null}
            busy={false} onAsk={ask} onSelectFinding={() => {}} />
        : <ReviewFindingCard review={review} finding={review.findings[0]} viewerLogin="me" viewerAvatarUrl={null} projection="review"
            actions={{ onAsk: (_finding, body) => ask(body), onResolve: async () => {}, onReopen: async () => {},
              onAddToReview: async () => {}, onPostNow: async () => {}, onEditDraft: async () => {}, onRemoveFromReview: async () => {} }} />;
    };
    try {
      flushSync(() => root.render(<Surface />));
      button(host, 'Ask reviewer').click(); await settle();
      const input = host.querySelector('textarea');
      assert(input !== null, 'The question editor must open');
      changeInput(input, draft); await settle();
      const submit = button(host.querySelector('.agr-composer') as HTMLElement, 'Ask reviewer');
      submit.click(); submit.click(); await settle();
      assert(requests.length === 1, 'Repeated clicks must not send a question twice');
      assert(host.querySelector('textarea') === null && host.querySelector('[role="status"]')?.textContent === 'Sending…',
        'A slow send must immediately replace the large editor with a compact status');

      requests[0].reject(new Error('Could not send the question')); await settle();
      const restored = host.querySelector('textarea');
      assert(restored?.value === draft && !restored.disabled && host.querySelector('[role="alert"]')?.textContent === 'Could not send the question',
        'Failure must restore the editable draft and show the error');
      restored.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true })); await settle();
      assert(Number(requests.length) === 2 && host.querySelector('textarea') === null, 'Keyboard retry must use the same sending state');
      requests[1].publish(); await settle();
      assert(host.querySelectorAll('.agr-thread .lrc-message').length === 1 && host.querySelector('textarea') === null,
        'A saved question arriving before the send response must not reappear in the editor');
      requests[1].resolve(); await settle();
      assert(host.querySelector('.agr-composer, [role="status"], [role="alert"]') === null,
        'Success must close the editor and remove the sending status and error');
      button(host, 'Ask reviewer').click(); await settle();
      assert(host.querySelector('textarea')?.value === '', 'The next question must start with an empty draft');
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
}

async function editFindingInPlace(): Promise<void> {
  for (const projection of ['review', 'changes'] as const) {
    let review = reviewFixture({ messages: [] });
    const finding = review.findings[0];
    review = { ...review, draft: { repo: review.repo, number: review.number, body: '', event: 'COMMENT', postedSummaryFindingIds: [], version: 1,
      updatedAt: finding.updatedAt, items: [{ id: 'outgoing-comment', source: 'AGENT_FINDING', findingId: finding.id,
        findingRevision: finding.revision, path: finding.path, side: finding.side, line: finding.endLine, startLine: finding.startLine,
        commitId: finding.validatedHeadSha, body: 'My saved comment', included: true, published: false, publication: null }] } };
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    let saves = 0;
    const render = () => flushSync(() => root.render(<ReviewFindingCard review={review} finding={finding} projection={projection}
      viewerLogin="me" viewerAvatarUrl={null} actions={{ onAsk: async () => {}, onResolve: async () => {}, onReopen: async () => {},
        onAddToReview: async () => {}, onPostNow: async () => {}, onRemoveFromReview: async () => {},
        onEditDraft: async (target, body) => {
          assert(target === finding && review.draft !== null, 'Save must retain the original finding');
          if (++saves === 1) throw new Error('Could not save the edit');
          review = { ...review, draft: { ...review.draft, items: [{ ...review.draft.items[0], body }] } };
          render();
        } }} />));
    try {
      render();
      const location = host.querySelector('.agr-draft')?.parentElement;
      assert(location !== null && location !== undefined, 'The outgoing comment must be visible');
      button(host, 'Edit').click(); await settle();
      const input = location.querySelector<HTMLTextAreaElement>('[aria-label="Outgoing comment text"]');
      assert(input?.value === 'My saved comment' && document.activeElement === input,
        'Edit must open and focus the saved text in the outgoing comment location');
      assert(host.querySelector('.agr-draft, .lrc__foot') === null && host.querySelectorAll('textarea').length === 1,
        'The editor must replace the outgoing box without adding a second editor below');
      changeInput(input, 'Discard this edit'); await settle();
      button(host, 'Cancel').click(); await settle();
      assert(location.querySelector('.agr-draft__body')?.textContent === 'My saved comment' && saves === 0 && host.querySelector('textarea') === null,
        'Cancel must restore the saved comment in the same location');
      button(host, 'Edit').click(); await settle();
      const reopened = location.querySelector('textarea');
      assert(reopened?.value === 'My saved comment', 'Reopening must discard canceled changes');
      changeInput(reopened, 'My edited comment'); await settle();
      button(host, 'Save').click(); await settle();
      assert(location.querySelector('textarea')?.value === 'My edited comment' && location.querySelector('[role="alert"]')?.textContent === 'Could not save the edit',
        'Failed saves must retain the inline editor and show its error beside it');
      button(host, 'Save').click(); await settle();
      assert(location.querySelector('.agr-draft__body')?.textContent === 'My edited comment' && host.querySelector('textarea, [role="alert"]') === null,
        'Save must restore the updated outgoing comment in place');
      assert(review.findings[0] === finding, 'Editing outgoing text must leave the agent finding unchanged');
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
}

async function copyConversations(): Promise<void> {
  const host = document.createElement('div');
  host.style.width = '420px';
  document.body.append(host);
  const root = createRoot(host);
  const previous = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  let copied = '';
  let fail = false;
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (value: string) => {
    if (fail) throw new Error('Clipboard unavailable');
    copied = value;
  } } });
  const review = reviewFixture();
  review.messages = review.messages.map(message => ({ ...message, findingId: review.findings[0].id }));
  review.findings[0] = { ...review.findings[0], state: 'RESOLVED' };
  const thread = { ...initialThread(), resolved: true };
  const noMutation = async () => { throw new Error('Copy must not send, resolve or publish'); };
  const clickCopy = () => {
    const control = host.querySelector<HTMLButtonElement>('.pr-copy-button');
    assert(control !== null, 'The card header must offer Copy conversation');
    control.click();
  };
  try {
    for (const projection of ['review', 'changes'] as const) {
      flushSync(() => root.render(<ReviewFindingCard review={review} finding={review.findings[0]} projection={projection}
        viewerLogin="me" viewerAvatarUrl={null} busy actions={{ onAsk: noMutation, onResolve: noMutation,
          onReopen: noMutation, onAddToReview: noMutation, onPostNow: noMutation, onEditDraft: noMutation, onRemoveFromReview: noMutation }} />));
      clickCopy(); await settle();
      assert(copied === findingConversation(review, review.findings[0]) && copied.includes(review.messages[1].body),
        'Both projections must copy all folded evidence and private follow-ups');
      assert(host.querySelector<HTMLDetailsElement>('details')?.open === false, 'Copy must not toggle a folded card');
    }
    flushSync(() => root.render(<LocalReviewThreadCard thread={thread} showCode={false} disabledReason="Task ended"
      onReply={noMutation} onResolve={noMutation} onRetry={noMutation} />));
    fail = true; clickCopy(); await settle();
    assert(host.querySelector('[role="alert"]')?.textContent === 'Could not copy. Try again.' && host.querySelector('.pr-copy-button [role="status"]') === null,
      'A failed clipboard write must show an error without claiming success');
    const alert = host.querySelector('[role="alert"]')?.getBoundingClientRect();
    const card = host.getBoundingClientRect();
    assert(alert !== undefined && alert.top >= card.top && alert.bottom <= card.bottom,
      'Clipboard failures must remain visible inside a folded card');
    fail = false; clickCopy(); await settle();
    assert(copied === localReviewConversation(thread, false) && copied.includes(thread.code), 'Copy must include code even when this view hides it');
    assert(host.querySelector('.pr-copy-button [role="status"]')?.textContent === 'Copied' && host.querySelector('[role="alert"]') === null,
      'A successful retry must confirm copying and clear the error');
    assert(host.querySelector<HTMLDetailsElement>('details')?.open === false && host.scrollWidth <= host.clientWidth + 1,
      'A resolved card must stay folded and fit the narrow pane');
  }
  finally {
    flushSync(() => root.unmount()); host.remove();
    if (previous) Object.defineProperty(navigator, 'clipboard', previous);
    else Reflect.deleteProperty(navigator, 'clipboard');
  }
}

async function threadSnapshotRaces(): Promise<void> {
  type Row = { id: string; version: number; createdAt: string };
  let current: ReturnType<typeof useThreadSnapshot<{ taskId: string }, Row>> | undefined;
  const requests: Array<{ resolve: (rows: Row[]) => void; reject: (error: Error) => void }> = [];
  const requestCount = () => requests.length;
  function Harness({ taskId, active }: { taskId: string; active: boolean }): null {
    current = useThreadSnapshot({ taskId }, active, () => new Promise<Row[]>((resolve, reject) => requests.push({ resolve, reject })));
    return null;
  }
  const read = () => { assert(current !== undefined, 'The hook must mount'); return current; };
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const row = (version: number): Row => ({ id: 'thread', version, createdAt: '2026-01-01' });
  try {
    flushSync(() => root.render(<Harness taskId="a" active={false} />));
    assert(requestCount() === 0, 'An inactive surface must not poll');
    const stale = read().refresh();
    read().accept(row(2), read().latest.current.key);
    requests[0].resolve([row(1)]);
    await stale; await settle();
    assert(read().items[0]?.version === 2, 'A stale poll must not replace an accepted write');
    read().accept(row(1), read().latest.current.key);
    await settle();
    assert(read().items[0]?.version === 2, 'An older write response must not replace a newer version');

    const oldScope = read().latest.current.key;
    const failed = read().refresh();
    flushSync(() => root.render(<Harness taskId="b" active={false} />));
    requests[1].reject(new Error('Old task failed'));
    await failed;
    read().accept(row(3), oldScope);
    await settle();
    assert(read().items.length === 0 && read().error === null, 'Another task must receive neither old rows nor old failures');

    flushSync(() => root.render(<Harness taskId="b" active />));
    flushSync(() => root.render(<Harness taskId="b" active />));
    assert(requestCount() === 3, 'Enabling starts one poll; equal query values must not restart it');
    requests[2].resolve([row(4)]);
    await settle();
    assert(read().items[0]?.version === 4, 'The new task must accept its own snapshot');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    ...originalBridge, getUserProfile: async () => { throw new Error('Profile unavailable'); },
  } });
  const cases: Array<[string, () => Promise<void>]> = [
    ['Thread snapshots reject stale reads and writes across task changes', threadSnapshotRaces],
    ['GitHub, local and pending comments share card layout and folding', sharedCardLayout],
    ['Local discussion reply, resolution and remount', replyAndResolve],
    ['Saved unsent message retry', savedRetry],
    ['Pending responses disable only their own discussion and preserve drafts', pendingThreadResponse],
    ['Private diff anchors preserve revision and code', anchoredDiff],
    ['Independent overview and commit discussions survive reload', independentLearningCards],
    ['Review-owned private replies use the fresh task version', reviewOwnedLocalDiscussion],
    ['Development learning refreshes and retains source-bound discussions', developmentLearningRefresh],
    ['Review editor reuses diff presentation and preserves private and outgoing behavior', sharedReviewEditor],
    ['Slow reviewer questions clear the editor immediately and restore failed drafts', pendingReviewerQuestion],
    ['Outgoing finding comments edit in place in Summary and Changes', editFindingInPlace],
    ['Copy retains full private conversations and reports clipboard failures', copyConversations],
  ];
  const results: { name: string; error?: string }[] = [];
  try {
    for (const [name, scenario] of cases) {
      try { await scenario(); results.push({ name }); }
      catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    }
  }
  finally { Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
  return results;
}
