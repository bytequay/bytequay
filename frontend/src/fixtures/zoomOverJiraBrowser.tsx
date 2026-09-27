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
import App from '../App';
import { createPreviewBridge } from '../agents/fixtures/previewBridge';
import { jiraRoute, type JiraConnection } from '../jira/contracts';
import { jiraDetail, jiraIssue } from '../jira/fixtures/jira';
import type { Bridge, InAppBrowserState, RepositoryIssueSnapshotDto, WorkspaceDto } from '../types';
import { assert, settle } from './harness';
import '../index.css';

const connection: JiraConnection = {
  id: 'connection', authType: 'api_token', tokenEmail: 'alex@example.com', state: 'connected',
  account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' },
  sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }],
  authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null,
};
const ticket = { ...jiraIssue, body: 'Same failure as https://github.com/trinodb/trino/issues/25702 after the upgrade.' };
const issue: RepositoryIssueSnapshotDto['issues'][number] = {
  id: 925702, number: 25702, title: 'Not an Avro data file', body: `Tracked in ${jiraIssue.url}`, author: 'alice', authorAvatarUrl: '',
  htmlUrl: 'https://github.com/trinodb/trino/issues/25702', labels: [], assignees: [], commentCount: 0,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-02T00:00:00Z',
};
const repository = { owner: 'trinodb', repo: 'trino' };

async function until(condition: () => boolean, message: string) {
  for (let i = 0; i < 45 && !condition(); i++) await settle();
  assert(condition(), message);
}

/** The element a pointer would hit at the middle of `element`, top layer included. */
function onTop(element: Element | null): boolean {
  if (element === null) return false;
  const box = element.getBoundingClientRect();
  return element.contains(document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2));
}

export async function run(): Promise<{ name: string }[]> {
  let browser: InAppBrowserState = { tabs: [], activeTabId: null };
  let showBrowser: (state: InAppBrowserState) => void = () => {};
  const setBrowser = (next: InAppBrowserState) => { browser = next; showBrowser(next); };
  const opened: string[] = [];
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: async () => true,
    getInAppBrowserState: async () => browser,
    onInAppBrowserState: (listener: (state: InAppBrowserState) => void) => { showBrowser = listener; return () => {}; },
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
    stopFindInPage: async () => {},
    listJiraConnections: async () => [connection],
    // The first ready render shows Home before the route is applied.
    listJiraAttention: async () => [],
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    listJiraBoards: async () => [{ id: 828, name: 'Connectors', type: 'scrum' }],
    listJiraSprints: async () => [{ id: 11, name: 'Connector reliability', state: 'active', startDate: null, endDate: null }],
    listJiraIssues: async () => ({ issues: [ticket], fetchedAt: ticket.updatedAt, error: null, partial: false }),
    getJiraIssue: async () => jiraDetail(ticket),
    listWorkspaces: async () => [{ id: 'workspace', name: 'Trino', monitoredOwner: 'trinodb', monitoredRepo: 'trino' } as WorkspaceDto],
    fetchWorkspaceIssue: async () => issue,
    fetchWorkspaceIssues: async () => ({ repositoryId: 40, repository, issues: [issue], page: 0, pageSize: 50, totalIssues: 1, hasMore: false, syncedAt: null }),
    getWorkspaceMetadata: async () => ({ repositoryId: 40, repository, contributors: [], labels: [], topics: [], contributorsSyncedAt: null, labelsTopicsSyncedAt: null }),
    fetchWorkspaceIssueDetail: async () => ({ repositoryId: 40, issueNumber: issue.number, timeline: [], currentType: null, availableTypes: [], refreshedAt: issue.updatedAt }),
    recordRecentTouch: async () => {},
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.location.hash = jiraRoute({ connectionId: 'connection', cloudId: 'site', issueId: ticket.id });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  try {
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
    await until(() => document.querySelector('.inapp-browser-overlay') !== null, 'The Jira page opened from the issue');
    assert(opened.includes(jiraIssue.url), 'The issue opened its Jira link');
    assert(onTop(document.querySelector('.inapp-browser')) && zoomCard() !== null, 'The Jira page is drawn above the issue, which stays behind it');

    document.querySelector<HTMLButtonElement>('.inapp-browser__back-to-app')?.click();
    await until(() => document.querySelector('.inapp-browser-overlay') === null, 'Returned from the Jira page');
    assert(onTop(zoomCard()) && document.querySelector('dialog:modal') === null, 'Returning keeps the issue above the ticket it came from');

    document.querySelector<HTMLButtonElement>('button[aria-label="Close issue details"]')?.click();
    await until(() => zoomCard() === null, 'The issue closed');
    assert(document.querySelector('dialog.jira-detail') === ticketDialog && ticketDialog.matches(':modal') && onTop(ticketDialog),
      'Closing the issue restores the same ticket on top');
    return [{ name: 'the most recently opened layer is drawn on top across a ticket, an issue and a web page' }];
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
