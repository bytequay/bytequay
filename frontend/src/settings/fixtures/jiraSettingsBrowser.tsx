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
import { createRoot, type Root } from 'react-dom/client';
import SettingsWindow from '../SettingsWindow';
import AppSidebar from '../../AppSidebar';
import { JIRA_CHANGED, type JiraConnection } from '../../jira/contracts';
import type { Bridge } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/settings-window.css';

const connected: JiraConnection = { id: 'work', state: 'connected', account: { accountId: 'alex', displayName: 'Alex Chen', email: 'alex@company.com' }, sites: [{ id: 'engineering', name: 'Engineering', url: 'https://company.atlassian.net' }], authorizationUrl: null, pairingCode: null, error: null, duplicateOf: null };
const pending: JiraConnection = { id: 'new', state: 'pending', account: null, sites: [], authorizationUrl: 'https://auth.example.test/start/new', pairingCode: 'ABCD1234', error: null, duplicateOf: null };
let root: Root | null = null;
let accounts: JiraConnection[] = [];
let reads = 0;
let opened: string[] = [];
let removed: string[] = [];
let refreshIds: string[] = [];
let changedEvents = 0;
let nextRefresh: (value: JiraConnection) => Promise<JiraConnection>;
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
async function settled() { await settle(); await settle(); }
async function advancePoll(by?: number) {
  const entry = jobs.entries().next().value; assert(entry !== undefined, 'A pending sign-in should schedule a check');
  const [id, job] = entry; jobs.delete(id); now += by ?? job.delay; job.run(); await settled();
}
function dispose() { root?.unmount(); root = null; jobs.clear(); window.setTimeout = originalTimeout; window.clearTimeout = originalClearTimeout; Date.now = originalNow; }

export async function mountPreview(values: JiraConnection[] = [connected, { ...connected, id: 'personal', account: { accountId: 'personal', displayName: 'Alex Chen', email: 'alex@personal.com' }, sites: [{ id: 'personal-site', name: 'Personal projects', url: 'https://alex.atlassian.net' }] }, pending], configured = true) {
  dispose(); document.body.innerHTML = ''; accounts = values.map(value => ({ ...value })); reads = 0; opened = []; removed = []; refreshIds = []; changedEvents = 0; now = originalNow();
  window.setTimeout = ((handler: TimerHandler, delay = 0, ...args: unknown[]) => {
    if (typeof handler === 'function' && delay >= 2_000 && delay <= 15_000) { const id = nextTimer--; jobs.set(id, { run: () => handler(...args), delay }); return id; }
    return originalTimeout(handler, delay, ...args);
  }) as typeof window.setTimeout;
  window.clearTimeout = (id?: number) => { if (id !== undefined) jobs.delete(id); originalClearTimeout(id); };
  Date.now = () => now;
  nextRefresh = async value => ({ ...connected, id: value.id });
  const fake = {
    getAppVersion: async () => ({ version: '0.4.0' }),
    getAppPreferences: async () => ({ taskRetentionDays: 7, recentRetentionDays: 14, refreshIntervalMinutes: 6, theme: 'SYSTEM' as const }),
    getJiraConfig: async () => ({ configured }),
    listJiraConnections: async () => { reads += 1; return accounts.map(value => ({ ...value })); },
    connectJira: async () => { accounts.push({ ...pending }); return { ...pending }; },
    reconnectJira: async (id: string) => { const previous = accounts.find(value => value.id === id); assert(previous !== undefined, 'Reconnect keeps the existing ID'); const value: JiraConnection = { ...previous, state: 'pending' as const, error: null, authorizationUrl: `https://auth.example.test/start/${id}/new`, pairingCode: 'WXYZ5678' }; accounts = accounts.map(account => account.id === id ? value : account); return value; },
    refreshJiraConnection: async (id: string) => { refreshIds.push(id); const existing = accounts.find(value => value.id === id); assert(existing !== undefined, 'Poll existing account'); const value = await nextRefresh(existing); accounts = accounts.map(account => account.id === id ? value : account); return value; },
    disconnectJira: async (id: string) => { removed.push(id); accounts = accounts.filter(value => value.id !== id).map(value => ({ ...value, duplicateOf: null as string | null })); },
    openExternal: async (url: string) => { opened.push(url); },
  } satisfies Partial<Bridge>;
  window.bridge = fake as Bridge;
  const host = document.createElement('div'); host.className = 'app-shell'; host.style.cssText = 'display:flex;height:100vh;overflow:hidden'; document.body.append(host); root = createRoot(host);
  const mounted = root; flushSync(() => mounted.render(<><AppSidebar page="settings" browserRailVisible={false} onToggleBrowserRail={() => {}} onNavigate={() => {}} canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}} workspace={null} workspaceTab="home" onWorkspaceTab={() => {}} settings={{ section: 'jira', workspaces: [], workspaceId: null, onSection: () => {}, onWorkspace: () => {}, onExit: () => {} }} /><main className="app-content"><SettingsWindow section="jira" workspace={null} onWorkspacesChanged={() => {}} /></main></>));
  await settled();
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  const results: { name: string; error?: string }[] = [];
  const changed = () => { changedEvents += 1; }; window.addEventListener(JIRA_CHANGED, changed);
  for (const [name, test] of Object.entries({
    async 'adds an account in the browser and completes without a manual check'() {
      await mountPreview([]); assert(document.querySelector('[aria-label="Settings navigation"] .is-active')?.textContent === 'Jira', 'Jira lives in the shared Settings navigation'); assert(!document.querySelector('input[type="url"]'), 'No service URL is requested');
      button('Add account').click(); await settled(); assert(opened[0] === pending.authorizationUrl, 'Add account opens system browser');
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
    async 'shows an unavailable installation without inventing a working sign-in'() {
      await mountPreview([connected], false); assert(button('Add account').disabled, 'Missing service disables new sign-in');
      assert(document.body.textContent?.includes('not available in this installation'), 'Configuration limitation is readable');
      assert(!button('Reconnect', row('work')).disabled, 'An existing account may reconnect through its saved service');
      assert(opened.length === 0 && !document.querySelector('.jira-settings input'), 'No credentials or server URL requested');
    },
  })) {
    try { await test(); results.push({ name }); } catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
    finally { dispose(); }
  }
  window.removeEventListener(JIRA_CHANGED, changed);
  return results;
}
