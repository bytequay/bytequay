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
import WorkspaceIssuesScreen from '../WorkspaceIssuesScreen';
import { assert, settle } from '../../fixtures/harness';
import { handledEntry, issueAttentionItem, loadHandled, saveHandled, visibleAttention } from '../../home/attention';
import { refreshSnoozedItem } from '../../home/useSnoozedAttention';
import { LayerCovered } from '../../Zoom';
import { loadPinned } from '../../home/pinned';

type Issue = RepositoryIssueSnapshotDto['issues'][number];


async function closeKeepsIssue(): Promise<void> {
  const writes: string[] = [];
  let issues = [7, 8, 9].map((number): Issue => ({
    id: 7000 + number, number, title: `Issue ${number}`, body: 'Details', author: 'alice', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  const repository = { owner: 'upstream', repo: 'app' };
  const snapshot = (): RepositoryIssueSnapshotDto => ({
    repositoryId: 40, repository, issues, page: 0, pageSize: 50, totalIssues: issues.length, hasMore: false, syncedAt: null,
  });
  const metadata: RepositoryMetadataDto = {
    repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
  };
  const detail = (number: number): WorkspaceIssueDetailDto => ({
    repositoryId: 40, issueNumber: number, timeline: [], currentType: null, availableTypes: [], refreshedAt: '2026-09-02T00:00:00Z',
  });
  window.bridge = {
    fetchWorkspaceIssues: async () => snapshot(),
    fetchWorkspaceIssue: async (_workspace: string, number: number) => issues.find(issue => issue.number === number),
    getWorkspaceMetadata: async () => metadata,
    fetchWorkspaceIssueDetail: async (_workspace: string, number: number) => detail(number),
    recordRecentTouch: async () => {},
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
    const found = host.querySelector('textarea');
    assert(found !== null, 'Missing issue composer');
    return found;
  };
  const type = (value: string) => {
    const field = composer();
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(field, value);
    field.dispatchEvent(new Event('input', { bubbles: true }));
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
    assert(host.querySelector('.attention-visit-status') === null && loadHandled()['upstream/app#7'] === undefined,
      'Viewing an issue retains its notification without a visit label while its detail stays open');
    type('Fixed in #30786'); await settle();
    const close = host.querySelector<HTMLButtonElement>('.pr-composer__close');
    assert(close?.textContent === 'Close with comment', 'A typed draft turns Close into Close with comment');
    close.click(); await settle();
    assert(writes.join() === 'comment #7: Fixed in #30786,close #7', 'Closing with a draft posts it before closing');
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === 'Issue 7' && host.querySelector('.pr-state')?.textContent?.trim() === 'Closed',
      'The closed issue stays open and shows its new state');
    assert(composer().value === '' && host.querySelector('.pr-composer__close') === null, 'The posted draft clears and a closed issue offers no close');
    assert(host.querySelector('.attention-visit-status')?.textContent === 'Updated state · Posted 1 comment',
      'Only completed issue actions appear in the visit status');
    open(8); await settle();
    assert(loadHandled()['upstream/app#7']?.label === 'Updated state · Posted 1 comment', 'Leaving the issue acknowledges the completed visit');
    assert(row(7) === undefined && host.querySelector('.issues-list__count')?.textContent === '2', 'Leaving the closed issue drops it from the open list');
    type('Draft for #8'); await settle();
    open(9); await settle();
    assert(composer().value === '', 'A draft never follows the reader to another issue');
    assert(loadHandled()['upstream/app#8']?.label === 'Viewed', 'Unsent draft text does not count as a posted comment');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}

async function zoomsOneIssue(): Promise<void> {
  const issues = [7, 8].map((number): Issue => ({
    id: 7000 + number, number, title: `Issue ${number}`, body: 'Details', author: 'alice', authorAvatarUrl: '',
    htmlUrl: `https://github.com/upstream/app/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
  }));
  const repository = { owner: 'upstream', repo: 'app' };
  window.bridge = {
    fetchWorkspaceIssue: async (_workspace: string, number: number) => issues.find(issue => issue.number === number),
    fetchWorkspaceIssues: async (): Promise<RepositoryIssueSnapshotDto> => ({
      repositoryId: 40, repository, issues, page: 0, pageSize: 50, totalIssues: issues.length, hasMore: false, syncedAt: null,
    }),
    getWorkspaceMetadata: async (): Promise<RepositoryMetadataDto> => ({
      repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
    }),
    fetchWorkspaceIssueDetail: async (_workspace: string, number: number): Promise<WorkspaceIssueDetailDto> => ({
      repositoryId: 40, issueNumber: number, timeline: [], currentType: null, availableTypes: [], refreshedAt: '2026-09-02T00:00:00Z',
    }),
    recordRecentTouch: async () => {},
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
    assert(card?.querySelector('h1')?.textContent?.trim() === 'Issue 8',
      'The zoom layer shows the issue it was opened on, not the first row of the list');
    const box = card.getBoundingClientRect();
    assert(box.top > 0 && box.left > 0 && box.bottom < window.innerHeight && box.height > 200,
      `The card leaves the page visible around it, not ${JSON.stringify(box.toJSON())}`);
    const body = card.querySelector<HTMLElement>('.pull-detail__body');
    assert(body !== null && body.getBoundingClientRect().bottom <= Math.ceil(box.bottom),
      'The issue body scrolls inside the card instead of running past it');
    const pin = card.querySelector<HTMLButtonElement>('[aria-label="Pin Issue 8"]');
    assert(pin !== null, 'An issue zoom offers a pin action');
    pin.click(); await settle();
    assert(loadPinned().some(item => item.key === 'upstream/app#8' && item.target.kind === 'issue' && item.target.workspaceId === 'workspace-1'),
      'The issue pin keeps its workspace route');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    assert(closed === 1, 'Escape leaves the zoom layer');
    assert(baseHost.querySelector('.issues-detail') !== null && loadHandled()['upstream/app#8']?.label === 'Viewed',
      'Leaving zoom handles the issue while the original detail stays mounted');
  }
  finally { flushSync(() => { root.unmount(); baseRoot.unmount(); }); host.remove(); baseHost.remove(); }
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
    fetchWorkspaceIssue: async (_workspace: string, number: number) => issues.find(issue => issue.number === number),
    fetchWorkspaceIssues: async (_workspace: string, page: number): Promise<RepositoryIssueSnapshotDto> => {
      listReads.push(page);
      return { repositoryId: 40, repository,
        issues: page === 0 ? issues : [{ ...issues[0], id: 7070, number: 70, title: 'Found later', author: 'carol', labels: ['correctness', 'later'] }],
        page, pageSize: 50, totalIssues: issues.length + 1, hasMore: page === 0, syncedAt: null };
    },
    getWorkspaceMetadata: async (): Promise<RepositoryMetadataDto> => ({
      repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null,
    }),
    fetchWorkspaceIssueDetail: async (_workspace: string, number: number): Promise<WorkspaceIssueDetailDto> => ({
      repositoryId: 40, issueNumber: number, timeline: [], currentType: null, availableTypes: [], refreshedAt: '2026-09-02T00:00:00Z',
    }),
    recordRecentTouch: async () => {},
    closeWorkspaceIssue: async () => { writes++; },
    commentWorkspaceIssue: async () => { writes++; },
    updateWorkspaceIssueMetadata: async () => { writes++; },
    updateWorkspaceIssueType: async () => { writes++; },
    setWorkspaceIssueGoal: async () => { writes++; },
  } as unknown as Bridge;
  const host = document.createElement('div'); document.body.append(host);
  host.style.cssText = 'width: 1400px; height: 900px';
  const root = createRoot(host);
  const numbers = () => [...host.querySelectorAll('.issues-list .pull-row__title small')].map(row => row.textContent).join();
  const search = async (value: string) => {
    const field = host.querySelector<HTMLInputElement>('input[type="search"][aria-label="Search issues by title, number, or author"]');
    assert(field !== null, 'The issue list offers the same search terms as pull requests');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(field, value);
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
  };
  const selectLabel = async (value: string) => {
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Filter issues by label"]');
    assert(select !== null, 'The issue list offers a label filter');
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
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === 'Flaky connector', 'Filtering keeps the issue being read open');
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
    const list = host.querySelector<HTMLElement>('.issues-list');
    assert(list !== null, 'The issue list stays mounted when no rows match');
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
    updatedAt: '2026-09-25T10:00:00Z', state: 'closed', pullRequest: false };
  const repository = { owner: 'upstream', repo: 'app' };
  let previewReads = 0;
  window.bridge = {
    fetchWorkspaceIssues: async (): Promise<RepositoryIssueSnapshotDto> => ({ repositoryId: 40, repository, issues: [], page: 0, pageSize: 50, totalIssues: 0, hasMore: false, syncedAt: null }),
    fetchWorkspaceIssue: async () => { throw new Error('The closed issue is no longer cached'); },
    fetchGitHubIssuePreview: async (owner: string, repo: string, number: number) => {
      assert(owner === 'upstream' && repo === 'app' && number === 99, 'The fallback reads the exact original issue');
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
    assert(host.querySelector('.issues-detail h1')?.textContent?.trim() === preview.title && host.textContent?.includes(preview.body),
      'The closed issue displays the latest title and description');
    assert(host.querySelector('.pr-state.is-closed')?.textContent?.includes('Closed') && host.textContent?.includes('fixed'),
      'The preview preserves the latest source state and labels');
    assert(host.querySelector('.pr-composer, [aria-label="Start development"], [aria-label="Mark as Goal"], [aria-label="Issue type"]') === null,
      'The read-only preview offers no source mutations or development');
    assert(host.querySelector('[aria-label="Pin Closed issue title"]') !== null && host.querySelector('.attention-visit-status') === null,
      'Closed issue previews retain pinning without a visit label');
    preview = { ...preview, title: 'Updated closed issue', body: 'Description changed after closure', updatedAt: '2026-09-26T10:00:00Z' };
    host.querySelector<HTMLButtonElement>('[aria-label="Refresh"]')?.click(); await settle();
    assert(previewReads === 2 && host.textContent?.includes(preview.body), 'Manual refresh updates the closed issue preview');
    host.querySelector<HTMLButtonElement>('[aria-label="Close issue details"]')?.click(); await settle();
    assert(loadHandled()['upstream/app#99']?.label === 'Viewed' && loadHandled()['upstream/app#99']?.seenUpdatedAt === preview.updatedAt,
      'Closing acknowledges the exact latest preview that was read');
    const latestReminder = await refreshSnoozedItem(reminder, window.bridge);
    assert(visibleAttention([latestReminder], loadHandled(), Date.now()).length === 0,
      'The handled closed preview matches the retained reminder, including its source state and comment count');
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
