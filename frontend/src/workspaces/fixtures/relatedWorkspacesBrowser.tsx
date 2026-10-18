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
import { workspaceFixture } from './workspace';
import { RelatedWorkspaceNavigation, RelatedWorkspaceSettings } from '../RelatedWorkspaces';
import App from '../../App';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { relatedWorkspaceRoute, workspaceRoute } from '../model';
import type { Bridge, WorkspaceDto, WorkspaceRelationshipDto } from '../../types';
import { assert, changeInput, settle, until } from '../../fixtures/harness';
import '../../index.css';
import '../../css/settings-window.css';

const workspace = (id: string, repositoryId: number, name: string): WorkspaceDto => workspaceFixture({
  id, name, selectedOwner: 'owner', selectedRepo: id, monitoredOwner: 'apache', monitoredRepo: id,
  selectedRepositoryId: repositoryId, monitoredRepositoryId: repositoryId,
  localRepositoryPath: `/managed/${id}`, lastCodeSyncAt: '2026-01-01T00:00:00Z',
});
const trino = workspace('trino', 1, 'Trino');
const iceberg = workspace('iceberg', 2, 'Apache Iceberg');
const alias = workspace('alias', 1, 'Same monitored project');

export function mount() {
  let links: WorkspaceRelationshipDto[] = [];
  let reject = false;
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getWorkspaceRelationships: async () => links,
    listWorkspaces: async () => [trino, iceberg, alias],
    saveWorkspaceRelationship: async (_: string, link: WorkspaceRelationshipDto) => {
      if (reject) throw new Error('Workspace became unavailable');
      links = [...links.filter(item => item.workspaceId !== link.workspaceId), link];
      return links;
    },
    removeWorkspaceRelationship: async (_: string, id: string) => { links = links.filter(item => item.workspaceId !== id); return links; },
  } });
  const host = document.createElement('div');
  host.style.cssText = 'display:flex;max-width:1000px;margin:36px auto;gap:32px';
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<><aside style={{ width: 190 }}><RelatedWorkspaceNavigation workspaceId="trino" /></aside>
    <main className="setw-page" style={{ flex: 1 }}><h1>Repository</h1><RelatedWorkspaceSettings workspace={trino} /></main></>));
  return { host, reject: (value: boolean) => { reject = value; },
    missingCheckout: () => { links = [{ workspaceId: 'iceberg', kind: 'DEPENDENCY', backportByDefault: false, guidance: 'Keep this', unavailableReason: 'Managed checkout missing' }]; window.dispatchEvent(new Event('bytequay:relationships-changed')); },
    unavailable: () => { links = [{ workspaceId: 'missing', kind: 'FORK', backportByDefault: false, guidance: 'Saved guidance' }]; window.dispatchEvent(new Event('bytequay:relationships-changed')); },
    close: () => { flushSync(() => root.unmount()); host.remove(); } };
}

async function settingsScenario() {
  const view = mount();
  const button = (label: string) => {
    const found = [...view.host.querySelectorAll('button')].find(item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label);
    assert(found !== undefined && !found.disabled, `Enabled ${label} button`);
    return found;
  };
  const click = async (label: string) => { button(label).click(); await settle(); };
  try {
    await settle();
    assert(view.host.textContent?.includes('No related workspaces'), 'Empty state');
    await click('+ Add related workspace');
    await click('Related workspace');
    const options = [...view.host.querySelectorAll<HTMLButtonElement>('[role="option"]')];
    assert(options.find(item => item.textContent?.includes('Trino'))?.disabled, 'Self disabled');
    assert(options.find(item => item.textContent?.includes('Same monitored project'))?.disabled, 'Same logical project disabled');
    await click('Apache Iceberg');
    assert(button('Dependency').getAttribute('aria-checked') === 'false', 'Type requires an explicit choice');
    await click('Dependency');
    const guidance = view.host.querySelector<HTMLTextAreaElement>('textarea');
    assert(guidance !== null, 'Guidance input');
    changeInput(guidance, 'For connector behavior, read Iceberg. Version is in pom.xml.');
    await settle();
    view.reject(true);
    await click('Add');
    assert(view.host.querySelector('[role="alert"]')?.textContent === 'Workspace became unavailable', 'Server rejection inline');
    assert(view.host.querySelector('textarea')?.value.includes('pom.xml'), 'Failed save keeps guidance');
    view.reject(false);
    await click('Add');
    assert(view.host.querySelector('textarea') === null, 'Successful save closes form');
    assert(view.host.querySelectorAll('a[href="#/workspaces/trino/related/iceberg/commits"]').length === 2, 'Settings and sidebar keep the current workspace');
    assert(view.host.querySelectorAll('a').length === 4, 'Memory and commits links on both surfaces');
    const row = view.host.querySelector<HTMLDetailsElement>('.related-nav-row');
    assert(row !== null && !row.open, 'Related repository starts collapsed');
    row.querySelector('summary')?.click(); await settle();
    assert(row.open, 'Clicking the repository expands its links');
    row.querySelector('summary')?.click(); await settle();
    assert(!row.open, 'Clicking again folds its links');
    await click('Edit Apache Iceberg');
    await click('Fork');
    const backport = view.host.querySelector<HTMLInputElement>('input[type="checkbox"]');
    assert(backport !== null, 'Fork exposes a default backport source');
    backport.click(); await settle();
    await click('Save');
    assert(view.host.textContent?.includes('Default backport source → Trino'), 'Default backport direction is saved and shown');
    assert(view.host.querySelector('.related-kind')?.textContent === 'Fork', 'Edit updates sidebar');
    await click('Remove Apache Iceberg');
    assert(view.host.querySelector('[aria-label="Related workspaces"]') === null, 'Removed link disappears from sidebar');
    view.missingCheckout(); await settle();
    assert(view.host.querySelectorAll('a').length === 0, 'Missing checkout disables links on both surfaces');
    assert(view.host.textContent?.includes('Keep this'), 'Missing checkout keeps guidance');
    view.unavailable(); await settle();
    assert(view.host.textContent?.includes('Workspace unavailable'), 'Unavailable destination keeps saved relationship visible');
    await click('Remove missing');
    return [{ name: 'settings, server errors, navigation, edit and removal' }];
  } finally { view.close(); }
}

async function navigationScenario() {
  const reads: string[] = [];
  let links: WorkspaceRelationshipDto[] = [{ workspaceId: iceberg.id, kind: 'FORK', guidance: '', backportByDefault: false }];
  const bridge = {
    ...createPreviewBridge(trino.id),
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
    listJiraConnections: async () => [],
    listJiraAttention: async () => [],
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    fetchRecentChanges: async () => [],
    listGoals: async () => [],
    listWorkItems: async () => [],
    listWorkspaces: async () => [trino, iceberg],
    listRetiredWorkspaces: async () => [],
    listCherryPickExecutions: async () => [],
    getWorkspace: async (id: string) => { reads.push(`workspace:${id}`); return id === trino.id ? trino : iceberg; },
    getWorkspaceRelationships: async () => links,
    getMemoryPage: async (id: string) => {
      reads.push(`memory:${id}`);
      return { memories: [], guidelines: [], head: id, switches: { reviews: true, development: true }, harvest: { cursor: null, last: null } };
    },
    getRepositoryContextSpecChecks: async () => [],
    getRepositoryContextBuild: async () => ({ buildRequestSequence: 0, canStart: true, build: null }),
    listReviewReplays: async () => [],
    getWorkspaceSettings: async () => ({ agents: [], remotes: [], commitsTarget: { remote: 'origin', branch: 'main', overridden: false } }),
    gitOverview: async () => ({ currentBranch: 'main', branches: ['main'], remotes: [] }),
    gitRemoteBranches: async () => ['main'],
    gitStatus: async () => ({ branch: 'main', files: [] }),
    gitDiff: async () => [],
    gitLog: async (id: string) => {
      reads.push(`commits:${id}`);
      return { commits: [{ sha: 'a'.repeat(40), title: `${id} commit`, body: '', author: 'Alex', committer: 'Alex',
        authoredAt: '2026-01-01T00:00:00Z', committedAt: '2026-01-01T00:00:00Z' }], refresh: { workspaceId: id, state: 'IDLE' as const } };
    },
  } satisfies Partial<Bridge>;
  Object.defineProperty(window, 'bridge', { configurable: true, value: bridge });
  window.localStorage.clear();
  window.location.hash = workspaceRoute(trino.id, 'context');
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  const ownTab = (label: string) => host.querySelector<HTMLButtonElement>(`[aria-label="Workspace navigation"] [aria-label="${label}"]`);
  const relatedLink = (tab: 'commits' | 'context') => host.querySelector<HTMLAnchorElement>(`a[href="${relatedWorkspaceRoute(trino.id, iceberg.id, tab)}"]`);
  const selected = (tab: 'commits' | 'context') => relatedLink(tab)?.getAttribute('aria-current') === 'page';
  const staysHere = () => {
    assert(host.querySelector('.app-sidebar__ws-name')?.textContent === trino.name, 'Current workspace stays selected');
    assert(!reads.includes(`workspace:${iceberg.id}`), 'App never switches to the related workspace');
  };
  try {
    await until(() => host.querySelector('.related-nav-row') !== null && host.querySelector('.mem-head')?.textContent === trino.id, 'Current workspace loaded');
    const row = host.querySelector<HTMLDetailsElement>('.related-nav-row');
    assert(row !== null && !row.open, 'Related repository starts folded in the app');
    row.querySelector('summary')?.click(); await settle();
    relatedLink('commits')?.click();
    await until(() => selected('commits') && host.querySelector('.related-view')?.textContent?.includes('iceberg commit') === true, 'Related commits loaded');
    staysHere();
    assert(ownTab('Commits')?.getAttribute('aria-current') === null, 'Current repository commits are not highlighted');
    assert(reads.includes('commits:iceberg'), 'Commits read the related repository');
    const bounds = host.querySelector('.related-view')?.getBoundingClientRect();
    assert(bounds !== undefined && bounds.height > 300 && bounds.bottom <= window.innerHeight, 'Related content fits the main panel');

    relatedLink('context')?.click();
    await until(() => selected('context') && host.querySelector('.related-view .mem-head')?.textContent === iceberg.id, 'Related memory loaded');
    staysHere();
    assert(ownTab('Memory')?.getAttribute('aria-current') === null, 'Current repository memory is not highlighted');
    window.history.back();
    await until(() => selected('commits'), 'Back restores related commits');
    window.history.forward();
    await until(() => selected('context'), 'Forward restores related memory');
    staysHere();

    ownTab('Memory')?.click();
    await until(() => window.location.hash === workspaceRoute(trino.id, 'context') && host.querySelector('.mem-head')?.textContent === trino.id, 'Own memory tab returns to the current repository');
    assert(host.querySelector('.related-view') === null, 'Related panel closes on own tab');
    relatedLink('commits')?.click();
    await until(() => selected('commits'), 'Related commits reopen');
    ownTab('Commits')?.click();
    await until(() => window.location.hash === workspaceRoute(trino.id, 'commits') && host.textContent?.includes('trino commit') === true, 'Own commits tab returns to current repository data');

    links = [];
    window.location.hash = relatedWorkspaceRoute(trino.id, iceberg.id, 'context');
    await until(() => host.textContent?.includes('Related workspace unavailable') === true, 'Removed relationship cannot show stale related content');
    staysHere();
    return [{ name: 'related views preserve workspace, content ownership and navigation history' }];
  } finally { flushSync(() => root.unmount()); host.remove(); }
}

export async function run() {
  return [...await settingsScenario(), ...await navigationScenario()];
}
