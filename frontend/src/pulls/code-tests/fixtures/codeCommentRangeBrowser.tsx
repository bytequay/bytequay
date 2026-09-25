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
import '../../../css/base.css';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { PullRequestDto } from '../../../types';
import CodeView from '../CodeView';
import { useCodeComments, type CodeCommentsOptions, type CommentContext } from '../useCodeComments';
import { assert, settle } from '../../../fixtures/harness';


const text = Array.from({ length: 20 }, (_, index) => `line ${index + 1}`).join('\n');
const hunk = (from: number, to: number) => `@@ -${from},${to - from + 1} +${from},${to - from + 1} @@\n`
  + Array.from({ length: to - from + 1 }, (_, index) => ` line ${from + index}`).join('\n');
const context: CommentContext = {
  path: 'src/notes.txt', text, prPatch: `${hunk(1, 8)}\n${hunk(10, 16)}`, draft: false, uncommitted: false, atReviewedRevision: true,
  reviewedHead: 'abc', observedHead: 'abc', askBlocked: null,
};
const pr: PullRequestDto = {
  id: 1, repo: 'owner/repo', number: 1, title: 'Implementation', author: 'alice', htmlUrl: 'https://github.com/owner/repo/pull/1',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', origin: 'AUTHORED', labels: [], labelColors: null,
  draft: false, viewedAt: null, reviewedAt: null, handledAction: null, requestedReviewers: [], ciStatus: null, additions: 0,
  deletions: 0, commentCount: 0, state: 'open', mergeable: null, mergeableState: null,
  headPushedAt: null, headRef: 'feature/change',
};
let agentSends = 0;
const options: CodeCommentsOptions = {
  repo: pr.repo, number: pr.number, canReview: true, viewer: null, pending: [], onPendingChange: () => {},
  threads: { activities: [], pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} },
  onDetailChange: () => {},
  localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} },
  onAskAgent: async () => { agentSends++; },
};

function Harness() {
  const comments = useCodeComments(options, context);
  return <CodeView path={context.path} value={context.text} saved={context.text} line={0} token={0}
    onComment={comments.onComment} commentRange={comments.commentRange} cards={comments.cards} renderCard={comments.renderCard} />;
}

async function mounted(scenario: (host: HTMLElement) => Promise<void>): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<Harness />));
  await settle();
  try { await scenario(host); }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

function lineNumber(host: HTMLElement, line: number) {
  const target = [...host.querySelectorAll<HTMLElement>('.cm-lineNumbers .cm-gutterElement')]
    .find(element => element.style.visibility !== 'hidden' && element.textContent === String(line));
  assert(target !== undefined, `Missing line number ${line}`);
  return target;
}
const codeLine = (host: HTMLElement, line: number) => host.querySelectorAll<HTMLElement>('.cm-content .cm-line')[line - 1];
export const centre = (element: Element) => {
  const rect = element.getBoundingClientRect();
  return { clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 };
};

/** Trusted Chromium mouse input: the runner's main process relays `input:` console messages to sendInputEvent. */
export async function realMouse(type: 'mouseDown' | 'mouseMove' | 'mouseUp', x: number, y: number, modifiers: string[] = []) {
  assert(y >= 0 && y < window.innerHeight, `Pointer ${y} is outside the window`);
  const domType = type.toLowerCase();
  const arrived = new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`No ${domType} arrived from the runner`)), 2000);
    window.addEventListener(domType, () => { clearTimeout(timeout); resolve(); }, { capture: true, once: true });
  });
  console.log(`input:${JSON.stringify({ type, x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1, modifiers })}`);
  await arrived;
  await settle();
}

/** A press whose pointer jitters 1px, as a real click does, after the press has collapsed the composer above it. */
export async function realClick(target: Element, shift: boolean) {
  const { clientX, clientY } = centre(target);
  const modifiers = shift ? ['shift'] : [];
  await realMouse('mouseDown', clientX, clientY, modifiers);
  await realMouse('mouseMove', clientX, clientY + 1, [...modifiers, 'leftbuttondown']);
  await realMouse('mouseUp', clientX, clientY + 1, modifiers);
}
async function press(host: HTMLElement, line: number, shiftKey = false) {
  lineNumber(host, line).dispatchEvent(new MouseEvent('mousedown', { button: 0, shiftKey, bubbles: true, cancelable: true, ...centre(lineNumber(host, line)) }));
  await settle();
}
async function move(target: HTMLElement) {
  target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, ...centre(target) }));
  await settle();
}
async function release() {
  window.dispatchEvent(new MouseEvent('mouseup'));
  await settle();
}
const heading = (host: HTMLElement) => host.querySelector('.pull-diff-review__header strong')?.textContent;
const selectedLines = (host: HTMLElement) => [...host.querySelectorAll('.cm-line.ct-code__selected')].length;
const selectedNumbers = (host: HTMLElement) => [...host.querySelectorAll('.cm-lineNumbers .cm-gutterElement.ct-code__selected')].map(element => element.textContent);
function button(host: HTMLElement, label: string) {
  const result = [...host.querySelectorAll<HTMLButtonElement>('.pull-diff-review button')].find(item => item.textContent === label);
  assert(result !== undefined, `Missing ${label} button`);
  return result;
}
async function write(host: HTMLElement, value: string) {
  const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
  assert(input !== null, 'A released range must open the composer');
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
}

const dragRanges = () => mounted(async host => {
  await press(host, 3);
  await move(lineNumber(host, 6));
  assert(selectedLines(host) === 4 && selectedNumbers(host).join() === '3,4,5,6',
    'Dragging line numbers must tint every line and line number in the range');
  assert(host.querySelector('.pull-diff-review') === null, 'The composer must stay hidden until the drag is released');
  await release();
  assert(heading(host) === 'Add a comment on lines R3 to R6', 'Releasing a drag must open the composer on the dragged range');
  assert(codeLine(host, 6).nextElementSibling?.querySelector('.pull-diff-review') != null, 'The composer must sit directly after the last line');
  await press(host, 5);
  await move(codeLine(host, 2));
  await release();
  assert(heading(host) === 'Add a comment on lines R2 to R5' && selectedLines(host) === 4,
    'An upward drag that ends over the code column must select from the pointer line to the pressed line');
});

const shiftExtends = () => mounted(async host => {
  await press(host, 10);
  await release();
  await write(host, 'Keep this text');
  await press(host, 14, true);
  await release();
  assert(heading(host) === 'Add a comment on lines R10 to R14', 'A Shift-click must extend the range');
  assert(host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea')?.value === 'Keep this text', 'Extending must keep the typed text');
  await press(host, 16, true);
  await move(lineNumber(host, 12));
  await release();
  assert(heading(host) === 'Add a comment on lines R10 to R12', 'A Shift-drag must keep the existing range start as its anchor');
});

const escapeAndCancel = () => mounted(async host => {
  await press(host, 3);
  await move(lineNumber(host, 6));
  const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
  document.body.dispatchEvent(escape);
  await settle();
  assert(escape.defaultPrevented, 'Escape that cancels a drag must be handled so the screen does not also close');
  await move(lineNumber(host, 8));
  await release();
  assert(selectedLines(host) === 0 && host.querySelector('.pull-diff-review') === null, 'Escape must cancel a drag in progress');
  await press(host, 4);
  await release();
  button(host, 'Cancel').click();
  await settle();
  assert(selectedLines(host) === 0 && selectedNumbers(host).length === 0 && host.querySelector('.pull-diff-review') === null,
    'Cancel must clear the range highlight');
  await press(host, 4);
  await release();
  host.querySelector('.pull-diff-review textarea')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
  await settle();
  assert(host.querySelector('.pull-diff-review') === null && selectedLines(host) === 0, 'Escape must close an empty composer');
});

const crossHunk = () => mounted(async host => {
  const reason = "GitHub accepts line comments only on lines in this pull request's diff";
  await press(host, 10);
  await move(lineNumber(host, 14));
  await release();
  await write(host, 'Is this covered?');
  assert(!button(host, 'Post now').disabled && host.querySelector('.pull-diff-review .sr-only[role="status"]')?.textContent === '',
    'A range inside one hunk must allow GitHub comments');
  await press(host, 6);
  await move(lineNumber(host, 11));
  await release();
  assert(heading(host) === 'Add a comment on lines R6 to R11', 'A drag may cross hunks');
  assert(button(host, 'Post now').disabled && button(host, 'Add to review').disabled && button(host, 'Post now').title === reason
    && host.querySelector('.pull-diff-review .sr-only[role="status"]')?.textContent === reason,
  'A range across hunks must block GitHub actions with the reason');
  assert(!button(host, 'Send to agent').disabled, 'Send to agent must still take a range across hunks');
});

const pressBelowComposer = () => mounted(async host => {
  for (const shift of [true, false]) {
    await press(host, 1);
    await release();
    assert(heading(host) === 'Add a comment on line R1', 'A click must open the composer on line 1');
    await realClick(lineNumber(host, 3), shift);
    assert(heading(host) === (shift ? 'Add a comment on lines R1 to R3' : 'Add a comment on line R3') && selectedLines(host) === (shift ? 3 : 1),
      `A ${shift ? 'Shift-click' : 'click'} below an open composer must not drag onto the lines that move under the pointer`);
    button(host, 'Cancel').click();
    await settle();
  }
});

const composerKeys = () => mounted(async host => {
  const originalBridge = window.bridge;
  let posts = 0;
  Object.defineProperty(window, 'bridge', { configurable: true, value: { ...originalBridge, postPrReviewComment: async () => { posts++; return {}; } } });
  agentSends = 0;
  const key = async (init: KeyboardEventInit) => {
    host.querySelector('.pull-diff-review textarea')?.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
    await settle();
  };
  const input = () => host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea');
  try {
    await press(host, 10);
    await move(lineNumber(host, 14));
    await release();
    await write(host, 'Is this covered?');
    await key({ key: 'Escape' });
    assert(input()?.value === 'Is this covered?', 'Escape must keep a composer that has text');
    await key({ key: 'Enter', metaKey: true });
    assert(posts === 1 && agentSends === 0 && input() === null, 'Cmd+Enter must post a range inside one hunk');
    await press(host, 6);
    await move(lineNumber(host, 11));
    await release();
    await write(host, 'Across hunks');
    assert(button(host, 'Post now').disabled, 'Post now must be disabled on a range across hunks');
    await key({ key: 'Enter', ctrlKey: true });
    assert(posts === 1 && agentSends === 0 && input()?.value === 'Across hunks',
      'Ctrl+Enter must neither post nor send to the agent while Post now is disabled');
  }
  finally { Object.defineProperty(window, 'bridge', { configurable: true, value: originalBridge }); }
});

export async function runCodeCommentRangeScenarios(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Code & Tests drags a comment range on line numbers', dragRanges],
    ['Code & Tests Shift-click and Shift-drag extend a comment range', shiftExtends],
    ['Code & Tests Escape and Cancel clear a comment range', escapeAndCancel],
    ['Code & Tests blocks GitHub comments on a range across hunks', crossHunk],
    ['Code & Tests clicks below an open composer without dragging', pressBelowComposer],
    ['Code & Tests composer keys post, block and keep written text', composerKeys],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of cases) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
