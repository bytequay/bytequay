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
import { SourceSnapshot } from '../ArchivedCode';
import { useCodeComments, type CodeCommentsOptions } from '../useCodeComments';
import { assert, changeInput, settle } from '../../../fixtures/harness';
import type { PullRequestDto } from '../../../types';
const pr = { repo: 'owner/repo', number: 1, headRef: 'feature' } as PullRequestDto;

export async function runArchivedCodeScenarios(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  const options: CodeCommentsOptions = { repo: 'owner/repo', number: 1, canReview: true, viewer: null, pending: [], onPendingChange: () => {},
    threads: { activities: [], pr, unavailable: false, onRefresh: async () => {}, onDetailUpdate: () => {} }, onDetailChange: () => {},
    localReview: { threads: [], onReply: async () => {}, onResolve: async () => {}, onRetry: async () => {} } };
  const text = Array.from({ length: 20 }, (_, index) => `line ${index + 1}`).join('\n');
  const Harness = () => {
    const comments = useCodeComments(options, { path: 'notes.txt', text, prPatch: '@@ -1,8 +1,8 @@\n' + text.split('\n').slice(0, 8).map(line => ' ' + line).join('\n'),
      draft: false, uncommitted: false, atReviewedRevision: true, reviewedHead: 'abc', observedHead: 'abc', askBlocked: null });
    return <SourceSnapshot path="notes.txt" text={text} cards={comments.cards} renderCard={comments.renderCard}
      onComment={comments.onComment} commentRange={comments.commentRange} />;
  };
  try {
    flushSync(() => root.render(<Harness />)); await settle();
    host.querySelector<HTMLButtonElement>('[aria-label="Comment on line 3"]')?.click(); await settle();
    const input = host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea'); assert(input !== null, 'A line must open the shared composer');
    changeInput(input, 'Keep this private draft'); await settle();
    host.querySelector('[aria-label="Comment on line 6"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert(host.querySelector('.pull-diff-review__header strong')?.textContent === 'Add a comment on lines R3 to R6', 'Shift-click must extend the retained source range');
    assert(host.querySelector<HTMLTextAreaElement>('.pull-diff-review textarea')?.value === 'Keep this private draft', 'Range changes preserve the draft');
    host.querySelector('[aria-label="Comment on line 11"]')?.dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true })); await settle();
    assert([...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Post now')?.disabled, 'A source range outside the actual PR hunk cannot be posted');
    results.push({ name: 'Historical source preserves shared comment ranges and patch guards' });
  } catch (error) { results.push({ name: 'Historical source comments', error: String(error) }); }
  try {
    flushSync(() => root.render(<SourceSnapshot path="Example.java" text={'class Example {}\n'.repeat(1100)} cards={[]} commentRange={null} />)); await settle();
    assert(host.querySelectorAll('.ct-snapshot__line').length === 500 && host.textContent?.includes('Lines 1–500 of 1101'), 'Large snapshots must bound rendered lines');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Next lines')?.click(); await settle();
    assert(host.querySelector('.ct-snapshot__number')?.textContent === '501' && host.querySelector('.hljs-keyword')?.textContent === 'class', 'Paging keeps source line identity and syntax coloring');
    results.push({ name: 'Historical source pages retain line numbers and syntax' });
  } catch (error) { results.push({ name: 'Historical source paging', error: String(error) }); }
  finally { flushSync(() => root.unmount()); host.remove(); }
  return results;
}
