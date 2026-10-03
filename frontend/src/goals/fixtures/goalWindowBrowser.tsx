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
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/window-drag.css';

declare global {
  interface Window { goalMouse: (event: { type: string; x: number; y: number; button?: string; clickCount?: number; modifiers?: string[] }) => Promise<void> }
}

const goal: GoalDto = { id: 'goal', workspaceId: 'workspace', title: 'Iceberg connector expertise', description: 'Learn, investigate and contribute.',
  pinned: true, source: null, definition: null, revision: 0, course: null, items: [], recommendations: [], createdAt: '', updatedAt: '' };
const unexpected = async (): Promise<never> => { throw new Error('Unexpected write'); };
const goals: Goals = { goals: [goal], pinned: goal, workItems: [], error: null, create: async () => goal, pin: async () => {},
  remove: async () => {}, add: async () => {}, removeItem: async () => {}, search: async () => [],
  createWork: unexpected, decideWork: unexpected, linkWork: unexpected,
  start: unexpected, exchanges: async () => [], ask: unexpected, retry: unexpected, accept: unexpected,
  acceptCourse: unexpected, startCard: unexpected, dismissCard: unexpected, knowledge: async () => [], refresh: () => {},
  openLesson: unexpected, readLesson: unexpected, finishLesson: unexpected };

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
async function mouse(type: string, x: number, y: number, down = false): Promise<void> {
  await window.goalMouse({ type, x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1, modifiers: down ? ['leftButtonDown'] : [] });
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
  const host = document.createElement('div');
  host.style.cssText = 'position:absolute;inset:0';
  document.body.append(host);
  localStorage.removeItem('bq.goal.pill.home');
  const root = createRoot(host);
  const render = (page = 'home', covered = false): void => flushSync(() => root.render(<LayerCovered.Provider value={covered}>
    <GoalLayer page={page} workspaceId="workspace" repository="trinodb/trino" goals={goals} needsYou={new Set()} onOpenTask={() => {}}>
      <main style={{ height: '100%', padding: 32 }}><h1>Workspace home</h1><p>The page stays in place while the goal is open.</p></main>
    </GoalLayer>
  </LayerCovered.Provider>));
  try {
    render(); await settle();
    assert(document.querySelector('.goal-window') === null, 'Wide Home starts with the Goal pill');
    const pageWidth = element('main').getBoundingClientRect().width;
    let pane = await open();
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

    assert(pane.textContent?.includes('What does “Iceberg connector expertise” mean to you?') === true, 'A goal without a conversation opens on its question');
    button('Work').click(); await settle();
    button('New work item').click(); await settle();
    changeInput(element<HTMLTextAreaElement>('.goal-work-form textarea'), 'Keep this draft'); await settle();
    const form = element('.goal-work-form');
    button('Expand goal').click(); await settle();
    assert(element('.goal-work-form') === form && document.querySelectorAll('[role="dialog"]').length === 1, 'Expansion preserves one mounted editor');
    assert(pane.getBoundingClientRect().width > resized.width, 'Expansion uses the available page');
    button('Restore goal window').click(); await settle();
    assert(Math.abs(pane.getBoundingClientRect().width - resized.width) < 2, 'Restore remembers the resized window');

    element<HTMLButtonElement>('.goal-switch').click(); await settle();
    button('Archive this goal').click(); await settle();
    await mouse('mouseMove', 2, 2);
    assert(!pane.hidden && document.querySelector('dialog[open]') !== null, 'A confirmation is not dismissed by mouse leave');
    button('Cancel').click(); await settle();
    await mouse('mouseMove', 3, 3);
    assert(pane.hidden && pane.inert, 'Leaving Goal closes it and removes keyboard access');
    pane = await open();
    assert(element<HTMLTextAreaElement>('.goal-work-form textarea').value === 'Keep this draft', 'Reopening preserves an unsaved draft');

    render('home', true); await settle();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await mouse('mouseMove', 4, 4);
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
    assert(pane.hidden, 'Leaving during the opening animation also dismisses Goal');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
