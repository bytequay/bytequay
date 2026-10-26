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
import { createFixtureRoot } from './reactFixture';
import App from '../App';
import { zoomReference } from '../githubReference';
import { saveHandled } from '../home/attention';
import type { WorkItemDto, UnifiedWorkItem } from '../workitems/contracts';
import { createPreviewBridge } from '../agents/fixtures/previewBridge';
import { jiraRoute, type JiraConnection, type JiraIssueDetail } from '../jira/contracts';
import { jiraDetail, jiraIssue } from '../jira/fixtures/jira';
import type { Bridge, InAppBrowserState, RepositoryIssueSnapshotDto, WorkspaceDto } from '../types';
import { assert, changeInput, settle, until, runScenarios } from './harness';
import '../index.css';

const connection: JiraConnection = {
  id: 'connection', authType: 'api_token', tokenEmail: 'alex@example.com', state: 'connected',
  account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' },
  sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }],
  authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null,
};
const ticket = { ...jiraIssue, body: 'Same failure as https://github.com/owner/repository/issues/25702 after the upgrade.' };
const issue: RepositoryIssueSnapshotDto['issues'][number] = {
  id: 925702, number: 25702, title: 'Not an Avro data file', body: `Tracked in ${jiraIssue.url}`, author: 'alice', authorAvatarUrl: '',
  htmlUrl: 'https://github.com/owner/repository/issues/25702', labels: [], assignees: [], commentCount: 0,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
};
const repository = { owner: 'owner', repo: 'repository' };

/** The element a pointer would hit at the middle of `element`, top layer included. */
function onTop(element: Element | null): boolean {
  if (element === null) return false;
  const box = element.getBoundingClientRect();
  return element.contains(document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2));
}

const opened: string[] = [];
const lookups: string[] = [];
const posted: string[] = [];

/** The Jira page's own ticket sits in the page; a ticket opened from a link or a row is a layer beside it. */
const ticketLayer = () => [...document.querySelectorAll<HTMLDialogElement>('dialog.jira-detail')].find(dialog => dialog.closest('.app-shell') === null) ?? null;

function installBridge() {
  opened.length = 0; lookups.length = 0; posted.length = 0;
  let browser: InAppBrowserState = { tabs: [], activeTabId: null };
  let showBrowser: (state: InAppBrowserState) => void = () => {};
  const setBrowser = (next: InAppBrowserState) => { browser = next; showBrowser(next); };
  const detail = (): JiraIssueDetail => {
    const saved = jiraDetail(ticket);
    return { ...saved, comments: [...saved.comments, ...posted.map((body, index) => ({ ...saved.comments[0], id: `posted-${index}`, body }))] };
  };
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: async () => true,
    getInAppBrowserState: async () => browser,
    onInAppBrowserState: (listener: (state: InAppBrowserState) => void) => { showBrowser = listener; return () => {}; },
    onLabTask: () => () => {},
    openInAppBrowser: async (url: string) => {
      opened.push(url);
      setBrowser({ tabs: [{ id: 'jira', url, title: 'ENG-1007', canGoBack: false, canGoForward: false, loading: false, pinned: false, name: null, favicon: null, error: null }], activeTabId: 'jira' });
    },
    hideInAppBrowser: async () => setBrowser({ ...browser, activeTabId: null }),
    mountInAppBrowser: async () => {},
    setInAppBrowserBounds: async () => {},
    unmountInAppBrowser: async () => {},
    getFullScreen: async () => false,
    onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {},
    onPrAttentionUpdated: () => () => {},
    onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {},
    onInAppBrowserFind: () => () => {},
    stopFindInPage: async () => {},
    listJiraConnections: async () => [connection],
    refreshJiraIssueByKey: async (_connection: string, _cloud: string, key: string) => {
      lookups.push(key);
      return ticket;
    },
    // Home: the first ready render shows it before the route is applied, and the Home scenario reads its rows.
    listJiraAttention: async () => [ticket],
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    listJiraBoards: async () => [{ id: 828, name: 'Connectors', type: 'scrum' }],
    listJiraSprints: async () => [{ id: 11, name: 'Connector reliability', state: 'active', startDate: null, endDate: null }],
    listJiraIssues: async () => ({ issues: [ticket], fetchedAt: ticket.updatedAt, error: null, nextPageToken: null }),
    recordJiraRecentTouch: async () => {},
    getJiraIssue: async () => detail(),
    commentJiraIssue: async (_location: unknown, body: string) => {
      posted.push(body);
      return detail();
    },
    listWorkspaces: async () => [{ id: 'workspace', name: 'Repository', monitoredOwner: 'owner', monitoredRepo: 'repository' } as WorkspaceDto],
    fetchWorkspaceIssue: async () => issue,
    fetchWorkspaceIssues: async () => ({ repositoryId: 40, repository, issues: [issue], page: 0, pageSize: 50, totalIssues: 1, hasMore: false, syncedAt: null }),
    getWorkspaceMetadata: async () => ({ repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null }),
    fetchWorkspaceIssueDetail: async () => ({ repositoryId: 40, issueNumber: issue.number, timeline: [], currentType: null, availableTypes: [], refreshedAt: issue.updatedAt }),
    recordRecentTouch: async () => {},
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
}

async function withApp(hash: string, scenario: () => Promise<void>): Promise<void> {
  window.localStorage.clear();
  saveHandled({});
  installBridge();
  window.location.hash = hash;
  const { root, dispose: disposeRoot } = createFixtureRoot();
  flushSync(() => root.render(<App />));
  try { await scenario(); }
  finally { disposeRoot(); await settle(); }
}

async function ticketLinksOverThePage(): Promise<void> {
  await withApp(jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: ticket.id }), async () => {
    const zoomCard = () => document.querySelector('.issues-detail.zoom-card');
    await until(() => document.querySelector('dialog.jira-detail .md-reference') !== null, 'The ticket opened with its GitHub reference');
    const ticketDialog = document.querySelector<HTMLDialogElement>('dialog.jira-detail');
    assert(ticketDialog !== null && ticketDialog.matches(':modal') && onTop(ticketDialog), 'The ticket starts as the top layer');

    ticketDialog.querySelector<HTMLAnchorElement>('.md-reference')?.click();
    await until(() => zoomCard()?.querySelector('h1')?.textContent?.includes('Not an Avro data file') ?? false, 'The issue zoomed from the ticket');
    assert(onTop(zoomCard()), 'An issue opened from a ticket is drawn above that ticket');
    assert(document.querySelector('dialog:modal') === null, 'The ticket no longer blocks the issue above it');
    assert(document.querySelector('dialog.jira-detail') === ticketDialog, 'The ticket waits under the issue');

    zoomCard()?.querySelector<HTMLAnchorElement>(`a[href="${jiraIssue.url}"]`)?.click();
    await until(() => ticketLayer()?.matches(':modal') ?? false, 'The Jira link opened the ticket natively');
    assert(lookups.join() === 'ENG-1007' && opened.length === 0, 'The link resolved its key instead of opening the web page');
    assert(onTop(ticketLayer()) && zoomCard() !== null, 'The ticket is drawn above the issue, which stays behind it');
    const look = (dialog: Element | null, selector: string) => {
      const element = dialog?.querySelector(selector);
      return element ? `${getComputedStyle(element).borderTopWidth} ${getComputedStyle(element).borderRadius}` : null;
    };
    // The modal can match before its ticket body renders on a busy machine.
    await until(() => ['.bq-iconbtn', 'textarea'].every(selector => look(ticketLayer(), selector) !== null && look(ticketLayer(), selector) === look(ticketDialog, selector)),
      'A ticket opened from a link looks like the Jira page ticket');
    const reply = ticketLayer()?.querySelector<HTMLTextAreaElement>('textarea');
    assert(reply !== null && reply !== undefined, 'The ticket layer takes comments');
    changeInput(reply, 'Tracked from the issue');
    await settle();
    [...ticketLayer()?.querySelectorAll('button') ?? []].find(button => button.textContent === 'Send')?.click();
    await until(() => ticketLayer()?.textContent?.includes('Tracked from the issue') ?? false, 'The comment posted from the ticket layer');

    ticketLayer()?.querySelector<HTMLButtonElement>('button[aria-label="Open ENG-1007 in Jira"]')?.click();
    await until(() => document.querySelector('.inapp-browser-overlay') !== null, 'The Jira web page opened from the ticket');
    assert(onTop(document.querySelector('.inapp-browser')) && document.querySelector('dialog:modal') === null, 'The web page is drawn above every layer');
    document.querySelector<HTMLButtonElement>('.inapp-browser__back-to-app')?.click();
    await until(() => document.querySelector('.inapp-browser-overlay') === null, 'Returned from the Jira web page');
    assert(ticketLayer()?.matches(':modal') && onTop(ticketLayer()) && zoomCard() !== null, 'Returning keeps the ticket above the issue');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    assert(zoomCard() !== null, 'Escape is not answered by a layer under the top one');

    ticketLayer()?.querySelector<HTMLAnchorElement>('.md-reference')?.click();
    await until(() => onTop(zoomCard()), 'The issue came back to the top');
    assert(document.querySelectorAll('.issues-detail.zoom-card').length === 1 && document.querySelector('dialog:modal') === null,
      'Reopening an open issue brings it forward instead of opening it twice');

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await until(() => zoomCard() === null, 'Escape closed the issue');
    assert(ticketLayer()?.matches(':modal') && onTop(ticketLayer()), 'Escape leaves only the top layer');

    ticketLayer()?.querySelector<HTMLButtonElement>('button[aria-label="Close ticket"]')?.click();
    await until(() => ticketLayer() === null, 'The ticket layer closed');
    assert(document.querySelector('dialog.jira-detail') === ticketDialog && ticketDialog.matches(':modal') && onTop(ticketDialog),
      'Closing every layer restores the same page ticket on top');
    await until(() => ticketDialog.textContent?.includes('Tracked from the issue') ?? false, 'The page ticket shows a comment posted in a layer above it');
  });
}

async function myWorkOverHome(): Promise<void> {
  await withApp('#/home', async () => {
    const card = () => [...document.querySelectorAll<HTMLElement>('.mw-card')].find(item => item.textContent?.includes(ticket.title)) ?? null;
    await until(() => card() !== null, 'My work lists the Jira ticket');
    card()?.click();
    await until(() => ticketLayer()?.matches(':modal') ?? false, 'The My work card opened the ticket');
    assert(window.location.hash === '#/home' && onTop(ticketLayer()), 'The ticket is drawn over Home instead of leaving it');

    [...ticketLayer()?.querySelectorAll('button') ?? []].find(button => button.textContent === 'Start development')?.click();
    const assignment = () => document.querySelector<HTMLDialogElement>(`dialog[aria-label="Assign workspace for ${ticket.key}"]`);
    await until(() => assignment()?.matches(':modal') ?? false, 'Starting from the ticket asks for its workspace');
    assert(window.location.hash === '#/home' && ticketLayer() !== null && onTop(assignment()), 'Development starts over the ticket, which stays over Home');
  });
}

async function localWorkAndTasksOverEpic(): Promise<void> {
  await withApp('#/home', async () => {
    await until(() => document.querySelector('.mw-card') !== null, 'Home is ready');
    const workspace: WorkspaceDto = { id: 'workspace', name: 'Repository', monitoredOwner: 'owner', monitoredRepo: 'repository', selectedOwner: 'owner', selectedRepo: 'repository',
      topology: 'DIRECT', localRepositoryPath: '/managed/repository', defaultBranch: 'master', upstreamDefaultBranch: null, upstreamOwner: null, upstreamRepo: null,
      selectedRepositoryId: 40, monitoredRepositoryId: 40, upstreamRepositoryId: null, lastCodeSyncAt: null, lastMetadataSyncAt: null, displayOrder: 0 };
    const work: WorkItemDto = { id: 'local-work', workspaceId: workspace.id, isEpic: false, contextLinks: [], epicId: `ISSUE:github.com:${issue.id}`, title: 'Retain Arrow memory context',
      acceptanceCriteria: 'Keep memory accounting in the retained context.', kind: 'DEVELOP', completionRule: 'MERGED_DELIVERY', state: 'WORKING', revision: 0,
      history: [], createdAt: issue.createdAt, updatedAt: issue.updatedAt, priority: 'NORMAL', blockedBy: [], links: [
        { kind: 'TASK', key: 'old-attempt', label: 'Task', title: 'Investigate Arrow allocations', state: 'ENDED', status: 'Completed', blocked: false, url: null, activity: null, addedAt: issue.createdAt },
        { kind: 'TASK', key: 'current-attempt', label: 'Task', title: 'Implement Arrow accounting', state: 'WORKING', status: 'Running', blocked: false, url: null, activity: null, addedAt: issue.updatedAt },
      ] };
    const unlinked: WorkItemDto = { ...work, id: 'unlinked', title: 'Check unlinked memory context', links: [] };
    window.bridge.getWorkspace = async () => workspace;
    window.bridge.fetchWorkspaceIssue = async () => ({ ...issue, isEpic: true });
    window.bridge.fetchWorkspaceIssues = async () => ({ repositoryId: 40, repository, issues: [{ ...issue, isEpic: true }], page: 0, pageSize: 50, totalIssues: 1, hasMore: false, syncedAt: null });
    window.bridge.listWorkItems = async () => [work, unlinked];
    window.bridge.listUnifiedWorkItems = async () => [work, unlinked].map<UnifiedWorkItem>(item => ({ id: item.id, title: item.title, description: item.acceptanceCriteria,
      source: 'LOCAL', status: item.state, closed: false, updatedAt: item.updatedAt, reference: null, work: item }));
    const loadedTasks: string[] = [];
    const getTask = window.bridge.getAgentTask;
    window.bridge.getAgentTask = async (id, workspaceId) => { loadedTasks.push(id); return getTask(id, workspaceId); };
    zoomReference({ kind: 'issue', repo: 'owner/repository', number: issue.number, workspaceId: workspace.id });
    const epicLayer = () => document.querySelector<HTMLElement>('.issues-detail.zoom-card');
    await until(() => epicLayer()?.querySelector('.epic-tabs') != null, 'Epic opens');
    const epicCard = epicLayer(); assert(epicCard !== null, 'Epic card exists');
    const workTab = [...epicCard.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim().startsWith('Work'));
    assert(workTab !== undefined, 'Epic has Work tab'); workTab.click();
    await until(() => document.querySelectorAll('.epic-row').length === 2, 'Epic lists both local work items');
    const rows = [...document.querySelectorAll<HTMLElement>('.epic-row')];
    const linkedRow = rows.find(row => row.querySelector('.item-row__title > span')?.textContent === work.title);
    const unlinkedRow = rows.find(row => row.querySelector('.item-row__title > span')?.textContent === unlinked.title);
    assert(linkedRow !== undefined && unlinkedRow !== undefined, 'Both rows have titles');
    const localLayer = () => document.querySelector<HTMLElement>('.zoom-card[aria-label="Local work item"]');
    const taskLayer = () => document.querySelector<HTMLElement>('.zoom-card[aria-label="Task"]');
    unlinkedRow.click();
    await until(() => localLayer()?.textContent?.includes(unlinked.title) ?? false, 'An unlinked card opens its own context');
    localLayer()?.querySelector<HTMLButtonElement>('[aria-label="Close work item"]')?.click(); await settle();
    linkedRow.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await until(() => localLayer()?.textContent?.includes(work.acceptanceCriteria) ?? false, 'Keyboard opens linked local work, not its task');
    assert(onTop(localLayer()) && !onTop(epicLayer()) && window.location.hash === '#/home', 'Local context zooms over the retained Epic');
    localLayer()?.querySelector<HTMLButtonElement>('[aria-label="Enter full screen"]')?.click(); await settle();
    assert(localLayer()?.classList.contains('is-full'), 'Local detail can expand');
    const workCard = localLayer(); assert(workCard !== null, 'Work card exists');
    const viewTask = [...workCard.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'View task');
    assert(viewTask !== undefined, 'Work detail offers its current task'); viewTask.click();
    await until(() => taskLayer()?.querySelector('.agt-screen') != null && loadedTasks.includes('current-attempt'), 'Current task page opens over work');
    assert(onTop(taskLayer()) && !onTop(localLayer()), 'Task is topmost');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle();
    assert(taskLayer() === null && localLayer() !== null && epicLayer() !== null && onTop(localLayer()), 'Escape closes only the task');
    localLayer()?.querySelector<HTMLButtonElement>('[aria-label="Close work item"]')?.click(); await settle();
    const links = linkedRow.querySelectorAll<HTMLButtonElement>('.epic-row__task');
    assert(links.length === 2 && links[0].textContent?.includes('Completed') && links[1].textContent?.includes('Running'), 'Card names both past and current linked tasks');
    links[0].click();
    await until(() => taskLayer()?.querySelector('.agt-screen') != null && loadedTasks.includes('old-attempt'), 'Past task link opens the correct task page');
    assert(localLayer() === null && onTop(taskLayer()) && window.location.hash === '#/home', 'Task link does not bubble into opening local work or navigate');
    taskLayer()?.querySelector<HTMLButtonElement>('[aria-label="Close task"]')?.click(); await settle();
    assert(onTop(epicLayer()), 'Closing the task returns to the Epic');
  });
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results = await runScenarios([
    ['links open tickets, issues and web pages on top of whatever opened them', ticketLinksOverThePage],
    ['My work opens and starts tickets on top of Home', myWorkOverHome],
    ['Local Epic cards and task links retain the underlying layers', localWorkAndTasksOverEpic],
  ]);
  return results;
}
