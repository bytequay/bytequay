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
import '../../index.css';
import AttentionList from '../AttentionList';
import type { AttentionItem } from '../attention';
import TodayList from '../TodayList';
import { todayKeys, type TodayScope } from '../today';
import type { TodayAgenda } from '../useTodayAgenda';
import type { AgendaDto, AgendaItemDto, AgendaTargetDto } from '../../types';
import { element, runScenarios, button as findButton } from '../../fixtures/harness';

function assert(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

const target = (kind: AgendaTargetDto['kind'], extra: Partial<AgendaTargetDto>): AgendaTargetDto =>
  ({ kind, workspaceId: 'ws1', repo: null, number: null, id: null, connectionId: null, cloudId: null, ...extra });

const ITEMS: AgendaItemDto[] = [
  {
    ref: 'work:w22', action: 'start', title: 'Start “Expose snapshot id in metadata”', label: 'Expose snapshot id', at: '2026-10-16T00:14:00Z', workspaceId: 'ws1',
    why: '“Retry commit on conflict” merged at 00:14; this is next and unblocks 2 items.', target: target('WORK', { id: 'w22' }),
    where: 'Iceberg write path · 3/6', status: 'open', byYou: false, doneText: null, doneAt: null,
  },
  {
    ref: 'task:t1', action: 'reply', title: 'Answer elric-t’s review on #31402', label: 'elric-t on #31402', at: '2026-10-15T18:40:00Z', workspaceId: 'ws1',
    why: 'Changes requested yesterday 18:40: “Please split the retry policy into its own PR.” 2 findings unanswered.', target: target('TASK', { id: 't1' }),
    where: 'owner/repository #31402', status: 'open', byYou: false, doneText: null, doneAt: null,
  },
  {
    ref: 'pr:owner/repository#31377', action: 'review', title: 'Review #31377 Add Iceberg metadata cache', label: 'Review #31377', at: '2026-10-13T08:00:00Z',
    workspaceId: 'ws1', why: 'Asked 3 days ago. 134 lines, CI green; fits your 12:30 gap.', target: target('PULL', { repo: 'owner/repository', number: 31377 }),
    where: null, status: 'open', byYou: false, doneText: null, doneAt: null,
  },
  {
    ref: 'pr:owner/gateway#612', action: 'merge', title: 'Merge #612 Route by client tags', label: 'Merge #612', at: '2026-10-14T10:00:00Z', workspaceId: null,
    why: 'Approved by finn-ep, green and mergeable since Wednesday.', target: target('PULL', { repo: 'owner/gateway', number: 612, workspaceId: null }),
    where: null, status: 'open', byYou: false, doneText: null, doneAt: null,
  },
  {
    ref: 'pr:owner/repository#31390', action: 'fix', title: 'Fix maven-checks on #31390', label: 'maven-checks', at: '2026-10-15T09:00:00Z', workspaceId: 'ws1',
    why: 'maven-checks failed on your last push.', target: target('PULL', { repo: 'owner/repository', number: 31390 }),
    where: null, status: 'done', byYou: false, doneText: 'Checks passed', doneAt: null,
  },
];

const AGENDA: AgendaDto = {
  day: '2026-10-16', state: 'READY', enabled: true, at: 540, generatedAt: '2026-10-16T01:02:00Z', model: 'Sonnet',
  summary: 'Your conflict-retry fix merged last night, so the next Iceberg item is ready. Two people are waiting on you.',
  items: ITEMS,
  recap: {
    text: 'Merged 3 PRs. Iceberg write path is half done. #31377 has waited all week.',
    merged: [{ ref: 'pr:owner/repository#31390', target: target('PULL', { repo: 'owner/repository', number: 31390 }) },
      { ref: 'pr:owner/gateway#605', target: target('PULL', { repo: 'owner/gateway', number: 605 }) }],
    slipped: [{ ref: 'pr:owner/repository#31377', target: target('PULL', { repo: 'owner/repository', number: 31377 }) }],
  },
  calendar: { state: 'used', busyHours: 5.5, allDay: false, busy: [{ from: '10:00', to: '12:30' }, { from: '15:00', to: '18:00' }],
    free: [{ from: '09:00', to: '10:00' }, { from: '12:30', to: '15:00' }] },
  failure: null,
};

const ATTENTION: AttentionItem[] = [31377, 31411].map((number): AttentionItem => ({
  key: `owner/repository#${number}`, sourceKey: `github.com:issue:${number}`, type: 'issue', kind: 'assigned', repo: 'owner/repository', number,
  title: number === 31377 ? 'Add Iceberg metadata cache' : 'Snapshot expiry ignores branch retention', author: 'marlo-t', authorAvatarUrl: null,
  createdAt: '2026-10-10T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', labels: [], pr: null, body: '', commentCount: 0, workspaceId: 'ws1',
}));

type Mounted = { host: HTMLElement; marks: [string, string | null][]; starts: string[]; render: (agenda: AgendaDto) => void; dispose: () => void };

const settle = () => new Promise(resolve => setTimeout(resolve, 30));

function mount(scope: TodayScope, agenda: AgendaDto = AGENDA, start: (ref: string) => Promise<void> = async () => {}): Mounted {
  const host = document.createElement('div');
  host.className = 'home-main';
  host.style.cssText = `width:${scope === 'global' ? 932 : 836}px;padding:16px 24px;`;
  document.body.append(host);
  const root = createRoot(host);
  const marks: [string, string | null][] = [];
  const starts: string[] = [];
  const render = (value: AgendaDto) => {
    const today: TodayAgenda = {
      agenda: scope === 'workspace' ? { ...value, items: value.items.filter(item => item.workspaceId === 'ws1') } : value, error: null,
      makeAgain: () => {}, mark: (ref, mark) => { marks.push([ref, mark]); }, start: ref => { starts.push(ref); return start(ref); }, showHidden: () => {},
    };
    flushSync(() => root.render(<>
      <TodayList today={today} scope={scope} facts={ATTENTION} />
      <AttentionList candidates={ATTENTION} handled={{}} onHandledChange={() => {}} onOpen={() => {}} todayKeys={todayKeys(today.agenda)} />
    </>));
  };
  render(agenda);
  return { host, marks, starts, render, dispose: () => { flushSync(() => root.unmount()); host.remove(); } };
}

const button = (host: HTMLElement, label: string) => findButton(label, host);

function zoomed(run: () => void): unknown[] {
  const seen: unknown[] = [];
  const listener = (event: Event) => { seen.push((event as CustomEvent).detail); };
  window.addEventListener('bytequay:zoom-reference', listener);
  try { run(); }
  finally { window.removeEventListener('bytequay:zoom-reference', listener); }
  return seen;
}

const row = (host: HTMLElement, title: string) => element<HTMLElement>(`.today-row[aria-label^="${title}:"]`, host, `Missing row: ${title}`);

async function opensWork(): Promise<void> {
  const view = mount('global');
  try {
    const rows = [...view.host.querySelectorAll<HTMLElement>('.today-row')];
    assert(rows.length === 5, `Five rows must show, found ${rows.length}`);
    assert(rows[4].classList.contains('is-done'), 'The done row must sink to the bottom');
    assert(rows.every(each => each.querySelector('.today-row__why') === null), 'Rows start closed, with chips instead of the reason');
    assert(rows[2].querySelector('.today-reason')?.textContent?.startsWith('Waiting ') === true, `A row says how long it waited: ${rows[2].textContent}`);
    const clicked = zoomed(() => flushSync(() => rows[2].click()));
    assert(clicked.length === 0 && rows[2].classList.contains('is-open'), 'A click opens the row in place');
    assert(rows[2].querySelector('.today-row__why')?.textContent === ITEMS[2].why, 'An open row says the full reason');
    const opened = zoomed(() => button(rows[2], 'Open review').click());
    assert(JSON.stringify(opened) === JSON.stringify([{ kind: 'pull', repo: 'owner/repository', number: 31377, workspaceId: 'ws1' }]), `The row's action opens its pull request: ${JSON.stringify(opened)}`);
    flushSync(() => rows[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })));
    assert(rows[0].classList.contains('is-open') && !rows[2].classList.contains('is-open'), 'Enter opens a row, and only one row is open at a time');
    const done = zoomed(() => flushSync(() => rows[4].click()));
    assert(JSON.stringify(done) === JSON.stringify([{ kind: 'pull', repo: 'owner/repository', number: 31390, workspaceId: 'ws1' }]) && !rows[4].classList.contains('is-open'),
      `A click on a done row opens its item: ${JSON.stringify(done)}`);
    assert(view.marks.length === 0, 'Opening must not mark anything');

    view.render({ ...AGENDA, items: ITEMS.map(item => item.ref === 'work:w22' ? { ...item, where: `${'Migrate every connector to a shared context '.repeat(4)}· 0/8` } : item) });
    const line = element<HTMLElement>('.today-row__line', row(view.host, 'Start “Expose snapshot id in metadata”'), 'Missing the first row\'s line');
    const title = element<HTMLElement>('.today-row__title', line, 'Missing the first row\'s title');
    assert(title.offsetWidth > 0 && title.scrollWidth <= title.clientWidth, `A long reference must leave the title its line: ${title.clientWidth} of ${title.scrollWidth}`);
  }
  finally { view.dispose(); }
}

async function marksWithoutOpening(): Promise<void> {
  const view = mount('global');
  try {
    const opened = zoomed(() => {
      button(view.host, 'Mark “Review #31377 Add Iceberg metadata cache” done').click();
      const merge = row(view.host, 'Merge #612 Route by client tags');
      flushSync(() => merge.click());
      button(merge, 'Tomorrow').click();
      button(view.host, 'Reopen “Fix maven-checks on #31390”').click();
    });
    assert(opened.length === 0, 'Marking must not open the item');
    assert(JSON.stringify(view.marks) === JSON.stringify([['pr:owner/repository#31377', 'DONE'], ['pr:owner/gateway#612', 'DISMISSED'], ['pr:owner/repository#31390', null]]),
      `Marks must reach the agenda: ${JSON.stringify(view.marks)}`);
  }
  finally { view.dispose(); }
}

async function startsWorkFromItsRow(): Promise<void> {
  let refuse = true;
  const started = { ...AGENDA, items: ITEMS.map(item => item.ref === 'work:w22' ? { ...item, status: 'done' as const, doneText: 'Started' } : item) };
  const view = mount('global', AGENDA, async () => {
    if (refuse) throw new Error("Error invoking remote method 'agenda:start': Error: The development agent isn't ready; check Settings → Models");
    view.render(started);
  });
  const offered = () => [...view.host.querySelectorAll<HTMLButtonElement>('.today-row button')].filter(each => each.textContent === 'Start task');
  try {
    for (const each of view.host.querySelectorAll<HTMLElement>('.today-row')) flushSync(() => each.click());
    flushSync(() => row(view.host, 'Start “Expose snapshot id in metadata”').click());
    assert(offered().length === 1, `Only the row that starts work offers Start: ${offered().length}`);
    const opened = zoomed(() => offered()[0].click());
    await settle();
    assert(opened.length === 0 && JSON.stringify(view.starts) === '["work:w22"]', `Start begins the task without opening the item: ${JSON.stringify(view.starts)}`);
    const refusal = view.host.querySelector('.today-row__refusal');
    assert(refusal?.textContent === "The development agent isn't ready; check Settings → Models", `What refused it shows under the row: ${refusal?.textContent}`);

    refuse = false;
    offered()[0].click();
    await settle();
    assert(view.host.querySelector('.today-row__refusal') === null && offered().length === 0, 'Once started, the row reads done and offers Start no more');
    assert(view.host.textContent?.includes('Started') === true, 'The row says it started');
  }
  finally { view.dispose(); }
}

async function recapAndTag(): Promise<void> {
  const view = mount('global');
  try {
    assert(!view.host.textContent?.includes('Slipped'), 'The recap starts folded');
    const recap = element<HTMLElement>('.today-recap', view.host, 'This week must show on the recap day');
    flushSync(() => recap.click());
    assert(view.host.textContent?.includes('Slipped') === true && view.host.textContent.includes('gateway #605'), 'This week must unfold its chips');
    const marked = [...view.host.querySelectorAll<HTMLElement>('.atn-section .item-row')].map(each => each.querySelector('.today-tag') !== null);
    assert(JSON.stringify(marked) === JSON.stringify([true, false]), `Only the row also on Today wears the Today tag: ${JSON.stringify(marked)}`);
  }
  finally { view.dispose(); }
}

async function foldsWhenDone(): Promise<void> {
  const view = mount('workspace', { ...AGENDA, items: ITEMS.map(item => ({ ...item, status: 'done' as const })) });
  try {
    assert(view.host.querySelector('.today-list') === null, 'An all-done agenda folds to its header');
    flushSync(() => view.host.querySelector<HTMLButtonElement>('.today-head__toggle')?.click());
    assert(view.host.querySelectorAll('.today-row').length === 4, 'The header unfolds this workspace’s rows');
    assert(!view.host.textContent?.includes('Your conflict-retry fix'), 'A workspace Home leaves out the summary');
  }
  finally { view.dispose(); }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results = await runScenarios([['rows open their work', opensWork], ['marks without opening', marksWithoutOpening], ['recap and Today tag', recapAndTag],
    ['folds when done', foldsWhenDone], ['starts work from its row', startsWorkFromItsRow]]);
  return results;
}

let preview: Mounted | null = null;

/** Draws one Home's Today over its Needs attention for a screenshot. */
export async function showPreview(scope: TodayScope, recapOpen: boolean): Promise<void> {
  preview?.dispose();
  document.body.style.margin = '0';
  document.body.style.background = 'var(--bg)';
  preview = mount(scope);
  if (recapOpen) flushSync(() => preview?.host.querySelector<HTMLElement>('.today-recap')?.click());
  // A hidden window paints no frames, so a timer lets layout settle instead.
  await new Promise(resolve => setTimeout(resolve, 50));
}
