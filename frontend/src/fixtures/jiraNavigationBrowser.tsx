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
import type { AppNavigationTarget } from '../appNavigation';
import { createPreviewBridge } from '../agents/fixtures/previewBridge';
import { zoomReference } from '../githubReference';
import { JIRA_CHANGED, type JiraConnection } from '../jira/contracts';
import type { Bridge, InAppBrowserState, WorkspaceDto } from '../types';
import { assert, settle, until, element, changeInput } from './harness';
import '../css/base.css';

const connected: JiraConnection = {
  id: 'connection', authType: 'api_token', tokenEmail: 'alex@example.com', state: 'connected',
  account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@example.com' },
  sites: [{ id: 'site', name: 'Engineering', url: 'https://example.atlassian.net' }],
  authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null,
};

const jiraButton = () => document.querySelector<HTMLButtonElement>('.app-sidebar [aria-label="Jira"]');
const workspace: WorkspaceDto = {
  id: 'workspace', name: 'Example', selectedRepositoryId: 1, selectedOwner: 'example', selectedRepo: 'app',
  monitoredRepositoryId: 1, monitoredOwner: 'example', monitoredRepo: 'app', topology: 'DIRECT', defaultBranch: 'main',
  upstreamRepositoryId: null, upstreamOwner: null, upstreamRepo: null, upstreamDefaultBranch: null,
  localRepositoryPath: '/fixture/app', lastCodeSyncAt: null, lastMetadataSyncAt: null, displayOrder: 0,
};

export async function run(): Promise<{ name: string }[]> {
  let connections = [connected];
  let navigate: (target: AppNavigationTarget) => void = () => {};
  let assistantOpens: (target: { hash: string }) => void = () => {};
  let browserChanged: (state: InAppBrowserState) => void = () => {};
  let browser: InAppBrowserState = { tabs: [], activeTabId: null };
  let browserHidden = 0;
  let deferBrowserHide = false;
  let finishStartup: (connected: boolean) => void = () => {};
  const startup = new Promise<boolean>(resolve => { finishStartup = resolve; });
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: () => startup,
    onAppNavigation: callback => { navigate = callback; return () => { navigate = () => {}; }; },
    onAssistantNavigate: callback => { assistantOpens = callback; return () => { assistantOpens = () => {}; }; },
    getInAppBrowserState: async () => browser,
    onInAppBrowserState: callback => { browserChanged = callback; return () => { browserChanged = () => {}; }; },
    hideInAppBrowser: async () => {
      browserHidden++;
      if (!deferBrowserHide) { browser = { ...browser, activeTabId: null }; browserChanged(browser); }
    },
    mountInAppBrowser: async () => {},
    setInAppBrowserBounds: async () => {},
    unmountInAppBrowser: async () => {},
    onInAppBrowserFind: () => () => {},
    onLabTask: () => () => {},
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
    listRetiredWorkspaces: async () => [],
    getWorkspace: async () => workspace,
    getWorkspaceSettings: async () => { throw new Error('No workspace settings in this navigation fixture'); },
    getAppVersion: async () => ({ version: '0.0.0' }),
    getAppPreferences: async () => ({ taskRetentionDays: 14, recentRetentionDays: 14, prWarmCount: 10 as const, autoMergeDelayMinutes: 0, theme: 'SYSTEM' as const }),
    listCherryPickExecutions: async () => [],
    fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this navigation fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.history.replaceState({ fixture: true }, '', '#/recent');
  const { root, dispose: disposeRoot } = createFixtureRoot();
  flushSync(() => root.render(<App />));
  try {
    navigate('home');
    await settle();
    assert(window.location.hash === '#/recent', 'Native navigation waits until onboarding and startup are complete');
    finishStartup(true);
    await until(() => jiraButton() !== null && document.querySelector('[aria-label="Recent"][aria-current="page"]') !== null,
      'Jira navigation did not settle');
    const back = () => element<HTMLButtonElement>('.app-sidebar [aria-label="Back"]', document, `Back button is present at ${window.location.hash}`);
    const forward = () => element<HTMLButtonElement>('.app-sidebar [aria-label="Forward"]', document, `Forward button is present at ${window.location.hash}`);
    const at = (hash: string, canGoBack: boolean, canGoForward: boolean) => until(() =>
      window.location.hash === hash && back().disabled === !canGoBack && forward().disabled === !canGoForward,
    `History controls did not update for ${hash}`);
    await at('#/recent', false, false);
    assert(window.location.protocol === 'file:', 'Native history works in the packaged renderer scheme');
    assert(JSON.stringify(window.history.state) === '{"fixture":true}', 'App startup preserves history state without a navigation stamp');
    window.location.hash = '#/workspaces';
    await at('#/workspaces', true, false);
    window.location.hash = '#/recent';
    await at('#/recent', true, false);
    back().click();
    await at('#/workspaces', true, true);
    forward().click();
    await at('#/recent', true, false);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '[', metaKey: true }));
    await at('#/workspaces', true, true);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: ']', metaKey: true }));
    await at('#/recent', true, false);
    back().click();
    await at('#/workspaces', true, true);
    window.location.hash = '#/pulls';
    await at('#/pulls', true, false);
    window.history.replaceState({ fixture: 'replaced' }, '', '#/recent');
    await at('#/recent', true, false);
    back().click();
    await at('#/workspaces', true, true);
    forward().click();
    await at('#/recent', true, false);

    const globalLabels = [...document.querySelectorAll('[aria-label="Global navigation"] button')].map(button => button.getAttribute('aria-label'));
    assert(JSON.stringify(globalLabels) === JSON.stringify(['Assistant', 'Home', 'Workspaces', 'Circles', 'Recent']), 'Global navigation order is stable');
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

    const collapse = element<HTMLButtonElement>('[aria-label="Collapse sidebar"]', document, 'The sidebar can collapse');
    collapse.click();
    await settle();
    const icon = jiraButton()?.querySelector('svg');
    const sidebar = document.querySelector('.app-sidebar');
    assert(icon !== null && icon !== undefined && sidebar !== null, 'Collapsed Jira keeps its icon');
    const bounds = icon.getBoundingClientRect();
    const sidebarBounds = sidebar.getBoundingClientRect();
    assert(bounds.width === 16 && bounds.left >= sidebarBounds.left && bounds.right <= sidebarBounds.right, 'The Jira outline fits the collapsed sidebar');
    assert(getComputedStyle(icon).fill === 'none', 'The Jira icon stays an outline');

    const go = async (target: AppNavigationTarget, hash = `#/${target}`) => {
      navigate(target);
      await until(() => window.location.hash === hash, `Native navigation did not reach ${hash}`);
      await settle();
    };
    for (const target of ['home', 'workspaces', 'teams', 'recent', 'pulls'] as const) {
      await go(target);
      const label = target === 'home' ? 'Home' : target === 'workspaces' ? 'Workspaces' : target === 'teams' ? 'Circles' : target === 'recent' ? 'Recent' : 'Pull requests';
      const item = element<HTMLButtonElement>(`.app-sidebar [aria-label="${label}"]`);
      assert(item.getAttribute('aria-current') === 'page', `${label} is selected after its native command`);
      assert(item.title.includes('⌥⌘'), `${label} exposes its shortcut in the collapsed sidebar`);
    }
    element<HTMLButtonElement>('[aria-label="Expand sidebar"]').click();
    await settle();
    await go('shortcuts', '#/settings/personal/shortcuts');
    assert(document.querySelector('.setw-page h1')?.textContent === 'Keyboard shortcuts', 'Help opens the shortcut reference in Settings');
    const search = element<HTMLInputElement>('[aria-label="Search settings"]');
    search.focus();
    navigate('recent');
    await until(() => window.location.hash === '#/recent', 'Native navigation also works while an input is focused');
    await settle();
    await go('settings', '#/settings');
    assert(document.querySelector('.setw-page h1')?.textContent === 'Keyboard shortcuts', 'Settings reopens the last section outside a workspace');
    await go('recent');

    const dialog = document.createElement('dialog');
    document.body.append(dialog);
    dialog.showModal();
    navigate('home');
    await settle();
    assert(window.location.hash === '#/recent' && dialog.open, 'Native navigation cannot dismiss a modal form');
    dialog.close();
    dialog.remove();
    const customModal = document.createElement('section');
    customModal.setAttribute('aria-modal', 'true');
    customModal.textContent = 'Review draft';
    document.body.append(customModal);
    navigate('home');
    await settle();
    assert(window.location.hash === '#/recent', 'Native navigation also protects custom modal review forms');
    customModal.hidden = true;
    await go('home');
    customModal.remove();

    const referenceLayer = document.createElement('section');
    referenceLayer.setAttribute('aria-modal', 'true');
    referenceLayer.setAttribute('data-navigation-layer', '');
    referenceLayer.textContent = 'Pull request details';
    document.body.append(referenceLayer);
    await go('recent');
    const reviewForm = document.createElement('section');
    reviewForm.setAttribute('aria-modal', 'true');
    reviewForm.textContent = 'Unfinished review';
    referenceLayer.append(reviewForm);
    navigate('home');
    await settle();
    assert(window.location.hash === '#/recent', 'A modal form inside a reference layer still blocks navigation');
    reviewForm.remove();
    await go('home');
    referenceLayer.remove();

    await go('recent');
    browser = { tabs: [{ id: 'docs', url: 'https://example.com/docs', title: 'Docs', name: null, favicon: null, pinned: false,
      canGoBack: false, canGoForward: false, loading: false, error: null }], activeTabId: 'docs' };
    browserChanged(browser);
    await until(() => document.querySelector('.inapp-browser') !== null, 'The browser appears over the app');
    const hiddenBefore = browserHidden;
    await go('recent');
    await until(() => document.querySelector('.inapp-browser') === null, 'Navigating to the current app page hides the browser');
    assert(browserHidden > hiddenBefore && browser.tabs.length === 1, 'Leaving the native browser preserves its tab');

    window.location.hash = '#/workspaces/workspace/commits';
    await until(() => document.querySelector('[aria-label="Workspace navigation"]') !== null, 'The workspace opens before Settings');
    navigate('settings');
    await until(() => window.location.hash === '#/settings/workspace/workspace/repository', 'Native Settings selects the current workspace');
    await settle();
    window.location.hash = '#/workspaces/workspace/commits';
    await until(() => document.querySelector('[aria-label="Workspace navigation"]') !== null, 'The workspace reopens');
    element<HTMLButtonElement>('.app-sidebar [aria-label="Settings"]').click();
    await until(() => window.location.hash === '#/settings/workspace/workspace/repository', 'Sidebar and native Settings share the workspace destination');

    await go('recent');
    await go('new-task', '#/workspaces');
    await go('recent');
    await go('switch-task', '#/workspaces');
    window.location.hash = '#/workspaces/workspace/commits';
    await until(() => document.querySelector('[aria-label="Workspace navigation"]') !== null, 'Task shortcuts have a current workspace');
    navigate('new-task');
    await until(() => window.location.hash === '#/workspaces/workspace/tasks'
      && document.activeElement?.getAttribute('aria-label') === 'Describe a task', 'New task opens and focuses the workspace composer');
    let draft = element<HTMLTextAreaElement>('[aria-label="Describe a task"]');
    changeInput(draft, 'Keep this unfinished task request');
    await settle();
    draft.blur();
    navigate('new-task');
    await until(() => document.activeElement === draft, 'Repeating New task focuses the existing composer');
    assert(draft.value === 'Keep this unfinished task request', 'Repeating New task preserves the draft');
    assert(!bridge.commands.some(command => command.name === 'createAgentTask'), 'Opening the composer does not start an agent');

    element<HTMLButtonElement>('[data-task-list="archived"]').click();
    await until(() => document.querySelector('[aria-label="Describe a task"]') === null, 'Archived hides the composer');
    navigate('new-task');
    await until(() => document.querySelector('[data-task-list="active"]')?.getAttribute('aria-selected') === 'true'
      && document.activeElement?.getAttribute('aria-label') === 'Describe a task', 'New task selects Active and focuses the composer');
    draft = element<HTMLTextAreaElement>('[aria-label="Describe a task"]');
    assert(draft.value === 'Keep this unfinished task request', 'Returning from Archived preserves the unsent draft');

    const taskDialog = document.createElement('dialog');
    document.body.append(taskDialog);
    taskDialog.showModal();
    navigate('new-task');
    navigate('switch-task');
    await settle();
    assert(taskDialog.open && document.querySelector('[aria-label="All tasks"]') === null && document.activeElement !== draft,
      'Task commands leave an active modal and its focus in place');
    taskDialog.close();
    taskDialog.remove();
    const readBeforeSwitch = bridge.commands.filter(command => command.name === 'listAgentTasks').length;
    navigate('switch-task');
    await until(() => document.querySelector('[aria-label="All tasks"]') !== null, 'Switch task opens the existing task overview');
    await until(() => bridge.commands.filter(command => command.name === 'listAgentTasks').length > readBeforeSwitch,
      'Switch task reads the current workspace tasks');
    assert(bridge.commands.filter(command => command.name === 'listAgentTasks').at(-1)?.args[0] === workspace.id,
      'The switcher stays within the current workspace');
    assert(!bridge.commands.some(command => command.name === 'createAgentTask'), 'The switcher starts no task');
    navigate('switch-task');
    await until(() => document.querySelector('[aria-label="All tasks"]') === null, 'Repeating Switch task closes its overview');
    assert(draft.value === 'Keep this unfinished task request', 'Opening and closing the switcher keeps the composer draft');
    await go('recent');
    zoomReference({ kind: 'task', workspaceId: workspace.id, taskId: 'task-from-recent' });
    await until(() => document.querySelector('[aria-label="Task"][data-navigation-layer]') !== null,
      'A task detail layer opens over a global page');
    navigate('switch-task');
    await until(() => document.querySelector('[aria-label="All tasks"]') !== null, 'Switch task uses the visible task layer workspace');
    assert(bridge.commands.filter(command => command.name === 'listAgentTasks').at(-1)?.args[0] === workspace.id,
      'Switching from a task layer keeps that task workspace');
    await until(() => document.querySelector('.agt-overview .agt-card__open') !== null, 'The switcher lists an existing task');
    element<HTMLButtonElement>('.agt-overview .agt-card__open').click();
    await until(() => window.location.hash === `#/workspaces/workspace/tasks/agent/${bridge.events[0].taskId}`
      && document.querySelector('[aria-label="All tasks"]') === null, 'Selecting a switcher card opens that workspace task');

    window.location.hash = '#/workspaces/workspace';
    await until(() => document.querySelector('.agt-home [aria-label="Describe a task"]') !== null, 'Workspace Home has its own composer');
    const homeDraft = element<HTMLTextAreaElement>('.agt-home [aria-label="Describe a task"]');
    changeInput(homeDraft, 'Keep the Home draft');
    await settle();
    element<HTMLButtonElement>('[aria-label="Show all tasks"]').click();
    await until(() => document.querySelector('[aria-label="All tasks"]') !== null, 'Home opens its local overview');
    navigate('new-task');
    await until(() => document.querySelector('[aria-label="All tasks"]') === null && document.activeElement === homeDraft,
      'New task closes the Home overview and focuses the same composer');
    assert(window.location.hash === '#/workspaces/workspace' && homeDraft.value === 'Keep the Home draft',
      'New task keeps Home mounted with its unsent draft');

    const taskRoute = `#/workspaces/workspace/tasks/agent/${bridge.events[0].taskId}`;
    assistantOpens({ hash: taskRoute });
    await until(() => window.location.hash === taskRoute && document.activeElement?.getAttribute('aria-label') === 'Message the agent',
      'A task the Assistant opened takes the talk in its composer');
    assistantOpens({ hash: '#/workspaces/workspace' });
    await until(() => document.activeElement?.matches('.agt-home [aria-label="Describe a task"]') === true,
      'Workspace Home the Assistant opened focuses its composer');
    const homeInput = document.activeElement as HTMLTextAreaElement;
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'K' }));
    changeInput(homeInput, 'Keep talking here');
    homeInput.setSelectionRange(0, 0);
    homeInput.blur();
    await settle();
    assert(document.activeElement !== homeInput, 'Once the user types, the page keeps the focus they give it');
    assistantOpens({ hash: '#/workspaces/workspace' });
    await until(() => document.activeElement === homeInput && homeInput.selectionStart === homeInput.value.length,
      'Opening the page already on screen focuses its composer after the draft');
    zoomReference({ kind: 'task', workspaceId: workspace.id, taskId: bridge.events[0].taskId });
    await until(() => document.querySelector('[aria-label="Task"][data-navigation-layer]') !== null, 'A task layer opens over workspace Home');
    assistantOpens({ hash: '#/workspaces/workspace' });
    await until(() => document.querySelector('[aria-label="Task"][data-navigation-layer]') === null,
      'Opening the page under a layer closes the layer');

    deferBrowserHide = true;
    browser = { ...browser, activeTabId: 'docs' };
    browserChanged(browser);
    await until(() => document.querySelector('.inapp-browser') !== null, 'The browser covers Home before switching tasks');
    navigate('switch-task');
    await settle();
    assert(document.querySelector('[aria-label="All tasks"]') === null, 'The switcher waits for the native browser to hide');
    browser = { ...browser, activeTabId: null };
    browserChanged(browser);
    await until(() => document.activeElement?.matches('.agt-overview .agt-card__open') === true,
      'The switcher focuses a task after the browser releases the app');
    assert(!bridge.commands.some(command => command.name === 'createAgentTask'), 'Task shortcuts never sent either draft');
    return [{ name: 'connected-site navigation loads, updates and fits the collapsed sidebar' },
      { name: 'native navigation reaches app pages, protects modal forms and preserves browser tabs and workspace context' },
      { name: 'task shortcuts focus an unsent draft and open the current workspace switcher' }];
  }
  finally { disposeRoot(); }
}
