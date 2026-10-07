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
import EpicView from '../EpicView';
import { useUnmarkEpic } from '../useUnmarkEpic';
import type { CreateWorkItem, EpicChildDto, EpicRecordDto, GoalItemDto, WorkItemDecision, WorkItemDto } from '../../goals/contracts';
import { issueAttentionItem } from '../../home/attention';
import type { Bridge } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';

const NOW = '2026-10-01T08:00:00Z';
const EPIC_ID = 'ISSUE:github.com:24918';
const epic = issueAttentionItem({ id: 24918, number: 24918, title: 'Iceberg REST catalog: cache metadata', isEpic: true, developmentTask: null, body: '',
  author: 'wren-d', authorAvatarUrl: '', htmlUrl: '', labels: [], assignees: [], commentCount: 0, createdAt: NOW, updatedAt: NOW }, 'trinodb/trino', 'workspace');
const link = (kind: GoalItemDto['kind'], key: string, label: string, state: GoalItemDto['state'], status: string): GoalItemDto => ({
  kind, key, label, title: label, state, status, blocked: false, url: null, activity: null, addedAt: NOW });
const work = (id: string, title: string, state: WorkItemDto['state'], epicRank: number, extra: Partial<WorkItemDto> = {}): WorkItemDto => ({
  id, workspaceId: 'workspace', goalId: null, title, kind: 'DEVELOP', acceptanceCriteria: '', completionRule: 'MERGED_DELIVERY', state, revision: 0, links: [],
  history: [], createdAt: NOW, updatedAt: NOW, priority: 'NORMAL', rank: 0, blockedBy: [], epicId: EPIC_ID, epicRank, ...extra });
const CANDIDATE = link('ISSUE', 'trinodb/trino#24977', '#24977', 'IDLE', 'Open');
const child = (number: number, title: string, status: string): EpicChildDto => ({
  identity: `ISSUE:github.com:${number}`, kind: 'ISSUE', key: `trinodb/trino#${number}`, label: `#${number}`, title, status, closed: status === 'Closed', url: null });

function element<T extends HTMLElement = HTMLElement>(selector: string, root: ParentNode = document): T {
  const found = root.querySelector<T>(selector);
  assert(found !== null, `Missing ${selector}`);
  return found;
}
function button(label: string): HTMLButtonElement {
  const found = [...document.querySelectorAll('button')].find(value => value.getAttribute('aria-label') === label || value.textContent?.trim() === label);
  assert(found !== undefined, `Missing button ${label}`);
  return found;
}
function rows(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('.epic-view > .item-list .epic-row')];
}
function row(title: string): HTMLElement {
  const found = [...document.querySelectorAll<HTMLElement>('.epic-row')].find(value => value.querySelector('.item-row__title')?.textContent?.startsWith(title));
  assert(found !== undefined, `Missing row ${title}`);
  return found;
}
function type(input: HTMLInputElement, value: string): void {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
async function wait(milliseconds: number): Promise<void> {
  await new Promise(resolve => window.setTimeout(resolve, milliseconds));
  await settle();
}

function UnmarkProbe({ items, onUnmark }: { items: WorkItemDto[]; onUnmark: () => void }) {
  const unmark = useUnmarkEpic(() => ({ listWorkItems: async () => items }));
  return <>
    <button type="button" onClick={() => unmark.request('workspace', EPIC_ID, onUnmark)}>Unmark the Epic</button>
    <button type="button" onClick={() => unmark.request('workspace', 'ISSUE:github.com:1', onUnmark)}>Unmark an empty Epic</button>
    {unmark.dialog}
  </>;
}

export async function run(): Promise<boolean> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const calls: string[] = [];
  const details: string[] = [];
  let items = [
    work('a', 'Cache table metadata', 'DONE', 0, { links: [link('PULL_REQUEST', 'trinodb/trino#24950', '#24950', 'MERGED', 'Merged')] }),
    work('b', 'Invalidate on DDL', 'WORKING', 1, { links: [link('ISSUE', 'trinodb/trino#24931', '#24931', 'WORKING', 'Open'), link('TASK', 'task-1', 'Task', 'WORKING', 'Running')] }),
    work('c', 'Coordinator broadcast', 'WORKING', 2, { links: [link('PULL_REQUEST', 'trinodb/trino#24990', '#24990', 'MERGED', 'Merged')] }),
    work('d', 'Benchmark warm cache', 'PLANNED', 3, { blockedBy: ['b'] }),
    work('e', 'Old spike', 'DROPPED', 4),
    work('elsewhere', 'Another Epic', 'PLANNED', 0, { epicId: 'JIRA:4:site:10' }),
  ];
  const render = (): void => flushSync(() => root.render(<EpicView epic={epic} workspaceId="workspace" repository="trinodb/trino" work={items} needsYou={new Set()}
    goals={{
      createWork: async (request: CreateWorkItem) => {
        calls.push(`create ${request.title} ${request.epicId} ${request.goalId} ${request.kind} [${request.acceptanceCriteria}] ${request.completionRule}`);
        const created = work(request.id, request.title, 'PLANNED', 9);
        items = [...items, created];
        render();
        return created;
      },
      decideWork: async (target: WorkItemDto, command: WorkItemDecision) => {
        calls.push(['decide', target.id, command.action, command.acceptanceCriteria, command.completionRule, command.blocker, command.releaseDependents]
          .filter(part => part !== undefined).join(' '));
        details.push(command.detail);
        const state = command.action === 'ACCEPT' ? 'DONE' : command.action === 'DROP' ? 'DROPPED' : command.action === 'REOPEN' ? 'WORKING' : target.state;
        const blockedBy = command.action === 'UNBLOCK' ? target.blockedBy.filter(value => value !== command.blocker)
          : command.action === 'BLOCK' && command.blocker !== undefined ? [...target.blockedBy, command.blocker] : target.blockedBy;
        const updated = { ...target, state, blockedBy, revision: target.revision + 1 };
        items = items.map(value => (value.id === target.id ? updated : value));
        render();
        return updated;
      },
      search: async () => [CANDIDATE],
    }}
    epicTitle={id => (id === 'JIRA:4:site:10' ? 'Parquet lazy decode' : null)} onOpenTask={taskId => calls.push(`open task ${taskId}`)}
    onStartIssue={(number, instruction, replaces) => calls.push(`start issue ${number} [${instruction}] ${replaces}`)}
    onStartWork={(itemId, draft) => calls.push(`start work ${itemId} [${draft}]`)} onShowSource={() => calls.push('show source')} />));
  window.bridge = {
    joinEpic: async (_workspaceId: string, epicId: string, kind: string, key: string) => {
      calls.push(`join ${epicId} ${kind} ${key}`);
      const joined = work('f', 'Expose cache metrics', 'PLANNED', 6, { links: [CANDIDATE] });
      items = [...items, joined];
      render();
      return { item: joined, movedFrom: 'JIRA:4:site:10' };
    },
    orderEpicWork: async (_workspaceId: string, _epicId: string, ids: string[]): Promise<WorkItemDto[]> => {
      calls.push(`order ${ids.join(',')}`);
      items = items.map(value => (ids.includes(value.id) ? { ...value, epicRank: ids.indexOf(value.id) } : value));
      render();
      return [];
    },
    removeFromEpic: async (_workspaceId: string, id: string): Promise<WorkItemDto | null> => {
      calls.push(`remove ${id}`);
      items = items.filter(value => value.id !== id);
      render();
      return null;
    },
  } as unknown as Bridge;
  try {
    render(); await settle();
    const view = element('.epic-view');
    assert(element('.epic-view__title', view).textContent === 'Iceberg REST catalog: cache metadata', 'The view names the Epic');
    const counts = element('.epic-view__counts').textContent ?? '';
    assert(counts.includes('1 of 4 done') && counts.includes('2 working') && counts.includes('1 planned') && counts.includes('1 dropped'), `Counts: ${counts}`);
    const bar = element('.epic-progress');
    const [done, working] = [...bar.children].map(child => (child as HTMLElement).getBoundingClientRect().width);
    assert(Math.abs(done - bar.getBoundingClientRect().width / 4) < 1 && Math.abs(working - bar.getBoundingClientRect().width / 2) < 1, 'The bar is done, then working, over planned');
    assert(JSON.stringify(rows().map(value => value.querySelector('.item-row__title > span')?.textContent)) ===
      JSON.stringify(['Cache table metadata', 'Invalidate on DDL', 'Coordinator broadcast', 'Benchmark warm cache']), 'Open and done work in the Epic\'s order; dropped and other Epics\' work apart');
    assert(element('.epic-view__dropped > summary').textContent === '1 dropped', 'Dropped work folds away');
    assert(document.querySelector('.epic-view__found') === null && document.querySelector('.epic-row__upstream') === null, 'Without children read there is no strip');
    assert(row('Invalidate on DDL').querySelector('.pill')?.textContent === 'running' && row('Invalidate on DDL').querySelector('.epic-state.agt-spin') !== null, 'A running task spins');
    assert(row('Invalidate on DDL').querySelector('.card-key')?.textContent === '#24931', 'Work from an issue names it');
    assert(row('Benchmark warm cache').querySelector('.card-key') === null, 'Work without a source shows no reference');
    assert(row('Benchmark warm cache').textContent?.includes('after Invalidate on DDL') === true, 'Blocked work says what it waits on');
    assert(row('Cache table metadata').querySelector('.epic-row__accept') === null && row('Cache table metadata').textContent?.includes('#24950 merged') === true, 'Done work keeps its merge');

    // Run starts work where it starts; an attempt under way opens instead, and concluded work does not run.
    assert(row('Invalidate on DDL').querySelector('[aria-label="Run Invalidate on DDL"]') === null, 'Work with a task under way offers no Run');
    button('Open the task for Invalidate on DDL').click(); await settle();
    assert(calls.at(-1) === 'open task task-1', `Open task opens the attempt under way: ${calls.at(-1)}`);
    button('Run Benchmark warm cache').click(); await settle();
    assert(calls.at(-1) === 'start work d [Benchmark warm cache]', `Work without a source starts a task with its title: ${calls.at(-1)}`);
    assert(row('Cache table metadata').querySelector('[aria-label^="Run "]') === null, 'Done work does not run');

    element<HTMLButtonElement>('.epic-row__accept', row('Coordinator broadcast')).click(); await settle();
    assert(calls.at(-1) === 'decide c ACCEPT Coordinator broadcast', `Accept settles quick-added work against its title: ${calls.at(-1)}`);
    assert(element('.epic-view__counts').textContent?.includes('2 of 4 done') === true, 'Accepted work counts as done');

    button('Move Benchmark warm cache up').click(); await settle();
    assert(calls.at(-1) === 'order a,b,d,c,e', `Moving swaps neighbours and keeps every item: ${calls.at(-1)}`);

    const waits = element<HTMLSelectElement>('.epic-row__wait', row('Benchmark warm cache'));
    waits.value = 'unblock:b';
    waits.dispatchEvent(new Event('change', { bubbles: true })); await settle();
    assert(calls.at(-1) === 'decide d UNBLOCK b', `Blockers are edited in the Epic view: ${calls.at(-1)}`);
    assert(row('Benchmark warm cache').textContent?.includes('after') === false, 'Unblocked work waits on nothing');

    button('Drop Benchmark warm cache').click(); await settle();
    const dialog = element<HTMLDialogElement>('dialog.bq-confirm-dialog');
    assert(dialog.open && dialog.textContent?.includes('Drop "Benchmark warm cache"?') === true, 'Dropping asks for a reason first');
    type(element<HTMLInputElement>('input', dialog), 'Numbers came from the cache test');
    button('Drop').click(); await settle();
    assert(calls.at(-1) === 'decide d DROP true', `Dropping releases what waited on it: ${calls.at(-1)}`);
    assert(element('.epic-view__dropped > summary').textContent === '2 dropped', 'Dropped work joins the folded list');

    button('Remove Cache table metadata from the Epic').click(); await settle();
    assert(calls.at(-1) === 'remove a' && !rows().some(value => value.textContent?.includes('Cache table metadata')), 'Remove takes work out of the Epic');

    const input = element<HTMLInputElement>('.epic-view__new input');
    type(input, '  Profile the reader  ');
    element<HTMLFormElement>('.epic-view__new').requestSubmit(); await wait(20);
    assert(calls.at(-1) === 'create Profile the reader ISSUE:github.com:24918 null DEVELOP [] MERGED_DELIVERY', `Quick add takes a title: ${calls.at(-1)}`);
    assert(input.value === '' && rows().some(value => value.textContent?.includes('Profile the reader')), 'The new work joins the list and the field clears');

    button('Add existing').click(); await wait(250);
    const result = element('.goal-result');
    assert(result.textContent?.includes('#24977') === true, 'Add existing finds sources');
    element<HTMLButtonElement>('button', result).click(); await wait(20);
    assert(calls.at(-1) === 'join ISSUE:github.com:24918 ISSUE trinodb/trino#24977', `Add existing joins the Epic: ${calls.at(-1)}`);
    assert(element('.epic-view__notice').textContent === 'Moved here from Parquet lazy decode', 'Work taken from another Epic says so');


    // The source's children: read when the view opens, joined only on Sync, and labelled with where the source has them.
    let record: EpicRecordDto = {
      id: EPIC_ID, children: [child(24931, 'Invalidate on DDL', 'Open'), child(24960, 'Document the cache', 'Closed'), child(24961, 'Expose metrics', 'Open')],
      synced: ['ISSUE:github.com:24931', 'ISSUE:github.com:24970'], former: [child(24970, 'Old parent link', 'Open')], childrenReadAt: NOW, childrenTruncated: false,
      offered: true, concludedAt: null, outcome: '', proposals: [], breakingDown: false, breakdownError: null,
    };
    items = [
      work('p', 'Invalidate on DDL', 'WORKING', 0, { links: [link('ISSUE', 'trinodb/trino#24931', '#24931', 'WORKING', 'Open')] }),
      work('q', 'Old parent link', 'PLANNED', 1, { links: [link('ISSUE', 'trinodb/trino#24970', '#24970', 'IDLE', 'Open'), link('TASK', 'task-9', 'Task', 'ENDED', 'Ended')] }),
      work('o', 'Docs site update', 'PLANNED', 2, { links: [link('ISSUE', 'other/docs#3', 'other/docs#3', 'IDLE', 'Open')] }),
    ];
    window.bridge = {
      getEpic: async (): Promise<EpicRecordDto> => ({ ...record, children: [], former: [], offered: false }),
      refreshEpicChildren: async (_workspaceId: string, epicId: string, connectionId: string | null) => {
        calls.push(`children ${epicId} ${connectionId}`);
        return record;
      },
      syncEpicChildren: async () => {
        calls.push('sync');
        items = [...items, work('r', 'Document the cache', 'PLANNED', 2, { links: [link('ISSUE', 'trinodb/trino#24960', '#24960', 'IDLE', 'Closed')] }),
          work('s', 'Expose metrics', 'PLANNED', 3, { links: [link('ISSUE', 'trinodb/trino#24961', '#24961', 'IDLE', 'Open')] })];
        record = { ...record, synced: [...record.synced, 'ISSUE:github.com:24960', 'ISSUE:github.com:24961'], offered: false };
        render();
        return record;
      },
      dismissEpicChildren: async () => {
        calls.push('dismiss');
        record = { ...record, offered: false };
        return record;
      },
    } as unknown as Bridge;
    const reopen = async (): Promise<void> => {
      flushSync(() => root.render(<></>));
      render(); await wait(20);
    };
    await reopen();
    assert(calls.at(-1) === `children ${EPIC_ID} null`, `Opening the view reads the Epic's children: ${calls.at(-1)}`);
    assert(element('.epic-view__found').textContent?.includes('Found in GitHub · 2') === true, `The strip counts the children not synced: ${element('.epic-view__found').textContent}`);
    assert(row('Invalidate on DDL').querySelector('.epic-row__upstream')?.textContent === 'GitHub: Open', 'Synced work shows where the source has it');
    assert(row('Old parent link').querySelector('.epic-row__upstream')?.textContent === 'no longer a child', 'Work whose child the source dropped stays, marked');
    assert(rows().length === 3, 'Nothing joins on its own');
    button('Run Invalidate on DDL').click(); await settle();
    assert(calls.at(-1) === 'start issue 24931 [Invalidate on DDL] undefined', `Work from an issue starts as the issue's task: ${calls.at(-1)}`);
    button('Run Old parent link').click(); await settle();
    assert(calls.at(-1) === 'start issue 24970 [Old parent link] task-9', `Another attempt names the ended one: ${calls.at(-1)}`);
    assert(row('Docs site update').querySelector('[aria-label^="Run "]') === null, 'An issue of another repository does not run from here');
    button('Not now').click(); await settle();
    assert(calls.at(-1) === 'dismiss' && document.querySelector('.epic-view__found') === null, 'Not now hides the strip');

    record = { ...record, offered: true };
    await reopen();
    button('Sync').click(); await wait(20);
    assert(calls.at(-1) === 'sync' && document.querySelector('.epic-view__found') === null && rows().length === 5, 'Sync makes the children work and the strip goes');
    const closedChild = row('Document the cache');
    assert(closedChild.querySelector('.epic-row__upstream')?.textContent === 'GitHub: Closed', 'A child closed upstream says so');
    assert(row('Expose metrics').querySelector('.epic-row__accept') === null, 'An open child offers no Accept');
    element<HTMLButtonElement>('.epic-row__accept', closedChild).click(); await settle();
    assert(calls.at(-1) === 'decide r ACCEPT Document the cache ACCEPTED_RESULT' && details.at(-1) === 'Closed on GitHub',
      `Accepting a closed child settles it as an accepted result: ${calls.at(-1)} / ${details.at(-1)}`);

    // Suggest work asks the Development model; what it proposes waits until each proposal is accepted or dismissed.
    let polled = 0;
    window.bridge = {
      ...window.bridge,
      proposeEpicWork: async (_workspaceId: string, epicId: string, connectionId: string | null) => {
        calls.push(`propose ${epicId} ${connectionId}`);
        record = { ...record, breakingDown: true };
        return record;
      },
      getEpic: async (): Promise<EpicRecordDto> => {
        polled++;
        record = { ...record, breakingDown: false, proposals: [{ id: 'proposal-1', title: 'Expose cache metrics', doneWhen: 'Hits and misses are reported' },
          { id: 'proposal-2', title: 'Tune the cache', doneWhen: 'Reads are faster' }] };
        return record;
      },
      acceptEpicProposal: async (_workspaceId: string, _epicId: string, proposalId: string) => {
        calls.push(`accept proposal ${proposalId}`);
        record = { ...record, proposals: record.proposals.filter(proposal => proposal.id !== proposalId) };
        return record;
      },
      dismissEpicProposal: async (_workspaceId: string, _epicId: string, proposalId: string) => {
        calls.push(`dismiss proposal ${proposalId}`);
        record = { ...record, proposals: record.proposals.filter(proposal => proposal.id !== proposalId) };
        return record;
      },
    } as unknown as Bridge;
    button('Suggest work').click(); await settle();
    assert(calls.at(-1) === `propose ${EPIC_ID} null` && element('.epic-view__proposals').textContent?.includes('Proposing work…') === true,
      `Suggest work starts the Epic's breakdown: ${calls.at(-1)}`);
    await wait(1_150);
    assert(polled > 0 && element('.epic-view__proposals-head').textContent === 'Proposed · 2', 'The proposals arrive while the view is open');
    assert(rows().length === 5, 'Nothing becomes work before it is accepted');
    const proposed = (title: string): HTMLElement => {
      const found = [...document.querySelectorAll<HTMLElement>('.epic-proposal')].find(value => value.textContent?.includes(title));
      assert(found !== undefined, `Missing proposal ${title}`);
      return found;
    };
    element<HTMLButtonElement>('.epic-row__accept', proposed('Expose cache metrics')).click(); await settle();
    assert(calls.at(-1) === 'accept proposal proposal-1', `Accept makes the proposal work: ${calls.at(-1)}`);
    button('Dismiss Tune the cache').click(); await settle();
    assert(calls.at(-1) === 'dismiss proposal proposal-2' && document.querySelector('.epic-view__proposals') === null, 'Dismissing the last proposal folds the list away');

    // With all its work done the Epic concludes, recording what it delivered, and offers closing its issue.
    assert(document.querySelector('.epic-view__conclude') === null, 'Unfinished work offers no Conclude');
    items = [work('p', 'Invalidate on DDL', 'DONE', 0), work('s', 'Expose metrics', 'DONE', 1), work('x', 'Old spike', 'DROPPED', 2)];
    record = { ...record, offered: false };
    window.bridge = {
      ...window.bridge,
      getEpic: async (): Promise<EpicRecordDto> => record,
      concludeEpic: async (_workspaceId: string, epicId: string, outcome: string) => {
        calls.push(`conclude ${epicId}`);
        details.push(outcome);
        record = { ...record, concludedAt: NOW, outcome: outcome.trim() };
        return record;
      },
      closeWorkspaceIssue: async (_workspaceId: string, number: number) => { calls.push(`close ${number}`); },
    } as unknown as Bridge;
    await reopen();
    assert(element('.epic-view__counts').textContent?.includes('All 2 done · ready to conclude') === true, 'Dropped work does not hold the Epic open');
    button('Conclude Epic').click(); await settle();
    const concluding = element<HTMLDialogElement>('dialog.bq-confirm-dialog');
    const outcome = element<HTMLTextAreaElement>('textarea', concluding);
    assert(outcome.value === 'All 2 work items done:\n- Invalidate on DDL\n- Expose metrics', `The outcome starts from the done work: ${outcome.value}`);
    button('Conclude').click(); await settle();
    assert(calls.at(-1) === `conclude ${EPIC_ID}` && details.at(-1) === outcome.value, `Concluding records the outcome: ${calls.at(-1)}`);
    const concluded = element('.epic-view__concluded');
    assert(concluded.textContent?.includes('Concluded') === true && element('.epic-view__outcome').textContent === outcome.value.trim()
      && document.querySelector('.epic-view__conclude') === null && element('.epic-view__counts').textContent === 'All 2 done1 dropped',
      'The view shows the conclusion in place of Conclude');
    button('Close #24918 on GitHub').click(); await settle();
    assert(calls.at(-1) === 'close 24918', `Closing the issue is its own action: ${calls.at(-1)}`);

    // Unmarking an Epic with work asks first, naming what leaves and what goes.
    let unmarks = 0;
    const unmarked = (): number => unmarks;
    flushSync(() => root.render(<UnmarkProbe items={[work('x', 'In a Goal', 'WORKING', 0, { goalId: 'goal' }), work('y', 'Only here', 'PLANNED', 1)]}
      onUnmark={() => { unmarks++; }} />));
    button('Unmark an empty Epic').click(); await wait(20);
    assert(unmarked() === 1 && document.querySelector('dialog.bq-confirm-dialog') === null, 'An Epic without work unmarks at once');
    button('Unmark the Epic').click(); await wait(20);
    const confirm = element<HTMLDialogElement>('dialog.bq-confirm-dialog');
    assert(confirm.textContent?.includes('its 2 work items leave the Epic, and 1 that exists only there is deleted') === true, `Confirm says what goes: ${confirm.textContent}`);
    assert(unmarked() === 1, 'Nothing is unmarked before the user confirms');
    button('Unmark').click(); await settle();
    assert(unmarked() === 2, 'Confirming unmarks it');
    return true;
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
