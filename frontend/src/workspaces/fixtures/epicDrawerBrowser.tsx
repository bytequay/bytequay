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
import { createFixtureRoot } from '../../fixtures/reactFixture';
import EpicDrawer from '../EpicDrawer';
import { LayerCovered } from '../../Zoom';
import type { WorkItemDto } from '../../workitems/contracts';
import { issueAttentionItem, type AttentionItem } from '../../home/attention';
import { jiraAttentionItem } from '../../jira/attention';
import type { JiraRelatedIssue } from '../../jira/contracts';
import { jiraDetail, jiraIssue } from '../../jira/fixtures/jira';
import type { Bridge } from '../../types';
import { assert, settle, element } from '../../fixtures/harness';
import { epicIdentity, type EpicItem } from '../epics';
import '../../css/base.css';
import '../../css/window-drag.css';

declare global {
  interface Window { epicInput: (event: Record<string, unknown>) => Promise<void> }
}

const issue = (number: number, title: string, isEpic: boolean, updatedAt: string): AttentionItem => ({ ...issueAttentionItem({
  id: number, number, title, developmentTask: null, body: '', author: 'wren-d', authorAvatarUrl: '', htmlUrl: '', labels: [], assignees: [],
  commentCount: 0, createdAt: updatedAt, updatedAt }, 'owner/repository', 'workspace'), isEpic });
const ticket = (id: string, title: string, isEpic: boolean, hasChildren = false): AttentionItem => ({ ...jiraAttentionItem({
  ...jiraIssue, id, key: `ENG-${id}`, title, hasChildren, status: { id: 'progress', name: 'In Progress', category: 'indeterminate' } }), isEpic });
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
const work = (id: string, epicId: string, state: WorkItemDto['state']): WorkItemDto => ({ id, workspaceId: 'workspace', isEpic: false, contextLinks: [], title: id, kind: 'DEVELOP',
  acceptanceCriteria: '', completionRule: 'MERGED_DELIVERY', state, revision: 0, links: [], history: [], createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z', priority: 'NORMAL', blockedBy: [], epicId, epicRank: 0 });
const ICEBERG_WORK = ['DONE', 'WORKING', 'PLANNED', 'DROPPED'].map((state, index) => work(`iceberg-${index}`, 'ISSUE:github.com:24918', state as WorkItemDto['state']));


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
  const { root, dispose: disposeRoot } = createFixtureRoot();
  const openedEpics: string[] = [];
  let created = 0;
  // Read through a call, so an assertion on one count never narrows the next one.
  const createdCount = (): number => created;
  const children = Array.from({ length: 6 }, (_, index): JiraRelatedIssue => ({ id: `c${index}`, key: `ENG-${2000 + index}`, title: 'Child', type: 'Story',
    status: { id: 'todo', name: 'To Do', category: 'new' }, priority: null, assignee: null }));
  window.bridge = {
    getJiraIssue: async ({ issueId }: { issueId: string }) => ({ ...jiraDetail(), children: issueId === '1184' ? children : [] }),
  } as unknown as Bridge;
  const render = (items: EpicItem[], covered = false, workItems: WorkItemDto[] = ICEBERG_WORK, concluded: ReadonlySet<string> = new Set()): void =>
    flushSync(() => root.render(<LayerCovered.Provider value={covered}>
    <div className="app-shell">
      <nav className="app-sidebar"><button type="button" id="sidebar-control">Workspace home</button><div className="app-sidebar__resize" /></nav>
      <main className="app-content">
        <div style={{ position: 'relative', height: '100%' }}>
          <EpicDrawer items={items} concluded={concluded} work={workItems} onOpen={item => openedEpics.push(epicIdentity(item))} onCreate={() => { created++; }} />
          <div style={{ padding: '24px 32px' }}><h1>owner/repository</h1><button type="button" id="page-control">Composer</button></div>
        </div>
      </main>
    </div>
  </LayerCovered.Provider>));
  try {
    render(PLAIN); await settle();
    const pane = element('main').getBoundingClientRect();
    const empty = element<HTMLButtonElement>('.epic-cue.is-empty');
    const spot = empty.getBoundingClientRect();
    assert(empty.getAttribute('aria-label') === 'Create Epic' && document.querySelectorAll('.epic-cue__tick').length === 1
      && spot.left === pane.left && spot.top === pane.top + 40, 'A workspace without Epics shows one hollow tick at the hairline that offers Create Epic');
    const offer = element('.epic-create');
    assert(getComputedStyle(offer).visibility === 'hidden', 'Create Epic waits for the pointer');
    await mouse('mouseMove', spot.left + 10, spot.top + 30);
    await wait(150);
    assert(getComputedStyle(offer).visibility === 'visible', 'The pointer near the tick shows Create Epic');
    const label = element('.epic-create__label').getBoundingClientRect();
    assert(label.left >= spot.right && label.left - spot.right <= 6, 'Create Epic sits right beside the tick');
    await mouse('mouseMove', spot.right + 1, spot.top + 30);
    await mouse('mouseMove', label.left + label.width / 2, label.top + label.height / 2);
    await wait(150);
    assert(getComputedStyle(offer).visibility === 'visible', 'Moving onto Create Epic keeps it shown');
    await click(label.left + label.width / 2, label.top + label.height / 2);
    assert(createdCount() === 1, 'Clicking Create Epic asks to create one');
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(200);
    assert(getComputedStyle(offer).visibility === 'hidden', 'Leaving hides Create Epic again');

    render([...PLAIN, ...EPICS]); await settle();
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

    // Timing comes from when the drawer's class changed, never from when a check happens to run on a loaded machine.
    let openedAt = 0;
    const watcher = new MutationObserver(() => { if (openedAt === 0 && opened()) openedAt = performance.now(); });
    watcher.observe(element('.epic-drawer'), { attributes: true, attributeFilter: ['class'] });

    // A pointer sweeping past the edge never opens it.
    const swept = performance.now();
    await mouse('mouseMove', at.left + 10, at.top + 30);
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    const sweep = performance.now() - swept;
    await wait(300);
    assert(sweep > 140 || openedAt === 0, `Leaving before the delay cancels the open (swept in ${Math.round(sweep)}ms)`);
    if (openedAt !== 0) await wait(500);
    assert(!opened(), 'The swept drawer is closed');

    // Ticks widen and name themselves while the open waits; sampled every frame, then the drawer opens only after its delay.
    openedAt = 0;
    let widest = 0;
    let named = 0;
    let sampling = true;
    const sample = (): void => {
      widest = Math.max(widest, element('.epic-cue__tick').getBoundingClientRect().width);
      named = Math.max(named, Number(getComputedStyle(element('.epic-tip')).opacity));
      if (sampling) requestAnimationFrame(sample);
    };
    const entered = performance.now();
    requestAnimationFrame(sample);
    await mouse('mouseMove', at.left + 10, at.top + 30);
    await wait(250);
    sampling = false;
    watcher.disconnect();
    assert(opened() && cue.getAttribute('aria-expanded') === 'true', 'Hover opens the drawer');
    assert(openedAt - entered >= 145, `Hover waits before opening (${Math.round(openedAt - entered)}ms)`);
    assert(widest > 5 && named > 0, 'Hover widens the ticks and names them');
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
    assert(cards[1].textContent?.includes('ENG-1184') === true && cards[1].textContent.includes('In Progress'), 'A Jira Epic shows its key and status');
    assert(cards[1].querySelector('.epic-card__children')?.getAttribute('title') === '6 child items', 'A Jira Epic counts its children from the saved detail');
    assert(cards[0].querySelector('.epic-card__children') === null && cards[3].querySelector('.epic-card__children') === null, 'No child count without child data');
    const title = cards[3].querySelector<HTMLElement>('.epic-card__title');
    assert(title !== null && title.getBoundingClientRect().height <= 2 * 13 * 1.35 + 1 && cards[3].title === LONG, 'Long titles clamp to two lines with the full title on hover');

    // Leaving starts a grace period; coming back during it keeps the drawer. As above, timing comes from when each
    // pointer move reached the page and when the class changed; a move that arrives too late cannot test its window.
    let movedAt = 0;
    let closedAt = 0;
    const moved = (): void => { movedAt = performance.now(); };
    window.addEventListener('pointermove', moved, true);
    const closer = new MutationObserver(() => { if (!opened()) closedAt = performance.now(); });
    closer.observe(element('.epic-drawer'), { attributes: true, attributeFilter: ['class'] });
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    const leftAt = movedAt;
    await wait(150);
    assert(opened() || closedAt - leftAt >= 290, `The drawer waits after the pointer leaves (closed after ${Math.round(closedAt - leftAt)}ms)`);
    await mouse('mouseMove', out.left + 100, out.top + 100);
    const backAt = movedAt;
    await wait(400);
    assert(opened() || backAt - leftAt >= 250, `Coming back during the grace period keeps it open (back after ${Math.round(backAt - leftAt)}ms)`);
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(320);
    assert(!opened(), 'The drawer retracts after the grace period');
    await mouse('mouseMove', at.left + 10, at.top + 30);
    assert(opened() || movedAt - closedAt >= 150, `Coming back during the retract reverses it at once (back after ${Math.round(movedAt - closedAt)}ms)`);
    await mouse('mouseMove', pane.left + 600, pane.top + 500);
    await wait(320); await still();
    closer.disconnect();
    window.removeEventListener('pointermove', moved, true);
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
    assert(document.activeElement?.getAttribute('aria-label') === 'Create Epic', 'Tab moves into the drawer');
    await key('Enter');
    assert(createdCount() === 2 && !opened() && document.activeElement === cue, 'The header offers Create Epic too; it puts the drawer away and focus back on the trigger');
    await wait(250);
    await key('Enter');
    await still();
    await key('Tab'); await key('Tab');
    assert(document.activeElement?.getAttribute('aria-label') === 'Close Epics', 'Close follows Create Epic');
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
    assert(JSON.stringify(openedEpics) === JSON.stringify(['JIRA:4:site:1184', 'ISSUE:github.com:24918']), `Epics open their own view: ${JSON.stringify(openedEpics)}`);
    const progress = cards[0].querySelector<HTMLElement>('.epic-card__progress');
    assert(progress !== null && cards[0].querySelector('.epic-card__done')?.textContent === '1/3', 'A card counts its done work over the work in scope');
    const [done, working] = [...progress.children].map(child => (child as HTMLElement).getBoundingClientRect().width);
    const whole = progress.getBoundingClientRect().width;
    assert(Math.abs(done - whole / 3) < 1 && Math.abs(working - whole / 3) < 1, 'The bar draws done, then working, over the planned rest; dropped work is apart');
    assert(cards[1].querySelector('.epic-card__progress, .epic-card__done') === null, 'An Epic without work shows no progress');
    render([...PLAIN, ...EPICS], true); await settle();
    assert(!opened(), 'A layer over the page puts the drawer away');
    render([...PLAIN, ...EPICS]); await wait(250);

    await click(at.left + 10, at.top + 30);
    render(PLAIN); await settle();
    assert(document.querySelector('.epic-drawer__panel') === null && document.querySelector('.epic-cue.is-empty') !== null,
      'Unmarking the last Epic leaves only the hollow tick');
    render(EPICS.slice(0, 1)); await settle();
    assert(!opened() && document.querySelectorAll('.epic-cue__tick').length === 1, 'A first Epic marked again shows one tick, closed');
    render(EPICS.slice(0, 2), false, ICEBERG_WORK, new Set([epicIdentity(EPICS[0])])); await settle();
    assert(document.querySelectorAll('.epic-cue__tick').length === 1 && element('.epic-cue').getAttribute('aria-label') === 'Epics · 1', 'A concluded Epic leaves the drawer');
    render(EPICS.slice(0, 1), false, ICEBERG_WORK, new Set([epicIdentity(EPICS[0])])); await settle();
    assert(document.querySelector('.epic-cue:not(.is-empty)') === null, 'With every Epic concluded only the hollow tick is left');

    const localEpic: WorkItemDto = { ...work('local-parent', '', 'PLANNED'), isEpic: true, title: 'Private connector cleanup', epicId: null };
    render([localEpic], false, [work('child', 'LOCAL:local-parent', 'DONE')]); await settle();
    element<HTMLButtonElement>('.epic-cue').click(); await settle();
    const localCard = element<HTMLElement>('.epic-card');
    assert(localCard.querySelector('.card-local') !== null && localCard.querySelector('.epic-card__done')?.textContent === '1/1', 'A local Epic has a Local badge and its own progress');
    localCard.click();
    assert(openedEpics.at(-1) === 'LOCAL:local-parent', 'A local Epic opens by its stable local identity');
    render([localEpic], false, [], new Set(['LOCAL:local-parent'])); await settle();
    assert(document.querySelector('.epic-cue:not(.is-empty)') === null, 'A concluded local Epic leaves the drawer');

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
  finally { disposeRoot(); }
}
