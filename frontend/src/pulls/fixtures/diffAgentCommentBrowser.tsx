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

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
function changeInput(input: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
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
    assert(button(host, 'Send to agent').disabled && host.querySelector('[role="status"]')?.textContent === 'The agent is reconnecting.',
      'Unavailable private submission must stay visible with its reason');
    assert(host.textContent?.includes('Private — only you and the agent. Questions and replies stay in this thread.') === true,
      'Private submission must identify its local discussion without promising GitHub writes');
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
    ['Private submission preserves failed and in-flight feedback', asynchronousSubmission],
  ];
  try {
    const results: { name: string; error?: string }[] = [];
    for (const [name, scenario] of cases) {
      try { await scenario(); assert(githubWrites === 0, 'Private comment action wrote to GitHub'); results.push({ name }); }
      catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    }
    return [...results, ...await runTaskInlineCommentScenarios()];
  }
  finally { Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
}
