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
import type { DiffCommitDto, DiffFileDto, PullRequestDto } from '../../types';
import { DiffViewer, type DiffAgentComment, type DiffViewerProps } from '../PullChanges';
import { runTaskInlineCommentScenarios } from './taskInlineCommentBrowser';
import { centre, realClick, runCodeCommentRangeScenarios } from '../code-tests/fixtures/codeCommentRangeBrowser';
import { resolveFinding } from '../agent-review/fixtures/resolveFindingBrowser';
import { assert, changeInput, settle } from '../../fixtures/harness';

function button(host: HTMLElement, text: string) {
  const result = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === text);
  assert(result !== undefined, `Missing ${text} button`);
  return result;
}
async function selectLine(host: HTMLElement, side: 'old' | 'new', line: number, shiftKey = false) {
  const target = host.querySelector<HTMLButtonElement>(`[aria-label="Select ${side} line ${line}"]`);
  assert(target !== null, `Missing ${side} line ${line} gutter`);
  target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey, bubbles: true }));
  await settle();
}
async function writeComment(host: HTMLElement, body: string) {
  const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
  assert(input !== null, 'Selecting a line must mount the shared Markdown composer');
  changeInput(input, body);
  await settle();
}

const file: DiffFileDto = { filename: 'src/Example.java', status: 'modified', additions: 2, deletions: 2,
  patch: '@@ -1,3 +1,3 @@\n context\n-old one\n-old two\n+new one\n+new two' };
const commits: DiffCommitDto[] = [
  { sha: '1111111', parentSha: '0000000', message: 'First', author: 'alice', authoredAt: '2026-01-01T00:00:00Z' },
  { sha: '2222222', parentSha: '1111111', message: 'Second', author: 'alice', authoredAt: '2026-01-01T00:00:00Z' },
];
const pr: PullRequestDto = {
  id: 1, repo: 'owner/repo', number: 1, title: 'Implementation', author: 'alice', htmlUrl: 'https://github.com/owner/repo/pull/1',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', origin: 'AUTHORED', labels: [], labelColors: null,
  draft: true, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null, additions: 2,
  deletions: 2, commentCount: 0, attentionReason: null, state: 'open', mergeable: null, mergeableState: null,
  headPushedAt: null, headRef: 'feature/change',
};

async function privateComment(reviewMode: 'missing' | 'own PR' | 'reviewable PR'): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const received: DiffAgentComment[] = [];
  let pendingChanges = 0;
  const review: DiffViewerProps['review'] = reviewMode === 'missing' ? undefined : {
    repo: pr.repo, number: pr.number, canReview: reviewMode === 'reviewable PR', pendingComments: [],
    onPendingCommentsChange: () => { pendingChanges++; }, onDetailChange: () => {},
  };
  flushSync(() => root.render(<DiffViewer files={[file]} commits={commits} commitRange={{ start: 1, end: 1 }}
    review={review} onAskAgent={async comment => { received.push(comment); }} />));
  try {
    await selectLine(host, 'old', 2);
    await selectLine(host, 'old', 3, true);
    await writeComment(host, 'Why did you remove these lines?');
    button(host, 'Preview').click();
    await settle();
    assert(host.querySelector('.pull-diff-review__preview')?.textContent?.includes('Why did you remove these lines?') === true,
      'Markdown preview must work without GitHub review capability');
    const githubActions = [...host.querySelectorAll('button')].filter(item => item.textContent === 'Post now' || item.textContent === 'Add to review');
    assert(githubActions.length === (reviewMode === 'reviewable PR' ? 2 : 0), 'GitHub actions must follow canReview, independently of private agent feedback');
    button(host, 'Send to agent').click();
    await settle();
    assert(received.length === 1 && pendingChanges === 0, 'Private feedback must bypass the GitHub pending review');
    const left = received[0];
    assert(left.comment.path === file.filename && left.comment.side === 'LEFT' && left.comment.startSide === 'LEFT'
      && left.comment.startLine === 2 && left.comment.line === 3, 'Deleted range must retain its exact old side and line numbers');
    assert(left.comment.commitId === '2222222' && left.baseSha === '1111111' && left.code === 'old one\nold two',
      'Deleted code must come from the selected base, without including replacement lines');
    assert(host.querySelector('.pull-diff-review') === null, 'Adding private feedback must close its inline composer');
    const add = host.querySelector<HTMLButtonElement>('[aria-label="Add a comment on new line 2"]');
    assert(add !== null, 'Shared plus gutter must remain available');
    add.click();
    await settle();
    await selectLine(host, 'new', 3, true);
    await writeComment(host, 'Explain this implementation');
    button(host, 'Send to agent').click();
    await settle();
    assert(Number(received.length) === 2 && received[1].comment.side === 'RIGHT' && received[1].code === 'new one\nnew two',
      'Clicking the plus and extending the range must quote only the new code');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function unchangedContent(): Promise<void> {
  for (const selected of [false, true]) {
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const received: DiffAgentComment[] = [];
    const reads: unknown[][] = [];
    const newContent = 'context\ntwo\nthree\nfour\nnew fifth\nsixth';
    flushSync(() => root.render(<DiffViewer
      files={[{ ...file, patch: '@@ -1,1 +1,1 @@\n context\n@@ -5,2 +5,2 @@\n-old fifth\n+new fifth\n sixth' }]}
      commits={commits} commitRange={selected ? { start: 1, end: 1 } : null} onAskAgent={async comment => { received.push(comment); }}
      loadFileContent={async (path, baseSha, headSha) => {
        reads.push([path, baseSha, headSha]);
        return { oldContent: newContent.replace('new fifth', 'old fifth'), newContent };
      }} />));
    try {
      button(host, '↕ 3 unmodified lines').click();
      await settle();
      assert(reads.length === 1 && reads[0][0] === file.filename, 'Expanding context must load the selected file once');
      assert(reads[0][1] === (selected ? '1111111' : undefined) && reads[0][2] === (selected ? '2222222' : undefined),
        'Full PR context must omit both refs; selected context must pass both refs');
      await selectLine(host, 'new', 1);
      await selectLine(host, 'new', 6, true);
      await writeComment(host, 'Explain the full selected range');
      button(host, 'Send to agent').click();
      await settle();
      assert(received.length === 1 && received[0].code === newContent, 'Agent context must include loaded unchanged lines throughout the selected range');
      assert(received[0].baseSha === (selected ? '1111111' : null) && received[0].comment.commitId === '2222222',
        'Unknown full PR base must stay unknown while head anchoring remains available');
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  }
}

async function incrementalContext(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const content = Array.from({ length: 1500 }, (_, index) => `line ${index + 1}`).join('\n');
  flushSync(() => root.render(<DiffViewer files={[{ ...file, patch: '@@ -451,1 +451,1 @@\n line 451\n@@ -842,1 +842,1 @@\n line 842' }]}
    loadFileContent={async () => ({ oldContent: content, newContent: content })} />));
  const row = (line: number) => host.querySelector(`[data-diff-row="${file.filename}:RIGHT:${line}"]`);
  const gap = (remaining: number) => button(host, `↕ ${remaining} unmodified lines`).closest('tr');
  const expand = async (remaining: number) => {
    button(host, `↕ ${remaining} unmodified lines`).click();
    await settle();
  };
  try {
    await expand(450);
    assert(gap(250)?.nextElementSibling === row(251) && row(250) === null, 'A leading gap must reveal only its last 200 lines below the expander');
    await expand(390);
    assert(gap(190)?.previousElementSibling === row(551) && gap(190)?.nextElementSibling === row(742),
      'A gap between hunks must reveal 100 lines beside each hunk around the expander');
    await expand(658);
    assert(gap(458)?.previousElementSibling === row(1042) && row(1043) === null, 'A trailing gap must reveal only its first 200 lines above the expander');
    for (const remaining of [250, 50, 190, 458, 258, 58]) await expand(remaining);
    const rows = [...host.querySelectorAll('[data-diff-row]')].map(element => element.getAttribute('data-diff-row'));
    assert(host.querySelector('tr.is-gap') === null
      && rows.join() === Array.from({ length: 1500 }, (_, index) => `${file.filename}:RIGHT:${index + 1}`).join(),
    'Repeated expansion must reveal every unchanged line once, in order');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function asynchronousSubmission(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const requests: DiffAgentComment[] = [];
  const completions: Array<(error?: Error) => void> = [];
  const send = (comment: DiffAgentComment) => {
    requests.push(comment);
    return new Promise<void>((resolve, reject) => { completions.push(error => { if (error) reject(error); else resolve(); }); });
  };
  const render = (reason: string | null) => flushSync(() => root.render(<DiffViewer files={[file]} commits={commits}
    commitRange={{ start: 1, end: 1 }} onAskAgent={send} onAskAgentDisabled={reason} />));
  render('The agent is reconnecting.');
  try {
    await selectLine(host, 'old', 2);
    await selectLine(host, 'old', 3, true);
    await writeComment(host, 'Please explain this removal');
    assert(button(host, 'Send to agent').disabled && button(host, 'Send to agent').title === 'The agent is reconnecting.'
      && host.querySelector('.pull-diff-review [role="status"]')?.textContent === 'The agent is reconnecting.',
    'Unavailable private submission must give its reason as the action title and to assistive technology');
    assert(host.querySelector<HTMLButtonElement>('.pull-diff-review__bar .pull-diff-review__private')?.disabled === true
      && host.textContent?.includes('Private — only you and the agent') === false,
    'Private submission must show the Private label instead of a help text');
    button(host, 'Send to agent').click();
    assert(requests.length === 0, 'A disabled private action must not submit');
    render(null);
    const submit = button(host, 'Send to agent');
    submit.click();
    submit.click();
    await settle();
    assert(Number(requests.length) === 1 && button(host, 'Sending…').disabled, 'An in-flight private submission must not be duplicated');
    const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
    assert(input !== null && input.disabled && input.value === 'Please explain this removal' && button(host, 'Cancel').disabled,
      'Pending submission must preserve accepted text until the request settles');
    assert(host.querySelector<HTMLButtonElement>('[aria-label="Select new line 2"]')?.disabled === true,
      'Pending submission must preserve the selected code range');
    completions[0](new Error('The provider is unavailable'));
    await settle();
    assert(input.value === 'Please explain this removal' && !input.disabled
      && host.querySelector('[role="alert"]')?.textContent === 'The provider is unavailable',
    'Failed submission must retain editable feedback and show its error');
    assert(host.querySelector('.pull-diff-review__header')?.textContent?.includes('L2 to L3') === true,
      'Failed submission must retain the same old-line range');
    button(host, 'Send to agent').click();
    await settle();
    assert(Number(requests.length) === 2 && JSON.stringify(requests[0]) === JSON.stringify(requests[1]),
      'Explicit retry must submit the retained comment with its original code context');
    completions[1]();
    await settle();
    assert(host.querySelector('.pull-diff-review') === null, 'Only accepted private feedback closes the composer');
    assert(document.activeElement?.getAttribute('aria-label') === 'Select old line 3', 'Accepted feedback must restore focus to its gutter');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function dragSelection(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<DiffViewer files={[file]} commits={commits} commitRange={{ start: 1, end: 1 }} onAskAgent={async () => {}} />));
  const press = async (line: number, shiftKey = false) => {
    const target = host.querySelector(`[aria-label="Select new line ${line}"]`);
    assert(target !== null, `Missing new line ${line} gutter`);
    target.dispatchEvent(new MouseEvent('mousedown', { button: 0, shiftKey, bubbles: true, cancelable: true, ...centre(target) }));
    await settle();
  };
  // React reports a mouseover without a relatedTarget as entering every row ancestor.
  const hover = async (line: number) => {
    const row = host.querySelector(`[data-diff-row="${file.filename}:RIGHT:${line}"]`);
    assert(row !== null, `Missing new line ${line} row`);
    row.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, ...centre(row) }));
    await settle();
  };
  const release = async () => {
    window.dispatchEvent(new MouseEvent('mouseup'));
    await settle();
  };
  const heading = () => host.querySelector('.pull-diff-review__header strong')?.textContent;
  try {
    await press(1);
    await hover(2);
    assert(host.querySelectorAll('tr.is-selected').length === 4 && host.querySelector('.pull-diff-review') === null,
      'Dragging must select every row between its ends and hide the composer until release');
    await release();
    assert(heading() === 'Add a comment on lines R1 to R2', 'Releasing a drag must open the composer on the dragged range');
    await press(3, true);
    await hover(2);
    await release();
    assert(heading() === 'Add a comment on lines R1 to R2', 'A Shift-drag must keep the existing range start as its anchor');
    await press(3, true);
    await release();
    assert(heading() === 'Add a comment on lines R1 to R3', 'A Shift-click must still extend the range');
    await press(1);
    await hover(2);
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.body.dispatchEvent(escape);
    await settle();
    assert(escape.defaultPrevented, 'Escape that cancels a drag must be handled so the screen does not also close');
    await hover(3);
    await release();
    assert(host.querySelector('tr.is-selected') === null && host.querySelector('.pull-diff-review') === null,
      'Escape must cancel a drag in progress');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function pressBelowComposer(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const context = Array.from({ length: 30 }, (_, index) => ` line ${index + 1}`).join('\n');
  flushSync(() => root.render(<DiffViewer files={[{ ...file, additions: 0, deletions: 0, patch: `@@ -1,30 +1,30 @@\n${context}` }]}
    onAskAgent={async () => {}} />));
  const heading = () => host.querySelector('.pull-diff-review__header strong')?.textContent;
  try {
    for (const shift of [true, false]) {
      await selectLine(host, 'new', 1);
      assert(heading() === 'Add a comment on line R1', 'Selecting line 1 must open its composer');
      const target = host.querySelector('[aria-label="Select new line 3"]');
      assert(target !== null, 'Missing new line 3 gutter');
      await realClick(target, shift);
      assert(heading() === (shift ? 'Add a comment on lines R1 to R3' : 'Add a comment on line R3')
        && host.querySelectorAll('tr.is-selected').length === (shift ? 3 : 1),
      `A ${shift ? 'Shift-click' : 'click'} below an open composer must not drag onto the rows that move under the pointer`);
      button(host, 'Cancel').click();
      await settle();
    }
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function keyboardShortcuts(): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const sent: DiffAgentComment[] = [];
  let posts = 0;
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...originalBridge, postPrReviewComment: async () => { posts++; return {}; } } });
  const render = (canReview: boolean, reason: string | null = null) => flushSync(() => root.render(<DiffViewer files={[file]} commits={commits}
    commitRange={{ start: 1, end: 1 }} onAskAgent={async comment => { sent.push(comment); }} onAskAgentDisabled={reason}
    review={canReview ? { repo: pr.repo, number: pr.number, canReview, pendingComments: [], onPendingCommentsChange: () => {}, onDetailChange: () => {} } : undefined} />));
  const input = () => host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
  const press = async (init: KeyboardEventInit) => {
    input()?.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
    await settle();
  };
  try {
    render(true);
    await selectLine(host, 'new', 1);
    await press({ key: 'Escape' });
    assert(input() === null, 'Escape must close an empty composer');
    await selectLine(host, 'new', 1);
    await writeComment(host, 'Keep this comment');
    await press({ key: 'Escape' });
    assert(input()?.value === 'Keep this comment', 'Escape must not discard a written comment');
    await press({ key: 'Enter', metaKey: true });
    assert(posts === 1 && sent.length === 0 && input() === null, 'Cmd+Enter must post now when the viewer can review');
    render(false, 'The agent is reconnecting.');
    await selectLine(host, 'new', 1);
    await writeComment(host, 'Ask the agent');
    await press({ key: 'Enter', ctrlKey: true });
    assert(sent.length === 0 && input()?.value === 'Ask the agent', 'Ctrl+Enter must do nothing while the primary action is disabled');
    render(false);
    await press({ key: 'Enter', ctrlKey: true });
    assert(Number(sent.length) === 1 && posts === 1, 'Ctrl+Enter must send to the agent when that is the only action');
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge });
  }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  let githubWrites = 0;
  const originalBridge = window.bridge;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getUserProfile: async (): Promise<null> => null,
    postPrReviewComment: async () => { githubWrites++; throw new Error('Private comments must never post to GitHub'); },
  } });
  const cases: Array<[string, () => Promise<void>]> = [
    ...(['missing', 'own PR', 'reviewable PR'] as const).map(mode => [`Shared diff with ${mode} review`, () => privateComment(mode)] as [string, () => Promise<void>]),
    ['Unchanged context preserves full and selected revision pairs', unchangedContent],
    ['Unchanged context expands at most 200 lines per click', incrementalContext],
    ['Private submission preserves failed and in-flight feedback', asynchronousSubmission],
    ['Dragging and Shift-dragging line numbers select a range', dragSelection],
    ['Clicking below an open composer does not drag', pressBelowComposer],
    ['Line composer keys post, send and close only an empty comment', keyboardShortcuts],
    ...(['review', 'changes'] as const).map(projection => [`Resolve finding in ${projection}`, () => resolveFinding(projection)] as [string, () => Promise<void>]),
  ];
  try {
    const results: { name: string; error?: string }[] = [];
    for (const [name, scenario] of cases) {
      try { await scenario(); assert(githubWrites === 0, 'Private comment action wrote to GitHub'); results.push({ name }); }
      catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    }
    return [...results, ...await runTaskInlineCommentScenarios(), ...await runCodeCommentRangeScenarios()];
  }
  finally { Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
}
