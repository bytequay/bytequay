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

import '../../css/agents.css';
import { DiscussableAnswer, type SelectedPassage } from '../discussions/DiscussableAnswer';
import { assert, changeInput, settle } from '../../fixtures/harness';

const ANSWER = [
  'It resolves address.zip by descending from the root field, then falls back to any',
  'descendant whose name matches the leaf.',
  '',
  'When nothing matches it throws INVALID_TABLE_PROPERTY with the joined path.',
].join('\n');

/** Selects a run of text, in either direction, exactly as a drag would leave the selection. */
function select(text: string, backwards: boolean): void {
  const host = document.querySelector('.agt-md');
  assert(host !== null, 'The answer did not render');
  const walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const at = (node.textContent ?? '').indexOf(text);
    if (at === -1) continue;
    const selection = window.getSelection();
    assert(selection !== null, 'This browser reports no selection');
    // Anchor after focus is what the platform records for a right-to-left drag.
    if (backwards) selection.setBaseAndExtent(node, at + text.length, node, at);
    else selection.setBaseAndExtent(node, at, node, at + text.length);
    return;
  }
  assert(false, `The answer does not contain ${text}`);
}

async function scenario(name: string, run: () => Promise<void>, results: { name: string; error?: string }[]): Promise<void> {
  try {
    await run();
    results.push({ name });
  }
  catch (failure) {
    results.push({ name, error: failure instanceof Error ? failure.message : String(failure) });
  }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const host = document.createElement('div');
  document.body.append(host);
  const selected: SelectedPassage[] = [];
  flushSync(() => createRoot(host).render(
    <DiscussableAnswer turnId="turn-10" text={ANSWER} discussions={[]} onSelect={passage => selected.push(passage)} />));
  await settle();

  const pill = (): HTMLElement | null => document.querySelector('.agt-disc-pill');
  const ask = async (question: string): Promise<void> => {
    (pill() as HTMLElement).click();
    await settle();
    const field = document.querySelector('.agt-disc-pop textarea') as HTMLTextAreaElement;
    assert(field !== null, 'The inline composer did not open');
    changeInput(field, question);
    await settle();
    const send = document.querySelector('.agt-disc-pop__ask') as HTMLButtonElement;
    assert(!send.disabled, 'Ask stayed disabled after typing a question');
    send.click();
    await settle();
  };
  const release = (target: EventTarget): void => {
    target.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  };
  // Escape is handled on the document either way, so clearing cannot itself depend on the
  // behaviour under test; a scenario that inherits a stale pill would pass without earning it.
  const clear = async (): Promise<void> => {
    window.getSelection()?.removeAllRanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    assert(pill() === null, 'A pill survived into the next scenario');
  };

  await clear();
  await scenario('offers the pill for a left-to-right selection', async () => {
    select('falls back to any', false);
    release(document.querySelector('.agt-md') as EventTarget);
    await settle();
    assert(pill() !== null, 'No pill after a forward selection');
  }, results);
  await clear();

  await scenario('offers the pill for a right-to-left selection released outside the answer', async () => {
    select('falls back to any', true);
    // A backwards drag ends at the passage's start and commonly overshoots out of the answer.
    release(document.body);
    await settle();
    assert(pill() !== null, 'No pill after a backward selection released outside the answer');
  }, results);
  await clear();

  await scenario('sends the same passage whichever way it was dragged', async () => {
    selected.length = 0;
    select('descendant whose name', false);
    release(document.body);
    await settle();
    await ask('forwards?');
    const afterForward = selected.length;
    assert(afterForward === 1, `The first question sent ${afterForward} passages`);
    await clear();
    select('descendant whose name', true);
    release(document.body);
    await settle();
    await ask('backwards?');
    assert(selected.length === 2, `Expected two passages, got ${selected.length}`);
    assert(selected[0].quote === selected[1].quote, `Direction changed the quote: ${selected[0].quote} vs ${selected[1].quote}`);
    assert(selected[0].occurrence === selected[1].occurrence, 'Direction changed the occurrence');
  }, results);
  await clear();

  await clear();
  await scenario('keeps the pill inside the answer when the passage is on its first line', async () => {
    select('It resolves', false);
    release(document.body);
    await settle();
    const offered = pill();
    assert(offered !== null, 'No pill for a first-line passage');
    const frame = (document.querySelector('.agt-answer') as HTMLElement).getBoundingClientRect();
    assert(offered.getBoundingClientRect().top >= frame.top, 'The pill sits above the answer and is unreachable');
  }, results);

  await clear();
  await scenario('opens a composer wide enough to write in, which drags and resizes', async () => {
    select('falls back to any', false);
    release(document.body);
    await settle();
    (pill() as HTMLElement).click();
    await settle();
    const pop = document.querySelector('.agt-disc-pop') as HTMLElement | null;
    assert(pop !== null, 'The composer did not open');
    // The percentage width it used to carry resolved against a shrink-to-fit parent and collapsed.
    const width = pop.getBoundingClientRect().width;
    assert(width >= 440 && width <= 470, `The composer is ${Math.round(width)}px wide, not the 460 it asks for`);
    assert(getComputedStyle(pop).resize === 'both', 'The composer cannot be resized');

    const band = pop.querySelector('.agt-disc-pop__quote') as HTMLElement;
    const before = pop.getBoundingClientRect();
    band.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: before.left + 30, clientY: before.top + 10 }));
    window.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: before.left + 90, clientY: before.top + 40 }));
    window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    await settle();
    const after = (document.querySelector('.agt-disc-pop') as HTMLElement).getBoundingClientRect();
    assert(Math.round(after.left - before.left) === 60, `Dragging moved it ${Math.round(after.left - before.left)}px across, not 60`);
    assert(Math.round(after.top - before.top) === 30, `Dragging moved it ${Math.round(after.top - before.top)}px down, not 30`);
  }, results);

  await clear();
  await scenario('tints an anchored passage and opens its thread when clicked', async () => {
    const opened: string[] = [];
    const marked = document.createElement('div');
    document.body.append(marked);
    flushSync(() => createRoot(marked).render(
      <DiscussableAnswer turnId="turn-10" text={ANSWER} onOpen={id => opened.push(id)} discussions={[{
        id: 'd1', taskId: 't1', turnId: 'turn-10', quote: 'descendant whose name matches the leaf', occurrence: 1,
        resolved: false, pending: false, providerLane: 'claude', version: 0,
        createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z', messages: [],
      }]} />));
    await settle();
    const mark = marked.querySelector('button.agt-disc-mark') as HTMLElement | null;
    assert(mark !== null, 'The anchored passage is not marked');
    assert(mark.textContent === 'descendant whose name matches the leaf', `The mark reads "${mark.textContent ?? ''}"`);
    const style = getComputedStyle(mark);
    assert(style.backgroundColor !== 'rgba(0, 0, 0, 0)', 'The mark has no tint');
    assert(style.userSelect === 'text', `A drag cannot select the mark: user-select is ${style.userSelect}`);
    mark.click();
    await settle();
    assert(opened.join() === 'd1', `Clicking the mark opened ${opened.join() || 'nothing'}`);
    marked.remove();
  }, results);

  return results;
}
