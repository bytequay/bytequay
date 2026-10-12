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
import { GoalLayer } from '../GoalLayer';
import { LayerCovered } from '../../Zoom';
import type { Goals } from '../useGoals';
import type { GoalDto } from '../contracts';
import type { AppNoticeBounds } from '../../notices/contracts';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/window-drag.css';

declare global {
  interface Window { goalMouse: (event: { type: string; x: number; y: number; button?: string; clickCount?: number; modifiers?: string[] }) => Promise<void> }
}

const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Iceberg connector expertise', description: 'Learn, investigate and contribute.',
  pinned: true, paused: false, source: null, definition: null, revision: 0, course: null, items: [], recommendations: [], topics: [], createdAt: '', updatedAt: '' };
const unexpected = async (): Promise<never> => { throw new Error('Unexpected write'); };
const renamed: string[] = [];
const deleted: string[] = [];
let deleteFails = true;
const goals: Goals = { goals: [goal], pinned: goal, workItems: [], error: null, create: async () => goal, pin: async () => {},
  rename: async (_goalId, title) => { renamed.push(title); return { ...goal, title }; },
  remove: async (goalId) => { if (deleteFails) throw new Error('Deletion unavailable'); deleted.push(goalId); },
  add: async () => {}, removeItem: async () => {}, search: async () => [],
  createWork: unexpected, decideWork: unexpected, linkWork: unexpected, orderWork: unexpected,
  start: unexpected, exchanges: async () => [], ask: unexpected, retry: unexpected, stop: unexpected, accept: unexpected,
  acceptPlan: async () => { throw new Error('No plan change'); },
  acceptCourse: unexpected, startCard: unexpected, dismissCard: unexpected, knowledge: async () => [], rememberNote: unexpected, gaps: async () => [], addGap: unexpected,
  openGap: unexpected, refresh: () => {}, model: unexpected, chooseModel: unexpected, pause: unexpected, reassess: unexpected, summarize: unexpected,
  openLesson: unexpected, readLesson: unexpected, finishLesson: unexpected, setTopic: unexpected, researchRun: unexpected, stopResearch: unexpected };

function element<T extends HTMLElement = HTMLElement>(selector: string): T {
  const found = document.querySelector<T>(selector);
  assert(found !== null, `Missing ${selector}`);
  return found;
}
function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find(value => value.getAttribute('aria-label') === label || value.textContent?.trim() === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}
function setInput(input: HTMLInputElement, value: string): void {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function press(target: Element, key: string): void {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
}
async function mouse(type: string, x: number, y: number, down = false): Promise<void> {
  await window.goalMouse({ type, x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1, modifiers: down ? ['leftButtonDown'] : [] });
  await settle();
}
async function wait(milliseconds: number): Promise<void> {
  await new Promise(resolve => window.setTimeout(resolve, milliseconds));
  await settle();
}
async function drag(from: { x: number; y: number }, dx: number, dy: number): Promise<void> {
  await mouse('mouseMove', from.x, from.y);
  await mouse('mouseDown', from.x, from.y, true);
  await mouse('mouseMove', from.x + dx, from.y + dy, true);
  await mouse('mouseUp', from.x + dx, from.y + dy);
}
async function open(): Promise<HTMLElement> {
  element<HTMLButtonElement>('.goal-pill').click();
  await settle();
  const pane = element('.goal-window');
  await Promise.all(pane.getAnimations().map(animation => animation.finished));
  assert(!pane.hidden, 'Goal opens');
  return pane;
}

export async function run(): Promise<boolean> {
  const previousBridge = window.bridge;
  let noticeBounds: (bounds: AppNoticeBounds) => void = () => {};
  window.bridge = { ...previousBridge, onAppNoticeBounds: listener => { noticeBounds = listener; return () => {}; } };
  const host = document.createElement('div');
  host.style.cssText = 'position:absolute;inset:0';
  document.body.append(host);
  localStorage.removeItem('bq.goal.pill.home');
  const root = createRoot(host);
  const render = (page = 'home', covered = false, shown: Goals = goals): void => flushSync(() => root.render(<LayerCovered.Provider value={covered}>
    <GoalLayer page={page} workspaceId="workspace" repository="trinodb/trino" goals={shown} needsYou={new Set()} onOpenTask={() => {}} onStartIssue={() => {}} onSpecCheck={() => {}}>
      <main style={{ height: '100%', padding: 32 }}><h1>Workspace home</h1><p>The page stays in place while the goal is open.</p></main>
    </GoalLayer>
  </LayerCovered.Provider>));
  try {
    render(); await settle();
    assert(document.querySelector('.goal-window') === null, 'Wide Home starts with the Goal pill');
    // A native overlay may swallow the release before React receives it. Losing capture still ends the drag.
    const dragPill = element('.goal-pill');
    const start = dragPill.getBoundingClientRect();
    await mouse('mouseMove', start.left + 12, start.top + 18);
    await mouse('mouseDown', start.left + 12, start.top + 18, true);
    await mouse('mouseMove', start.left - 88, start.top - 62, true);
    const released = dragPill.getBoundingClientRect();
    window.addEventListener('pointerup', event => event.stopImmediatePropagation(), { capture: true, once: true });
    await mouse('mouseUp', released.left + 12, released.top + 18);
    assert(!dragPill.classList.contains('is-dragging'), 'Losing pointer capture ends a drag even when the release is missed');
    await mouse('mouseMove', released.left + 32, released.top + 28);
    assert(Math.abs(dragPill.getBoundingClientRect().left - released.left) < 2
      && Math.abs(dragPill.getBoundingClientRect().top - released.top) < 2, 'The released pill stays put instead of following hover');
    assert(document.querySelector('.goal-window') === null, 'Recovering a missed release does not open the goal');
    for (const ending of ['hover', 'blur', 'cancel']) {
      const before = dragPill.getBoundingClientRect();
      let pointerId = 0;
      dragPill.addEventListener('pointerdown', event => { pointerId = event.pointerId; }, { once: true });
      await mouse('mouseMove', before.left + 12, before.top + 18);
      await mouse('mouseDown', before.left + 12, before.top + 18, true);
      await mouse('mouseMove', before.left - 18, before.top - 2, true);
      const dropped = dragPill.getBoundingClientRect();
      if (ending === 'hover') await mouse('mouseMove', dropped.left + 32, dropped.top + 28);
      else {
        if (ending === 'blur') window.dispatchEvent(new Event('blur'));
        else dragPill.dispatchEvent(new PointerEvent('pointercancel', { pointerId, bubbles: true }));
        await settle();
      }
      assert(!dragPill.classList.contains('is-dragging'), `${ending} ends the active drag`);
      await mouse('mouseMove', dropped.left + 42, dropped.top + 28, ending !== 'hover');
      assert(Math.abs(dragPill.getBoundingClientRect().left - dropped.left) < 2
        && Math.abs(dragPill.getBoundingClientRect().top - dropped.top) < 2, `${ending} keeps the last dragged position`);
      await mouse('mouseUp', 2, 2);
    }
    const clickable = dragPill.getBoundingClientRect();
    await mouse('mouseMove', clickable.left + 12, clickable.top + 18);
    await mouse('mouseDown', clickable.left + 12, clickable.top + 18, true);
    await mouse('mouseUp', clickable.left + 12, clickable.top + 18);
    assert(!element('.goal-window').hidden, 'A deliberate click still opens the goal after interrupted drags');
    button('Close the goal').click(); await settle();
    const pageWidth = element('main').getBoundingClientRect().width;
    let pane = await open();
    assert(document.activeElement === element('.goal-composer textarea'), 'A goal without a conversation opens ready for typing');
    assert(element('main').getBoundingClientRect().width === pageWidth, 'Opening Goal does not squeeze the page');
    assert(document.querySelectorAll('[role="dialog"]').length === 1, 'Only one Goal panel exists');
    assert(getComputedStyle(pane).getPropertyValue('-webkit-app-region') === 'no-drag', 'Goal drag does not move the desktop window');
    const pill = element('.goal-pill').getBoundingClientRect();
    const initial = pane.getBoundingClientRect();
    assert(pill.left >= initial.left && pill.right <= initial.right && pill.bottom <= initial.bottom, 'Goal grows around its pill');

    // Real input exercises pointer capture and the browser's native resize grip.
    await drag({ x: initial.left + initial.width / 2, y: initial.top + 18 }, -250, -100);
    const moved = pane.getBoundingClientRect();
    assert(Math.abs(moved.left - initial.left + 250) < 2 && Math.abs(moved.top - initial.top + 100) < 2 && !pane.hidden, 'Header drags the open window');
    await drag({ x: moved.right - 4, y: moved.bottom - 4 }, 120, 60);
    const resized = pane.getBoundingClientRect();
    assert(resized.width > moved.width + 100 && resized.height > moved.height + 40 && !pane.hidden, 'Native grip enlarges the open window');

    assert(pane.textContent?.includes('What do you want to achieve?') === true && pane.textContent.includes('mean to you') === false,
      'A goal without a conversation asks what the user wants, not about its title');

    element<HTMLButtonElement>('button.goal-panel__title').click(); await settle();
    let name = element<HTMLInputElement>('input.goal-panel__title');
    assert(document.activeElement === name && name.value === 'Iceberg connector expertise', 'Clicking the title edits it in place');
    setInput(name, 'Trino Iceberg connector expert'); await settle();
    press(name, 'Escape'); await settle();
    assert(renamed.length === 0 && !pane.hidden && document.querySelector('input.goal-panel__title') === null, 'Escape keeps the name and the window');
    element<HTMLButtonElement>('button.goal-panel__title').click(); await settle();
    name = element<HTMLInputElement>('input.goal-panel__title');
    setInput(name, 'Trino Iceberg connector expert'); await settle();
    press(name, 'Enter'); await settle(); await settle();
    assert(renamed.join() === 'Trino Iceberg connector expert' && document.querySelector('input.goal-panel__title') === null, 'Enter saves the new name once');

    element<HTMLButtonElement>('.goal-switch').click(); await settle();
    button('New goal').click(); await settle();
    assert(document.querySelector('.goal-panel__title') === null && pane.textContent?.includes('What do you want to achieve?') === true
      && element('.goal-pill').textContent?.includes('Iceberg connector expertise') === true, 'New goal asks what the user wants while the pill keeps the current goal');
    assert(document.activeElement === element('.goal-composer textarea'), 'New goal is ready for typing');
    button('Back to the current goal').click(); await settle();
    assert(element('.goal-panel__title').textContent === 'Iceberg connector expertise', 'Back returns to the current goal');

    button('Work').click(); await settle();
    button('New work item').click(); await settle();
    changeInput(element<HTMLTextAreaElement>('.goal-work-form textarea'), 'Keep this draft'); await settle();
    const form = element('.goal-work-form');
    button('Expand goal').click(); await settle();
    assert(element('.goal-work-form') === form && document.querySelectorAll('[role="dialog"]').length === 1, 'Expansion preserves one mounted editor');
    assert(pane.getBoundingClientRect().width > resized.width, 'Expansion uses the available page');
    button('Restore goal window').click(); await settle();
    assert(Math.abs(pane.getBoundingClientRect().width - resized.width) < 2, 'Restore remembers the resized window');

    button('Cancel goal').click(); await settle();
    await mouse('mouseMove', 2, 2);
    assert(!pane.hidden && document.querySelector('dialog[open]') !== null, 'A confirmation is not dismissed by mouse leave');
    let confirmation = element<HTMLInputElement>('dialog input');
    for (const value of ['', 'Cancel', 'cance', ' cancel', 'cancel ']) {
      setInput(confirmation, value); await settle();
      const confirm = button('Confirm delete goal');
      assert(confirm.disabled, `Deletion stays disabled for ${JSON.stringify(value)}`);
      confirm.click(); await settle();
      assert(deleted.length === 0 && document.querySelector('dialog [role="alert"]') === null, 'Invalid confirmation never requests deletion');
    }
    setInput(confirmation, 'cancel'); await settle();
    assert(!button('Confirm delete goal').disabled, 'Typing exactly cancel enables deletion');
    button('Keep goal').click(); await settle();
    assert(deleted.length === 0 && document.querySelector('dialog[open]') === null, 'Keep goal dismisses confirmation without deleting');
    button('Cancel goal').click(); await settle();
    confirmation = element<HTMLInputElement>('dialog input');
    assert(confirmation.value === '' && button('Confirm delete goal').disabled, 'Reopening requires a fresh confirmation');
    setInput(confirmation, 'cancel'); await settle();
    button('Confirm delete goal').click(); await settle();
    assert(element('dialog [role="alert"]').textContent === 'Deletion unavailable' && !button('Confirm delete goal').disabled,
      'A failed deletion keeps the confirmation open for retry');
    deleteFails = false;
    button('Confirm delete goal').click(); await settle();
    assert(deleted.join() === goal.id && document.querySelector('dialog[open]') === null, 'Confirm deletes the selected goal once and closes the dialog');
    await mouse('mouseMove', 3, 3);
    await wait(1_000);
    assert(!pane.hidden && !pane.inert, 'Leaving Goal gives the pointer time to return');
    await mouse('mouseMove', resized.left + 40, resized.top + 40);
    await wait(1_600);
    assert(!pane.hidden, 'Returning to Goal cancels the pending close');
    await mouse('mouseMove', 3, 3);
    await mouse('mouseMove', pill.left + pill.width / 2, pill.top + pill.height / 2);
    await wait(1_600);
    assert(!pane.hidden, 'Returning to the Goal pill also cancels the pending close');
    await mouse('mouseMove', 3, 3);
    await wait(1_000);
    assert(!pane.hidden, 'A later departure gets a fresh delay');
    await mouse('mouseMove', 4, 4);
    await wait(600);
    assert(pane.hidden && pane.inert, 'Staying outside Goal closes it after 1.5 seconds even while the pointer moves');
    pane = await open();
    assert(element<HTMLTextAreaElement>('.goal-work-form textarea').value === 'Keep this draft', 'Reopening preserves an unsaved draft');

    await mouse('mouseMove', 5, 5);
    render('home', true); await settle();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await mouse('mouseMove', 4, 4);
    await wait(1_600);
    assert(!pane.hidden && pane.inert, 'A covering layer owns Escape and pointer dismissal');
    render(); await settle();
    element('.goal-switch').focus();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle();
    assert(pane.hidden && document.activeElement === element('.goal-pill'), 'Escape closes Goal and returns focus');
    window.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyG', altKey: true, bubbles: true })); await settle();
    assert(!pane.hidden, 'The Goal keyboard shortcut opens the window');
    await Promise.all(pane.getAnimations().map(animation => animation.finished));

    host.style.width = '410px'; host.style.height = '500px'; await settle();
    const small = pane.getBoundingClientRect();
    assert(small.left >= 8 && small.top >= 8 && small.right <= 402 && small.bottom <= 492, `Window stays inside a shrinking page: ${JSON.stringify(small.toJSON())}`);
    render('prs'); await settle();
    assert(document.querySelector('.goal-window') === null && element('.goal-pill') !== null, 'Page navigation closes the previous Goal window');
    pane = await open();
    assert(pane.getBoundingClientRect().width <= 394, 'Narrow pages open the same floating Goal');
    button('Close the goal').click(); await settle();
    element<HTMLButtonElement>('.goal-pill').click(); await settle();
    await mouse('mouseMove', 600, 100);
    assert(!pane.hidden, 'Leaving during the opening animation also gets the delay');
    await wait(1_600);
    assert(pane.hidden, 'Staying outside during the opening animation dismisses Goal after the delay');

    // A card hangs from the pill, but never past the page edge beside a pill dropped near it.
    host.style.width = '900px'; host.style.height = '700px';
    localStorage.setItem('bq.goal.pill.issues', JSON.stringify({ right: 680, bottom: 420 }));
    const recommended: GoalDto = { ...goal, recommendations: [{ id: 'card-1', goalId: 'goal', state: 'SHOWN', taskId: null, workItemId: null, checkId: null, createdAt: '',
      card: { kind: 'RESEARCH', title: 'Read how Trino plans a query', reason: 'You want to know the engine before changing it', scope: 'The planner and optimizer',
        pages: [], workItemId: null, issue: null, ticket: null, module: null, reproductions: null } }] };
    render('issues', false, { ...goals, goals: [recommended], pinned: recommended }); await settle();
    const page = element('.goal-layer__page').getBoundingClientRect();
    const card = element('.goal-cards').getBoundingClientRect();
    assert(element('.goal-pill').getBoundingClientRect().left < page.left + 100, 'The pill sits near the page\'s left edge');
    assert(card.left >= page.left + 8 && card.right <= page.right - 8, `The card stays inside the page: ${JSON.stringify(card.toJSON())}`);
    const title = element('.goal-rec__title').getBoundingClientRect();
    assert(title.left - element('.goal-rec').getBoundingClientRect().left >= 12, 'The card keeps its text off its border');

    // Dropped against a side of the page, the pill folds into it as a tab; a click still opens the goal, and dragging it out unfolds it.
    const withCard: Goals = { ...goals, goals: [recommended], pinned: recommended };
    render('jira', false, withCard); await settle();
    const wide = element('.goal-pill').getBoundingClientRect();
    await drag({ x: wide.left + 12, y: wide.top + wide.height / 2 }, page.left + 4 - wide.left - 12, 0);
    let tab = element('.goal-pill');
    assert(tab.classList.contains('is-folded') && tab.classList.contains('is-left') && Math.abs(tab.getBoundingClientRect().left - page.left) < 1,
      'Dropped against the left side, the pill folds into it');
    assert(tab.querySelector('.goal-pill__title') === null && document.querySelector('.goal-cards') === null
      && tab.querySelector('.goal-pill__attention')?.textContent?.trim() === '1', 'The tab hides the name and the card, and counts the card');
    render('issues', false, withCard); await settle();
    render('jira', false, withCard); await settle();
    tab = element('.goal-pill');
    assert(tab.classList.contains('is-folded') && tab.classList.contains('is-left'), 'The page remembers its folded pill');
    tab.click(); await settle();
    assert(!element('.goal-window').hidden, 'A click on the tab opens the goal');
    button('Close the goal').click(); await settle();
    const folded = tab.getBoundingClientRect();
    await drag({ x: folded.left + folded.width / 2, y: folded.top + folded.height / 2 }, 240, 0);
    assert(!element('.goal-pill').classList.contains('is-folded') && document.querySelector('.goal-pill__title') !== null
      && document.querySelector('.goal-cards') !== null, 'Dragged out of the side, the pill unfolds and its card floats again');
    render('notice-overlap'); await settle();
    noticeBounds({ x: 480, y: 440, width: 420, height: 260 }); await settle();
    const clear = element('.goal-pill').getBoundingClientRect();
    assert(clear.bottom <= 430, 'Native notifications lift the goal tab out of their hit area');
    await drag({ x: clear.left + 12, y: clear.top + clear.height / 2 }, -200, -80);
    const movedPill = element('.goal-pill').getBoundingClientRect();
    assert(Math.abs(movedPill.left - clear.left + 200) < 2 && Math.abs(movedPill.top - clear.top + 80) < 2,
      'The exposed goal tab remains draggable while a notification is present');
    noticeBounds(null); await settle();
    assert(Math.abs(element('.goal-pill').getBoundingClientRect().top - movedPill.top) < 2, 'Clearing notices keeps the user\'s new position');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); window.bridge = previousBridge; }
}
