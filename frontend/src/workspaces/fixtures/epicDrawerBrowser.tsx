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
import EpicDrawer from '../EpicDrawer';
import { LayerCovered } from '../../Zoom';
import { ZOOM_REFERENCE } from '../../githubReference';
import { issueAttentionItem, type AttentionItem } from '../../home/attention';
import { jiraAttentionItem } from '../../jira/attention';
import type { JiraRelatedIssue } from '../../jira/contracts';
import { jiraDetail, jiraIssue } from '../../jira/fixtures/jira';
import type { Bridge } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/window-drag.css';

declare global {
  interface Window { epicInput: (event: Record<string, unknown>) => Promise<void> }
}

const issue = (number: number, title: string, isEpic: boolean, updatedAt: string): AttentionItem => ({ ...issueAttentionItem({
  id: number, number, title, developmentTask: null, body: '', author: 'wren-d', authorAvatarUrl: '', htmlUrl: '', labels: [], assignees: [],
  commentCount: 0, createdAt: updatedAt, updatedAt }, 'trinodb/trino', 'workspace'), isEpic });
const ticket = (id: string, title: string, isEpic: boolean, hasChildren = false): AttentionItem => ({ ...jiraAttentionItem({
  ...jiraIssue, id, key: `TRINO-${id}`, title, hasChildren, status: { id: 'progress', name: 'In Progress', category: 'indeterminate' } }), isEpic });
const LONG = 'Hive ACID v2 writes for partitioned and bucketed tables, preserving transactional properties through compaction and concurrent inserts';
const EPICS = [
  issue(24918, 'Iceberg REST catalog: cache metadata and invalidate across coordinators', true, '2026-10-02T08:00:00Z'),
  ticket('1184', 'Parquet reader: skip unselected values and decode dictionaries lazily', true, true),
  issue(23077, 'Pin every product-test container image', true, '2026-09-30T08:00:00Z'),
  ticket('1022', LONG, true),
  issue(22950, 'ORC writer memory accounting', true, '2026-09-21T08:00:00Z'),
  ticket('998', 'Delta Lake deletion vectors in the native reader', true),
];
const PLAIN = [issue(30303, 'Skip unselected values in Parquet projections', false, '2026-10-03T08:00:00Z'), ticket('1300', 'Not an Epic', false)];

function element<T extends HTMLElement = HTMLElement>(selector: string): T {
  const found = document.querySelector<T>(selector);
  assert(found !== null, `Missing ${selector}`);
  return found;
}
async function mouse(type: string, x: number, y: number): Promise<void> {
  await window.epicInput({ type, x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
  await settle();
}
async function click(x: number, y: number): Promise<void> {
  await mouse('mouseMove', x, y);
  await mouse('mouseDown', x, y);
  await mouse('mouseUp', x, y);
}
async function key(keyCode: string): Promise<void> {
  await window.epicInput({ type: 'keyDown', keyCode });
  if (keyCode === 'Enter') await window.epicInput({ type: 'char', keyCode: '\r' });
  await window.epicInput({ type: 'keyUp', keyCode });
  await settle();
}
async function wait(milliseconds: number): Promise<void> {
  await new Promise(resolve => window.setTimeout(resolve, milliseconds));
  await settle();
}
const opened = (): boolean => element('.epic-drawer').classList.contains('is-open');
const panel = (): HTMLElement => element('.epic-drawer__panel');
async function still(): Promise<void> {
  await Promise.all(panel().getAnimations().map(animation => animation.finished));
  await settle();
}

export async function run(): Promise<boolean> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const zoomed: unknown[] = [];
  window.addEventListener(ZOOM_REFERENCE, event => zoomed.push((event as CustomEvent).detail));
  const children = Array.from({ length: 6 }, (_, index): JiraRelatedIssue => ({ id: `c${index}`, key: `TRINO-${2000 + index}`, title: 'Child', type: 'Story',
    status: { id: 'todo', name: 'To Do', category: 'new' }, priority: null, assignee: null }));
  window.bridge = {
    getJiraIssue: async ({ issueId }: { issueId: string }) => ({ ...jiraDetail(), children: issueId === '1184' ? children : [] }),
  } as unknown as Bridge;
  const render = (items: AttentionItem[], covered = false): void => flushSync(() => root.render(<LayerCovered.Provider value={covered}>
    <div className="app-shell">
      <nav className="app-sidebar"><button type="button" id="sidebar-control">Workspace home</button><div className="app-sidebar__resize" /></nav>
      <main className="app-content">
        <div style={{ position: 'relative', height: '100%' }}>
          <EpicDrawer items={items} />
          <div style={{ padding: '24px 32px' }}><h1>trinodb/trino</h1><button type="button" id="page-control">Composer</button></div>
        </div>
      </main>
    </div>
  </LayerCovered.Provider>));
  try {
    render(PLAIN); await settle();
    assert(document.querySelector('.epic-drawer, .epic-cue') === null, 'A workspace without Epics has no cue, hit target or tab stop');

    render([...PLAIN, ...EPICS]); await settle();
    const pane = element('main').getBoundingClientRect();
    const cue = element<HTMLButtonElement>('.epic-cue');
    const at = cue.getBoundingClientRect();
    assert(at.left === pane.left && at.top === pane.top + 40 && at.width === 22 && at.height === 64,
      `The trigger sits on the content side of the hairline, below the window controls: ${JSON.stringify(at.toJSON())}`);
    assert(document.querySelectorAll('.epic-cue__tick').length === 4, 'Six Epics show four ticks');
    assert(cue.getAttribute('aria-label') === 'Epics · 6' && cue.getAttribute('aria-expanded') === 'false', 'The trigger names the Epic count');
    assert(document.elementFromPoint(pane.left + 1, at.top + 30)?.closest('.epic-cue') === cue, 'The trigger owns the content side of the hairline');
    assert(document.elementFromPoint(pane.left - 2, at.top + 30)?.classList.contains('app-sidebar__resize') === true, 'Sidebar resizing keeps its band on the sidebar side');
    assert(getComputedStyle(panel()).visibility === 'hidden', 'The collapsed drawer is out of the tab order');
    const control = element('#page-control').getBoundingClientRect();

    // A pointer sweeping past the edge never opens it.
    await mouse('mouseMove', at.left + 10, at.top + 30);
    await wait(60);
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(300);
    assert(!opened(), 'Leaving before the delay cancels the open');

    await mouse('mouseMove', at.left + 10, at.top + 30);
    assert(!opened(), 'Hover waits before opening');
    // Mid-transition either way: growing during the delay, or shrinking back as the drawer comes out.
    await wait(40);
    assert(element('.epic-cue__tick').getBoundingClientRect().width > 5 && Number(getComputedStyle(element('.epic-tip')).opacity) > 0, 'Hover widens the ticks and names them');
    await wait(100);
    assert(opened() && cue.getAttribute('aria-expanded') === 'true', 'Hover opens the drawer after the delay');
    await still();
    const out = panel().getBoundingClientRect();
    assert(out.left === pane.left && out.top === at.top && out.width === Math.max(260, Math.min(320, pane.width - 120)),
      `The drawer is anchored to the hairline at its clamped width: ${JSON.stringify(out.toJSON())}`);
    assert(JSON.stringify(element('#page-control').getBoundingClientRect()) === JSON.stringify(control), 'Opening never moves the page');
    await wait(500);
    assert(opened(), 'The trigger and the panel sliding over it form one hover region');

    const text = panel().textContent ?? '';
    assert(text.includes('Epics') && element('.epic-drawer__head .pill').textContent === '6', 'The header counts the Epics');
    assert(!/\bRun\b/.test(text), 'Epics never offer Run');
    const cards = [...panel().querySelectorAll<HTMLElement>('.epic-card')];
    assert(cards.length === 6 && cards[0].textContent?.includes('#24918') === true && cards[0].textContent.includes('Open'), 'An issue Epic shows its number and state');
    assert(cards[1].textContent?.includes('TRINO-1184') === true && cards[1].textContent.includes('In Progress'), 'A Jira Epic shows its key and status');
    assert(cards[1].querySelector('.epic-card__children')?.getAttribute('title') === '6 child items', 'A Jira Epic counts its children from the saved detail');
    assert(cards[0].querySelector('.epic-card__children') === null && cards[3].querySelector('.epic-card__children') === null, 'No child count without child data');
    const title = cards[3].querySelector<HTMLElement>('.epic-card__title');
    assert(title !== null && title.getBoundingClientRect().height <= 2 * 13 * 1.35 + 1 && cards[3].title === LONG, 'Long titles clamp to two lines with the full title on hover');

    // Leaving starts a grace period; coming back during it keeps the drawer.
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(150);
    assert(opened(), 'The drawer waits after the pointer leaves');
    await mouse('mouseMove', out.left + 100, out.top + 100);
    await wait(400);
    assert(opened(), 'Coming back during the grace period keeps it open');
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(320);
    assert(!opened(), 'The drawer retracts after the grace period');
    await mouse('mouseMove', at.left + 10, at.top + 30);
    assert(opened(), 'Coming back during the retract reverses it at once');
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(320); await still();
    assert(!opened() && getComputedStyle(panel()).visibility === 'hidden', 'The retracted drawer hides again');

    // A click opens at once and pins it until a click outside.
    await click(at.left + 10, at.top + 30);
    assert(opened(), 'A click opens the drawer at once');
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(500);
    assert(opened(), 'A pinned drawer stays open after the pointer leaves');
    await click(pane.left + 600, pane.top + 500);
    assert(!opened(), 'A click outside closes a pinned drawer');
    await wait(250);

    // The keyboard opens it from the trigger, Tab moves inside, and Escape returns to the trigger.
    element('#sidebar-control').focus(); await settle();
    await key('Tab');
    assert(document.activeElement === cue && !opened(), 'The trigger is the next tab stop after the sidebar, and focus alone does not open it');
    await key('Enter');
    assert(opened(), 'Return opens the drawer');
    await still();
    await key('Tab');
    assert(document.activeElement?.getAttribute('aria-label') === 'Close Epics', 'Tab moves into the drawer');
    await key('Escape');
    assert(!opened() && document.activeElement === cue, 'Escape closes the drawer and returns focus to the trigger');
    await wait(250);

    await click(at.left + 10, at.top + 30); await still();
    const close = element('[aria-label="Close Epics"]').getBoundingClientRect();
    await click(close.left + close.width / 2, close.top + close.height / 2);
    assert(!opened() && document.activeElement === cue, 'Close puts the drawer away and returns focus to the trigger');
    await wait(250);

    await click(at.left + 10, at.top + 30); await still();
    cards[1].click(); cards[0].click();
    assert(JSON.stringify(zoomed) === JSON.stringify([{ kind: 'jira', connectionId: 'connection', cloudId: 'site', issueId: '1184' },
      { kind: 'issue', repo: 'trinodb/trino', number: 24918, workspaceId: 'workspace' }]), `Epics open their issue or ticket: ${JSON.stringify(zoomed)}`);
    render([...PLAIN, ...EPICS], true); await settle();
    assert(!opened(), 'A layer over the page puts the drawer away');
    render([...PLAIN, ...EPICS]); await wait(250);

    await click(at.left + 10, at.top + 30);
    render(PLAIN); await settle();
    assert(document.querySelector('.epic-drawer') === null, 'Unmarking the last Epic removes the drawer');
    render(EPICS.slice(0, 1)); await settle();
    assert(!opened() && document.querySelectorAll('.epic-cue__tick').length === 1, 'A first Epic marked again shows one tick, closed');

    // Many Epics in a short, narrow window: the width clamps and the list scrolls under the header.
    const shell = element('.app-shell');
    shell.style.width = '640px'; shell.style.height = '640px';
    render([...EPICS, ...EPICS.map(item => ({ ...item, key: `${item.key}-again` }))]); await settle();
    await click(at.left + 10, at.top + 30); await still();
    const narrow = element('main').getBoundingClientRect();
    const small = panel().getBoundingClientRect();
    const list = element('.epic-drawer__list');
    assert(small.width === Math.max(260, Math.min(320, narrow.width - 120)) && small.height <= 480 && list.scrollHeight > list.clientHeight,
      `A short, narrow window clamps the drawer and scrolls its list: ${JSON.stringify(small.toJSON())}`);
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
