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
import App from '../../App';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { assert, changeInput, settle } from '../../fixtures/harness';
import type { Bridge } from '../../types';
import { settingsRoute } from '../routes';
import '../../index.css';

/** Starts the whole app on a route. Every web page it asks for is added to `opened` instead of being loaded. */
export function start(route: string, opened: string[] = []): () => void {
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: async () => true,
    getInAppBrowserState: async () => ({ tabs: [], activeTabId: null }),
    onInAppBrowserState: () => () => {},
    openInAppBrowser: async (url: string) => { opened.push(url); },
    getFullScreen: async () => false,
    onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {},
    onPrAttentionUpdated: () => () => {},
    onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {},
    stopFindInPage: async () => {},
    listJiraConnections: async () => [],
    listJiraAttention: async () => [],
    listWorkspaces: async () => [],
    fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    getAppVersion: async () => ({ version: 'test' }),
    getAppPreferences: async () => ({ taskRetentionDays: 14, recentRetentionDays: 14, prWarmCount: 10, theme: 'SYSTEM' }),
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.location.hash = route;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  return () => { flushSync(() => root.unmount()); host.remove(); };
}

const labItem = () => document.querySelector<HTMLButtonElement>('.app-sidebar [aria-label="Application settings"] [aria-label="Lab"]');
const toggle = () => document.querySelector<HTMLButtonElement>('.setw-page [role="switch"][aria-label="Show the Lab"]');
const address = () => document.querySelector<HTMLInputElement>('.setw-page input[aria-label="Dashboard address"]');
const save = () => [...document.querySelectorAll<HTMLButtonElement>('.setw-page button')].find(button => button.textContent?.trim() === 'Save');
const hint = () => address()?.closest('.setw-row')?.querySelector('.setw-row-sub')?.textContent ?? '';

async function until(condition: () => boolean, message: string) {
  for (let i = 0; i < 120 && !condition(); i++) await settle();
  assert(condition(), message);
}

/** Clicks the Lab item and waits for the web page it asks for. */
async function opens(opened: string[], expected: string, message: string) {
  const before = opened.length;
  labItem()?.click();
  await until(() => opened.length === before + 1, `${message}: the Lab item opened nothing`);
  assert(opened.at(-1) === expected, `${message}, got ${opened.at(-1)}`);
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  window.localStorage.removeItem('bytequay.lab-link');
  const opened: string[] = [];
  let stop = start(settingsRoute('lab'), opened);
  const results: { name: string; error?: string }[] = [];
  const step = async (name: string, body: () => Promise<void>) => {
    try { await body(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  };
  try {
    await step('The Lab is off until it is turned on in Settings', async () => {
      await until(() => toggle() !== null, 'Settings shows the Lab page');
      assert(document.querySelector('[aria-label="Settings navigation"] [aria-label="Lab"][aria-current="page"]') !== null, 'Lab is a page under Settings');
      assert(toggle()?.getAttribute('aria-checked') === 'false' && labItem() === null, 'The Lab starts turned off, with no item in the sidebar');
      assert(address()?.value === 'http://127.0.0.1:8517/' && save()?.disabled === true, 'The usual address is filled in and there is nothing to save');
    });

    await step('Turning it on adds a Lab item that opens the dashboard as a web page', async () => {
      toggle()?.click();
      await until(() => labItem() !== null && toggle()?.getAttribute('aria-checked') === 'true', 'The switch shows on and the Lab item appears');
      const labels = [...document.querySelectorAll('[aria-label="Application settings"] button')].map(button => button.getAttribute('aria-label'));
      assert(labels[labels.indexOf('Lab') + 1] === 'Web pages', 'Lab sits just above Web pages');
      await opens(opened, 'http://127.0.0.1:8517/', 'Lab opens the dashboard address');
    });

    await step('Only an address on this Mac can be saved', async () => {
      const field = address();
      assert(field !== null, 'The address field is there');
      changeInput(field, 'http://example.com:8517');
      await until(() => address()?.getAttribute('aria-invalid') === 'true', 'An address elsewhere is marked as not usable');
      assert(save()?.disabled === true, 'An address elsewhere cannot be saved');
      assert(hint().includes('Use an address on this Mac'), 'The row says what kind of address it takes');
      await opens(opened, 'http://127.0.0.1:8517/', 'A typed address is not used before it is saved');

      changeInput(field, 'localhost:8600');
      await until(() => save()?.disabled === false && address()?.getAttribute('aria-invalid') === 'false', 'An address on this Mac can be saved');
      save()?.click();
      await until(() => address()?.value === 'http://localhost:8600/' && save()?.disabled === true, 'The saved address is shown in full and nothing is left to save');
      assert(toggle()?.getAttribute('aria-checked') === 'true', 'Saving the address leaves the switch on');
      await opens(opened, 'http://localhost:8600/', 'Lab opens the saved address');
    });

    await step('The choice stays on this Mac and the item is on every page', async () => {
      stop();
      stop = start('#/recent', opened);
      await until(() => document.querySelector('[aria-label="Recent"][aria-current="page"]') !== null, 'The app starts again on Recent');
      assert(labItem() !== null, 'The Lab item is still there after the app starts again');
      await opens(opened, 'http://localhost:8600/', 'It still opens the saved address');
      assert(opened.length === 4, 'Every click opened one page');
    });

    await step('Turning it off takes the item away', async () => {
      window.location.hash = settingsRoute('lab');
      await until(() => toggle() !== null, 'Settings shows the Lab page again');
      assert(toggle()?.getAttribute('aria-checked') === 'true', 'The switch is still on');
      toggle()?.click();
      await until(() => labItem() === null, 'The Lab item goes away');
      assert(address()?.value === 'http://localhost:8600/', 'The address is kept for the next time');
    });
    return results;
  }
  finally {
    stop();
    window.localStorage.removeItem('bytequay.lab-link');
  }
}
