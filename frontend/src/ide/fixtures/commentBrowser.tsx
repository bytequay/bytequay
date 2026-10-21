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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import ReviewComments from '../ReviewComments';
import { reviewCommentCards, type ReviewCommentTarget } from '../reviewCommentModel';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import { assert, settle, element } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/pulls.css';
import '../../css/agent-review.css';
import '../workbench.css';

export async function run() {
  const host = document.createElement('main'); host.className = 'ide-workbench'; document.body.append(host);
  const root = createRoot(host);
  const cards = reviewCommentCards({ review: reviewFixture() });
  const source = cards[0];
  cards.push({ ...source, id: 'other', path: 'src/Other.java', location: 'src/Other.java:R5', kind: 'Pending', title: 'Check the fallback',
    status: 'Not posted', messages: [{ author: 'You', body: 'Please check the **fallback** path.', timestamp: null }],
    target: { path: 'src/Other.java', start: null, end: 5, revision: 'head' } });
  let selectedPath: string | null = source.path;
  let closed = false, failed = false, composed = false;
  const jumps: ReviewCommentTarget[] = [];
  const render = () => flushSync(() => root.render(<div className="ide-code-row">
    <section className="ide-editor" style={{ padding: 20 }}><strong>Tears of time</strong><p>Select a review card to open its code.</p></section>
    <ReviewComments cards={cards} selectedPath={selectedPath} repository="owner/repo" unavailable={false}
      onJump={async target => { if (failed) throw new Error('File no longer exists'); jumps.push(target); }}
      onComment={() => { composed = true; }} onClose={() => { closed = true; }} />
  </div>));
  const click = async (selector: string) => {
    const button = element<HTMLButtonElement>(selector, host, `Missing ${selector}`);
    flushSync(() => button.click()); await settle();
  };
  const count = () => host.querySelectorAll('[data-comment-id]').length;
  render(); await settle();
  assert(count() === cards.length, 'All shows every review card');
  assert(host.textContent?.includes(source.messages[0].body.split('`')[0]), 'Card contents are readable before navigation');
  await click('[role="tab"]:nth-child(2)');
  assert(count() === cards.filter(card => card.path === selectedPath).length, 'This file only shows selected-file cards');
  selectedPath = 'src/Other.java'; render(); await settle();
  assert(count() === 1 && host.querySelector('[data-comment-id="other"] strong')?.textContent === 'You', 'The file filter follows active-file changes');
  await click('.ide-comments__jump');
  assert(jumps.length === 1 && jumps[0].path === selectedPath && jumps[0].end === 5, 'Card navigation retains its own path and line');
  failed = true; await click('.ide-comments__jump');
  assert(host.querySelector('[role="alert"]')?.textContent === 'File no longer exists', 'Navigation errors are visible and leave cards available');
  await click('.ide-comments__compose'); assert(composed, 'Commenting on the selected lines remains available');
  selectedPath = 'empty.java'; render(); await settle();
  assert(count() === 0 && host.textContent?.includes('No review comments in this file.'), 'Files without comments have an empty state');
  selectedPath = null; render(); await settle();
  assert(host.textContent?.includes('Select a file'), 'No editor selection has a useful empty state');
  const tab = element<HTMLButtonElement>('[role="tab"][aria-selected="true"]', host, 'A tab is selected');
  tab.focus(); flushSync(() => tab.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))); await settle();
  assert(count() === cards.length && document.activeElement?.getAttribute('aria-selected') === 'true', 'Arrow keys select and focus the other tab');
  const panel = element<HTMLElementTagNameMap['aside']>('aside', host, 'Panel is visible');
  panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); assert(closed, 'Escape closes the comment panel');
  const list = element<HTMLElement>('.ide-comments__list', host, 'Cards have a scrollable list');
  assert(list.scrollHeight > list.clientHeight && getComputedStyle(list).overflowY === 'auto', 'Long review lists scroll inside the panel');
  assert(list.getBoundingClientRect().right <= window.innerWidth, 'Cards stay within the editor viewport');
  failed = false; await click('.ide-comments__jump');
  host.classList.add('is-dark'); document.documentElement.style.colorScheme = 'dark'; await settle();
  return true;
}
