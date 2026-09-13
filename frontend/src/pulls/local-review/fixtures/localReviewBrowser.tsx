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
import { DiffViewer } from '../../PullChanges';
import LocalReviewThreadCard from '../LocalReviewThreadCard';
import type { LocalReviewThreadDto, LocalReviewThreads } from '../types';

const settle = () => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
function assert(condition: boolean, message: string): asserts condition { if (!condition) throw new Error(message); }
function button(host: HTMLElement, text: string) {
  const value = [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent === text);
  assert(value !== undefined, `Missing ${text} button`);
  return value;
}
function changeInput(input: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function initialThread(): LocalReviewThreadDto {
  return { id: 'local-1', taskId: 'task-1', workspaceId: 'workspace-1', repo: 'owner/repo', number: 17,
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
  const render = () => flushSync(() => root.render(<LocalReviewThreadCard thread={thread}
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
    const input = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Reply to local review"]');
    assert(input !== null, 'Reply must open the normal inline editor');
    changeInput(input, 'Please keep the validation');
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

export async function run(): Promise<{ name: string; error?: string }[]> {
  const cases: Array<[string, () => Promise<void>]> = [
    ['Local discussion reply, resolution and remount', replyAndResolve],
    ['Saved unsent message retry', savedRetry],
    ['Private diff anchors preserve revision and code', anchoredDiff],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of cases) {
    try { await scenario(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
