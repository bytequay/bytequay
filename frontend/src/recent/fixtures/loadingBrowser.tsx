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

import '../../css/base.css';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import type { RecentFilterQuery, RecentRowDto } from '../../types';
import RecentPage from '../RecentPage';
import RecentPalette from '../RecentPalette';
import { isoDay, parseDay, shiftDay } from '../model';
import { recentItemTarget } from '../routes';
import { useRecentTouch } from '../useRecentTouch';
import { assert, changeInput, settle } from '../../fixtures/harness';

type DayPage = Awaited<ReturnType<Window['bridge']['fetchRecent']>>;
type Pending = { query: RecentFilterQuery; resolve: (page: DayPage) => void; reject: (error: Error) => void };

const delay = (milliseconds: number) => new Promise<void>(resolve => window.setTimeout(resolve, milliseconds));


function dayPage(day: string, count = 20, nextCursor: string | null = null): DayPage {
  const rows = Array.from({ length: count }, (_, index): RecentRowDto => ({
    id: `${day}-${index}`, itemKey: `pr:owner/repository#${index}`, type: 'PULL_REQUEST', number: index,
    title: `Activity ${day} item ${index}`, repo: 'owner/repository', workspaceId: null, mine: true,
    touchedAt: `${day}T12:00:00Z`, reason: 'READ', reasonLabel: 'Read', htmlUrl: null,
    subtitle: null, snippet: null, textId: null, pullRequest: { state: 'open', draft: false, queued: false },
  }));
  return { day, rows, nextCursor, endOfWindow: nextCursor === null, activeDays: nextCursor ? [day, nextCursor] : [day], retentionDays: 14 };
}

function fixture(active = true) {
  const requests: Pending[] = [];
  const metadataReads: string[] = [];
  const openedTargets: ReturnType<typeof recentItemTarget>[] = [];
  const bridge = {
    listWorkspaces: async () => { metadataReads.push('workspaces'); return []; },
    fetchRecentChanges: async () => { metadataReads.push('changes'); return []; },
    fetchRecent: (query: RecentFilterQuery) => new Promise<DayPage>((resolve, reject) => requests.push({ query, resolve, reject })),
  } satisfies Partial<Window['bridge']>;
  Object.defineProperty(window, 'bridge', { value: bridge, configurable: true });
  const host = document.createElement('div');
  host.style.cssText = 'height: 500px; width: 1000px; font: 14px sans-serif';
  document.body.append(host);
  const root = createRoot(host);
  const render = () => flushSync(() => root.render(<RecentPage active={active} onOpenRow={row => openedTargets.push(recentItemTarget(row))} />));
  render();
  const stream = host.querySelector<HTMLElement>('.rc-stream');
  assert(stream !== null, 'Recent stream is missing');
  return {
    host, stream, requests, metadataReads, openedTargets,
    requestCount: () => requests.length,
    async activate(next: boolean) { active = next; render(); await settle(); },
    async click(text: string) {
      const button = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === text);
      assert(button !== undefined, `Button ${text} is missing`);
      button.click();
      await settle();
    },
    async resolve(index: number, page: DayPage) { requests[index].resolve(page); await settle(); },
    dispose() { flushSync(() => root.unmount()); host.remove(); },
  };
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const today = isoDay(new Date());
  const yesterday = shiftDay(today, -1);
  const check = async (name: string, test: (view: ReturnType<typeof fixture>) => Promise<void>, active = true) => {
    const view = fixture(active);
    try { await settle(); await test(view); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
    finally { view.dispose(); }
  };
  await check('shows the same distinct queue and merge states in the page and palette', async view => {
    const page = dayPage(today, 3);
    page.rows[0].pullRequest = { state: 'open', draft: false, queued: true };
    page.rows[1].pullRequest = { state: 'merged', draft: false, queued: false };
    await view.resolve(0, page);
    Object.defineProperty(window, 'bridge', { value: { ...window.bridge, searchRecent: async () => page.rows }, configurable: true });
    const host = document.createElement('div');
    view.host.append(host);
    const root = createRoot(host);
    try {
      flushSync(() => root.render(<RecentPalette onClose={() => {}} onOpenRow={() => {}} onOpenWordHit={() => {}} />));
      await settle();
      for (const selector of ['.rc-row', '.rc-item']) {
        const queued = view.host.querySelector<SVGElement>(`${selector} [aria-label="In merge queue"]`);
        const merged = view.host.querySelector<SVGElement>(`${selector} [aria-label="Merged pull request"]`);
        const open = view.host.querySelector<SVGElement>(`${selector} [aria-label="Open pull request"]`);
        assert(queued !== null && merged !== null && open !== null, 'Each surface must identify all three PR states');
        assert(new Set([queued, merged, open].map(icon => getComputedStyle(icon).color)).size === 3, 'Queue, merged, and open icons need distinct colours');
      }
    }
    finally { flushSync(() => root.unmount()); host.remove(); }
  });
  await check('filters tickets and toggles back to all items', async view => {
    await view.resolve(0, dayPage(today, 1));
    await view.click('Tickets');
    assert(view.requests[1].query.types?.join() === 'JIRA', 'Tickets must request the Jira type');
    const page = dayPage(today, 1);
    page.rows[0] = { ...page.rows[0], type: 'JIRA', itemKey: 'jira:cloud:1001', number: null, repo: null, subtitle: 'ENG-7 · example.atlassian.net' };
    await view.resolve(1, page);
    assert(view.host.textContent?.includes('ENG-7 · example.atlassian.net'), 'The ticket key and site must be visible');
    await view.click('Tickets');
    assert(!view.requests[2].query.types?.length, 'Toggling Tickets off must request every type');
  });
  await check('records the ticket being left and stops dwell while covered', async () => {
    const reports: Array<{ id: string; seconds: number }> = [];
    Object.defineProperty(window, 'bridge', { configurable: true, value: {
      recordJiraRecentTouch: async (location: { issueId: string }, seconds: number) => { reports.push({ id: location.issueId, seconds }); },
    } });
    const originalTimeout = window.setTimeout;
    const originalNow = Date.now;
    let now = 0;
    let threshold: (() => void) | null = null;
    window.setTimeout = ((handler: TimerHandler, timeout?: number, ...args: unknown[]) => {
      if (timeout === 20_000 && typeof handler === 'function') { threshold = () => handler(); return 0; }
      return originalTimeout(handler, timeout, ...args);
    }) as typeof window.setTimeout;
    Date.now = () => now;
    const host = document.createElement('div');
    const root = createRoot(host);
    function Visit({ id }: { id: string | null }): null {
      useRecentTouch(id === null ? null : { type: 'JIRA', location: { connectionId: 'connection', cloudId: 'cloud', issueId: id } });
      return null;
    }
    const render = (id: string | null) => flushSync(() => root.render(<Visit id={id} />));
    const reachThreshold = () => { assert(threshold !== null, 'A visible ticket needs a dwell timer'); threshold(); };
    try {
      render('1001');
      now = 20_000; reachThreshold();
      now = 65_000; render('1002');
      assert(JSON.stringify(reports) === JSON.stringify([{ id: '1001', seconds: 20 }, { id: '1001', seconds: 65 }]), 'Leaving a ticket must not assign its time to the next ticket');
      now = 70_000; render(null);
      assert(reports.length === 2, 'A short visit must not be recorded');
      render('1002');
      now = 90_000; reachThreshold();
      now = 100_000; render(null);
      assert(reports.at(-1)?.id === '1002' && reports.at(-1)?.seconds === 30, 'Covering a ticket must finish its own visit');
    }
    finally { flushSync(() => root.unmount()); window.setTimeout = originalTimeout; Date.now = originalNow; }
  });
  await check('opens the prompted task from the expanded thread action', async view => {
    const page = dayPage(today, 1);
    const taskId = '622f3ef2-b16a-4b86-9ac7-92e3f80bbc81';
    page.rows[0] = {
      ...page.rows[0], type: 'TASK', itemKey: `task:${taskId}`, id: `task:${taskId}@${today}`,
      workspaceId: 'workspace-1', number: null, reason: 'PROMPTED', reasonLabel: 'You prompted',
      snippet: 'Find another nit and refactor it', textId: `task:${taskId}:prompt:request-1`,
    };
    await view.resolve(0, page);
    view.host.querySelector<HTMLElement>('.rc-row')?.click();
    await settle();
    assert(view.openedTargets.length === 0, 'Expanding the prompt must keep Recent open');
    await view.click('Open the thread');
    assert(view.openedTargets.join() === `#/workspaces/workspace-1/tasks/agent/${taskId}`, 'Open the thread must select the original task conversation');
  });
  await check('shows real pending loads and retries a failed page once without dropping existing rows', async view => {
    assert(view.requestCount() === 1, 'Mount should request the first page once');
    assert(view.host.querySelector('[role="status"]')?.textContent?.includes('Loading recent') === true, 'The initial request needs an initial-loading status');
    await view.resolve(0, dayPage(today, 20, yesterday));
    assert(view.stream.scrollHeight > view.stream.clientHeight + 120, 'The first page must overflow the viewport');
    assert(view.requestCount() === 1 && view.host.querySelector('.rc-spin') === null, 'An idle page with a cursor must not show a spinner or fetch earlier days');
    view.stream.scrollTop = view.stream.scrollHeight;
    for (let index = 0; index < 3; index++) view.stream.dispatchEvent(new Event('scroll'));
    await settle();
    assert(view.requestCount() === 2 && view.requests[1].query.cursor === yesterday, 'Scrolling should start exactly one older-page request');
    assert(view.host.querySelector('[role="status"]')?.textContent?.includes('Loading earlier days') === true, 'Pending pagination needs a loading status');
    view.requests[1].reject(new Error('fetch failed'));
    await settle();
    view.stream.dispatchEvent(new Event('scroll'));
    await settle();
    assert(view.requestCount() === 2 && view.host.querySelector('.rc-spin') === null, 'A failed page must not spin or automatically retry');
    assert(view.host.querySelector('[role="alert"]') !== null && view.host.querySelectorAll('.rc-row').length === 20, 'A page error must preserve existing rows');
    await view.click('Retry');
    assert(view.requestCount() === 3 && view.requests[2].query.cursor === yesterday, 'Retry must request the failed cursor once');
    await view.resolve(2, dayPage(yesterday, 2));
    assert(view.host.querySelectorAll('.rc-row').length === 22 && view.host.querySelector('[role="alert"]') === null, 'Retry should append rows and clear the error');
  });
  await check('debounces typing into a single trimmed query', async view => {
    await view.resolve(0, dayPage(today));
    const input = view.host.querySelector<HTMLInputElement>('input[type="search"]');
    assert(input !== null, 'Search input is missing');
    for (const text of ['m', 'mem', ' memory ']) {
      changeInput(input, text);
      await delay(40);
    }
    assert(view.requestCount() === 1, 'Typing must not fetch before the debounce delay');
    await delay(300);
    await settle();
    assert(view.requestCount() === 2 && view.requests[1].query.text === 'memory', 'Typing should issue one trimmed search request');
  });
  await check('keeps the newest filter response when earlier requests finish late', async view => {
    await view.resolve(0, dayPage(today));
    await view.click('PRs');
    await view.click('Issues');
    assert(view.requestCount() === 3 && view.requests[2].query.types?.[0] === 'ISSUE', 'Changing filters must start the newest request while an earlier filter is pending');
    await view.resolve(2, dayPage(yesterday, 2));
    await view.resolve(1, dayPage(today, 20));
    assert(view.host.querySelectorAll('.rc-row').length === 2 && view.host.textContent?.includes(`Activity ${yesterday}`) === true, 'An earlier filter response must not replace the newest rows');
  });
  await check('continues a date jump after widening a completed range', async view => {
    const target = shiftDay(today, -8);
    await view.resolve(0, { ...dayPage(today), activeDays: [today, target] });
    view.host.querySelector<HTMLButtonElement>('.rc-range')?.click();
    await settle();
    await view.click('Jump to a date…');
    if (parseDay(target).getMonth() !== parseDay(today).getMonth()) {
      view.host.querySelector<HTMLButtonElement>('[aria-label="Previous month"]')?.click();
      await settle();
    }
    const date = [...view.host.querySelectorAll<HTMLButtonElement>('.rc-cal__day')].find(item => item.textContent?.trim() === String(parseDay(target).getDate()));
    assert(date !== undefined && !date.disabled, 'The earlier retained day must be selectable');
    date.click();
    await settle();
    assert(view.requestCount() === 2 && view.requests[1].query.from === shiftDay(today, -13), 'A date jump should widen the range');
    await view.resolve(1, dayPage(today, 20, target));
    assert(view.requestCount() === 3 && view.requests[2].query.cursor === target, 'The date jump must survive the range reset and load the older day despite an overflowing viewport');
    await view.resolve(2, dayPage(target, 2));
    assert(view.host.querySelector(`#rc-day-${target}`) !== null, 'The requested day should remain in the stream');
  });
  await check('restores filters, loaded days and scroll immediately while quietly refreshing the loaded span', async view => {
    assert(view.requestCount() === 0 && view.metadataReads.length === 0, 'An unvisited hidden page must not make API calls');
    assert(view.stream.getBoundingClientRect().height === 0, 'An inactive page must not occupy layout space');
    await view.activate(true);
    await view.resolve(0, dayPage(today));
    await view.click('PRs');
    await view.resolve(1, dayPage(today, 20, yesterday));
    view.stream.scrollTop = view.stream.scrollHeight;
    view.stream.dispatchEvent(new Event('scroll'));
    await settle();
    await view.resolve(2, dayPage(yesterday, 20, shiftDay(today, -2)));
    view.stream.scrollTop = 220;
    await settle();
    await view.activate(false);
    view.stream.dispatchEvent(new Event('scroll'));
    await settle();
    assert(view.requestCount() === 3, 'Hiding an overflowing page must not load more days');
    await view.activate(true);
    const selected = [...view.host.querySelectorAll('button')].find(item => item.textContent?.trim() === 'PRs');
    assert(selected?.getAttribute('aria-pressed') === 'true', 'Returning must retain the selected filter');
    assert(view.host.querySelectorAll('.rc-row').length === 40 && view.stream.scrollTop === 220, 'Returning must immediately retain all loaded rows and scroll position');
    assert(view.host.querySelector('.rc-spin') === null, 'Returning to a loaded page must refresh without a loading footer');
    assert(view.requestCount() === 4 && view.requests[3].query.types?.[0] === 'PULL_REQUEST', 'Return refresh must use the retained filter');
    await view.resolve(3, dayPage(today, 20, yesterday));
    assert(view.requestCount() === 5 && view.requests[4].query.cursor === yesterday, 'Refresh must include the previously loaded older day');
    assert(view.host.querySelectorAll('.rc-row').length === 40, 'A partial refresh must not discard older loaded rows');
    const older = dayPage(yesterday, 20, shiftDay(today, -2));
    older.rows[0].title = 'Refreshed older activity';
    await view.resolve(4, older);
    assert(view.requestCount() === 5 && view.host.textContent?.includes('Refreshed older activity') === true, 'Refresh must update the loaded span and stop before unseen days');
    assert(view.host.querySelectorAll('.rc-row').length === 40 && view.stream.scrollTop === 220, 'A completed refresh must preserve older pages and scroll');
    await view.activate(false);
    await view.activate(true);
    view.requests[5].reject(new Error('fetch failed'));
    await settle();
    assert(view.requestCount() === 6 && view.host.querySelectorAll('.rc-row').length === 40 && view.host.querySelector('.rc-spin') === null, 'A failed quiet refresh must retain rows without looping');
    await view.click('Retry');
    assert(view.requestCount() === 7 && view.requests[6].query.cursor === undefined, 'Retry must restart the quiet refresh instead of appending another page');
    await view.resolve(6, dayPage(today, 20, yesterday));
    assert(view.requestCount() === 8 && view.requests[7].query.cursor === yesterday, 'Retry must revalidate older loaded days too');
    view.requests[7].reject(new Error('fetch failed'));
    await settle();
    assert(view.host.querySelectorAll('.rc-row').length === 40 && view.host.querySelector('.rc-spin') === null, 'A failed older-day refresh must leave the complete previous snapshot visible');
    await view.activate(false);
    await view.activate(true);
    assert(view.requestCount() === 9, 'The next visit must retry a failed quiet refresh');
  }, false);
  await check('does not duplicate pending requests or page automatically while hidden', async view => {
    await view.activate(false);
    await view.activate(true);
    assert(view.requestCount() === 1, 'Returning during the initial request must reuse it');
    await view.activate(false);
    await view.resolve(0, dayPage(today, 20, yesterday));
    assert(view.requestCount() === 1, 'A response received while hidden must not start pagination');
    await view.activate(true);
    assert(view.requestCount() === 2 && view.host.querySelectorAll('.rc-row').length === 20, 'Returning after a hidden response should show it and quietly refresh');
    await view.activate(false);
    await view.activate(true);
    assert(view.requestCount() === 2, 'Returning during a quiet refresh must reuse it');
    await view.resolve(1, dayPage(today, 20, yesterday));
    assert(view.requestCount() === 2 && view.host.querySelector('.rc-spin') === null, 'Completing a one-day refresh must not load unseen days');
  });
  await check('clearing history invalidates retained and pending rows and preserves a loaded empty result', async view => {
    await view.resolve(0, dayPage(today));
    await view.activate(false);
    await view.activate(true);
    await view.activate(false);
    window.dispatchEvent(new Event('bytequay:recent-cleared'));
    await settle();
    assert(view.requestCount() === 2 && view.host.querySelectorAll('.rc-row').length === 0, 'Clear must discard retained rows without reading while hidden');
    await view.resolve(1, dayPage(today));
    assert(view.host.querySelectorAll('.rc-row').length === 0, 'A response started before clear must not restore deleted history');
    await view.activate(true);
    assert(view.requestCount() === 3, 'The next active visit after clear must make one fresh request');
    await view.resolve(2, { ...dayPage(today, 0), day: null, activeDays: [] });
    assert(view.host.textContent?.includes('Nothing recorded in this window yet.') === true, 'An empty response should show the empty state');
    await view.activate(false);
    await view.activate(true);
    assert(view.requestCount() === 4 && view.host.querySelector('.rc-spin') === null, 'A previously loaded empty result must also revalidate quietly');
    assert(view.host.textContent?.includes('Nothing recorded in this window yet.') === true, 'Returning to an empty result must retain its empty state');
  });
  return results;
}
