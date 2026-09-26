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

import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import SettingsWindow from '../SettingsWindow';
import AppSidebar from '../../AppSidebar';
import { JIRA_CHANGED, type JiraConnection, type JiraTokenRequest } from '../../jira/contracts';
import type { Bridge, QuietHoursDto } from '../../types';
import { settingsLocationFromRoute, settingsRoute, type SettingsSection } from '../routes';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/settings-window.css';

const connected: JiraConnection = { id: 'work', authType: 'oauth', tokenEmail: null, state: 'connected', account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@company.com' }, sites: [{ id: 'engineering', name: 'Engineering', url: 'https://company.atlassian.net' }], authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null };
const pending: JiraConnection = { id: 'new', authType: 'oauth', tokenEmail: null, state: 'pending', account: null, sites: [], authorizationUrl: 'https://auth.example.test/start/new', pairingCode: 'ABCD1234', error: null, duplicateOf: null };
const tokenConnected: JiraConnection = { ...connected, id: 'token-account', authType: 'api_token', tokenEmail: 'alex@company.com' };
let root: Root | null = null;
let accounts: JiraConnection[] = [];
let reads = 0;
let opened: string[] = [];
let removed: string[] = [];
let refreshIds: string[] = [];
let changedEvents = 0;
let nextRefresh: (value: JiraConnection) => Promise<JiraConnection>;
let nextToken: (request: JiraTokenRequest, id?: string) => Promise<JiraConnection>;
let tokenRequests: { request: JiraTokenRequest; id?: string }[] = [];
let quietHours: QuietHoursDto;
let quietWrites: QuietHoursDto[] = [];
let nextQuietSave: (value: QuietHoursDto) => Promise<QuietHoursDto>;
const originalTimeout = window.setTimeout.bind(window);
const originalClearTimeout = window.clearTimeout.bind(window);
const originalNow = Date.now;
let now = originalNow();
let nextTimer = -1;
const jobs = new Map<number, { run: () => void; delay: number }>();
const button = (label: string, scope: ParentNode = document) => {
  const result = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(value => value.textContent?.trim() === label || value.getAttribute('aria-label') === label);
  assert(result !== undefined, `Missing button: ${label}`); return result;
};
const row = (id: string) => { const value = document.querySelector(`[data-connection-id="${id}"]`); assert(value !== null, `Missing account: ${id}`); return value; };
const input = (label: string) => { const element = [...document.querySelectorAll('label')].find(value => value.textContent === label); const result = document.getElementById(element?.htmlFor ?? ''); assert(result instanceof HTMLInputElement, `Missing input: ${label}`); return result; };
function changeInput(label: string, value: string) { const element = input(label); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value); element.dispatchEvent(new Event('input', { bubbles: true })); }
async function settled() { await settle(); await settle(); }
async function advancePoll(by?: number) {
  const entry = jobs.entries().next().value; assert(entry !== undefined, 'A pending sign-in should schedule a check');
  const [id, job] = entry; jobs.delete(id); now += by ?? job.delay; job.run(); await settled();
}
function dispose() { root?.unmount(); root = null; jobs.clear(); window.setTimeout = originalTimeout; window.clearTimeout = originalClearTimeout; Date.now = originalNow; }

export async function mountPreview(values: JiraConnection[] = [connected, { ...connected, id: 'personal', account: { accountId: 'personal', displayName: 'Alex Chen', email: 'alex@personal.com' }, sites: [{ id: 'personal-site', name: 'Personal projects', url: 'https://alex.atlassian.net' }] }, pending], configured = true) {
  dispose(); document.body.innerHTML = ''; accounts = values.map(value => ({ ...value })); reads = 0; opened = []; removed = []; refreshIds = []; changedEvents = 0; tokenRequests = []; now = originalNow();
  quietHours = { enabled: true, startMinute: 1380, endMinute: 420 }; quietWrites = []; nextQuietSave = async value => value;
  window.location.hash = settingsRoute('jira');
  window.setTimeout = ((handler: TimerHandler, delay = 0, ...args: unknown[]) => {
    if (typeof handler === 'function' && delay >= 2_000 && delay <= 15_000) { const id = nextTimer--; jobs.set(id, { run: () => handler(...args), delay }); return id; }
    return originalTimeout(handler, delay, ...args);
  }) as typeof window.setTimeout;
  window.clearTimeout = (id?: number) => { if (id !== undefined) jobs.delete(id); originalClearTimeout(id); };
  Date.now = () => now;
  nextRefresh = async value => ({ ...connected, id: value.id });
  nextToken = async (request, id) => ({ ...tokenConnected, id: id ?? tokenConnected.id, tokenEmail: request.email });
  const saveToken = async (request: JiraTokenRequest, id?: string) => { tokenRequests.push({ request, id }); const value = await nextToken(request, id); accounts = [...accounts.filter(account => account.id !== value.id), value]; return value; };
  const fake = {
    getAppVersion: async () => ({ version: '0.4.0' }),
    getAppPreferences: async () => ({ taskRetentionDays: 7, recentRetentionDays: 14, refreshIntervalMinutes: 6, theme: 'SYSTEM' as const }),
    getQuietHours: async () => ({ ...quietHours }),
    saveQuietHours: async (value: QuietHoursDto) => { quietWrites.push(value); quietHours = await nextQuietSave(value); return { ...quietHours }; },
    getGitHubAccount: async () => ({ configured: false, source: 'PERSONAL_ACCESS_TOKEN' as const }),
    getJiraConfig: async () => ({ configured }),
    listJiraConnections: async () => { reads += 1; return accounts.map(value => ({ ...value })); },
    connectJira: async () => { accounts.push({ ...pending }); return { ...pending }; },
    connectJiraToken: (request: JiraTokenRequest) => saveToken(request),
    replaceJiraToken: (id: string, request: JiraTokenRequest) => saveToken(request, id),
    reconnectJira: async (id: string) => { const previous = accounts.find(value => value.id === id); assert(previous !== undefined, 'Reconnect keeps the existing ID'); const value: JiraConnection = { ...previous, state: 'pending' as const, error: null, authorizationUrl: `https://auth.example.test/start/${id}/new`, pairingCode: 'WXYZ5678' }; accounts = accounts.map(account => account.id === id ? value : account); return value; },
    refreshJiraConnection: async (id: string) => { refreshIds.push(id); const existing = accounts.find(value => value.id === id); assert(existing !== undefined, 'Poll existing account'); const value = await nextRefresh(existing); accounts = accounts.map(account => account.id === id ? value : account); return value; },
    disconnectJira: async (id: string) => { removed.push(id); accounts = accounts.filter(value => value.id !== id).map(value => ({ ...value, duplicateOf: null as string | null })); },
    openExternal: async (url: string) => { opened.push(url); },
  } satisfies Partial<Bridge>;
  window.bridge = fake as Bridge;
  const host = document.createElement('div'); host.className = 'app-shell'; host.style.cssText = 'display:flex;height:100vh;overflow:hidden'; document.body.append(host); root = createRoot(host);
  function SettingsHarness() {
    const [section, setSection] = useState<SettingsSection>('jira');
    useEffect(() => { const changed = () => setSection(settingsLocationFromRoute(window.location.hash)?.section ?? 'jira'); window.addEventListener('hashchange', changed); return () => window.removeEventListener('hashchange', changed); }, []);
    return <><AppSidebar page="settings" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}} workspace={null} workspaceTab="home" onWorkspaceTab={() => {}} settings={{ section, workspaces: [], workspaceId: null, onSection: value => { window.location.hash = settingsRoute(value); }, onWorkspace: () => {}, onExit: () => {} }} /><main className="app-content"><SettingsWindow section={section} workspace={null} onWorkspacesChanged={() => {}} /></main></>;
  }
  const mounted = root; flushSync(() => mounted.render(<SettingsHarness />));
  await settled();
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const changed = () => { changedEvents += 1; }; window.addEventListener(JIRA_CHANGED, changed);
  for (const [name, test] of Object.entries({
    async 'adds an account in the browser and completes without a manual check'() {
      await mountPreview([]); assert(document.querySelector('[aria-label="Settings navigation"] .is-active')?.textContent === 'Jira', 'Jira lives in the shared Settings navigation'); assert(!document.querySelector('input[type="url"]'), 'No service URL is requested');
      button('Sign in with browser').click(); await settled(); assert(opened[0] === pending.authorizationUrl, 'Browser sign-in opens system browser');
      assert(row('new').textContent?.includes('ABCD1234'), 'Pairing code remains visible');
      button('Reopen sign-in', row('new')).click(); await settled(); assert(opened.length === 2, 'Closed browser can reopen the same sign-in');
      const before = reads; await advancePoll();
      assert(row('new').textContent?.includes('alex@company.com') && row('new').textContent?.includes('company.atlassian.net'), 'Verified account and site appear automatically');
      assert(Number(jobs.size) === 0 && refreshIds.length === 1, 'Connected is terminal for polling');
      assert(reads > before && changedEvents > 0, 'Terminal sign-in updates all connection rows and application listeners');
    },
    async 'recovers a polling failure then exposes expiry and stops'() {
      await mountPreview([pending]); nextRefresh = async () => { throw new Error('Temporarily offline'); };
      await advancePoll(); assert(document.body.textContent?.includes('Temporarily offline'), 'Network failure remains visible');
      assert([...jobs.values()][0]?.delay === 4_000, 'Polling backs off after a failed request');
      nextRefresh = async value => ({ ...value, state: 'error', authorizationUrl: null, pairingCode: null, error: 'Sign-in expired. Reconnect to try again.' });
      await advancePoll(); assert(document.body.textContent?.includes('Sign-in expired'), 'Expiry explains recovery');
      assert(Number(jobs.size) === 0 && !document.body.textContent?.includes('ABCD1234'), 'Expiry stops polling and clears the old code');
    },
    async 'preserves reconnect identity and reports a retained grant error'() {
      await mountPreview([connected]); button('Reconnect', row('work')).click(); await settled();
      assert(row('work').textContent?.includes('alex@company.com') && row('work').textContent?.includes('WXYZ5678'), 'Reconnect retains identity while waiting');
      nextRefresh = async value => ({ ...connected, id: value.id, error: 'The selected account did not match. Your original connection was kept.' });
      await advancePoll(); assert(refreshIds[0] === 'work', 'Reconnect polls the original connection ID');
      assert(row('work').textContent?.includes('original connection was kept'), 'A retained connected state does not hide the failure');
      assert(removed.length === 0 && jobs.size === 0, 'Mismatch does not remove or continue polling the original account');
    },
    async 'shows multiple identities and clears surviving duplicate notices after remove'() {
      const second = { ...connected, id: 'other', account: { accountId: 'other', displayName: 'Sam Rivera', email: 'sam@company.com' } };
      await mountPreview([{ ...connected, duplicateOf: 'duplicate' }, second, { ...connected, id: 'duplicate', duplicateOf: 'work' }]);
      assert(document.body.textContent?.includes('alex@company.com') && document.body.textContent?.includes('sam@company.com'), 'Account identities distinguish the same site');
      assert(row('work').textContent?.includes('Already connected'), 'Duplicate sign-in is an explicit advisory');
      button('Remove', row('duplicate')).click(); await settled(); assert(removed.length === 0, 'Remove first describes the local effect');
      assert(row('duplicate').textContent?.includes('saved ticket context will remain'), 'Remove explains saved task context');
      button('Remove connection', row('duplicate')).click(); await settled();
      assert(removed[0] === 'duplicate' && !row('work').textContent?.includes('Already connected'), 'Removing a duplicate refreshes surviving row metadata');
    },
    async 'cancels timers and ignores an in-flight result after leaving settings'() {
      await mountPreview([pending]); assert(Number(jobs.size) === 1, 'Pending account starts a timer');
      root?.unmount(); root = null; assert(Number(jobs.size) === 0 && refreshIds.length === 0, 'Leaving clears the scheduled check');
      await mountPreview([pending]); let finish: ((value: JiraConnection) => void) | undefined;
      nextRefresh = () => new Promise(resolve => { finish = resolve; }); await advancePoll();
      const before = changedEvents; root?.unmount(); root = null; finish?.({ ...connected, id: 'new' }); await settled();
      assert(Number(jobs.size) === 0 && changedEvents === before, 'An obsolete response cannot restart polling or notify the app');
    },
    async 'bounds a browser cancellation wait and allows a deliberate final check'() {
      await mountPreview([pending]); nextRefresh = async value => value;
      await advancePoll(10 * 60_000); assert(Number(jobs.size) === 0 && refreshIds.length === 0, 'Automatic checks stop after ten minutes');
      assert(document.body.textContent?.includes('Automatic checking paused'), 'No false expiry or successful connection is claimed');
      nextRefresh = async value => ({ ...connected, id: value.id }); button('Check status').click(); await settled();
      assert(row('new').textContent?.includes('Connected'), 'User can check a late browser completion');
    },
    async 'does not overlap manual checks or apply their result after leaving'() {
      await mountPreview([pending]); await advancePoll(10 * 60_000);
      let finish: ((value: JiraConnection) => void) | undefined;
      nextRefresh = () => new Promise(resolve => { finish = resolve; });
      const check = button('Check status'); check.click(); check.click(); await settled();
      assert(refreshIds.length === 1 && check.disabled, 'Manual check runs once and disables duplicate clicks');
      const before = changedEvents; root?.unmount(); root = null; finish?.({ ...connected, id: 'new' }); await settled();
      assert(changedEvents === before, 'Manual result cannot restore a card after navigation');
    },
    async 'connects a personal token without a configured browser sign-in service'() {
      await mountPreview([connected], false); assert(!button('Add account').disabled, 'A personal token does not require the sign-in service');
      assert(!document.body.textContent?.includes('Sign in with browser') && !document.body.textContent?.includes('not available in this installation'), 'Unavailable browser sign-in does not block token entry');
      assert(!button('Reconnect', row('work')).disabled, 'An existing OAuth account keeps its saved service');
      button('Add account').click(); await settled();
      changeInput('Jira site', 'https://company.atlassian.net'); changeInput('Atlassian account email', 'alex@company.com'); changeInput('API token', 'fixture-token'); await settled();
      assert(input('API token').type === 'password' && !document.body.textContent?.includes('fixture-token'), 'Token is masked and absent from readable page text');
      const page = document.querySelector('.jira-settings'); assert(page instanceof HTMLElement && page.scrollWidth <= page.clientWidth, 'Token form fits the settings pane');
      let finish: ((value: JiraConnection) => void) | undefined; nextToken = () => new Promise(resolve => { finish = resolve; });
      button('Connect account').click(); await settled();
      assert(input('API token').value === '' && input('API token').disabled && button('Cancel').disabled, 'Submit clears the token immediately and disables the form while validating');
      document.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settled();
      assert(tokenRequests.length === 1 && tokenRequests[0].request.token === 'fixture-token', 'Only one explicit submit sends the entered token');
      finish?.(tokenConnected); await settled();
      assert(row('token-account').textContent?.includes('API token') && row('token-account').textContent?.includes('company.atlassian.net'), 'Verified token account and site appear');
      assert(!document.querySelector('input[type="password"]') && opened.length === 0 && refreshIds.length === 0 && jobs.size === 0, 'Connecting a token neither opens OAuth nor schedules refresh');
    },
    async 'clears a cancelled token and links directly to personal token creation'() {
      await mountPreview([], false); button('Add account').click(); await settled(); changeInput('API token', 'cancelled-fixture-token'); await settled();
      button('Create API token').click(); await settled(); assert(opened[0] === 'https://id.atlassian.com/manage-profile/security/api-tokens', 'Creation opens the account token page');
      button('Cancel').click(); await settled(); button('Add account').click(); await settled();
      assert(input('API token').value === '' && tokenRequests.length === 0, 'Cancelling discards the secret without submitting it');
      assert(document.body.textContent?.includes('stored encrypted on this Mac') && document.body.textContent?.includes('does not refresh automatically'), 'Local storage and manual replacement are explained');
      const permissions = document.querySelector<HTMLDetailsElement>('.jira-token-form details'); assert(permissions !== null && !permissions.open, 'Required token permissions stay compact by default');
      permissions.querySelector('summary')?.click(); await settled();
      assert(permissions.open && permissions.textContent?.includes('Create API token with scopes') && permissions.querySelectorAll('li').length === 9, 'Expandable help explains Jira token creation and all required permissions');
    },
    async 'retains the original account after a failed token replacement and allows retry'() {
      await mountPreview([tokenConnected], false); button('Replace token', row('token-account')).click(); await settled();
      assert(input('Jira site').readOnly && input('Jira site').value === 'https://company.atlassian.net', 'Replacement keeps the bound site');
      assert(input('Atlassian account email').value === 'alex@company.com' && input('API token').value === '', 'Replacement prefills email but never retrieves the saved token');
      nextToken = async () => { throw new Error('The selected account did not match. Your original connection was kept.'); };
      changeInput('API token', 'wrong-account-fixture-token'); await settled(); button('Save replacement').click(); await settled();
      assert(tokenRequests[0].id === tokenConnected.id && input('API token').value === '' && !input('API token').disabled, 'Failed replacement clears its secret and keeps the form editable');
      assert(document.body.textContent?.includes('original connection was kept') && row('token-account').textContent?.includes('Connected') && removed.length === 0, 'Replacement failure retains the existing connection');
      nextToken = async () => tokenConnected; changeInput('API token', 'replacement-fixture-token'); await settled(); button('Save replacement').click(); await settled();
      assert(tokenRequests.length === 2 && !document.querySelector('input[type="password"]') && accounts.length === 1, 'Retry replaces the same account without adding a duplicate');
    },
    async 'shares confirmed quiet hours through General from both integration pages'() {
      await mountPreview([], false);
      const fromJira = document.querySelector<HTMLAnchorElement>(`a[href="${settingsRoute('general')}"]`); assert(fromJira !== null, 'Jira links to General quiet hours'); fromJira.click(); await settled();
      assert(document.querySelector('h1')?.textContent === 'General' && document.body.textContent?.includes('GitHub and Jira · this Mac’s local time'), 'Quiet hours describe both integrations and the clock they use');
      assert(button('Quiet hours start').textContent?.includes('11:00 PM') && button('Quiet hours end').textContent?.includes('7:00 AM'), 'Existing saved time boundaries are preserved');
      assert(document.body.textContent?.includes('Manual refresh'), 'Quiet hours leave manual refresh available');
      let finish: ((value: QuietHoursDto) => void) | undefined; nextQuietSave = () => new Promise(resolve => { finish = resolve; });
      button('Quiet hours start').click(); await settled(); button('10:30 PM').click(); await settled();
      assert(button('Quiet hours start').disabled && button('Pause background refresh during quiet hours').disabled, 'Shared settings wait for the save before allowing another edit');
      assert(button('Quiet hours start').textContent?.includes('11:00 PM'), 'An unconfirmed save does not display a new persisted value');
      assert(quietWrites.length === 1 && quietWrites[0].startMinute === 1350 && quietWrites[0].endMinute === 420, 'One shared API updates only the selected boundary');
      finish?.(quietWrites[0]); await settled();
      button('GitHub').click(); await settled();
      assert(!document.querySelector('[aria-label="Quiet hours start"]') && button('Refresh interval').textContent?.includes('6 minutes'), 'GitHub retains only its own refresh interval');
      const fromGithub = document.querySelector<HTMLAnchorElement>(`a[href="${settingsRoute('general')}"]`); assert(fromGithub !== null, 'GitHub links to the same General setting'); fromGithub.click(); await settled();
      assert(button('Quiet hours start').textContent?.includes('10:30 PM'), 'The confirmed setting persists across navigation');
      const pane = document.querySelector('.setw-page'); assert(pane instanceof HTMLElement && pane.scrollWidth <= pane.clientWidth, 'Quiet hours fit a narrow settings pane');
      button('Jira').click(); await settled(); assert(document.body.textContent?.includes('every 10 minutes'), 'Jira keeps its separate ten-minute refresh cadence');
    },
    async 'reports a quiet-hours save failure without changing the saved switch'() {
      await mountPreview([], false); button('General').click(); await settled();
      nextQuietSave = async () => { throw new Error('Unable to save quiet hours'); };
      button('Pause background refresh during quiet hours').click(); await settled();
      assert(document.querySelector('[role="alert"]')?.textContent?.includes('Unable to save quiet hours'), 'A failed save remains visible');
      assert(button('Pause background refresh during quiet hours').getAttribute('aria-checked') === 'true' && quietHours.enabled, 'The saved setting is not optimistically overwritten on failure');
      assert(!button('Pause background refresh during quiet hours').disabled, 'The user can retry a failed save');
    },
  })) {
    try { await test(); results.push({ name }); } catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { dispose(); }
  }
  window.removeEventListener(JIRA_CHANGED, changed);
  return results;
}
