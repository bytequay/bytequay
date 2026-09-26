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
import DevelopmentLearningPanel from '../../../agents/DevelopmentLearningPanel';
import { parseAgentTurn } from '../../../agents/contracts';
import agentContracts from '../../../agents/fixtures/agent-contracts-v1.json';
import { previewTask } from '../../../agents/fixtures/previewBridge';
import type { AgentTurnDto, Bridge, DevelopmentLearningSummaryDto } from '../../../types';
import { DiffViewer } from '../../PullChanges';
import ReviewSummaryCard from '../../agent-review/ReviewSummaryCard';
import ReviewComposer from '../../agent-review/ReviewComposer';
import '../../../css/base.css';
import '../../../css/markdown-editor.css';
import '../../../css/pulls.css';
import '../../../css/agent-review.css';
import { reviewFixture } from '../../agent-review/fixtures/review';
import type { ReviewMessageDto } from '../../agent-review/contracts';
import LocalReviewThreadCard from '../LocalReviewThreadCard';
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
    assert(host.querySelector('.pr-inline-thread.is-local-review') !== null && host.textContent?.includes('Local review') === true,
      'Private threads must use the normal card with a distinct local treatment');
    assert(host.querySelectorAll('.pr-thread-comment').length === 2 && host.textContent?.includes('You') === true && host.textContent?.includes('Agent') === true,
      'User and agent messages must share normal comment rows');
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
    assert(host.querySelector('textarea') === null && host.querySelectorAll('.pr-thread-comment').length === 4,
      'Accepted reply and agent answer must appear in the same saved thread');
    button(host, 'Resolve').click();
    await settle();
    const resolved = host.querySelector<HTMLDetailsElement>('.pr-thread-row.is-local-review');
    assert(resolved !== null && !resolved.open && resolved.textContent?.includes('Resolved') === true, 'Resolution must use the normal collapsed card');
    resolved.querySelector('summary')?.click();
    button(host, 'Reopen').click();
    await settle();
    assert(resolutions === 2 && host.querySelector<HTMLDetailsElement>('.pr-inline-thread')?.open === true, 'The user must be able to reopen a local thread');
    flushSync(() => root.unmount());
    root = createRoot(host);
    render();
    assert(host.querySelectorAll('.pr-thread-comment').length === 4, 'Remounting from saved data must retain both sides of the discussion');
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
    assert(host.querySelectorAll('.pr-thread-comment').length === 1 && host.textContent?.includes('queued') === true
      && host.querySelector('[role="alert"]') === null, 'Accepted retry must update delivery state without duplicating user text');
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
    assert(section(purposeId).querySelector('.pr-inline-thread.is-local-review') !== null,
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
    assert(purpose.querySelectorAll('.pr-thread-comment').length === 3 && commit.querySelectorAll('.pr-thread-comment').length === 3,
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
    assert(questions === 1 && discussion?.querySelectorAll('.pr-thread-comment').length === 3
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
      button(editor, 'Post now').click();
      assert(Number(submissions.length) === 2 && submissions[1].outgoing && submissions[1].body === suggestion,
        'Only an explicit outgoing action may pass the original suggestion fence to its callback');
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Local discussion reply, resolution and remount', replyAndResolve],
    ['Saved unsent message retry', savedRetry],
    ['Private diff anchors preserve revision and code', anchoredDiff],
    ['Independent overview and commit discussions survive reload', independentLearningCards],
    ['Review-owned private replies use the fresh task version', reviewOwnedLocalDiscussion],
    ['Development learning refreshes and retains source-bound discussions', developmentLearningRefresh],
    ['Review editor reuses diff presentation and preserves private and outgoing behavior', sharedReviewEditor],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of cases) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
