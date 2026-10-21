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

import '../../css/base.css';
import '../../css/agents.css';
import { DiscussableAnswer, revealPassage, type SelectedPassage } from '../discussions/DiscussableAnswer';
import { assert, changeInput, settle, element } from '../../fixtures/harness';

const ANSWER = [
  'It resolves address.zip by descending from the root field, then falls back to any',
  'descendant whose name matches the leaf.',
  '',
  'When nothing matches it throws INVALID_TABLE_PROPERTY with the joined path.',
].join('\n');

/** Selects a run of text, in either direction, exactly as a drag would leave the selection. */
function select(text: string, backwards: boolean): void {
  const host = element<Element>('.agt-md', document, 'The answer did not render');
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
    <DiscussableAnswer turnId="turn-10" text={ANSWER} discussions={[]} onSelect={async passage => selected.push(passage)} />));
  await settle();

  const pill = (): HTMLElement | null => document.querySelector('.agt-disc-pill');
  const ask = async (question: string): Promise<void> => {
    const offered = pill();
    assert(offered !== null, 'The selection did not offer a discussion');
    offered.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    offered.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    await settle();
    assert(pill() === offered, 'Releasing the Ask button replaced it before the click');
    offered.click();
    await settle();
    const field = element<HTMLTextAreaElement>('.agt-disc-pop textarea', document, 'The inline composer did not open');
    changeInput(field, question);
    await settle();
    const send = document.querySelector('.agt-disc-pop [aria-label="Ask"]') as HTMLButtonElement;
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
    const pop = element<HTMLElement | null>('.agt-disc-pop', document, 'The composer did not open');
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
  const sentence = 'Body follows the repository template: Description (root cause and the new key), Additional context (Fixes #31252, test coverage by behavior), and Release notes with a proposed Delta Lake connector entry.';
  const markdownSentence = sentence.replace('Fixes #31252', '`Fixes #31252`');
  const edgeHost = document.createElement('div');
  edgeHost.className = 'agt-transcript';
  edgeHost.style.cssText = 'position:fixed;left:130px;top:40px;width:540px;height:500px;box-sizing:border-box;overflow:auto;background:white;';
  document.body.append(edgeHost);
  const sent: { passage: SelectedPassage; question: string }[] = [];
  const edgeRoot = createRoot(edgeHost);
  flushSync(() => edgeRoot.render(<div>
    <DiscussableAnswer turnId="turn-edge" text={`- ${markdownSentence}\n- ${markdownSentence}`} discussions={[]}
      onSelect={async (passage, question) => sent.push({ passage, question })} />
    <p className="selection-after">PR #31264</p>
  </div>));
  await settle();
  const edgeAnswer = edgeHost.querySelector('.agt-answer') as HTMLElement;
  const finalItem = edgeAnswer.querySelector('li:last-child') as HTMLElement;
  const start = finalItem.firstChild as Text;
  const after = edgeHost.querySelector('.selection-after')?.firstChild as Text;
  const selectFinal = (end: Node, offset: number, backwards: boolean): void => {
    const selection = window.getSelection();
    assert(selection !== null, 'This browser reports no selection');
    if (backwards) selection.setBaseAndExtent(end, offset, start, 0);
    else selection.setBaseAndExtent(start, 0, end, offset);
    release(document.body);
  };
  const assertSent = (question: string): void => {
    const latest = sent.at(-1);
    assert(latest?.passage.turnId === 'turn-edge', 'The question belongs to the wrong answer');
    assert(latest.passage.quote === sentence, `The sentence changed: ${latest.passage.quote}`);
    assert(latest.passage.occurrence === 2, `The second sentence was sent as occurrence ${latest.passage.occurrence}`);
    assert(latest.question === question, `The question changed: ${latest.question}`);
  };
  const inside = (element: HTMLElement, label: string): void => {
    const box = element.getBoundingClientRect();
    const frame = edgeHost.getBoundingClientRect();
    assert(box.left >= frame.left && box.right <= frame.right,
      `${label} escapes the transcript horizontally: ${Math.round(box.left)}..${Math.round(box.right)} outside ${Math.round(frame.left)}..${Math.round(frame.right)}`);
    assert(box.top >= frame.top && box.bottom <= frame.bottom,
      `${label} escapes the transcript vertically: ${Math.round(box.top)}..${Math.round(box.bottom)} outside ${Math.round(frame.top)}..${Math.round(frame.bottom)}`);
  };

  await scenario('asks about the last list sentence ending at its outer parent boundary in either direction', async () => {
    for (const backwards of [false, true]) {
      await clear();
      selectFinal(edgeAnswer.parentNode as Node, 1, backwards);
      await settle();
      assert(pill() !== null, 'A structural boundary after the answer was treated as foreign text');
      inside(pill() as HTMLElement, 'Sentence pill');
      const question = `Explain this sentence ${backwards ? 'backwards' : 'forwards'}?`;
      await ask(question);
      assertSent(question);
    }
  }, results);

  await clear();
  await scenario('asks about the last sentence ending at offset zero of adjacent text in either direction', async () => {
    for (const backwards of [false, true]) {
      await clear();
      selectFinal(after, 0, backwards);
      await settle();
      assert(pill() !== null, 'An unselected adjacent label prevented asking about the sentence');
      const question = `Explain the template ${backwards ? 'backwards' : 'forwards'}?`;
      await ask(question);
      assertSent(question);
    }
  }, results);

  await clear();
  await scenario('refuses selected adjacent text and trims it with the pointer before asking', async () => {
    selectFinal(after, after.length, false);
    await settle();
    assert(pill() === null, 'Foreign text was accepted as part of the answer');
    const warning = element<HTMLElement | null>('.agt-disc-inert', edgeHost, 'Foreign text did not explain why it cannot be discussed');
    inside(warning, 'Selection warning');
    const fix = warning.querySelector('button') as HTMLButtonElement;
    assert(!fix.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true })),
      'Pressing Trim must prevent the browser from clearing the selection');
    release(fix);
    fix.click();
    await settle();
    assert(pill() !== null, 'Trimming the selection did not offer a discussion');
    await ask('What does this template require?');
    assertSent('What does this template require?');
  }, results);

  await clear();
  await scenario('keeps sentence actions and the composer within an offset narrow transcript near either edge', async () => {
    edgeHost.style.width = '400px';
    edgeHost.style.height = '330px';
    for (const backwards of [false, true]) {
      await clear();
      const node = backwards ? finalItem.lastChild as Text : start;
      const from = backwards ? Math.max(0, node.length - 8) : 0;
      window.getSelection()?.setBaseAndExtent(node, from, node, backwards ? node.length : 4);
      edgeHost.scrollTop = edgeHost.scrollHeight;
      release(document.body);
      await settle();
      assert(pill() !== null, 'An edge selection did not offer a discussion');
      inside(pill() as HTMLElement, 'Edge pill');
      (pill() as HTMLElement).click();
      await settle();
      const composer = element<HTMLElement | null>('.agt-disc-pop', edgeHost, 'The edge composer did not open');
      inside(composer, 'Edge composer');
    }
  }, results);
  await clear();
  flushSync(() => edgeRoot.unmount());
  edgeHost.remove();

  await clear();
  await scenario('tints an anchored passage and opens its thread when clicked', async () => {
    const opened: string[] = [];
    const marked = document.createElement('div');
    document.body.append(marked);
    flushSync(() => createRoot(marked).render(
      <DiscussableAnswer turnId="turn-10" text={ANSWER} onOpen={id => opened.push(id)} discussions={[{
        id: 'd1', taskId: 't1', turnId: 'turn-10', quote: 'descendant whose name matches the leaf', occurrence: 1,
        resolved: false, pending: false, providerLane: 'claude', version: 0, comment: null, replyDraft: null, replyDraftError: null,
        createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z', messages: [],
      }]} />));
    await settle();
    const mark = element<HTMLElement | null>('button.agt-disc-mark', marked, 'The anchored passage is not marked');
    assert(mark.textContent === 'descendant whose name matches the leaf', `The mark reads "${mark.textContent ?? ''}"`);
    const style = getComputedStyle(mark);
    assert(style.backgroundColor !== 'rgba(0, 0, 0, 0)', 'The mark has no tint');
    assert(style.userSelect === 'text', `A drag cannot select the mark: user-select is ${style.userSelect}`);
    mark.click();
    await settle();
    assert(opened.join() === 'd1', `Clicking the mark opened ${opened.join() || 'nothing'}`);
    marked.remove();
  }, results);

  await clear();
  await scenario('jumps to the passage itself, at the top of the transcript', async () => {
    const scroller = document.createElement('div');
    scroller.className = 'agt-transcript';
    scroller.style.height = '300px';
    // A turn runs far past one screen, which is what used to hide the passage when the jump
    // scrolled to the turn and centred it.
    scroller.innerHTML = '<section class="agt-turn" data-history-anchor="turn-10">'
      + '<div class="agt-user"><div class="agt-user__bubble">Ask</div></div>'
      + '<p style="height:900px">Log</p><div class="agt-answer"></div><p style="height:900px">After</p></section>';
    document.body.append(scroller);
    flushSync(() => createRoot(scroller.querySelector('.agt-answer') as HTMLElement).render(
      <DiscussableAnswer turnId="turn-10" text={ANSWER} discussions={[{
        id: 'd2', taskId: 't1', turnId: 'turn-10', quote: 'descendant whose name matches the leaf', occurrence: 1,
        resolved: false, pending: false, providerLane: 'claude', version: 0, comment: null, replyDraft: null, replyDraftError: null,
        createdAt: '2026-09-22T00:00:00Z', updatedAt: '2026-09-22T00:00:00Z', messages: [],
      }]} />));
    await settle();
    scroller.scrollTop = 0;
    revealPassage({ turnId: 'turn-10', discussionId: 'd2' });
    await settle();
    const mark = element<HTMLElement | null>('button.agt-disc-mark[data-discussion="d2"]', scroller, 'The jump target is not marked');
    const top = mark.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    assert(Math.abs(top - 72) <= 8, `The passage landed ${Math.round(top)}px below the top, not the 72 it asks for`);
    scroller.remove();
  }, results);

  await clear();
  await scenario('keeps the question and says why while the task cannot take it', async () => {
    const busyHost = document.createElement('div');
    document.body.append(busyHost);
    const busyRoot = createRoot(busyHost);
    const render = (busy: boolean): void => flushSync(() => busyRoot.render(
      <DiscussableAnswer turnId="turn-busy" text="A passage asked about while another answer runs." discussions={[]} busy={busy}
        onSelect={async () => { throw new Error("Error invoking remote method 'agent:discussions:open': Error: This task is already answering a question"); }} />));
    try {
      render(true);
      await settle();
      const text = busyHost.querySelector('.agt-md p')?.firstChild;
      assert(text instanceof Text, 'The busy answer did not render');
      window.getSelection()?.setBaseAndExtent(text, 2, text, 9);
      release(document.body);
      await settle();
      (busyHost.querySelector('.agt-disc-pill') as HTMLElement).click();
      await settle();
      const field = busyHost.querySelector('.agt-disc-pop textarea') as HTMLTextAreaElement;
      changeInput(field, 'why?');
      await settle();
      const send = busyHost.querySelector('.agt-disc-pop [aria-label="Ask"]') as HTMLButtonElement;
      assert(send.disabled && send.title === 'The agent is answering another question on this task', 'Ask stayed open while the task was busy');
      render(false);
      await settle();
      assert(!send.disabled, 'Ask stayed disabled once the task was free');
      send.click();
      await settle();
      assert(busyHost.querySelector('.agt-disc-pop') !== null && field.value === 'why?', 'A refused question lost its composer or its draft');
      const reason = busyHost.querySelector('.agt-disc-pop__refusal')?.textContent;
      assert(reason === 'This task is already answering a question', `The refusal read "${reason ?? ''}"`);
    }
    finally {
      flushSync(() => busyRoot.unmount());
      busyHost.remove();
    }
  }, results);

  return results;
}
