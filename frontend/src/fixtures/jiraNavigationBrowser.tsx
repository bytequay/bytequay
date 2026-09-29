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
import { JIRA_CHANGED, type JiraConnection } from '../jira/contracts';
import type { Bridge } from '../types';
import { assert, settle } from './harness';
import '../css/base.css';

const connected: JiraConnection = {
  id: 'connection', authType: 'api_token', tokenEmail: 'alex@example.com', state: 'connected',
  account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' },
  sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }],
  authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null,
};

const jiraButton = () => document.querySelector<HTMLButtonElement>('.app-sidebar [aria-label="Jira"]');

async function until(condition: () => boolean) {
  for (let i = 0; i < 45 && !condition(); i++) await settle();
  assert(condition(), 'Jira navigation did not settle');
}

export async function run(): Promise<{ name: string }[]> {
  let connections = [connected];
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: async () => true,
    getInAppBrowserState: async () => ({ tabs: [], activeTabId: null }),
    onInAppBrowserState: () => () => {},
    getFullScreen: async () => false,
    onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {},
    onPrAttentionUpdated: () => () => {},
    onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {},
    stopFindInPage: async () => {},
    listJiraConnections: async () => connections,
    listJiraAttention: async () => [],
    listWorkspaces: async () => [],
    fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this navigation fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.location.hash = '#/recent';
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  try {
    await until(() => jiraButton() !== null && document.querySelector('[aria-label="Recent"][aria-current="page"]') !== null);
    const globalLabels = [...document.querySelectorAll('[aria-label="Global navigation"] button')].map(button => button.getAttribute('aria-label'));
    assert(JSON.stringify(globalLabels) === JSON.stringify(['Home', 'Workspaces', 'Circles', 'Recent']), 'Global navigation order is stable');
    assert(jiraButton()?.closest('nav')?.getAttribute('aria-label') === 'Application navigation', 'Jira belongs below global navigation');
    const label = jiraButton()?.querySelector('.app-sidebar__label');
    assert(label !== null && label !== undefined && label.getBoundingClientRect().right <= (jiraButton()?.getBoundingClientRect().right ?? 0), 'The Jira label fits the expanded sidebar');

    const change = async (next: JiraConnection[], event = JIRA_CHANGED) => {
      connections = next;
      window.dispatchEvent(new Event(event));
      await settle();
    };
    await change([]);
    assert(jiraButton() === null, 'Removing the last connection hides Jira');
    await change([{ ...connected, state: 'pending' }]);
    assert(jiraButton() === null, 'Pending connections do not expose Jira');
    await change([{ ...connected, sites: [] }]);
    assert(jiraButton() === null, 'A connection without a site does not expose Jira');
    await change([connected]);
    assert(jiraButton() !== null, 'Connecting a site exposes Jira immediately');
    await change([], 'focus');
    assert(jiraButton() === null, 'Window focus reloads connection visibility');
    await change([connected]);

    const collapse = document.querySelector<HTMLButtonElement>('[aria-label="Collapse sidebar"]');
    assert(collapse !== null, 'The sidebar can collapse');
    collapse.click();
    await settle();
    const icon = jiraButton()?.querySelector('svg');
    const sidebar = document.querySelector('.app-sidebar');
    assert(icon !== null && icon !== undefined && sidebar !== null, 'Collapsed Jira keeps its icon');
    const bounds = icon.getBoundingClientRect();
    const sidebarBounds = sidebar.getBoundingClientRect();
    assert(bounds.width === 16 && bounds.left >= sidebarBounds.left && bounds.right <= sidebarBounds.right, 'The Jira outline fits the collapsed sidebar');
    assert(getComputedStyle(icon).fill === 'none', 'The Jira icon stays an outline');
    return [{ name: 'connected-site navigation loads, updates and fits the collapsed sidebar' }];
  }
  finally { flushSync(() => root.unmount()); host.remove(); }
}
