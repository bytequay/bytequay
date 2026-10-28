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
import type { Bridge, RepositoryIssueSnapshotDto, RepositoryMetadataDto, WorkspaceIssueDetailDto } from '../../types';
import type { WorkItemDecision, WorkItemDto } from '../../workitems/contracts';
import { issueEpicId, lendEpicWorkspace, takeEpicStart } from '../epics';
import { workspaceRoute } from '../model';
import WorkspaceIssuesScreen from '../WorkspaceIssuesScreen';
import { assert, changeInput, settle, element, button } from '../../fixtures/harness';
import { handledEntry, issueAttentionItem, loadHandled, saveHandled, visibleAttention } from '../../home/attention';
import { refreshSnoozedItem } from '../../home/useSnoozedAttention';
import { LayerCovered } from '../../Zoom';
import { loadPinned } from '../../home/pinned';

type Issue = RepositoryIssueSnapshotDto['issues'][number];


function issueReads(issues: () => Issue[], repository = { owner: 'upstream', repo: 'app' }) {
  return {
    fetchWorkspaceIssue: async (_workspace: string, number: number) => issues().find(issue => issue.number === number),
    fetchWorkspaceIssues: async (): Promise<RepositoryIssueSnapshotDto> => ({
      repositoryId: 40, repository, issues: issues(), page: 0, pageSize: 50, totalIssues: issues().length, hasMore: false, syncedAt: null,
    }),
    getWorkspaceMetadata: async (): Promise<RepositoryMetadataDto> => ({
      repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
    }),
    fetchWorkspaceIssueDetail: async (_workspace: string, number: number): Promise<WorkspaceIssueDetailDto> => ({
      repositoryId: 40, issueNumber: number, timeline: [], currentType: null, availableTypes: [], refreshedAt: '2026-09-02T00:00:00Z',
    }),
    recordRecentTouch: async () => {},
  };
}

async function closeKeepsIssue(): Promise<void> {
  const writes: string[] = [];
  let issues = [7, 8, 9].map((number): Issue => ({
    id: 7000 + number, number, title: `Issue ${number}`, body: 'Details', author: 'alice', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  const repository = { owner: 'upstream', repo: 'app' };
  const metadata: RepositoryMetadataDto = {
    repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
  };
  window.bridge = {
    ...issueReads(() => issues, repository),
    getWorkspaceMetadata: async () => metadata,
    commentWorkspaceIssue: async (_workspace: string, number: number, body: string) => { writes.push(`comment #${number}: ${body}`); },
    // The backend drops a closed issue from the open list, so a reload no longer contains it.
    closeWorkspaceIssue: async (_workspace: string, number: number) => {
      writes.push(`close #${number}`);
      issues = issues.filter(issue => issue.number !== number);
    },
  } as unknown as Bridge;
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  const composer = () => {
    const found = element<HTMLElementTagNameMap['textarea']>('textarea', host, 'Missing issue composer');
    return found;
  };
  const type = (value: string) => {
    const field = composer();
    changeInput(field, value);
  };
  const row = (number: number) => [...host.querySelectorAll<HTMLButtonElement>('button[aria-pressed]')]
    .find(candidate => candidate.textContent?.includes(`#${number}`));
  const open = (number: number) => {
    const found = row(number);
    assert(found !== undefined, `Missing issue row #${number}`);
    found.click();
  };
  try {
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={7} onSelectIssue={() => {}} />));
    await settle();
    assert(host.querySelector('.attention-visit-status') === null && loadHandled()['issue:workspace-1:7'] === undefined,
      'Viewing an issue retains its notification without a visit label while its detail stays open');
    type('Fixed in #30786'); await settle();
    const close = host.querySelector<HTMLButtonElement>('.pr-composer__close');
    assert(close?.textContent === 'Close with comment', 'A typed draft turns Close into Close with comment');
    close.click(); await settle();
    assert(writes.join() === 'comment #7: Fixed in #30786,close #7', 'Closing with a draft posts it before closing');
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === 'Issue 7 #7' && host.querySelector('.pr-state')?.textContent?.trim() === 'Closed',
      'The closed issue stays open and shows its new state');
    assert(composer().value === '' && host.querySelector('.pr-composer__close') === null, 'The posted draft clears and a closed issue offers no close');
    assert(host.querySelector('.attention-visit-status') === null,
      'Completed issue actions do not add a visit label to the toolbar');
    open(8); await settle();
    assert(loadHandled()['issue:workspace-1:7']?.label === 'Updated state · Posted 1 comment', 'Leaving the issue acknowledges the completed visit');
    assert(row(7) === undefined && host.querySelector('.issues-list__count')?.textContent === '2', 'Leaving the closed issue drops it from the open list');
    type('Draft for #8'); await settle();
    open(9); await settle();
    assert(composer().value === '', 'A draft never follows the reader to another issue');
    assert(loadHandled()['issue:workspace-1:8']?.label === 'Viewed', 'Unsent draft text does not count as a posted comment');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function zoomsOneIssue(): Promise<void> {
  const issues = [7, 8].map((number): Issue => ({
    id: 7000 + number, number, title: `Issue ${number}`, body: 'Details', author: 'alice', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  window.bridge = {
    ...issueReads(() => issues),
  } as unknown as Bridge;
  let closed = 0;
  const baseHost = document.createElement('div'); document.body.append(baseHost);
  const baseRoot = createRoot(baseHost);
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => baseRoot.render(<LayerCovered.Provider value={true}><WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={8} /></LayerCovered.Provider>));
    await settle();
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app"
      initialIssueNumber={8} onClose={() => { closed += 1; root.unmount(); }} />));
    await settle();
    assert(host.querySelector('.pulls-list') === null, 'The zoom layer shows one issue, never the list');
    const card = host.querySelector<HTMLElement>('.issues-detail.zoom-card');
    assert(card?.querySelector('h1')?.textContent?.trim() === 'Issue 8 #8',
      'The zoom layer shows the issue it was opened on, not the first row of the list');
    const box = card.getBoundingClientRect();
    assert(box.top > 0 && box.left > 0 && box.bottom < window.innerHeight && box.height > 200,
      `The card leaves the page visible around it, not ${JSON.stringify(box.toJSON())}`);
    const body = card.querySelector<HTMLElement>('.pull-detail__body');
    assert(body !== null && body.getBoundingClientRect().bottom <= Math.ceil(box.bottom),
      'The issue body scrolls inside the card instead of running past it');
    const pin = element<HTMLButtonElement>('[aria-label="Pin Issue 8"]', card, 'An issue zoom offers a pin action');
    pin.click(); await settle();
    assert(loadPinned().some(item => item.key === 'upstream/app#8' && item.target.kind === 'issue' && item.target.workspaceId === 'workspace-1'),
      'The issue pin keeps its workspace route');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    assert(closed === 1, 'Escape leaves the zoom layer');
    assert(baseHost.querySelector('.issues-detail') !== null && loadHandled()['issue:workspace-1:8']?.label === 'Viewed',
      'Leaving zoom handles the issue while the original detail stays mounted');
  }
  finally { flushSync(() => { root.unmount(); baseRoot.unmount(); }); host.remove(); baseHost.remove(); }
}

async function epicTabs(): Promise<void> {
  const issues = [24, 25].map((number): Issue => ({
    id: 7000 + number, number, title: number === 24 ? 'Migrate every connector to MemoryContext' : 'Not an Epic', body: 'Follows #23', author: 'alice',
    authorAvatarUrl: '', htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z', isEpic: number === 24, developmentTask: null,
  }));
  let received = 0;
  const decisions: number[] = [];
  const work = (id: string, title: string, state: WorkItemDto['state'], links: WorkItemDto['links'] = []): WorkItemDto => ({
    id, workspaceId: 'workspace-1', isEpic: false, contextLinks: [], title, kind: 'DEVELOP', acceptanceCriteria: '', completionRule: 'MERGED_DELIVERY', state, revision: 0, links,
    history: [], createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z', priority: 'NORMAL', blockedBy: [], epicId: issueEpicId(7024),
    epicRank: 0 });
  window.bridge = {
    ...issueReads(() => issues),
    listWorkItems: async () => [work('a', 'Port the Hive reader', 'DONE'),
      work('b', 'Port the Delta reader', 'WORKING', [{ kind: 'TASK', key: 'task-9', label: 'Task', title: 'Port Delta', state: 'IDLE', status: 'Idle',
        blocked: false, url: null, activity: null, addedAt: null }]),
      { ...work('c', 'Port the Iceberg reader', 'PLANNED'), revision: received }],
    decideWorkItem: async (_workspace: string, id: string, command: WorkItemDecision): Promise<WorkItemDto> => {
      decisions.push(command.expectedRevision);
      // A receipt lands while the user decides, so the first try names a revision that moved on.
      if (decisions.length === 1) {
        received = 1;
        throw new Error(`Work item changed: ${id}`);
      }
      return { ...work(id, 'Port the Iceberg reader', 'DROPPED'), revision: 2 };
    },
  } as unknown as Bridge;
  // The workspace on screen lends what needs the user; a task of this Epic waits on them.
  lendEpicWorkspace('workspace-1', { needsYou: new Set(['TASK task-9']), epicTitles: new Map(), repository: 'upstream/app' });
  let closed = 0;
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  const tabs = (): HTMLButtonElement[] => [...host.querySelectorAll<HTMLButtonElement>('.epic-tabs button')];
  // Read again after each click, so an earlier assertion cannot narrow it.
  const overviewHidden = (): boolean | undefined => host.querySelector<HTMLElement>('.issue-overview')?.closest<HTMLElement>('.pull-detail__body')?.hidden;
  try {
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={24} onClose={() => { closed += 1; }} openOnWork />));
    await settle(); await settle();
    const labels = tabs().map(tab => tab.textContent?.trim()).join('|');
    assert(labels === 'Issue #24|Work1/3', `The card's tabs name the issue and its work's progress: ${labels}`);
    assert(tabs()[1].getAttribute('aria-pressed') === 'true' && host.querySelector('.epic-view') !== null && overviewHidden() === true,
      'Opened from the drawer, an Epic shows its work, with its issue behind the other tab');
    const waiting = [...host.querySelectorAll<HTMLElement>('.epic-row')].find(row => row.textContent?.includes('Port the Delta reader'));
    assert(waiting?.textContent?.includes('waiting on you') === true, 'The workspace on screen tells the tab what waits on the user');
    tabs()[0].click(); await settle();
    assert(host.querySelector('.epic-view') === null && overviewHidden() === false && tabs()[0].getAttribute('aria-pressed') === 'true',
      'The issue tab shows the issue in the same card');
    tabs()[1].click(); await settle();
    window.location.hash = '';
    element<HTMLButtonElement>('[aria-label="Run Port the Iceberg reader"]', host).click(); await settle();
    const start = takeEpicStart('workspace-1');
    assert(start !== null && 'itemId' in start && start.itemId === 'c' && start.draft === 'Port the Iceberg reader' && start.send === true
      && window.location.hash === workspaceRoute('workspace-1'),
      `Run hands work without a source to its workspace to start at once, opening the workspace when another page is on screen: ${JSON.stringify(start)} ${window.location.hash}`);
    element<HTMLButtonElement>('[aria-label="Drop Port the Iceberg reader"]', host).click(); await settle();
    button('Drop', element('dialog.bq-confirm-dialog')).click();
    await settle(); await settle();
    assert(decisions.join(',') === '0,1' && host.querySelector('.epic-view__error') === null,
      `A decision a receipt made stale goes again at the current revision: ${decisions.join(',')}`);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await settle();
    assert(closed === 1, 'Escape leaves the Epic from either tab');

    // Opened anywhere else, the Epic's issue shows first and its work is one tab away; an issue that is no Epic has no tabs.
    flushSync(() => root.render(<WorkspaceIssuesScreen key="elsewhere" workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={24} onClose={() => {}} />));
    await settle(); await settle();
    assert(tabs().length === 2 && tabs()[0].getAttribute('aria-pressed') === 'true' && host.querySelector('.epic-view') === null && overviewHidden() === false,
      'An Epic opened elsewhere shows its issue first');
    tabs()[1].click(); await settle();
    assert(host.querySelector('.epic-view') !== null, 'Its Work tab is there too');
    flushSync(() => root.render(<WorkspaceIssuesScreen key="plain" workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={25} onClose={() => {}} />));
    await settle(); await settle();
    assert(host.querySelector('.issue-overview') !== null && tabs().length === 0, 'An issue that is no Epic has no tabs');
  }
  finally { lendEpicWorkspace('workspace-1', null); window.location.hash = ''; flushSync(() => root.unmount()); host.remove(); }
}

async function filtersLoadedIssues(): Promise<void> {
  const issues: Issue[] = [
    { number: 7, title: 'Flaky connector', author: 'alice', labels: ['enhancement', 'ui'] },
    { number: 8, title: 'Flaky planner', author: 'albert', labels: ['correctness'] },
    { number: 41, title: 'Backport 7', author: 'bob', labels: ['enhancement'] },
    { number: 42, title: 'Repair connector', author: 'alice', labels: ['ui'] },
  ].map((issue): Issue => ({
    ...issue, id: 7000 + issue.number, body: 'Details', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${issue.number}`, assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  const repository = { owner: 'upstream', repo: 'app' };
  const listReads: number[] = [];
  let writes = 0;
  const originalBridge = window.bridge;
  window.bridge = {
    ...issueReads(() => issues, repository),
    fetchWorkspaceIssues: async (_workspace: string, page: number): Promise<RepositoryIssueSnapshotDto> => {
      listReads.push(page);
      return { repositoryId: 40, repository,
        issues: page === 0 ? issues : [{ ...issues[0], id: 7070, number: 70, title: 'Found later', author: 'carol', labels: ['correctness', 'later'] }],
        page, pageSize: 50, totalIssues: issues.length + 1, hasMore: page === 0, syncedAt: null };
    },
    closeWorkspaceIssue: async () => { writes++; },
    commentWorkspaceIssue: async () => { writes++; },
    updateWorkspaceIssueMetadata: async () => { writes++; },
    updateWorkspaceIssueType: async () => { writes++; },
    setWorkspaceIssueEpic: async () => { writes++; },
  } as unknown as Bridge;
  const host = document.createElement('div'); document.body.append(host);
  host.style.cssText = 'width: 1400px; height: 900px';
  const root = createRoot(host);
  const numbers = () => [...host.querySelectorAll('.issues-list .pull-row__title small')].map(row => row.textContent).join();
  const search = async (value: string) => {
    const field = element<HTMLInputElement>('input[type="search"][aria-label="Search issues by title, number, or author"]', host, 'The issue list offers the same search terms as pull requests');
    changeInput(field, value);
    await settle();
  };
  const selectLabel = async (value: string) => {
    const select = element<HTMLSelectElement>('select[aria-label="Filter issues by label"]', host, 'The issue list offers a label filter');
    select.value = value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
  };
  const clickLabel = async (label: string, pressed: boolean) => {
    const button = host.querySelector<HTMLButtonElement>(`button[aria-label="Filter by label ${label}"]`);
    assert(button !== null && button.getAttribute('aria-pressed') === String(pressed), 'Label buttons expose their selected state');
    button.click();
    await settle();
  };
  try {
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={7} />));
    await settle();
    assert(numbers() === '#7,#8,#41,#42', 'The initial list shows all loaded issues');
    assert(host.querySelector('button button') === null, 'Clickable labels must not be nested in the issue selection button');
    await search('@AL');
    assert(numbers() === '#7,#8,#42', 'An author prefix is case-insensitive');
    await search('@AL flaky');
    assert(numbers() === '#7,#8', 'Author and title search terms intersect');
    await selectLabel('enhancement');
    assert(numbers() === '#7', 'The selected label also narrows the search');
    const options = [...host.querySelectorAll<HTMLOptionElement>('select[aria-label="Filter issues by label"] option')].map(option => option.value).sort();
    assert(options.join() === ',correctness,enhancement,ui', 'Label options retain every loaded label while filtering');
    await search('#41');
    assert(numbers() === '#41', 'A number prefix searches inside the selected label');
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === 'Flaky connector #7', 'Filtering keeps the issue being read open');
    await search('7');
    assert(numbers() === '#7', 'A numeric search matches issue numbers rather than title digits');
    await search('');
    assert(numbers() === '#7,#41', 'Clearing search retains the selected label');
    await clickLabel('enhancement', true);
    assert(numbers() === '#7,#8,#41,#42', 'Clicking the selected label clears the filter');
    await clickLabel('correctness', false);
    assert(numbers() === '#8', 'Clicking another issue label filters the list');
    await search('missing');
    assert(numbers() === '' && host.querySelector('.issues-list .hp-empty') !== null, 'A search with no matches has an empty state');
    await selectLabel('');
    await search('');
    assert(numbers() === '#7,#8,#41,#42', 'Clearing both filters restores the loaded issues');
    assert(listReads.join() === '0' && writes === 0, 'Filtering does not fetch pages or change GitHub or issue metadata');
    await selectLabel('correctness');
    await search('@carol');
    const list = element<HTMLElement>('.issues-list', host, 'The issue list stays mounted when no rows match');
    list.style.flex = '0 0 280px';
    await settle();
    const bounds = list.getBoundingClientRect();
    for (const control of list.querySelectorAll('.issues-search input, .issues-search select')) {
      const box = control.getBoundingClientRect();
      assert(box.width > 0 && box.left >= bounds.left && box.right <= bounds.right,
        'Search and label controls remain inside a narrow issue pane');
    }
    list.querySelector('.pulls-list__rows')?.dispatchEvent(new Event('scroll'));
    await settle();
    assert(listReads.join() === '0', 'A filtered list shrinking or scrolling does not automatically fetch another page');
    const loadMore = list.querySelector<HTMLButtonElement>('.issues-load-more');
    assert(loadMore !== null && !loadMore.disabled && numbers() === '', 'More issues can be loaded even when the filters match no current rows');
    loadMore.click();
    await settle();
    assert(listReads.join() === '0,1' && numbers() === '#70', 'Explicitly loading more keeps and applies both filters to the new page');
    assert(list.querySelector('option[value="later"]') !== null && list.querySelector('.issues-load-more') === null,
      'New page labels become available and pagination ends with the last page');
    assert(writes === 0, 'Loading more filtered issues does not write issue metadata');
  }
  finally { flushSync(() => root.unmount()); host.remove(); window.bridge = originalBridge; }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const scenarios: [string, () => Promise<void>][] = [
    ['Closing an issue keeps it in view until the reader leaves', closeKeepsIssue],
    ['An issue reference zooms that issue over the page it came from', zoomsOneIssue],
    ['An Epic switches between its issue and its work in one card', epicTabs],
    ['Search and clickable labels filter only the loaded issues', filtersLoadedIssues],
    ['A closed issue reminder opens the latest read-only preview', closedIssueReminder],
  ];
  const results: { name: string; error?: string }[] = [];
  for (const [name, scenario] of scenarios) {
    try {
      await settle(); saveHandled({});
      await scenario();
      results.push({ name });
    }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}

async function closedIssueReminder(): Promise<void> {
  let preview = { title: 'Closed issue title', body: 'Latest closed issue description', author: 'alice', labels: ['fixed'],
    updatedAt: '2026-09-25T10:00:00Z', state: 'closed', pullRequest: false, htmlUrl: 'https://github.com/upstream/app/issues/99', authorAvatarUrl: '' };
  const repository = { owner: 'upstream', repo: 'app' };
  let previewReads = 0;
  window.bridge = {
    fetchWorkspaceIssues: async (): Promise<RepositoryIssueSnapshotDto> => ({ repositoryId: 40, repository, issues: [], page: 0, pageSize: 50, totalIssues: 0, hasMore: false, syncedAt: null }),
    fetchWorkspaceIssue: async (): Promise<null> => null,
    fetchWorkspaceIssuePreview: async (workspaceId: string, number: number) => {
      assert(workspaceId === 'workspace-1' && number === 99, 'The fallback reads the exact original issue');
      previewReads++; return preview;
    },
    getWorkspaceMetadata: async (): Promise<RepositoryMetadataDto> => ({ repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null }),
    recordRecentTouch: async () => {},
  } as unknown as Bridge;
  const reminder = issueAttentionItem({ id: 7099, number: 99, title: 'Original title', body: 'Original body', author: 'alice', authorAvatarUrl: '',
    htmlUrl: 'https://github.com/upstream/app/issues/99', labels: [], assignees: ['bob'], commentCount: 7, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z' },
  'upstream/app', 'workspace-1');
  saveHandled({ [reminder.key]: handledEntry(reminder, 'snooze', Date.now() - 1000, '1 hour') });
  const host = document.createElement('div'); document.body.append(host);
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<WorkspaceIssuesScreen workspaceId="workspace-1" repository="upstream/app" initialIssueNumber={99}
      onStartDevelopment={() => { throw new Error('Closed issues cannot launch development'); }} onClose={() => root.unmount()} />));
    await settle();
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === `${preview.title} #99` && host.textContent?.includes(preview.body),
      'The closed issue displays the latest title and description');
    assert(host.querySelector('.pr-state.is-closed')?.textContent?.includes('Closed') && host.textContent?.includes('fixed'),
      'The preview preserves the latest source state and labels');
    assert(host.querySelector('.pr-composer, [aria-label="Start development"], [aria-label="Mark as Epic"], [aria-label="Issue type"]') === null,
      'The read-only preview offers no source mutations or development');
    assert(host.querySelector('[aria-label="Pin Closed issue title"]') !== null && host.querySelector('.attention-visit-status') === null,
      'Closed issue previews retain pinning without a visit label');
    preview = { ...preview, title: 'Updated closed issue', body: 'Description changed after closure', updatedAt: '2026-09-26T10:00:00Z' };
    host.querySelector<HTMLButtonElement>('[aria-label="Refresh"]')?.click(); await settle();
    assert(previewReads === 2 && host.textContent?.includes(preview.body), 'Manual refresh updates the closed issue preview');
    host.querySelector<HTMLButtonElement>('[aria-label="Close issue details"]')?.click(); await settle();
    assert(loadHandled()['issue:workspace-1:99']?.label === 'Viewed' && loadHandled()['issue:workspace-1:99']?.seenUpdatedAt === preview.updatedAt,
      'Closing acknowledges the exact latest preview that was read');
    const latestReminder = await refreshSnoozedItem(reminder, window.bridge);
    assert(visibleAttention([latestReminder], loadHandled(), Date.now()).length === 0,
      'The handled closed preview matches the retained reminder, including its source state and comment count');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
